package br.com.example.payment.infrastructure.web;

import br.com.example.payment.application.port.in.ExecutePaymentCommands;
import br.com.example.payment.application.port.in.ExecutePaymentCommands.PaymentView;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/payments")
public class PaymentController {

    private final ExecutePaymentCommands payments;

    public PaymentController(ExecutePaymentCommands payments) {
        this.payments = payments;
    }

    /** O stream de eventos (v1..vN) e o estado derivado dele. Nada foi apagado. */
    @GetMapping("/{paymentId}")
    public ResponseEntity<PaymentView> lookup(@PathVariable UUID paymentId) {
        return ResponseEntity.of(payments.lookup(paymentId));
    }
}
