package br.com.example.inventory.infrastructure.persistence;

import br.com.example.inventory.application.port.out.InventoryRepository;
import br.com.example.inventory.domain.StockItem;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class JdbcInventoryRepository implements InventoryRepository {

    private final JdbcClient jdbc;

    public JdbcInventoryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<String, StockItem> block(Collection<String> skus) {
        var items = new LinkedHashMap<String, StockItem>();
        if (skus.isEmpty()) return items;
        jdbc.sql("SELECT sku, available FROM inventory WHERE sku IN (:skus) ORDER BY sku FOR UPDATE")
                .param("skus", List.copyOf(skus))
                .query((rs, n) -> new StockItem(rs.getString("sku"), rs.getInt("available")))
                .list()
                .forEach(i -> items.put(i.sku(), i));
        return items;
    }

    @Override
    public void save(Collection<StockItem> items) {
        for (var item : items) {
            jdbc.sql("UPDATE inventory SET available = :available WHERE sku = :sku")
                    .param("available", item.available())
                    .param("sku", item.sku())
                    .update();
        }
    }

    @Override
    public List<StockItem> all() {
        return jdbc.sql("SELECT sku, available FROM inventory ORDER BY sku")
                .query((rs, n) -> new StockItem(rs.getString("sku"), rs.getInt("available")))
                .list();
    }
}
