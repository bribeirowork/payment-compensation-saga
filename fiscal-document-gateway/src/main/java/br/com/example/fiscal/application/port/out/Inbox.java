package br.com.example.fiscal.application.port.out;

import java.util.UUID;

public interface Inbox {

    boolean alreadyProcessed(String consumer, UUID messageId);

    boolean registerIfNew(String consumer, UUID messageId);
}
