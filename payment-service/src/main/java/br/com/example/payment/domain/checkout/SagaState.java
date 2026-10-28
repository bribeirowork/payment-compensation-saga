package br.com.example.payment.domain.checkout;

public enum SagaState {
    // Caminho feliz (T2..T5). T1 = a própria criação da saga/pedido.
    AUTHORIZING_PAYMENT,
    RESERVING_INVENTORY,
    ISSUING_INVOICE,
    CAPTURING_PAYMENT,   // pivô: depois daqui não se volta atrás
    COMPLETED,

    // Compensação em ordem reversa
    CANCELLING_INVOICE,
    RELEASING_INVENTORY,
    COMPENSATING_PAYMENT,
    AWAITING_RECONCILIATION, // a compensação do pagamento esgotou os retries (Caso 6)
    CANCELLED,

    MANUAL_INTERVENTION;     // ex.: NF-e fora do prazo de cancelamento

    public boolean terminal() {
        return this == COMPLETED || this == CANCELLED;
    }
}
