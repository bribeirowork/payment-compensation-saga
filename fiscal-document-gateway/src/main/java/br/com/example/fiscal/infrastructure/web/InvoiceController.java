package br.com.example.fiscal.infrastructure.web;

import br.com.example.fiscal.application.port.in.ManageInvoices;
import br.com.example.fiscal.domain.Invoice;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/invoices")
public class InvoiceController {

    private final ManageInvoices invoices;

    public InvoiceController(ManageInvoices invoices) {
        this.invoices = invoices;
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<Invoice.Data> byOrder(@PathVariable String orderId) {
        return ResponseEntity.of(invoices.byOrder(orderId).map(Invoice::data));
    }
}
