package br.com.example.payment.application.port.out;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Gateway de pagamento (PSP). Toda operação leva uma Idempotency-Key: se a falha acontecer entre
 * a chamada e o registro local, a repetição não cobra duas vezes.
 *
 * Recusas de negócio voltam como {@link Resultado} não aprovado; indisponibilidade lança
 * {@link br.com.exemplo.payment.application.TransientFailureException}.
 */
public interface PaymentProvider {

    Result authorize(UUID paymentId, BigDecimal amount, String cardToken, String idempotencyKey);

    Result capture(String authorizationId, String idempotencyKey);

    Result voidAuthorization(String authorizationId, String idempotencyKey);

    Result refund(String authorizationId, String idempotencyKey);

    PspStatus lookup(String authorizationId);

    record Result(boolean approved, String reference, String reason) {}

    enum PspStatus { AUTHORIZED, CAPTURED, CANCELLED, REFUNDED }
}
