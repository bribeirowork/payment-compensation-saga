package br.com.example.payment.application.port.in;

import br.com.example.payment.application.port.out.SagaRepository.HistoryEntry;
import br.com.example.payment.domain.checkout.Customer;
import br.com.example.payment.domain.checkout.SagaState;
import br.com.example.payment.domain.checkout.OrderItem;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ManageCheckout {

    /** Cria o pedido e dispara a saga. Idempotente por orderId. */
    String start(NewCheckout checkout);

    /** "Em que passo está o pedido 48213?" */
    Optional<CheckoutView> lookup(String orderId);

    record NewCheckout(String orderId, Customer customer, List<OrderItem> items, String cardToken) {}

    record CheckoutView(String orderId, SagaState state, UUID paymentId, BigDecimal totalAmount,
                         String accessKey, Instant captureDeadlineAt, String failureReason,
                         List<HistoryEntry> history) {}
}
