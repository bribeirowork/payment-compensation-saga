package br.com.example.fiscal.domain;

import java.math.BigDecimal;

public record InvoiceItem(String sku, String description, String ncm, int quantity, BigDecimal unitPrice) {
}
