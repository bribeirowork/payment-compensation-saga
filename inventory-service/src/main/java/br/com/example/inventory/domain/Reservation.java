package br.com.example.inventory.domain;

import java.util.List;
import java.util.Map;

public final class Reservation {

    public enum Status { RESERVED, RELEASED }

    public record Item(String sku, int quantity) {}

    private final String orderId;
    private final List<Item> items;
    private Status status;

    private Reservation(String orderId, List<Item> items, Status status) {
        this.orderId = orderId;
        this.items = List.copyOf(items);
        this.status = status;
    }

    public static Reservation place(String orderId, List<Item> items, Map<String, StockItem> inventory) {
        if (items.isEmpty()) throw new DomainException("Reserva sem itens");
        var missing = items.stream()
                .filter(i -> !inventory.containsKey(i.sku()) || !inventory.get(i.sku()).fits(i.quantity()))
                .map(Item::sku)
                .toList();
        if (!missing.isEmpty()) throw new InsufficientStockException(missing);
        items.forEach(i -> inventory.get(i.sku()).reserve(i.quantity()));
        return new Reservation(orderId, items, Status.RESERVED);
    }

    public static Reservation restore(String orderId, List<Item> items, Status status) {
        return new Reservation(orderId, items, status);
    }

    public boolean release(Map<String, StockItem> inventory) {
        if (status == Status.RELEASED) return false;
        items.forEach(i -> inventory.get(i.sku()).restock(i.quantity()));
        status = Status.RELEASED;
        return true;
    }

    public String orderId() { return orderId; }
    public List<Item> items() { return items; }
    public Status status() { return status; }
}
