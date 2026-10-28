package br.com.example.payment.application.port.out;

import java.util.UUID;

/**
 * Registro das mensagens já processadas por cada consumidor (inbox).
 * Kafka entrega at-least-once: sem isto, um rebalance cobra o cliente duas vezes (Caso 4).
 */
public interface Inbox {

    boolean alreadyProcessed(String consumer, UUID messageId);

    /** Registra na transação corrente. Retorna false se a mensagem já tinha sido processada. */
    boolean registerIfNew(String consumer, UUID messageId);
}
