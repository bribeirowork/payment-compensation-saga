package br.com.example.payment.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Mensagens que esgotaram as tentativas e precisam de conciliação (manual ou automática). */
public interface ReconciliationLog {

    long register(String topic, String key, String type, String message, String error);

    List<PendingItem> pending();

    Optional<PendingItem> find(long id);

    void markReprocessed(long id);

    record PendingItem(long id, String topic, String key, String type, String message, String error,
                     Instant createdAt) {}
}
