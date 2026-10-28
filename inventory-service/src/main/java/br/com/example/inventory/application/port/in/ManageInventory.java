package br.com.example.inventory.application.port.in;

import br.com.example.inventory.domain.StockItem;
import br.com.example.inventory.domain.Reservation;

import java.util.List;
import java.util.UUID;

public interface ManageInventory {

    void reserve(UUID messageId, String orderId, List<Reservation.Item> items);

    void release(UUID messageId, String orderId);

    List<StockItem> balance();
}
