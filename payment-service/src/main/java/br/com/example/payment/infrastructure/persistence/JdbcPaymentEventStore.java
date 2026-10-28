package br.com.example.payment.infrastructure.persistence;

import br.com.example.payment.application.ConcurrencyConflictException;
import br.com.example.payment.application.port.out.PaymentEventStore;
import br.com.example.payment.domain.payment.PaymentEvent;
import br.com.example.payment.domain.payment.Payment;
import br.com.example.payment.infrastructure.messaging.Envelope;
import br.com.example.payment.infrastructure.messaging.Topics;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Event store em PostgreSQL. A PK (payment_id, versao) é a concorrência otimista:
 * dois comandos simultâneos no mesmo pagamento não sobrescrevem um ao outro (Caso 7).
 *
 * Kafka é o transporte, não o event store: ele não oferece concorrência otimista por agregado.
 */
@Component
public class JdbcPaymentEventStore implements PaymentEventStore {

    @SuppressWarnings("unchecked")
    private static final Map<String, Class<? extends PaymentEvent>> TYPES =
            Arrays.stream(PaymentEvent.class.getPermittedSubclasses())
                    .map(c -> (Class<? extends PaymentEvent>) c)
                    .collect(Collectors.toMap(Class::getSimpleName, Function.identity()));

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final JdbcOutbox outbox;

    public JdbcPaymentEventStore(JdbcClient jdbc, ObjectMapper json, JdbcOutbox outbox) {
        this.jdbc = jdbc;
        this.json = json;
        this.outbox = outbox;
    }

    @Override
    public Optional<Payment> load(UUID paymentId) {
        var events = jdbc.sql("""
                        SELECT type, data::text AS data FROM payment_events
                         WHERE payment_id = :id ORDER BY version
                        """)
                .param("id", paymentId)
                .query((rs, n) -> deserialize(rs.getString("type"), rs.getString("data")))
                .list();
        return events.isEmpty() ? Optional.empty() : Optional.of(Payment.rehydrate(events));
    }

    @Override
    public void save(Payment payment) {
        int version = payment.persistedVersion();
        for (var event : payment.newEvents()) {
            version++;
            var type = event.getClass().getSimpleName();
            try {
                jdbc.sql("""
                                INSERT INTO payment_events (payment_id, version, type, data, occurred_at)
                                VALUES (:id, :version, :type, CAST(:data AS jsonb), :occurredAt)
                                """)
                        .param("id", payment.id())
                        .param("version", version)
                        .param("type", type)
                        .param("data", serialize(event))
                        .param("occurredAt", Timestamp.from(event.occurredAt()))
                        .update();
            } catch (DuplicateKeyException e) {
                throw new ConcurrencyConflictException("Pagamento " + payment.id() + " já tem a versão "
                        + version + ": outro comando chegou antes");
            }
            // Mesma transação: evento no event store e no outbox, juntos (Caso 5).
            outbox.append(Topics.PAYMENT_EVENTS, new Envelope(UUID.randomUUID(), type, payment.orderId(),
                    event.occurredAt(), json.valueToTree(event)));
        }
        payment.markAsPersisted();
    }

    @Override
    public List<StoredEvent> history(UUID paymentId) {
        return jdbc.sql("""
                        SELECT version, type, data::text AS data, occurred_at FROM payment_events
                         WHERE payment_id = :id ORDER BY version
                        """)
                .param("id", paymentId)
                .query((rs, n) -> new StoredEvent(rs.getInt("version"), rs.getString("type"),
                        readTree(rs.getString("data")), rs.getTimestamp("occurred_at").toInstant()))
                .list();
    }

    private PaymentEvent deserialize(String type, String data) {
        var clazz = TYPES.get(type);
        if (clazz == null) throw new IllegalStateException("Tipo de evento desconhecido: " + type);
        try {
            return json.readValue(data, clazz);
        } catch (Exception e) {
            throw new IllegalStateException("Evento " + type + " ilegível", e);
        }
    }

    private String serialize(PaymentEvent event) {
        try {
            return json.writeValueAsString(event);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Object readTree(String data) {
        try {
            return json.readTree(data);
        } catch (Exception e) {
            return data;
        }
    }
}
