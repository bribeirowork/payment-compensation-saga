package br.com.example.payment.infrastructure.persistence;

import br.com.example.payment.application.port.out.ReconciliationLog;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
public class JdbcReconciliationLog implements ReconciliationLog {

    private static final RowMapper<PendingItem> MAPPER = (rs, n) -> new PendingItem(rs.getLong("id"),
            rs.getString("topic"), rs.getString("key"), rs.getString("type"), rs.getString("message"),
            rs.getString("error"), rs.getTimestamp("created_at").toInstant());

    private final JdbcClient jdbc;

    public JdbcReconciliationLog(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long register(String topic, String key, String type, String message, String error) {
        return jdbc.sql("""
                        INSERT INTO pending_reconciliation (topic, key, type, message, error)
                        VALUES (:topic, :key, :type, :message, :error)
                        RETURNING id
                        """)
                .param("topic", topic)
                .param("key", key)
                .param("type", type)
                .param("message", message)
                .param("error", error)
                .query(Long.class)
                .single();
    }

    @Override
    public List<PendingItem> pending() {
        return jdbc.sql("SELECT * FROM pending_reconciliation WHERE reprocessed_at IS NULL ORDER BY id")
                .query(MAPPER)
                .list();
    }

    @Override
    public Optional<PendingItem> find(long id) {
        return jdbc.sql("SELECT * FROM pending_reconciliation WHERE id = :id AND reprocessed_at IS NULL")
                .param("id", id)
                .query(MAPPER)
                .optional();
    }

    @Override
    public void markReprocessed(long id) {
        jdbc.sql("UPDATE pending_reconciliation SET reprocessed_at = now() WHERE id = :id").param("id", id).update();
    }
}
