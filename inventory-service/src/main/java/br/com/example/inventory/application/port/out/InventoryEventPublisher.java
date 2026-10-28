package br.com.example.inventory.application.port.out;

import br.com.example.inventory.domain.InventoryEvent;

/** Grava no outbox, na transação corrente. */
public interface InventoryEventPublisher {

    void publish(InventoryEvent event);
}
