package br.com.example.payment.application.port.out;

import br.com.example.payment.domain.payment.Payment;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Event store do agregado Pagamento.
 * {@link #salvar} grava os novos eventos com concorrência otimista (versão) e, na MESMA transação,
 * os enfileira no outbox para o Kafka (Caso 5: sem dual write).
 */
public interface PaymentEventStore {

    Optional<Payment> load(UUID paymentId);

    void save(Payment payment);

    List<StoredEvent> history(UUID paymentId);

    record StoredEvent(int version, String type, Object data, Instant occurredAt) {}
}
