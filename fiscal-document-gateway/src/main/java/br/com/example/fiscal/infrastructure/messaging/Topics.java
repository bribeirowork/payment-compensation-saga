package br.com.example.fiscal.infrastructure.messaging;

/** Chave de partição = orderId em todos os tópicos (ordem por pedido, Caso 7). */
public final class Topics {

    public static final String FISCAL_COMMANDS = "fiscal.commands";
    public static final String FISCAL_EVENTS = "fiscal.events";

    private Topics() {
    }
}
