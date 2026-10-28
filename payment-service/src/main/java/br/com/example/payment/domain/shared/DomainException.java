package br.com.example.payment.domain.shared;

/** Violação de uma regra de negócio. O domínio não conhece Spring, Kafka nem banco. */
public class DomainException extends RuntimeException {

    public DomainException(String message) {
        super(message);
    }
}
