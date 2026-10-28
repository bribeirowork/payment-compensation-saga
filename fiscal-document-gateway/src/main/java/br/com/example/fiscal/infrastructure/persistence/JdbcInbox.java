package br.com.example.fiscal.infrastructure.persistence;

import br.com.example.fiscal.application.port.out.Inbox;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class JdbcInbox implements Inbox {

    private final JdbcClient jdbc;

    public JdbcInbox(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean alreadyProcessed(String consumer, UUID messageId) {
        return jdbc.sql("""
                        SELECT count(*) FROM processed_messages
                         WHERE consumer = :consumer AND message_id = :id
                        """)
                .param("consumer", consumer)
                .param("id", messageId)
                .query(Integer.class)
                .single() > 0;
    }

    @Override
    public boolean registerIfNew(String consumer, UUID messageId) {
        return jdbc.sql("""
                        INSERT INTO processed_messages (consumer, message_id) VALUES (:consumer, :id)
                        ON CONFLICT DO NOTHING
                        """)
                .param("consumer", consumer)
                .param("id", messageId)
                .update() == 1;
    }
}
