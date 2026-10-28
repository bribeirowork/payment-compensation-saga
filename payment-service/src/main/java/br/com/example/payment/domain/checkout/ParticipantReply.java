package br.com.example.payment.domain.checkout;

/**
 * O que a saga ouve dos participantes. É a linguagem do contexto de Checkout:
 * a camada de infraestrutura traduz os eventos de cada tópico para estes tipos.
 */
public sealed interface ParticipantReply {

    record PaymentAuthorized() implements ParticipantReply {}

    record PaymentDeclined(String reason) implements ParticipantReply {}

    record InventoryReserved() implements ParticipantReply {}

    record InventoryUnavailable(String reason) implements ParticipantReply {}

    record InvoiceAuthorized(String accessKey, String protocol) implements ParticipantReply {}

    record InvoiceRejected(String code, String reason) implements ParticipantReply {}

    record PaymentCaptured() implements ParticipantReply {}

    record CaptureDeclined(String reason) implements ParticipantReply {}

    record InvoiceCancelled(String protocol) implements ParticipantReply {}

    record InvoiceCancellationRejected(String code, String reason) implements ParticipantReply {}

    record ReservationReleased() implements ParticipantReply {}

    /** Autorização cancelada (void) ou pagamento estornado (refund). */
    record PaymentCompensated(String description) implements ParticipantReply {}

    /** Uma compensação esgotou os retries e caiu na DLQ. */
    record CompensationExhausted(String reason) implements ParticipantReply {}
}
