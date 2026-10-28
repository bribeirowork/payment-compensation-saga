package br.com.example.payment.infrastructure.persistence;

import br.com.example.payment.application.ConcurrencyConflictException;
import br.com.example.payment.application.port.out.SagaRepository;
import br.com.example.payment.domain.checkout.CheckoutSaga;
import br.com.example.payment.domain.checkout.SagaState;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Component
public class JdbcSagaRepository implements SagaRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public JdbcSagaRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public void create(CheckoutSaga saga) {
        jdbc.sql("""
                        INSERT INTO checkout_saga (order_id, state, capture_deadline_at, version, data)
                        VALUES (:orderId, :state, :deadline, 0, CAST(:data AS jsonb))
                        """)
                .param("orderId", saga.orderId())
                .param("state", saga.state().name())
                .param("deadline", timestamp(saga.captureDeadlineAt()))
                .param("data", serialize(saga))
                .update();
        recordHistory(saga);
    }

    @Override
    public void update(CheckoutSaga saga) {
        int rows = jdbc.sql("""
                        UPDATE checkout_saga
                           SET state = :state, capture_deadline_at = :deadline, data = CAST(:data AS jsonb),
                               version = version + 1, updated_at = now()
                         WHERE order_id = :orderId AND version = :version
                        """)
                .param("orderId", saga.orderId())
                .param("state", saga.state().name())
                .param("deadline", timestamp(saga.captureDeadlineAt()))
                .param("data", serialize(saga))
                .param("version", saga.version())
                .update();
        if (rows == 0) {
            throw new ConcurrencyConflictException("Saga " + saga.orderId() + " foi alterada por outra transação");
        }
        recordHistory(saga);
    }

    @Override
    public Optional<CheckoutSaga> find(String orderId) {
        return jdbc.sql("SELECT data::text AS data, version FROM checkout_saga WHERE order_id = :orderId")
                .param("orderId", orderId)
                .query((rs, n) -> deserialize(rs.getString("data"), rs.getInt("version")))
                .optional();
    }

    @Override
    public List<String> withExpiredCaptureDeadline(Instant now) {
        return jdbc.sql("""
                        SELECT order_id FROM checkout_saga
                         WHERE state = :state AND capture_deadline_at < :now
                        """)
                .param("state", SagaState.CAPTURING_PAYMENT.name())
                .param("now", Timestamp.from(now))
                .query(String.class)
                .list();
    }

    @Override
    public List<HistoryEntry> history(String orderId) {
        return jdbc.sql("""
                        SELECT occurred_at, description FROM checkout_saga_history
                         WHERE order_id = :orderId ORDER BY id
                        """)
                .param("orderId", orderId)
                .query((rs, n) -> new HistoryEntry(rs.getTimestamp("occurred_at").toInstant(),
                        rs.getString("description")))
                .list();
    }

    private void recordHistory(CheckoutSaga saga) {
        for (var row : saga.history()) {
            jdbc.sql("INSERT INTO checkout_saga_history (order_id, description) VALUES (:orderId, :description)")
                    .param("orderId", saga.orderId())
                    .param("description", row)
                    .update();
        }
    }

    private String serialize(CheckoutSaga saga) {
        try {
            return json.writeValueAsString(saga.data());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private CheckoutSaga deserialize(String data, int version) {
        try {
            var d = json.readValue(data, CheckoutSaga.Data.class);
            return CheckoutSaga.restore(new CheckoutSaga.Data(d.orderId(), d.paymentId(), d.customer(),
                    d.items(), d.cardToken(), d.captureDeadline(), d.state(), d.paymentAuthorized(),
                    d.inventoryReserved(), d.invoiceAuthorized(), d.accessKey(), d.captureDeadlineAt(),
                    d.failureReason(), version));
        } catch (Exception e) {
            throw new IllegalStateException("Saga ilegível", e);
        }
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
