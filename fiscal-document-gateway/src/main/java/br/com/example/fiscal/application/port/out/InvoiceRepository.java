package br.com.example.fiscal.application.port.out;

import br.com.example.fiscal.domain.Invoice;

import java.util.Optional;

public interface InvoiceRepository {

    Optional<Invoice> byOrder(String orderId);

    long nextNumber();

    void insert(Invoice invoice);

    void update(Invoice invoice);
}
