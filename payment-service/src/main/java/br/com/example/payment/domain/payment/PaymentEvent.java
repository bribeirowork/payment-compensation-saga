package br.com.example.payment.domain.payment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Fatos imutáveis do agregado Pagamento. O estado não é guardado: é derivado destes eventos.
 *
 * Um stream típico de compensação (seção 1 do artigo):
 * <pre>
 * v1 PaymentInitiated
 * v2 PaymentAuthorized
 * v3 CaptureRequested
 * v4 CaptureDeclined
 * v5 AuthorizationVoided   ← evento de compensação
 * </pre>
 */
public sealed interface PaymentEvent {

    UUID paymentId();

    Instant occurredAt();

    record PaymentInitiated(UUID paymentId, String orderId, BigDecimal amount, String cardToken,
                             Instant occurredAt) implements PaymentEvent {}

    record PaymentAuthorized(UUID paymentId, String authorizationId, Instant occurredAt) implements PaymentEvent {}

    record PaymentDeclined(UUID paymentId, String reason, Instant occurredAt) implements PaymentEvent {}

    record CaptureRequested(UUID paymentId, Instant occurredAt) implements PaymentEvent {}

    record PaymentCaptured(UUID paymentId, Instant occurredAt) implements PaymentEvent {}

    record CaptureDeclined(UUID paymentId, String reason, Instant occurredAt) implements PaymentEvent {}

    /** Compensação de uma pré-autorização (void): o dinheiro nunca saiu da conta do cliente. */
    record AuthorizationVoided(UUID paymentId, String reason, Instant occurredAt) implements PaymentEvent {}

    /** Compensação de uma captura (refund): mais cara, por isso a captura é o pivô da saga. */
    record PaymentRefunded(UUID paymentId, String reason, Instant occurredAt) implements PaymentEvent {}
}
