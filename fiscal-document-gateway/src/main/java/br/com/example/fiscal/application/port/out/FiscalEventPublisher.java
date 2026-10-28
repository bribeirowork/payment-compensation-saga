package br.com.example.fiscal.application.port.out;

import br.com.example.fiscal.domain.FiscalEvent;

public interface FiscalEventPublisher {

    void publish(FiscalEvent event);
}
