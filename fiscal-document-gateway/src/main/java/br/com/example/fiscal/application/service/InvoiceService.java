package br.com.example.fiscal.application.service;

import br.com.example.fiscal.application.TransientFailureException;
import br.com.example.fiscal.application.SefazTimeoutException;
import br.com.example.fiscal.application.port.in.ManageInvoices;
import br.com.example.fiscal.application.port.out.*;
import br.com.example.fiscal.application.port.out.Sefaz.AuthorizationResponse;
import br.com.example.fiscal.application.port.out.Sefaz.AuthorizationResponse.Authorized;
import br.com.example.fiscal.application.port.out.Sefaz.AuthorizationResponse.NotFound;
import br.com.example.fiscal.application.port.out.Sefaz.AuthorizationResponse.Rejected;
import br.com.example.fiscal.application.port.out.Sefaz.EventResponse;
import br.com.example.fiscal.domain.Issuer;
import br.com.example.fiscal.domain.FiscalEvent.InvoiceAuthorized;
import br.com.example.fiscal.domain.FiscalEvent.InvoiceCancelled;
import br.com.example.fiscal.domain.FiscalEvent.InvoiceCancellationRejected;
import br.com.example.fiscal.domain.FiscalEvent.InvoiceRejected;
import br.com.example.fiscal.domain.SefazEvent;
import br.com.example.fiscal.domain.Invoice;
import br.com.example.fiscal.domain.InvoiceStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Emissão e cancelamento de NF-e.
 *
 * Regra de ouro do Caso 3: a chave de acesso é gerada e GRAVADA antes do envio. Se a SEFAZ não
 * responder, a nota fica em STATUS_UNKNOWN e a próxima tentativa (retry topic) CONSULTA a
 * situação pela chave antes de qualquer reenvio. Nunca se reemite com outro número.
 */
public class InvoiceService implements ManageInvoices {

    private static final Logger log = LoggerFactory.getLogger(InvoiceService.class);
    static final String CONSUMER = "fiscal-commands";

    private final InvoiceRepository invoices;
    private final Sefaz sefaz;
    private final FiscalEventPublisher publisher;
    private final Inbox inbox;
    private final UnitOfWork unitOfWork;
    private final Clock clock;
    private final Issuer issuer;
    private final Duration cancellationDeadline;

    public InvoiceService(InvoiceRepository invoices, Sefaz sefaz, FiscalEventPublisher publisher,
                             Inbox inbox, UnitOfWork unitOfWork, Clock clock,
                             Issuer issuer, Duration cancellationDeadline) {
        this.invoices = invoices;
        this.sefaz = sefaz;
        this.publisher = publisher;
        this.inbox = inbox;
        this.unitOfWork = unitOfWork;
        this.clock = clock;
        this.issuer = issuer;
        this.cancellationDeadline = cancellationDeadline;
    }

    @Override
    public void issue(UUID messageId, IssuanceRequest s) {
        if (alreadyProcessed(messageId)) return;

        var invoice = unitOfWork.compute(() -> invoices.byOrder(s.orderId()).orElseGet(() -> {
            var created = Invoice.create(s.orderId(), issuer, invoices.nextNumber(), s.recipient(), s.items(),
                    s.totalAmount(), clock.instant());
            invoices.insert(created);
            log.info("NF-e nº {} do pedido {} criada com chave {}", created.number(), created.orderId(), created.accessKey());
            return created;
        }));

        if (!invoice.status().awaitingSefaz()) {
            log.info("NF-e do pedido {} já está {}: nada a fazer", s.orderId(), invoice.status());
            unitOfWork.execute(() -> inbox.registerIfNew(CONSUMER, messageId));
            return;
        }

        AuthorizationResponse response;
        if (invoice.status() == InvoiceStatus.PENDING_SUBMISSION) {
            response = send(invoice);
        } else {
            log.warn("[Caso 3] NF-e {} em {}: consultando a situação pela chave antes de qualquer reenvio",
                    invoice.accessKey(), invoice.status());
            response = sefaz.lookup(invoice.accessKey());
            if (response instanceof NotFound) {
                log.info("[Caso 3] A SEFAZ não conhece a chave {}: reenviando com a MESMA chave", invoice.accessKey());
                response = send(invoice);
            } else {
                log.info("[Caso 3] A SEFAZ já tinha processado a chave {}: nenhuma reemissão", invoice.accessKey());
            }
        }

        var r = response;
        unitOfWork.execute(() -> {
            if (!inbox.registerIfNew(CONSUMER, messageId)) return;
            var current = invoices.byOrder(s.orderId()).orElseThrow();
            if (!current.status().awaitingSefaz()) return;
            switch (r) {
                case Authorized a -> {
                    current.registerAuthorization(a.protocol(), a.authorizedAt());
                    publisher.publish(new InvoiceAuthorized(current.orderId(), current.accessKey().value(), a.protocol()));
                }
                case Rejected rj -> {
                    current.registerRejection(rj.code(), rj.reason());
                    publisher.publish(new InvoiceRejected(current.orderId(), current.accessKey().value(), rj.code(),
                            rj.reason()));
                }
                case NotFound n ->
                        throw new TransientFailureException("SEFAZ ainda não reconhece a NF-e " + current.accessKey());
            }
            invoices.update(current);
            log.info("NF-e do pedido {} -> {}", current.orderId(), current.status());
        });
    }

    private AuthorizationResponse send(Invoice invoice) {
        try {
            var response = sefaz.authorize(invoice);
            if (response instanceof Rejected rj && "204".equals(rj.code())) {
                log.warn("SEFAZ acusou duplicidade (204) para {}: consultando a situação", invoice.accessKey());
                return sefaz.lookup(invoice.accessKey());
            }
            return response;
        } catch (SefazTimeoutException e) {
            log.warn("[Caso 3] Timeout ao enviar a NF-e {}: situação DESCONHECIDA. Timeout não é falha, é "
                    + "desconhecimento; a próxima tentativa vai consultar antes de reenviar", invoice.accessKey());
            unitOfWork.execute(() -> invoices.byOrder(invoice.orderId()).ifPresent(current -> {
                if (current.status() == InvoiceStatus.PENDING_SUBMISSION) {
                    current.markStatusUnknown();
                    invoices.update(current);
                }
            }));
            throw e;
        }
    }

    @Override
    public void cancel(UUID messageId, String orderId, String justification) {
        if (alreadyProcessed(messageId)) return;

        var invoice = unitOfWork.compute(() -> invoices.byOrder(orderId)).orElse(null);
        var now = clock.instant();

        if (invoice == null) {
            respondOnce(messageId, () -> publisher.publish(new InvoiceCancellationRejected(orderId, null,
                    "217", "NF-e não encontrada para o pedido")));
            return;
        }
        if (invoice.status() == InvoiceStatus.CANCELLED) {
            var protocol = invoice.events().stream().filter(e -> SefazEvent.CANCELLATION.equals(e.code()))
                    .map(SefazEvent::protocol).findFirst().orElse(null);
            respondOnce(messageId, () -> publisher.publish(new InvoiceCancelled(orderId,
                    invoice.accessKey().value(), protocol)));
            return;
        }
        if (!invoice.withinCancellationDeadline(now, cancellationDeadline)) {
            log.warn("NF-e {} fora do prazo de cancelamento ({}): exige cancelamento extemporâneo ou nota de "
                    + "devolução", invoice.accessKey(), cancellationDeadline);
            respondOnce(messageId, () -> publisher.publish(new InvoiceCancellationRejected(orderId,
                    invoice.accessKey().value(), "501", "Prazo de cancelamento expirado")));
            return;
        }
        Invoice.validateJustification(justification);

        var response = sefaz.cancel(invoice.accessKey(), invoice.protocol(), justification);

        unitOfWork.execute(() -> {
            if (!inbox.registerIfNew(CONSUMER, messageId)) return;
            var current = invoices.byOrder(orderId).orElseThrow();
            switch (response) {
                case EventResponse.Registered reg -> {
                    if (current.status() == InvoiceStatus.AUTHORIZED) {
                        current.registerCancellation(reg.protocol(), justification, now);
                        invoices.update(current);
                    }
                    log.info("NF-e {} cancelada (evento 110111, protocolo {})", current.accessKey(), reg.protocol());
                    publisher.publish(new InvoiceCancelled(orderId, current.accessKey().value(), reg.protocol()));
                }
                case EventResponse.Rejected rj -> publisher.publish(new InvoiceCancellationRejected(orderId,
                        current.accessKey().value(), rj.code(), rj.reason()));
            }
        });
    }

    @Override
    public void issuanceExhausted(String orderId, String error) {
        unitOfWork.execute(() -> invoices.byOrder(orderId).ifPresent(invoice -> {
            if (invoice.status().awaitingSefaz() && invoice.status() != InvoiceStatus.PENDING_CONTINGENCY) {
                invoice.markPendingContingency();
                invoices.update(invoice);
            }
        }));
        // Em produção: emitir em contingência (EPEC/SVC) e transmitir quando a SEFAZ voltar.
        log.error("ALERTA: emissão da NF-e do pedido {} esgotou as tentativas e entrou em contingência: {}",
                orderId, error);
    }

    @Override
    public void cancellationExhausted(UUID messageId, String orderId, String error) {
        log.error("ALERTA: cancelamento da NF-e do pedido {} esgotou as tentativas: {}", orderId, error);
        respondOnce(messageId, () -> publisher.publish(new InvoiceCancellationRejected(orderId,
                invoices.byOrder(orderId).map(n -> n.accessKey().value()).orElse(null), "999",
                "SEFAZ indisponível após todas as tentativas")));
    }

    @Override
    public Optional<Invoice> byOrder(String orderId) {
        return invoices.byOrder(orderId);
    }

    private void respondOnce(UUID messageId, Runnable response) {
        unitOfWork.execute(() -> {
            if (inbox.registerIfNew(CONSUMER, messageId)) response.run();
        });
    }

    private boolean alreadyProcessed(UUID messageId) {
        if (inbox.alreadyProcessed(CONSUMER, messageId)) {
            log.info("[Caso 4] Comando {} já processado: ignorando", messageId);
            return true;
        }
        return false;
    }
}
