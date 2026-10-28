package br.com.example.inventory.application.service;

import br.com.example.inventory.application.port.in.ManageInventory;
import br.com.example.inventory.application.port.out.*;
import br.com.example.inventory.domain.InsufficientStockException;
import br.com.example.inventory.domain.InventoryEvent.InventoryUnavailable;
import br.com.example.inventory.domain.InventoryEvent.InventoryReserved;
import br.com.example.inventory.domain.InventoryEvent.ReservationReleased;
import br.com.example.inventory.domain.StockItem;
import br.com.example.inventory.domain.Reservation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;

public class InventoryService implements ManageInventory {

    private static final Logger log = LoggerFactory.getLogger(InventoryService.class);
    static final String CONSUMER = "inventory-commands";

    private final InventoryRepository inventory;
    private final ReservationRepository reservations;
    private final InventoryEventPublisher publisher;
    private final Inbox inbox;
    private final UnitOfWork unitOfWork;

    public InventoryService(InventoryRepository inventory, ReservationRepository reservations,
                          InventoryEventPublisher publisher, Inbox inbox,
                          UnitOfWork unitOfWork) {
        this.inventory = inventory;
        this.reservations = reservations;
        this.publisher = publisher;
        this.inbox = inbox;
        this.unitOfWork = unitOfWork;
    }

    @Override
    public void reserve(UUID messageId, String orderId, List<Reservation.Item> items) {
        unitOfWork.execute(() -> {
            if (!newMessage(messageId)) return;
            if (reservations.byOrder(orderId).isPresent()) {
                log.info("Pedido {} já tem reserva: respondendo de novo sem reservar outra vez", orderId);
                publisher.publish(new InventoryReserved(orderId));
                return;
            }
            var balance = inventory.block(items.stream().map(Reservation.Item::sku).toList());
            try {
                var reservation = Reservation.place(orderId, items, balance);
                inventory.save(balance.values());
                reservations.save(reservation);
                publisher.publish(new InventoryReserved(orderId));
                log.info("Estoque reservado para o pedido {}", orderId);
            } catch (InsufficientStockException e) {
                publisher.publish(new InventoryUnavailable(orderId, e.getMessage()));
                log.info("Pedido {}: {}", orderId, e.getMessage());
            }
        });
    }

    @Override
    public void release(UUID messageId, String orderId) {
        unitOfWork.execute(() -> {
            if (!newMessage(messageId)) return;
            reservations.byOrder(orderId).ifPresent(reservation -> {
                var balance = inventory.block(reservation.items().stream().map(Reservation.Item::sku).toList());
                if (reservation.release(balance)) {
                    inventory.save(balance.values());
                    reservations.save(reservation);
                    log.info("Reserva do pedido {} liberada", orderId);
                }
            });
            publisher.publish(new ReservationReleased(orderId));
        });
    }

    @Override
    public List<StockItem> balance() {
        return inventory.all();
    }

    private boolean newMessage(UUID messageId) {
        if (inbox.registerIfNew(CONSUMER, messageId)) return true;
        log.info("[Caso 4] Comando {} já processado: ignorando", messageId);
        return false;
    }
}
