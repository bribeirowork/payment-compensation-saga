package br.com.example.payment.domain.checkout;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Comandos que o orquestrador envia aos participantes. Todo comando é roteado pela chave orderId. */
public sealed interface SagaCommand {

    String orderId();

    // --- passos ---
    record AuthorizePayment(String orderId, UUID paymentId, BigDecimal amount, String cardToken)
            implements SagaCommand {}

    record ReserveInventory(String orderId, List<OrderItem> items) implements SagaCommand {}

    record IssueInvoice(String orderId, Customer recipient, List<OrderItem> items, BigDecimal totalAmount)
            implements SagaCommand {}

    record CapturePayment(String orderId, UUID paymentId) implements SagaCommand {}

    // --- compensações ---
    record CancelInvoice(String orderId, String accessKey, String justification) implements SagaCommand {}

    record ReleaseReservation(String orderId) implements SagaCommand {}

    record CompensatePayment(String orderId, UUID paymentId, String reason) implements SagaCommand {}
}
