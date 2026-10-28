package br.com.example.payment.domain.checkout;

import br.com.example.payment.domain.shared.DomainException;

import java.math.BigDecimal;

public record OrderItem(String sku, String description, String ncm, int quantity, BigDecimal unitPrice) {

    public OrderItem {
        if (sku == null || sku.isBlank()) throw new DomainException("SKU é obrigatório");
        if (quantity <= 0) throw new DomainException("Quantidade deve ser positiva");
        if (unitPrice == null || unitPrice.signum() <= 0) {
            throw new DomainException("Valor unitário deve ser positivo");
        }
        // O formato é validado aqui; se o NCM existe na tabela oficial, quem diz é a SEFAZ (Caso 1).
        if (ncm == null || !ncm.matches("\\d{8}")) throw new DomainException("NCM deve ter 8 dígitos");
    }

    public BigDecimal subtotal() {
        return unitPrice.multiply(BigDecimal.valueOf(quantity));
    }
}
