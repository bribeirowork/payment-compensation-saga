package br.com.example.payment.application.port.out;

import br.com.example.payment.domain.checkout.CheckoutSaga;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface SagaRepository {

    void create(CheckoutSaga saga);

    /** Grava com concorrência otimista; lança ConcurrencyConflictException se a versão mudou. */
    void update(CheckoutSaga saga);

    Optional<CheckoutSaga> find(String orderId);

    List<String> withExpiredCaptureDeadline(Instant now);

    List<HistoryEntry> history(String orderId);

    record HistoryEntry(Instant occurredAt, String description) {}
}
