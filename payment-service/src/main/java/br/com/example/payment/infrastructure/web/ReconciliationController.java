package br.com.example.payment.infrastructure.web;

import br.com.example.payment.application.port.in.Reconcile;
import br.com.example.payment.application.port.out.ReconciliationLog.PendingItem;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Operação da DLQ: listar o que ficou pendente e reprocessar depois que o gateway voltou. */
@RestController
@RequestMapping("/reconciliations")
public class ReconciliationController {

    private final Reconcile reconciliation;

    public ReconciliationController(Reconcile reconciliation) {
        this.reconciliation = reconciliation;
    }

    @GetMapping
    public List<PendingItem> pending() {
        return reconciliation.pending();
    }

    @PostMapping("/{id}/reprocess")
    public ResponseEntity<Void> reprocess(@PathVariable long id) {
        reconciliation.reprocess(id);
        return ResponseEntity.accepted().build();
    }
}
