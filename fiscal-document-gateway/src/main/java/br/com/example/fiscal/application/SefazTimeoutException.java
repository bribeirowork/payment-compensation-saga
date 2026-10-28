package br.com.example.fiscal.application;

public class SefazTimeoutException extends TransientFailureException {

    public SefazTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
