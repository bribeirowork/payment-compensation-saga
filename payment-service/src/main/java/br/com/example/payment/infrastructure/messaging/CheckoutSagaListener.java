package br.com.example.payment.infrastructure.messaging;

import br.com.example.payment.application.port.in.Reconcile;
import br.com.example.payment.application.port.in.DriveSaga;
import br.com.example.payment.domain.checkout.ParticipantReply;
import br.com.example.payment.domain.checkout.ParticipantReply.*;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.TopicSuffixingStrategy;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;

import static br.com.example.payment.infrastructure.messaging.Messages.text;

/**
 * Adapter de entrada da saga: ouve os eventos dos participantes e traduz para a linguagem do
 * contexto de Checkout. Falhas transitórias (ex.: conflito de versão) vão para os retry topics;
 * esgotadas as tentativas, para a DLQ.
 */
@Component
public class CheckoutSagaListener {

    private static final Logger log = LoggerFactory.getLogger(CheckoutSagaListener.class);

    private final DriveSaga saga;
    private final Reconcile reconciliation;
    private final Messages messages;

    public CheckoutSagaListener(DriveSaga saga, Reconcile reconciliation, Messages messages) {
        this.saga = saga;
        this.reconciliation = reconciliation;
        this.messages = messages;
    }

    @RetryableTopic(
            attempts = "${app.kafka.attempts}",
            backoff = @Backoff(delayExpression = "${app.kafka.initial-backoff-ms}", multiplier = 2.0, maxDelay = 30_000),
            retryTopicSuffix = "-retry",
            dltTopicSuffix = "-dlq",
            topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,
            exclude = InvalidMessageException.class,
            traversingCauses = "true")
    @KafkaListener(id = "checkout-saga", groupId = "payment-service.checkout-saga",
            topics = {Topics.PAYMENT_EVENTS, Topics.INVENTORY_EVENTS, Topics.FISCAL_EVENTS})
    public void onEvent(ConsumerRecord<String, String> record) {
        var envelope = messages.read(record.value());
        var response = translate(envelope);
        if (response == null) return; // evento que não interessa à saga (ex.: CaptureRequested)
        log.debug("Saga recebeu {} do pedido {} ({})", envelope.type(), envelope.orderId(), record.topic());
        saga.process(envelope.id(), envelope.orderId(), response);
    }

    @DltHandler
    public void onDlq(ConsumerRecord<String, String> record) {
        reconciliation.registerPermanentFailure(Messages.originalTopic(record), record.key(),
                typeOrUnknown(record.value()), record.value(), Messages.error(record));
    }

    private ParticipantReply translate(Envelope e) {
        var p = e.payload();
        return switch (e.type()) {
            // pagamento.eventos (o próprio agregado Pagamento, via outbox)
            case "PaymentAuthorized" -> new PaymentAuthorized();
            case "PaymentDeclined" -> new PaymentDeclined(text(p, "reason"));
            case "PaymentCaptured" -> new PaymentCaptured();
            case "CaptureDeclined" -> new CaptureDeclined(text(p, "reason"));
            case "AuthorizationVoided" -> new PaymentCompensated("Autorização cancelada (void)");
            case "PaymentRefunded" -> new PaymentCompensated("Pagamento estornado (refund)");
            // estoque.eventos
            case "InventoryReserved" -> new InventoryReserved();
            case "InventoryUnavailable" -> new InventoryUnavailable(text(p, "reason"));
            case "ReservationReleased" -> new ReservationReleased();
            // fiscal.eventos
            case "InvoiceAuthorized" -> new InvoiceAuthorized(text(p, "accessKey"), text(p, "protocol"));
            case "InvoiceRejected" -> new InvoiceRejected(text(p, "code"), text(p, "reason"));
            case "InvoiceCancelled" -> new InvoiceCancelled(text(p, "protocol"));
            case "InvoiceCancellationRejected" -> new InvoiceCancellationRejected(text(p, "code"), text(p, "reason"));
            default -> null;
        };
    }

    private String typeOrUnknown(String value) {
        try {
            return messages.read(value).type();
        } catch (RuntimeException e) {
            return "unknown";
        }
    }
}
