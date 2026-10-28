package br.com.example.inventory.application.port.out;

import br.com.example.inventory.domain.StockItem;

import java.util.Collection;
import java.util.List;
import java.util.Map;

public interface InventoryRepository {

    /** Carrega com lock pessimista (SELECT ... FOR UPDATE), sempre na mesma ordem para evitar deadlock. */
    Map<String, StockItem> block(Collection<String> skus);

    void save(Collection<StockItem> items);

    List<StockItem> all();
}
