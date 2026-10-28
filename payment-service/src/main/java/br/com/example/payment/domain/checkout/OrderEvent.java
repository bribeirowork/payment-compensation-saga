package br.com.example.payment.domain.checkout;

/** Desfecho do pedido, publicado para quem quiser reagir (notificação, expedição...). */
public sealed interface OrderEvent {

    String orderId();

    record OrderConfirmed(String orderId, String accessKey) implements OrderEvent {}

    record OrderCancelled(String orderId, String reason) implements OrderEvent {}
}
