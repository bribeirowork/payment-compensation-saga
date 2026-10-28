package br.com.example.inventory.infrastructure.messaging;

import br.com.example.inventory.application.port.out.InventoryEventPublisher;
import br.com.example.inventory.domain.InventoryEvent;
import br.com.example.inventory.infrastructure.persistence.JdbcOutbox;
import org.springframework.stereotype.Component;

import java.time.Clock;

@Component
public class OutboxInventoryEventPublisher implements InventoryEventPublisher {

    private final JdbcOutbox outbox;
    private final Messages messages;
    private final Clock clock;

    public OutboxInventoryEventPublisher(JdbcOutbox outbox, Messages messages, Clock clock) {
        this.outbox = outbox;
        this.messages = messages;
        this.clock = clock;
    }

    @Override
    public void publish(InventoryEvent event) {
        outbox.append(Topics.INVENTORY_EVENTS, messages.create(event.getClass().getSimpleName(),
                event.orderId(), clock.instant(), event));
    }
}
