package br.com.example.payment.domain.payment;

/** O que é preciso fazer no PSP para desfazer o pagamento, dado o estado atual. */
public enum CompensationAction {
    /** Pré-autorização ainda não capturada: void, praticamente grátis. */
    VOID_AUTHORIZATION,
    /** Já houve captura: refund, com custo e atrito. */
    REFUND,
    /** Captura solicitada sem resposta conhecida: antes de compensar, descubra o que aconteceu. */
    QUERY_PSP,
    /** Nada a desfazer (recusado ou já compensado). */
    NONE
}
