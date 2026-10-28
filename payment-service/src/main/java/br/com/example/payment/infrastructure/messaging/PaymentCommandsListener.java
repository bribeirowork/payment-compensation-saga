package br.com.example.payment.infrastructure.messaging;

import br.com.example.payment.application.port.in.Reconcile;
import br.com.example.payment.application.port.in.ExecutePaymentCommands;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.TopicSuffixingStrategy;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;

import java.util.UUID;

import static br.com.example.payment.infrastructure.messaging.Messages.text;

/**
 * Comandos que a saga envia ao contexto de Pagamento.
 *
 * pagamento.comandos → pagamento.comandos-retry-0 → -retry-1 → -retry-2 → pagamento.comandos-dlq
 * com backoff exponencial. Uma captura com 503 (tok_capture_flaky) se resolve no retry;
 * uma compensação que nunca confirma (tok_void_down) termina na DLQ + conciliação.
 */
@Component
public class PaymentCommandsListener {

    private final ExecutePaymentCommands payments;
    private final Reconcile reconciliation;
    private final Messages messages;

    public PaymentCommandsListener(ExecutePaymentCommands payments, Reconcile reconciliation,
                                     Messages messages) {
        this.payments = payments;
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
    @KafkaListener(id = "payment-commands", groupId = "payment-service.payment", topics = Topics.PAYMENT_COMMANDS)
    public void onCommand(ConsumerRecord<String, String> record) {
        var envelope = messages.read(record.value());
        var p = envelope.payload();
        var paymentId = UUID.fromString(text(p, "paymentId"));
        switch (envelope.type()) {
            case "AuthorizePayment" -> payments.authorize(envelope.id(), paymentId, envelope.orderId(),
                    p.get("amount").decimalValue(), text(p, "cardToken"));
            case "CapturePayment" -> payments.capture(envelope.id(), paymentId);
            case "CompensatePayment" -> payments.compensate(envelope.id(), paymentId, text(p, "reason"));
            default -> throw new InvalidMessageException("Comando desconhecido: " + envelope.type());
        }
    }

    @DltHandler
    public void onDlq(ConsumerRecord<String, String> record) {
        String type;
        try {
            type = messages.read(record.value()).type();
        } catch (RuntimeException e) {
            type = "unknown";
        }
        reconciliation.registerPermanentFailure(Messages.originalTopic(record), record.key(), type,
                record.value(), Messages.error(record));
    }
}
