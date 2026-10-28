package br.com.example.payment.application.service;

import br.com.example.payment.application.TransientFailureException;
import br.com.example.payment.application.port.in.ExecutePaymentCommands;
import br.com.example.payment.application.port.out.Inbox;
import br.com.example.payment.application.port.out.PaymentEventStore;
import br.com.example.payment.application.port.out.PaymentProvider;
import br.com.example.payment.application.port.out.PaymentProvider.Result;
import br.com.example.payment.application.port.out.PaymentProvider.PspStatus;
import br.com.example.payment.application.port.out.UnitOfWork;
import br.com.example.payment.domain.payment.CompensationAction;
import br.com.example.payment.domain.payment.Payment;
import br.com.example.payment.domain.payment.PaymentStatus;
import br.com.example.payment.domain.shared.DomainException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

/**
 * Casos de uso do agregado Pagamento.
 *
 * Padrão usado em toda chamada externa:
 *   1. transação local registra a INTENÇÃO (ex.: CaptureRequested) — fica no stream mesmo se o PSP falhar;
 *   2. chamada ao PSP, fora da transação, com Idempotency-Key determinística;
 *   3. transação local registra o RESULTADO + inbox + outbox.
 * Se cair entre 2 e 3, a mensagem é reentregue, a chamada se repete com a mesma chave e o PSP
 * devolve a mesma resposta: duas camadas de idempotência (Caso 4).
 */
public class PaymentService implements ExecutePaymentCommands {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    static final String CONSUMER = "payment-commands";

    private final PaymentEventStore eventStore;
    private final PaymentProvider psp;
    private final Inbox inbox;
    private final UnitOfWork unitOfWork;
    private final Clock clock;

    public PaymentService(PaymentEventStore eventStore, PaymentProvider psp, Inbox inbox,
                            UnitOfWork unitOfWork, Clock clock) {
        this.eventStore = eventStore;
        this.psp = psp;
        this.inbox = inbox;
        this.unitOfWork = unitOfWork;
        this.clock = clock;
    }

    @Override
    public void authorize(UUID messageId, UUID paymentId, String orderId, BigDecimal amount, String cardToken) {
        if (alreadyProcessed(messageId)) return;

        Payment payment = unitOfWork.compute(() -> eventStore.load(paymentId).orElseGet(() -> {
            var newPayment = Payment.start(paymentId, orderId, amount, cardToken, clock.instant());
            eventStore.save(newPayment);
            return newPayment;
        }));

        Result result = payment.status() == PaymentStatus.INITIATED
                ? psp.authorize(paymentId, amount, cardToken, key(paymentId, "authorization"))
                : null;

        unitOfWork.execute(() -> {
            if (!inbox.registerIfNew(CONSUMER, messageId) || result == null) return;
            var current = load(paymentId);
            if (current.status() != PaymentStatus.INITIATED) return;
            if (result.approved()) current.registerAuthorization(result.reference(), clock.instant());
            else current.registerDecline(result.reason(), clock.instant());
            eventStore.save(current);
        });
    }

    @Override
    public void capture(UUID messageId, UUID paymentId) {
        if (alreadyProcessed(messageId)) return;

        Payment payment = unitOfWork.compute(() -> {
            var current = load(paymentId);
            if (current.status() == PaymentStatus.AUTHORIZED) {
                current.requestCapture(clock.instant());
                eventStore.save(current);
            }
            return current;
        });

        if (payment.status() != PaymentStatus.CAPTURE_REQUESTED) {
            // Ex.: a saga já compensou (prazo vencido) enquanto esta mensagem estava no retry topic.
            log.info("Captura do pagamento {} ignorada: status {}", paymentId, payment.status());
            unitOfWork.execute(() -> inbox.registerIfNew(CONSUMER, messageId));
            return;
        }

        // 503 do PSP => TransientFailureException => retry topic com backoff (Caso 2).
        Result result = psp.capture(payment.authorizationId(), key(paymentId, "capture"));

        unitOfWork.execute(() -> {
            if (!inbox.registerIfNew(CONSUMER, messageId)) return;
            var current = load(paymentId);
            if (current.status() != PaymentStatus.CAPTURE_REQUESTED) return;
            if (result.approved()) current.registerCapture(clock.instant());
            else current.registerCaptureDecline(result.reason(), clock.instant());
            eventStore.save(current);
        });
    }

    /**
     * Compensação não pode desistir: se o PSP não confirmar, lança exceção transitória para que a
     * mensagem vá aos retry topics e, esgotadas as tentativas, à DLQ + conciliação (Caso 6).
     */
    @Override
    public void compensate(UUID messageId, UUID paymentId, String reason) {
        if (alreadyProcessed(messageId)) return;

        var payment = unitOfWork.compute(() -> load(paymentId));
        var action = payment.compensationAction();
        boolean captureConfirmedAtPsp = false;

        if (action == CompensationAction.QUERY_PSP) {
            // Timeout não é falha, é desconhecimento: descubra o que aconteceu antes de compensar.
            var pspStatus = psp.lookup(payment.authorizationId());
            log.warn("Captura do pagamento {} em estado desconhecido; PSP diz {}", paymentId, pspStatus);
            captureConfirmedAtPsp = pspStatus == PspStatus.CAPTURED;
            action = captureConfirmedAtPsp ? CompensationAction.REFUND : CompensationAction.VOID_AUTHORIZATION;
        }

        Result result = switch (action) {
            case VOID_AUTHORIZATION -> psp.voidAuthorization(payment.authorizationId(),
                    key(paymentId, "cancellation"));
            case REFUND -> psp.refund(payment.authorizationId(), key(paymentId, "refund"));
            case NONE, QUERY_PSP -> null;
        };
        if (result != null && !result.approved()) {
            throw new TransientFailureException("PSP não confirmou a compensação do pagamento " + paymentId
                    + ": " + result.reason());
        }

        var finalAction = action;
        var registerCaptureFirst = captureConfirmedAtPsp;
        unitOfWork.execute(() -> {
            if (!inbox.registerIfNew(CONSUMER, messageId) || result == null) return;
            var current = load(paymentId);
            var now = clock.instant();
            if (finalAction == CompensationAction.REFUND) {
                if (registerCaptureFirst && current.status() == PaymentStatus.CAPTURE_REQUESTED) {
                    current.registerCapture(now); // o fato aconteceu: fica no histórico
                }
                if (current.status() == PaymentStatus.CAPTURED) current.registerRefund(reason, now);
            } else if (current.compensationAction() != CompensationAction.NONE) {
                current.registerAuthorizationVoid(reason, now);
            }
            eventStore.save(current);
        });
    }

    @Override
    public Optional<PaymentView> lookup(UUID paymentId) {
        return eventStore.load(paymentId).map(p -> new PaymentView(p.id(), p.orderId(), p.status(),
                p.version(), eventStore.history(paymentId)));
    }

    private boolean alreadyProcessed(UUID messageId) {
        if (inbox.alreadyProcessed(CONSUMER, messageId)) {
            log.info("[Caso 4] Comando {} já processado: ignorando", messageId);
            return true;
        }
        return false;
    }

    private Payment load(UUID paymentId) {
        return eventStore.load(paymentId)
                .orElseThrow(() -> new DomainException("Pagamento " + paymentId + " não encontrado"));
    }

    /** Determinística: a mesma operação no mesmo pagamento sempre usa a mesma chave. */
    private static String key(UUID paymentId, String operation) {
        return paymentId + ":" + operation;
    }
}
