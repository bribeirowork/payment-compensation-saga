package br.com.example.inventory.infrastructure.web;

import br.com.example.inventory.application.port.in.ManageInventory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/inventory")
public class InventoryController {

    private final ManageInventory inventory;

    public InventoryController(ManageInventory inventory) {
        this.inventory = inventory;
    }

    @GetMapping
    public List<Map<String, Object>> balance() {
        return inventory.balance().stream()
                .map(i -> Map.<String, Object>of("sku", i.sku(), "available", i.available()))
                .toList();
    }
}
