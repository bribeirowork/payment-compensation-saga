package br.com.example.payment.domain.payment;

import br.com.example.payment.domain.payment.PaymentEvent.*;
import br.com.example.payment.domain.shared.DomainException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Agregado event-sourced. Cada comando valida as regras e registra um novo fato;
 * nada é apagado ou sobrescrito: "desfazer" é registrar um evento de compensação.
 */
public final class Payment {

    private UUID id;
    private String orderId;
    private BigDecimal amount;
    private String cardToken;
    private String authorizationId;
    private PaymentStatus status;

    /** Versão do último evento aplicado (persistido ou não). */
    private int version;
    /** Versão que já está no event store: base da concorrência otimista. */
    private int persistedVersion;
    private final List<PaymentEvent> newEvents = new ArrayList<>();

    private Payment() {
    }

    public static Payment start(UUID id, String orderId, BigDecimal amount, String cardToken, Instant now) {
        if (amount == null || amount.signum() <= 0) {
            throw new DomainException("O valor do pagamento deve ser positivo");
        }
        var payment = new Payment();
        payment.register(new PaymentInitiated(id, orderId, amount, cardToken, now));
        return payment;
    }

    /** O estado atual é o resultado de aplicar todos os eventos, em ordem. */
    public static Payment rehydrate(List<PaymentEvent> events) {
        if (events.isEmpty()) {
            throw new DomainException("Não é possível reconstituir um pagamento sem eventos");
        }
        var payment = new Payment();
        events.forEach(payment::apply);   // v1 → vN
        payment.persistedVersion = payment.version;
        return payment;
    }

    public void registerAuthorization(String authorizationId, Instant now) {
        requireStatus(Set.of(PaymentStatus.INITIATED), "registrar autorização");
        register(new PaymentAuthorized(id, authorizationId, now));
    }

    public void registerDecline(String reason, Instant now) {
        requireStatus(Set.of(PaymentStatus.INITIATED), "registrar recusa");
        register(new PaymentDeclined(id, reason, now));
    }

    /** Idempotente: solicitar de novo uma captura já solicitada não gera outro evento. */
    public void requestCapture(Instant now) {
        if (status == PaymentStatus.CAPTURE_REQUESTED) return;
        requireStatus(Set.of(PaymentStatus.AUTHORIZED), "solicitar captura");
        register(new CaptureRequested(id, now));
    }

    public void registerCapture(Instant now) {
        requireStatus(Set.of(PaymentStatus.CAPTURE_REQUESTED), "registrar captura");
        register(new PaymentCaptured(id, now));
    }

    public void registerCaptureDecline(String reason, Instant now) {
        requireStatus(Set.of(PaymentStatus.CAPTURE_REQUESTED), "registrar recusa da captura");
        register(new CaptureDeclined(id, reason, now));
    }

    public CompensationAction compensationAction() {
        return switch (status) {
            case AUTHORIZED, CAPTURE_DECLINED -> CompensationAction.VOID_AUTHORIZATION;
            case CAPTURED -> CompensationAction.REFUND;
            case CAPTURE_REQUESTED -> CompensationAction.QUERY_PSP;
            case INITIATED, DECLINED, CANCELLED, REFUNDED -> CompensationAction.NONE;
        };
    }

    public void registerAuthorizationVoid(String reason, Instant now) {
        requireStatus(Set.of(PaymentStatus.AUTHORIZED, PaymentStatus.CAPTURE_DECLINED,
                PaymentStatus.CAPTURE_REQUESTED), "cancelar a autorização");
        register(new AuthorizationVoided(id, reason, now));
    }

    public void registerRefund(String reason, Instant now) {
        requireStatus(Set.of(PaymentStatus.CAPTURED), "refund");
        register(new PaymentRefunded(id, reason, now));
    }

    void apply(PaymentEvent event) {
        switch (event) {
            case PaymentInitiated e -> {
                id = e.paymentId();
                orderId = e.orderId();
                amount = e.amount();
                cardToken = e.cardToken();
                status = PaymentStatus.INITIATED;
            }
            case PaymentAuthorized e -> {
                authorizationId = e.authorizationId();
                status = PaymentStatus.AUTHORIZED;
            }
            case PaymentDeclined e -> status = PaymentStatus.DECLINED;
            case CaptureRequested e -> status = PaymentStatus.CAPTURE_REQUESTED;
            case PaymentCaptured e -> status = PaymentStatus.CAPTURED;
            case CaptureDeclined e -> status = PaymentStatus.CAPTURE_DECLINED;
            case AuthorizationVoided e -> status = PaymentStatus.CANCELLED;
            case PaymentRefunded e -> status = PaymentStatus.REFUNDED;
        }
        version++;
    }

    private void register(PaymentEvent event) {
        apply(event);
        newEvents.add(event);
    }

    private void requireStatus(Set<PaymentStatus> allowed, String operation) {
        if (!allowed.contains(status)) {
            throw new DomainException("Não é possível " + operation + " de um pagamento " + status);
        }
    }

    public List<PaymentEvent> newEvents() {
        return List.copyOf(newEvents);
    }

    public void markAsPersisted() {
        newEvents.clear();
        persistedVersion = version;
    }

    public UUID id() { return id; }
    public String orderId() { return orderId; }
    public BigDecimal amount() { return amount; }
    public String cardToken() { return cardToken; }
    public String authorizationId() { return authorizationId; }
    public PaymentStatus status() { return status; }
    public int version() { return version; }
    public int persistedVersion() { return persistedVersion; }
}
