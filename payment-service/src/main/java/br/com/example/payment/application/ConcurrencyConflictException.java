package br.com.example.payment.application;

/** Dois comandos simultâneos no mesmo agregado: quem chegou depois relê o estado e tenta de novo (Caso 7). */
public class ConcurrencyConflictException extends TransientFailureException {

    public ConcurrencyConflictException(String message) {
        super(message);
    }
}
