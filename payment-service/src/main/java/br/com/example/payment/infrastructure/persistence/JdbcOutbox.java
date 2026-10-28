package br.com.example.payment.infrastructure.persistence;

import br.com.example.payment.infrastructure.messaging.Envelope;
import br.com.example.payment.infrastructure.messaging.Messages;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Transactional Outbox (Caso 5). A mensagem é gravada na mesma transação da mudança de estado;
 * o {@link br.com.exemplo.payment.infrastructure.messaging.OutboxRelay} entrega no Kafka depois.
 * Se o serviço cair entre o commit e o publish, a linha continua lá e será publicada na volta.
 */
@Component
public class JdbcOutbox {

    private final JdbcClient jdbc;
    private final Messages messages;

    public JdbcOutbox(JdbcClient jdbc, Messages messages) {
        this.jdbc = jdbc;
        this.messages = messages;
    }

    public void append(String topic, Envelope envelope) {
        appendRaw(topic, envelope.orderId(), messages.serialize(envelope));
    }

    public void appendRaw(String topic, String key, String message) {
        jdbc.sql("INSERT INTO outbox (topic, key, message) VALUES (:topic, :key, CAST(:message AS jsonb))")
                .param("topic", topic)
                .param("key", key)
                .param("message", message)
                .update();
    }
}
