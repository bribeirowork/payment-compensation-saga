package br.com.example.simulators.sefaz;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * SEFAZ simplificada (a real é SOAP/XML assinado; aqui é JSON para focar no fluxo).
 *
 * NCMs "mágicos" que disparam os cenários do artigo:
 *   00000000 -> Caso 1: rejeição 778 (NCM inexistente)
 *   11111111 -> Caso 3: AUTORIZA a nota, mas só responde depois do timeout do cliente
 *   22222222 -> SEFAZ indisponível (HTTP 503) para essa nota -> retry -> DLQ -> contingência
 */
@RestController
@RequestMapping("/sefaz/nfe")
public class SefazController {

    private static final Logger log = LoggerFactory.getLogger(SefazController.class);

    static final String NCM_NONEXISTENT = "00000000";
    static final String NCM_SLOW_RESPONSE = "11111111";
    static final String NCM_SEFAZ_DOWN = "22222222";
    static final String CANCELLATION_EVENT = "110111";

    private final Map<String, RegisteredInvoice> invoices = new ConcurrentHashMap<>();
    private final Set<String> alreadyOverdue = ConcurrentHashMap.newKeySet();
    private final Duration cancellationDeadline;
    private final Duration slowDelay;

    public SefazController(@Value("${simulator.sefaz.cancellation-deadline}") Duration cancellationDeadline,
                           @Value("${simulator.sefaz.slow-delay}") Duration slowDelay) {
        this.cancellationDeadline = cancellationDeadline;
        this.slowDelay = slowDelay;
    }

    public record Item(String sku, String description, String ncm, int quantity, BigDecimal unitPrice) {}

    public record Recipient(String name, String document) {}

    public record AuthorizationRequest(String accessKey, String issuerCnpj, Recipient recipient,
                                         List<Item> items, BigDecimal totalAmount) {}

    public record EventRequest(String tpEvento, String nProt, String xJust) {}

    static final class RegisteredInvoice {
        final String key;
        final String protocol;
        final Instant authorizedAt;
        volatile String cancellationProtocol;

        RegisteredInvoice(String key, String protocol, Instant authorizedAt) {
            this.key = key;
            this.protocol = protocol;
            this.authorizedAt = authorizedAt;
        }
    }

    @PostMapping("/authorization")
    public ResponseEntity<Map<String, Object>> authorize(@RequestBody AuthorizationRequest req)
            throws InterruptedException {
        var ncms = req.items().stream().map(Item::ncm).toList();
        log.info("[SEFAZ] autorização recebida chave={} ncms={}", req.accessKey(), ncms);

        if (ncms.contains(NCM_SEFAZ_DOWN)) {
            log.warn("[SEFAZ] fora do ar para chave={}", req.accessKey());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("cStat", "999", "xMotivo", "Serviço paralisado momentaneamente"));
        }

        if (invoices.containsKey(req.accessKey())) {
            return ResponseEntity.ok(Map.of("status", "REJECTED", "cStat", "204",
                    "xMotivo", "Rejeição: Duplicidade de NF-e"));
        }

        if (ncms.contains(NCM_NONEXISTENT)) {
            return ResponseEntity.ok(Map.of("status", "REJECTED", "cStat", "778",
                    "xMotivo", "Rejeição: Informado NCM inexistente"));
        }

        var invoice = new RegisteredInvoice(req.accessKey(), newProtocol(), Instant.now());
        invoices.put(invoice.key, invoice);

        if (ncms.contains(NCM_SLOW_RESPONSE) && alreadyOverdue.add(invoice.key)) {
            log.warn("[SEFAZ] nota {} autorizada, mas respondendo só em {}", invoice.key, slowDelay);
            Thread.sleep(slowDelay.toMillis());
        }

        return ResponseEntity.ok(authorized(invoice));
    }

    @GetMapping("/{key}")
    public ResponseEntity<Map<String, Object>> lookup(@PathVariable String key) {
        var invoice = invoices.get(key);
        log.info("[SEFAZ] consulta situação chave={} encontrada={}", key, invoice != null);
        if (invoice == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("status", "NOT_FOUND",
                    "cStat", "217", "xMotivo", "Rejeição: NF-e não consta na base de dados da SEFAZ"));
        }
        if (invoice.cancellationProtocol != null) {
            return ResponseEntity.ok(Map.of("status", "CANCELLED", "cStat", "101",
                    "xMotivo", "Cancelamento de NF-e homologado", "protocol", invoice.protocol));
        }
        return ResponseEntity.ok(authorized(invoice));
    }

    @PostMapping("/{key}/events")
    public ResponseEntity<Map<String, Object>> registerEvent(@PathVariable String key,
                                                               @RequestBody EventRequest req) {
        log.info("[SEFAZ] evento {} para chave={}", req.tpEvento(), key);
        if (!CANCELLATION_EVENT.equals(req.tpEvento())) {
            return ResponseEntity.badRequest().body(Map.of("status", "REJECTED", "cStat", "489",
                    "xMotivo", "Rejeição: tipo de evento não suportado pelo simulador"));
        }
        var invoice = invoices.get(key);
        if (invoice == null) {
            return ResponseEntity.ok(Map.of("status", "REJECTED", "cStat", "217",
                    "xMotivo", "Rejeição: NF-e não consta na base de dados da SEFAZ"));
        }
        if (invoice.cancellationProtocol != null) {
            return ResponseEntity.ok(eventRegistered(invoice.cancellationProtocol));
        }
        if (Instant.now().isAfter(invoice.authorizedAt.plus(cancellationDeadline))) {
            return ResponseEntity.ok(Map.of("status", "REJECTED", "cStat", "501",
                    "xMotivo", "Rejeição: Prazo de cancelamento superior ao previsto na Legislação"));
        }
        invoice.cancellationProtocol = newProtocol();
        return ResponseEntity.ok(eventRegistered(invoice.cancellationProtocol));
    }

    private static Map<String, Object> authorized(RegisteredInvoice invoice) {
        return Map.of("status", "AUTHORIZED", "cStat", "100", "xMotivo", "Autorizado o uso da NF-e",
                "protocol", invoice.protocol, "dhRecbto", invoice.authorizedAt.toString());
    }

    private static Map<String, Object> eventRegistered(String protocol) {
        return Map.of("status", "REGISTERED", "cStat", "135",
                "xMotivo", "Evento registrado e vinculado a NF-e", "protocol", protocol);
    }

    private static String newProtocol() {
        return "135" + ThreadLocalRandom.current().nextLong(100_000_000_000L, 999_999_999_999L);
    }
}
