package br.com.example.payment.infrastructure.messaging;

/** Mensagem que nunca vai dar certo (poison pill): vai direto para a DLQ, sem retry. */
public class InvalidMessageException extends RuntimeException {

    public InvalidMessageException(String message, Throwable cause) {
        super(message, cause);
    }

    public InvalidMessageException(String message) {
        super(message);
    }
}
