package br.com.example.fiscal.domain;

public enum InvoiceStatus {
    /** Chave gerada e gravada; ainda não houve resposta da SEFAZ. */
    PENDING_SUBMISSION,
    /** Enviada, mas a conexão caiu: a nota pode ou não estar autorizada (Caso 3). */
    STATUS_UNKNOWN,
    /** SEFAZ indisponível além do limite de retries: fluxo de contingência. */
    PENDING_CONTINGENCY,
    AUTHORIZED,
    REJECTED,
    CANCELLED;

    public boolean awaitingSefaz() {
        return this == PENDING_SUBMISSION || this == STATUS_UNKNOWN || this == PENDING_CONTINGENCY;
    }
}
