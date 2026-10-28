package br.com.example.fiscal.application.port.in;

import br.com.example.fiscal.domain.Recipient;
import br.com.example.fiscal.domain.InvoiceItem;
import br.com.example.fiscal.domain.Invoice;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ManageInvoices {

    void issue(UUID messageId, IssuanceRequest request);

    void cancel(UUID messageId, String orderId, String justification);

    void issuanceExhausted(String orderId, String error);

    void cancellationExhausted(UUID messageId, String orderId, String error);

    Optional<Invoice> byOrder(String orderId);

    record IssuanceRequest(String orderId, Recipient recipient, List<InvoiceItem> items,
                                BigDecimal totalAmount) {}
}
