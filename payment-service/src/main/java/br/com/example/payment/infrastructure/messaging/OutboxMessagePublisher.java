package br.com.example.payment.infrastructure.messaging;

import br.com.example.payment.application.port.out.MessagePublisher;
import br.com.example.payment.domain.checkout.SagaCommand;
import br.com.example.payment.domain.checkout.OrderEvent;
import br.com.example.payment.infrastructure.persistence.JdbcOutbox;
import org.springframework.stereotype.Component;

import java.time.Clock;

/** Traduz comandos/eventos do domínio em mensagens no outbox, com o tópico de cada participante. */
@Component
public class OutboxMessagePublisher implements MessagePublisher {

    private final JdbcOutbox outbox;
    private final Messages messages;
    private final Clock clock;

    public OutboxMessagePublisher(JdbcOutbox outbox, Messages messages, Clock clock) {
        this.outbox = outbox;
        this.messages = messages;
        this.clock = clock;
    }

    @Override
    public void send(SagaCommand command) {
        var envelope = messages.create(command.getClass().getSimpleName(), command.orderId(), clock.instant(),
                command);
        outbox.append(topicOf(command), envelope);
    }

    @Override
    public void publish(OrderEvent event) {
        var envelope = messages.create(event.getClass().getSimpleName(), event.orderId(), clock.instant(),
                event);
        outbox.append(Topics.CHECKOUT_EVENTS, envelope);
    }

    @Override
    public void resendOriginal(String topic, String key, String message) {
        outbox.appendRaw(topic, key, message);
    }

    private static String topicOf(SagaCommand command) {
        return switch (command) {
            case SagaCommand.AuthorizePayment c -> Topics.PAYMENT_COMMANDS;
            case SagaCommand.CapturePayment c -> Topics.PAYMENT_COMMANDS;
            case SagaCommand.CompensatePayment c -> Topics.PAYMENT_COMMANDS;
            case SagaCommand.ReserveInventory c -> Topics.INVENTORY_COMMANDS;
            case SagaCommand.ReleaseReservation c -> Topics.INVENTORY_COMMANDS;
            case SagaCommand.IssueInvoice c -> Topics.FISCAL_COMMANDS;
            case SagaCommand.CancelInvoice c -> Topics.FISCAL_COMMANDS;
        };
    }
}
