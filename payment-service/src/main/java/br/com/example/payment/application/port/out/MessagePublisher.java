package br.com.example.payment.application.port.out;

import br.com.example.payment.domain.checkout.SagaCommand;
import br.com.example.payment.domain.checkout.OrderEvent;

/**
 * Publicação assíncrona. A implementação grava no outbox, dentro da transação corrente;
 * um relay separado entrega no Kafka. Nunca publica direto no broker de dentro do caso de uso.
 */
public interface MessagePublisher {

    void send(SagaCommand command);

    void publish(OrderEvent event);

    /** Reenfileira uma mensagem original, tal como chegou (usado na conciliação). */
    void resendOriginal(String topic, String key, String message);
}
