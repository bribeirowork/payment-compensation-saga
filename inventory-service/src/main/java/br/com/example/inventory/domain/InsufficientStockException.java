package br.com.example.inventory.domain;

import java.util.List;

public class InsufficientStockException extends DomainException {

    public InsufficientStockException(List<String> skus) {
        super("Estoque insuficiente para " + String.join(", ", skus));
    }
}
