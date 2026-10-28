package br.com.example.inventory.domain;
public final class StockItem {

    private final String sku;
    private int available;

    public StockItem(String sku, int available) {
        this.sku = sku;
        this.available = available;
    }

    public boolean fits(int quantity) {
        return available >= quantity;
    }

    public void reserve(int quantity) {
        if (!fits(quantity)) {
            throw new DomainException("Saldo insuficiente de " + sku + ": " + available + " < " + quantity);
        }
        available -= quantity;
    }

    public void restock(int quantity) {
        available += quantity;
    }

    public String sku() { return sku; }
    public int available() { return available; }
}
