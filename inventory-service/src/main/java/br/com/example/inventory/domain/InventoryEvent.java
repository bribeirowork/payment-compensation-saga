package br.com.example.inventory.domain;

public sealed interface InventoryEvent {

    String orderId();

    record InventoryReserved(String orderId) implements InventoryEvent {}

    record InventoryUnavailable(String orderId, String reason) implements InventoryEvent {}

    record ReservationReleased(String orderId) implements InventoryEvent {}
}
