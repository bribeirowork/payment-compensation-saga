package br.com.example.payment.domain.payment;

public enum PaymentStatus {
    INITIATED,
    AUTHORIZED,
    DECLINED,
    CAPTURE_REQUESTED,
    CAPTURED,
    CAPTURE_DECLINED,
    CANCELLED,
    REFUNDED
}
