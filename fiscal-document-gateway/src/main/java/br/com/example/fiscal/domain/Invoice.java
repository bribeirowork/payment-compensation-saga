package br.com.example.fiscal.domain;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Agregado NF-e. Uma nota por pedido; a chave de acesso nasce aqui e nunca muda. */
public final class Invoice {

    private static final ZoneId FISCAL_ZONE = ZoneId.of("America/Sao_Paulo");

    public record Data(UUID id, String orderId, int series, long number, String accessKey,
                        Recipient recipient, List<InvoiceItem> items, BigDecimal totalAmount,
                        InvoiceStatus status, String protocol, Instant authorizedAt,
                        String rejectionCode, String rejectionReason, List<SefazEvent> events, int version) {}

    private final UUID id;
    private final String orderId;
    private final int series;
    private final long number;
    private final AccessKey accessKey;
    private final Recipient recipient;
    private final List<InvoiceItem> items;
    private final BigDecimal totalAmount;
    private InvoiceStatus status;
    private String protocol;
    private Instant authorizedAt;
    private String rejectionCode;
    private String rejectionReason;
    private final List<SefazEvent> events;
    private final int version;

    private Invoice(Data d) {
        this.id = d.id();
        this.orderId = d.orderId();
        this.series = d.series();
        this.number = d.number();
        this.accessKey = new AccessKey(d.accessKey());
        this.recipient = d.recipient();
        this.items = List.copyOf(d.items());
        this.totalAmount = d.totalAmount();
        this.status = d.status();
        this.protocol = d.protocol();
        this.authorizedAt = d.authorizedAt();
        this.rejectionCode = d.rejectionCode();
        this.rejectionReason = d.rejectionReason();
        this.events = new ArrayList<>(d.events() == null ? List.of() : d.events());
        this.version = d.version();
    }

    public static Invoice create(String orderId, Issuer issuer, long number, Recipient recipient,
                                   List<InvoiceItem> items, BigDecimal totalAmount, Instant now) {
        if (items == null || items.isEmpty()) throw new DomainException("NF-e sem itens");
        var period = YearMonth.from(now.atZone(FISCAL_ZONE));
        var key = AccessKey.generate(issuer.ufCode(), period, issuer.cnpj(), issuer.series(), number);
        return new Invoice(new Data(UUID.randomUUID(), orderId, issuer.series(), number, key.value(),
                recipient, items, totalAmount, InvoiceStatus.PENDING_SUBMISSION, null, null, null, null,
                List.of(), 0));
    }

    public static Invoice restore(Data data) {
        return new Invoice(data);
    }

    public void registerAuthorization(String protocol, Instant authorizedAt) {
        requireAwaitingSefaz("registrar autorização");
        this.status = InvoiceStatus.AUTHORIZED;
        this.protocol = protocol;
        this.authorizedAt = authorizedAt;
    }

    public void registerRejection(String code, String reason) {
        requireAwaitingSefaz("registrar rejeição");
        this.status = InvoiceStatus.REJECTED;
        this.rejectionCode = code;
        this.rejectionReason = reason;
    }

    /** Timeout: não sabemos se a SEFAZ autorizou. O próximo passo é CONSULTAR, nunca reemitir. */
    public void markStatusUnknown() {
        requireAwaitingSefaz("marcar situação desconhecida");
        this.status = InvoiceStatus.STATUS_UNKNOWN;
    }

    public void markPendingContingency() {
        requireAwaitingSefaz("entrar em contingência");
        this.status = InvoiceStatus.PENDING_CONTINGENCY;
    }

    /** Em regra, 24h após a autorização. Depois disso: cancelamento extemporâneo ou nota de devolução. */
    public boolean withinCancellationDeadline(Instant now, Duration deadline) {
        return status == InvoiceStatus.AUTHORIZED && now.isBefore(authorizedAt.plus(deadline));
    }

    public void registerCancellation(String eventProtocol, String justification, Instant now) {
        if (status != InvoiceStatus.AUTHORIZED) {
            throw new DomainException("Só uma NF-e autorizada pode ser cancelada (status " + status + ")");
        }
        validateJustification(justification);
        events.add(new SefazEvent(SefazEvent.CANCELLATION, "Cancellation", eventProtocol, justification, now));
        status = InvoiceStatus.CANCELLED;
    }

    public static void validateJustification(String justification) {
        if (justification == null || justification.length() < 15 || justification.length() > 255) {
            throw new DomainException("A justificativa do cancelamento deve ter entre 15 e 255 caracteres");
        }
    }

    private void requireAwaitingSefaz(String operation) {
        if (!status.awaitingSefaz()) {
            throw new DomainException("Não é possível " + operation + " de uma NF-e " + status);
        }
    }

    public Data data() {
        return new Data(id, orderId, series, number, accessKey.value(), recipient, items, totalAmount, status,
                protocol, authorizedAt, rejectionCode, rejectionReason, List.copyOf(events), version);
    }

    public UUID id() { return id; }
    public String orderId() { return orderId; }
    public long number() { return number; }
    public AccessKey accessKey() { return accessKey; }
    public Recipient recipient() { return recipient; }
    public List<InvoiceItem> items() { return items; }
    public BigDecimal totalAmount() { return totalAmount; }
    public InvoiceStatus status() { return status; }
    public String protocol() { return protocol; }
    public String rejectionCode() { return rejectionCode; }
    public String rejectionReason() { return rejectionReason; }
    public List<SefazEvent> events() { return List.copyOf(events); }
    public int version() { return version; }
}
