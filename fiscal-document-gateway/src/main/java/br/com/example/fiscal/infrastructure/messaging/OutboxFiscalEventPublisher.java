package br.com.example.fiscal.infrastructure.messaging;

import br.com.example.fiscal.application.port.out.FiscalEventPublisher;
import br.com.example.fiscal.domain.FiscalEvent;
import br.com.example.fiscal.infrastructure.persistence.JdbcOutbox;
import org.springframework.stereotype.Component;

import java.time.Clock;

@Component
public class OutboxFiscalEventPublisher implements FiscalEventPublisher {

    private final JdbcOutbox outbox;
    private final Messages messages;
    private final Clock clock;

    public OutboxFiscalEventPublisher(JdbcOutbox outbox, Messages messages, Clock clock) {
        this.outbox = outbox;
        this.messages = messages;
        this.clock = clock;
    }

    @Override
    public void publish(FiscalEvent event) {
        outbox.append(Topics.FISCAL_EVENTS, messages.create(event.getClass().getSimpleName(),
                event.orderId(), clock.instant(), event));
    }
}
