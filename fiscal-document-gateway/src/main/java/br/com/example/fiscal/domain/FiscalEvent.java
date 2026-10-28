package br.com.example.fiscal.domain;

/** Eventos que o gateway fiscal publica para o resto do ecossistema. */
public sealed interface FiscalEvent {

    String orderId();

    record InvoiceAuthorized(String orderId, String accessKey, String protocol) implements FiscalEvent {}

    record InvoiceRejected(String orderId, String accessKey, String code, String reason) implements FiscalEvent {}

    record InvoiceCancelled(String orderId, String accessKey, String protocol) implements FiscalEvent {}

    record InvoiceCancellationRejected(String orderId, String accessKey, String code, String reason)
            implements FiscalEvent {}
}
