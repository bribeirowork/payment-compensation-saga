package br.com.example.payment.domain.checkout;

import br.com.example.payment.domain.shared.DomainException;

/** Destinatário da NF-e. */
public record Customer(String name, String document) {

    public Customer {
        if (name == null || name.isBlank()) throw new DomainException("Nome do cliente é obrigatório");
        if (document == null || !document.matches("\\d{11}|\\d{14}")) {
            throw new DomainException("Documento do cliente deve ser um CPF (11) ou CNPJ (14) só com dígitos");
        }
    }
}
