package br.com.example.payment.application.port.in;

import br.com.example.payment.application.port.out.PaymentEventStore.StoredEvent;
import br.com.example.payment.domain.payment.PaymentStatus;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ExecutePaymentCommands {

    void authorize(UUID messageId, UUID paymentId, String orderId, BigDecimal amount, String cardToken);

    void capture(UUID messageId, UUID paymentId);

    void compensate(UUID messageId, UUID paymentId, String reason);

    Optional<PaymentView> lookup(UUID paymentId);

    record PaymentView(UUID paymentId, String orderId, PaymentStatus status, int version,
                          List<StoredEvent> events) {}
}
