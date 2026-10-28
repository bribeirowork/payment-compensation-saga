package br.com.example.fiscal.infrastructure.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.TimeUnit;

/**
 * Relay do outbox por polling. Em produção, um CDC como o Debezium lendo o WAL do Postgres
 * faz o mesmo papel com menos carga no banco (veja docs/debezium-outbox.json).
 *
 * A entrega é at-least-once: se cair depois do send e antes do UPDATE, a mensagem sai de novo.
 * Por isso todo consumidor é idempotente.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final JdbcClient jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final boolean simulateDuplicateDelivery;

    public OutboxRelay(JdbcClient jdbc, KafkaTemplate<String, String> kafka,
                       @Value("${outbox.simulate-duplicate-delivery:false}") boolean simulateDuplicateDelivery) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.simulateDuplicateDelivery = simulateDuplicateDelivery;
    }

    public record Pending(long seq, String topic, String key, String message) {}

    @Scheduled(fixedDelayString = "${outbox.interval-ms:300}")
    @Transactional
    public void publishPending() throws Exception {
        var pending = jdbc.sql("""
                        SELECT seq, topic, key, message::text AS message
                          FROM outbox
                         WHERE published_at IS NULL
                         ORDER BY seq
                         LIMIT 100
                         FOR UPDATE SKIP LOCKED
                        """)
                .query(Pending.class)
                .list();

        for (var p : pending) {
            kafka.send(p.topic(), p.key(), p.message()).get(10, TimeUnit.SECONDS);
            if (simulateDuplicateDelivery) {
                log.warn("[Caso 4] Simulando entrega duplicada de {} em {}", p.seq(), p.topic());
                kafka.send(p.topic(), p.key(), p.message()).get(10, TimeUnit.SECONDS);
            }
            jdbc.sql("UPDATE outbox SET published_at = now() WHERE seq = :seq").param("seq", p.seq()).update();
        }
    }
}
