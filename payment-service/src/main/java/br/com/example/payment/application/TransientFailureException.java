package br.com.example.payment.application;

/**
 * Falha que vale a pena tentar de novo (rede, 5xx, conflito de versão...).
 * O consumidor Kafka deixa a exceção subir e a mensagem segue para o retry topic com backoff;
 * esgotadas as tentativas, vai para a DLQ.
 */
public class TransientFailureException extends RuntimeException {

    public TransientFailureException(String message) {
        super(message);
    }

    public TransientFailureException(String message, Throwable cause) {
        super(message, cause);
    }
}
