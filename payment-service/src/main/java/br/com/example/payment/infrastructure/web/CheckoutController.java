package br.com.example.payment.infrastructure.web;

import br.com.example.payment.application.port.in.ManageCheckout;
import br.com.example.payment.application.port.in.ManageCheckout.NewCheckout;
import br.com.example.payment.application.port.in.ManageCheckout.CheckoutView;
import br.com.example.payment.domain.checkout.Customer;
import br.com.example.payment.domain.checkout.OrderItem;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/checkouts")
public class CheckoutController {

    private final ManageCheckout checkout;

    public CheckoutController(ManageCheckout checkout) {
        this.checkout = checkout;
    }

    public record CustomerRequest(String name, String document) {}

    public record ItemRequest(String sku, String description, String ncm, int quantity, BigDecimal unitPrice) {}

    public record CheckoutRequest(String orderId, CustomerRequest customer, List<ItemRequest> items, String cardToken) {}

    /** 202: a resposta chega depois. Consistência eventual faz parte do contrato. */
    @PostMapping
    public ResponseEntity<Map<String, String>> start(@RequestBody CheckoutRequest req) {
        var items = req.items() == null ? List.<OrderItem>of() : req.items().stream()
                .map(i -> new OrderItem(i.sku(), i.description(), i.ncm(), i.quantity(), i.unitPrice()))
                .toList();
        var customer = req.customer() == null ? null : new Customer(req.customer().name(), req.customer().document());
        var orderId = checkout.start(new NewCheckout(req.orderId(), customer, items, req.cardToken()));
        return ResponseEntity.accepted()
                .location(URI.create("/checkouts/" + orderId))
                .body(Map.of("orderId", orderId, "track", "/checkouts/" + orderId));
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<CheckoutView> lookup(@PathVariable String orderId) {
        return ResponseEntity.of(checkout.lookup(orderId));
    }
}
