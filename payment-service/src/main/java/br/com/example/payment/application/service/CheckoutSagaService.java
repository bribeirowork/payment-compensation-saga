package br.com.example.payment.application.service;

import br.com.example.payment.application.port.in.DriveSaga;
import br.com.example.payment.application.port.in.ManageCheckout;
import br.com.example.payment.application.port.out.Inbox;
import br.com.example.payment.application.port.out.MessagePublisher;
import br.com.example.payment.application.port.out.SagaRepository;
import br.com.example.payment.application.port.out.UnitOfWork;
import br.com.example.payment.domain.checkout.CheckoutSaga;
import br.com.example.payment.domain.checkout.ParticipantReply;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Casos de uso do orquestrador. Cada reação acontece numa única transação local:
 * inbox (dedup) + novo estado da saga + comandos no outbox. Ou tudo, ou nada.
 */
public class CheckoutSagaService implements ManageCheckout, DriveSaga {

    private static final Logger log = LoggerFactory.getLogger(CheckoutSagaService.class);
    static final String CONSUMER = "checkout-saga";

    private final SagaRepository sagas;
    private final Inbox inbox;
    private final MessagePublisher publisher;
    private final UnitOfWork unitOfWork;
    private final Clock clock;
    private final Duration captureDeadline;

    public CheckoutSagaService(SagaRepository sagas, Inbox inbox,
                               MessagePublisher publisher, UnitOfWork unitOfWork,
                               Clock clock, Duration captureDeadline) {
        this.sagas = sagas;
        this.inbox = inbox;
        this.publisher = publisher;
        this.unitOfWork = unitOfWork;
        this.clock = clock;
        this.captureDeadline = captureDeadline;
    }

    @Override
    public String start(NewCheckout checkout) {
        var orderId = checkout.orderId() != null ? checkout.orderId() : UUID.randomUUID().toString();
        unitOfWork.execute(() -> {
            if (sagas.find(orderId).isPresent()) {
                log.info("Checkout {} já existe: requisição repetida ignorada", orderId);
                return;
            }
            var saga = CheckoutSaga.start(orderId, UUID.randomUUID(), checkout.customer(), checkout.items(),
                    checkout.cardToken(), captureDeadline);
            sagas.create(saga);
            dispatch(saga);
        });
        return orderId;
    }

    @Override
    public Optional<CheckoutView> lookup(String orderId) {
        return sagas.find(orderId).map(s -> new CheckoutView(s.orderId(), s.state(), s.paymentId(),
                s.totalAmount(), s.accessKey(), s.captureDeadlineAt(), s.failureReason(), sagas.history(orderId)));
    }

    @Override
    public void process(UUID messageId, String orderId, ParticipantReply response) {
        unitOfWork.execute(() -> {
            if (!inbox.registerIfNew(CONSUMER, messageId)) {
                log.info("[Caso 4] Mensagem {} ({}) já processada: ignorando", messageId,
                        response.getClass().getSimpleName());
                return;
            }
            var saga = sagas.find(orderId).orElse(null);
            if (saga == null) {
                log.warn("Resposta {} para pedido desconhecido {}", response.getClass().getSimpleName(), orderId);
                return;
            }
            saga.react(response, clock.instant());
            sagas.update(saga);
            dispatch(saga);
        });
    }

    @Override
    public void checkDeadlines() {
        var now = clock.instant();
        for (var orderId : sagas.withExpiredCaptureDeadline(now)) {
            unitOfWork.execute(() -> sagas.find(orderId).ifPresent(saga -> {
                saga.checkDeadline(now);
                sagas.update(saga);
                dispatch(saga);
                log.warn("[Caso 2] Prazo de captura vencido no pedido {}: compensando", orderId);
            }));
        }
    }

    private void dispatch(CheckoutSaga saga) {
        saga.history().forEach(row -> log.info("[saga {}] {}", saga.orderId(), row));
        saga.commands().forEach(publisher::send);
        saga.events().forEach(publisher::publish);
    }
}
