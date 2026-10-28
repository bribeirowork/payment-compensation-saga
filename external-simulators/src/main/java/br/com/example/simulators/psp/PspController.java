package br.com.example.simulators.psp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Gateway de pagamento (PSP)
 *
 * cardTokens "mágicos":
 *   tok_ok                -> tudo aprovado
 *   tok_auth_declined     -> pré-autorização recusada
 *   tok_capture_flaky     -> Caso 2: captura falha 2x com 503 e depois aprova (retry com backoff)
 *   tok_capture_declined  -> Caso 2: captura recusada de forma definitiva (compensa a NF-e)
 *   tok_capture_down      -> Caso 2: captura sempre 503 -> DLQ -> prazo da saga cancela a NF-e
 *   tok_void_down         -> Caso 6: cancelamento da autorização (void) fora do ar até
 *                            POST /psp/admin/void-indisponivel?ativo=false
 */
@RestController
@RequestMapping("/psp")
public class PspController {

    private static final Logger log = LoggerFactory.getLogger(PspController.class);

    public enum TransactionStatus { AUTHORIZED, CAPTURED, CANCELLED, REFUNDED }

    static final class Transaction {
        final String id;
        final UUID paymentId;
        final BigDecimal amount;
        final String cardToken;
        volatile TransactionStatus status = TransactionStatus.AUTHORIZED;
        final AtomicInteger charges = new AtomicInteger();

        Transaction(String id, UUID paymentId, BigDecimal amount, String cardToken) {
            this.id = id;
            this.paymentId = paymentId;
            this.amount = amount;
            this.cardToken = cardToken;
        }
    }

    public record AuthorizationRequest(UUID paymentId, BigDecimal amount, String cardToken) {}

    private final Map<String, Transaction> transactions = new ConcurrentHashMap<>();
    private final Map<String, ResponseEntity<Map<String, Object>>> responsesByKey = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> captureAttempts = new ConcurrentHashMap<>();
    private volatile boolean voidUnavailable = true;

    @PostMapping("/authorizations")
    public ResponseEntity<Map<String, Object>> authorize(@RequestHeader("Idempotency-Key") String key,
                                                         @RequestBody AuthorizationRequest req) {
        return idempotent(key, () -> {
            if ("tok_auth_declined".equals(req.cardToken())) {
                return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED)
                        .body(Map.of("status", "DECLINED", "reason", "Saldo insuficiente"));
            }
            var t = new Transaction("auth_" + UUID.randomUUID().toString().substring(0, 8),
                    req.paymentId(), req.amount(), req.cardToken());
            transactions.put(t.id, t);
            log.info("[PSP] pré-autorização {} de {} aprovada", t.id, t.amount);
            return ResponseEntity.status(HttpStatus.CREATED).body(response(t));
        });
    }

    @PostMapping("/authorizations/{id}/capture")
    public ResponseEntity<Map<String, Object>> capture(@RequestHeader("Idempotency-Key") String key,
                                                        @PathVariable String id) {
        var t = transactions.get(id);
        if (t == null) return notFound();
        return idempotent(key, () -> {
            int attempt = captureAttempts.computeIfAbsent(id, k -> new AtomicInteger()).incrementAndGet();
            switch (t.cardToken) {
                case "tok_capture_flaky" -> {
                    if (attempt <= 2) return unavailable("capture", attempt);
                }
                case "tok_capture_down" -> {
                    return unavailable("capture", attempt);
                }
                case "tok_capture_declined" -> {
                    return ResponseEntity.unprocessableEntity()
                            .body(Map.of("status", "DECLINED", "reason", "Autorização expirada no emissor"));
                }
                default -> { }
            }
            if (t.status != TransactionStatus.AUTHORIZED) return conflict(t);
            t.status = TransactionStatus.CAPTURED;
            int charges = t.charges.incrementAndGet();
            log.info("[PSP] captura {} aprovada (tentativa {}, cobranças={})", t.id, attempt, charges);
            return ResponseEntity.ok(response(t));
        });
    }

    @PostMapping("/authorizations/{id}/cancellation")
    public ResponseEntity<Map<String, Object>> cancel(@RequestHeader("Idempotency-Key") String key,
                                                        @PathVariable String id) {
        var t = transactions.get(id);
        if (t == null) return notFound();
        return idempotent(key, () -> {
            if ("tok_void_down".equals(t.cardToken) && voidUnavailable) return unavailable("void", 0);
            if (t.status != TransactionStatus.AUTHORIZED) return conflict(t);
            t.status = TransactionStatus.CANCELLED;
            log.info("[PSP] autorização {} cancelada (void)", t.id);
            return ResponseEntity.ok(response(t));
        });
    }

    @PostMapping("/authorizations/{id}/refund")
    public ResponseEntity<Map<String, Object>> refund(@RequestHeader("Idempotency-Key") String key,
                                                        @PathVariable String id) {
        var t = transactions.get(id);
        if (t == null) return notFound();
        return idempotent(key, () -> {
            if (t.status != TransactionStatus.CAPTURED) return conflict(t);
            t.status = TransactionStatus.REFUNDED;
            log.info("[PSP] captura {} estornada (refund)", t.id);
            return ResponseEntity.ok(response(t));
        });
    }

    @GetMapping("/authorizations/{id}")
    public ResponseEntity<Map<String, Object>> lookup(@PathVariable String id) {
        var t = transactions.get(id);
        return t == null ? notFound() : ResponseEntity.ok(response(t));
    }

    @GetMapping("/authorizations")
    public Collection<Map<String, Object>> list() {
        return transactions.values().stream().map(PspController::response).toList();
    }

    @PostMapping("/admin/void-unavailable")
    public Map<String, Object> toggleVoid(@RequestParam boolean active) {
        voidUnavailable = active;
        log.warn("[PSP] void indisponível = {}", active);
        return Map.of("voidUnavailable", active);
    }

    /**
     * Mesma Idempotency-Key => mesma resposta, sem efeito colateral.
     * Respostas 5xx não são memorizadas: o cliente pode (e deve) tentar de novo.
     */
    private synchronized ResponseEntity<Map<String, Object>> idempotent(
            String key, Supplier<ResponseEntity<Map<String, Object>>> operation) {
        var previous = responsesByKey.get(key);
        if (previous != null) {
            log.info("[PSP] Idempotency-Key {} repetida: devolvendo a resposta anterior", key);
            return previous;
        }
        var response = operation.get();
        if (!response.getStatusCode().is5xxServerError()) responsesByKey.put(key, response);
        return response;
    }

    private static Map<String, Object> response(Transaction t) {
        return Map.of("id", t.id, "paymentId", t.paymentId.toString(), "amount", t.amount,
                "status", t.status.name(), "charges", t.charges.get());
    }

    private static ResponseEntity<Map<String, Object>> unavailable(String operation, int attempt) {
        log.warn("[PSP] {} indisponível (tentativa {})", operation, attempt);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("status", "ERROR", "reason", "Adquirente indisponível"));
    }

    private static ResponseEntity<Map<String, Object>> conflict(Transaction t) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("status", "DECLINED", "reason", "Transação está " + t.status));
    }

    private static ResponseEntity<Map<String, Object>> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("status", "NOT_FOUND"));
    }
}
