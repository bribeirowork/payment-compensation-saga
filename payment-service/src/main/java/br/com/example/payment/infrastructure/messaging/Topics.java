package br.com.example.payment.infrastructure.messaging;

/**
 * Todos os tópicos usam orderId como chave de partição: os eventos de um mesmo pedido caem
 * na mesma partição e mantêm a ordem (Caso 7).
 */
public final class Topics {

    public static final String PAYMENT_COMMANDS = "payment.commands";
    public static final String PAYMENT_EVENTS = "payment.events";
    public static final String INVENTORY_COMMANDS = "inventory.commands";
    public static final String INVENTORY_EVENTS = "inventory.events";
    public static final String FISCAL_COMMANDS = "fiscal.commands";
    public static final String FISCAL_EVENTS = "fiscal.events";
    public static final String CHECKOUT_EVENTS = "checkout.events";

    private Topics() {
    }
}
