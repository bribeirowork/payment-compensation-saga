package br.com.example.inventory.application.port.out;

import java.util.UUID;

public interface Inbox {

    boolean alreadyProcessed(String consumer, UUID messageId);

    boolean registerIfNew(String consumer, UUID messageId);
}
