package br.com.example.payment.domain.checkout;

import br.com.example.payment.domain.checkout.SagaCommand.*;
import br.com.example.payment.domain.checkout.OrderEvent.OrderCancelled;
import br.com.example.payment.domain.checkout.OrderEvent.OrderConfirmed;
import br.com.example.payment.domain.checkout.ParticipantReply.*;
import br.com.example.payment.domain.shared.DomainException;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Orquestrador da saga de checkout: uma máquina de estados pura, sem I/O.
 *
 * <pre>
 * Passo  Ação                       Compensação
 * T1     Pedido criado              Pedido cancelado
 * T2     Pagamento pré-autorizado   Autorização cancelada (void)
 * T3     Estoque reservado          Reserva liberada
 * T4     NF-e emitida na SEFAZ      NF-e cancelada (evento 110111)
 * T5     Pagamento capturado        —  (pivô: é o último passo, de propósito)
 * </pre>
 *
 * Os passos são ordenados pelo custo de desfazer. Tudo que pode falhar (SEFAZ) acontece antes do pivô.
 * A saga também é dona de um prazo: se a captura não confirmar a tempo, cancela a NF-e enquanto ainda dá.
 *
 * Cada reação acumula comandos, eventos e linhas de histórico que a camada de aplicação persiste
 * e publica (via outbox) na mesma transação.
 */
public final class CheckoutSaga {

    /** Estado persistível da saga. */
    public record Data(String orderId, UUID paymentId, Customer customer, List<OrderItem> items, String cardToken,
                        Duration captureDeadline, SagaState state, boolean paymentAuthorized,
                        boolean inventoryReserved, boolean invoiceAuthorized, String accessKey,
                        Instant captureDeadlineAt, String failureReason, int version) {}

    private final String orderId;
    private final UUID paymentId;
    private final Customer customer;
    private final List<OrderItem> items;
    private final String cardToken;
    private final Duration captureDeadline;
    private SagaState state;

    // O que já foi feito e, portanto, precisa ser desfeito em caso de falha.
    private boolean paymentAuthorized;
    private boolean inventoryReserved;
    private boolean invoiceAuthorized;

    private String accessKey;
    private Instant captureDeadlineAt;
    private String failureReason;
    private final int version;

    private final List<SagaCommand> commands = new ArrayList<>();
    private final List<OrderEvent> events = new ArrayList<>();
    private final List<String> history = new ArrayList<>();

    private CheckoutSaga(Data d) {
        this.orderId = d.orderId();
        this.paymentId = d.paymentId();
        this.customer = d.customer();
        this.items = List.copyOf(d.items());
        this.cardToken = d.cardToken();
        this.captureDeadline = d.captureDeadline();
        this.state = d.state();
        this.paymentAuthorized = d.paymentAuthorized();
        this.inventoryReserved = d.inventoryReserved();
        this.invoiceAuthorized = d.invoiceAuthorized();
        this.accessKey = d.accessKey();
        this.captureDeadlineAt = d.captureDeadlineAt();
        this.failureReason = d.failureReason();
        this.version = d.version();
    }

    /** T1: pedido criado. O primeiro passo é a pré-autorização (barata de desfazer). */
    public static CheckoutSaga start(String orderId, UUID paymentId, Customer customer, List<OrderItem> items,
                                       String cardToken, Duration captureDeadline) {
        if (orderId == null || orderId.isBlank()) throw new DomainException("orderId é obrigatório");
        if (items == null || items.isEmpty()) throw new DomainException("O pedido precisa ter itens");
        if (cardToken == null || cardToken.isBlank()) throw new DomainException("cardToken é obrigatório");

        var saga = new CheckoutSaga(new Data(orderId, paymentId, customer, items, cardToken, captureDeadline,
                SagaState.AUTHORIZING_PAYMENT, false, false, false, null, null, null, 0));
        saga.register("T1 Pedido criado (valor " + saga.totalAmount() + ")");
        saga.commands.add(new AuthorizePayment(orderId, paymentId, saga.totalAmount(), cardToken));
        return saga;
    }

    public static CheckoutSaga restore(Data data) {
        return new CheckoutSaga(data);
    }

    public void react(ParticipantReply response, Instant now) {
        switch (response) {
            case PaymentAuthorized r when state == SagaState.AUTHORIZING_PAYMENT -> {
                paymentAuthorized = true;
                advance(SagaState.RESERVING_INVENTORY, "T2 Pagamento pré-autorizado",
                        new ReserveInventory(orderId, items));
            }
            case PaymentDeclined r when state == SagaState.AUTHORIZING_PAYMENT ->
                    compensate("Pagamento recusado: " + r.reason());

            case InventoryReserved r when state == SagaState.RESERVING_INVENTORY -> {
                inventoryReserved = true;
                advance(SagaState.ISSUING_INVOICE, "T3 Estoque reservado",
                        new IssueInvoice(orderId, customer, items, totalAmount()));
            }
            case InventoryUnavailable r when state == SagaState.RESERVING_INVENTORY ->
                    compensate("Estoque indisponível: " + r.reason());

            case InvoiceAuthorized r when state == SagaState.ISSUING_INVOICE -> {
                invoiceAuthorized = true;
                accessKey = r.accessKey();
                captureDeadlineAt = now.plus(captureDeadline);
                advance(SagaState.CAPTURING_PAYMENT,
                        "T4 NF-e autorizada (chave " + accessKey + ", protocolo " + r.protocol()
                                + "). A captura precisa confirmar até " + captureDeadlineAt,
                        new CapturePayment(orderId, paymentId));
            }
            case InvoiceRejected r when state == SagaState.ISSUING_INVOICE ->
                    compensate("NF-e rejeitada pela SEFAZ (" + r.code() + " " + r.reason() + ")");

            case PaymentCaptured r when state == SagaState.CAPTURING_PAYMENT -> {
                captureDeadlineAt = null;
                state = SagaState.COMPLETED;
                register("T5 Pagamento capturado: pivô ultrapassado, saga concluída");
                events.add(new OrderConfirmed(orderId, accessKey));
            }
            case CaptureDeclined r when state == SagaState.CAPTURING_PAYMENT ->
                    compensate("Captura recusada de forma definitiva: " + r.reason());

            // --- compensações, em ordem reversa ---
            case InvoiceCancelled r when state == SagaState.CANCELLING_INVOICE -> {
                invoiceAuthorized = false;
                register("C4 NF-e cancelada (evento 110111, protocolo " + r.protocol() + ")");
                nextCompensation();
            }
            case InvoiceCancellationRejected r when state == SagaState.CANCELLING_INVOICE -> {
                state = SagaState.MANUAL_INTERVENTION;
                register("Cancelamento da NF-e rejeitado (" + r.code() + " " + r.reason() + "). "
                        + "Caminho: cancelamento extemporâneo (depende da UF) ou nota de devolução.");
            }
            case ReservationReleased r when state == SagaState.RELEASING_INVENTORY -> {
                inventoryReserved = false;
                register("C3 Reserva de estoque liberada");
                nextCompensation();
            }
            case PaymentCompensated r when state == SagaState.COMPENSATING_PAYMENT
                    || state == SagaState.AWAITING_RECONCILIATION -> {
                paymentAuthorized = false;
                register("C2 " + r.description());
                nextCompensation();
            }
            case CompensationExhausted r when state == SagaState.COMPENSATING_PAYMENT -> {
                state = SagaState.AWAITING_RECONCILIATION;
                register("Compensação do pagamento foi para a DLQ: " + r.reason()
                        + ". Aguardando conciliação com o gateway (a compensação não pode desistir).");
            }

            // Duplicado, atrasado ou fora de ordem: a máquina de estados protege o fluxo.
            default -> register("Ignorado: " + response.getClass().getSimpleName() + " no estado " + state);
        }
    }

    /** "Se a captura não confirmar em X horas, cancele a NF-e enquanto ainda dá." */
    public void checkDeadline(Instant now) {
        if (state == SagaState.CAPTURING_PAYMENT && captureDeadlineAt != null && now.isAfter(captureDeadlineAt)) {
            compensate("Captura não confirmada até " + captureDeadlineAt
                    + ": cancelando a NF-e enquanto ainda está no prazo legal");
        }
    }

    private void advance(SagaState newState, String description, SagaCommand nextCommand) {
        state = newState;
        register(description);
        commands.add(nextCommand);
    }

    private void compensate(String reason) {
        failureReason = reason;
        captureDeadlineAt = null;
        register("Falha: " + reason + ". Iniciando compensação em ordem reversa.");
        nextCompensation();
    }

    /** Desfaz o passo mais recente que ainda está "feito". Cada compensação espera a confirmação da anterior. */
    private void nextCompensation() {
        if (invoiceAuthorized) {
            state = SagaState.CANCELLING_INVOICE;
            commands.add(new CancelInvoice(orderId, accessKey,
                    "Cancelamento por falha no pagamento do pedido " + orderId));
        } else if (inventoryReserved) {
            state = SagaState.RELEASING_INVENTORY;
            commands.add(new ReleaseReservation(orderId));
        } else if (paymentAuthorized) {
            state = SagaState.COMPENSATING_PAYMENT;
            commands.add(new CompensatePayment(orderId, paymentId, failureReason));
        } else {
            state = SagaState.CANCELLED;
            register("C1 Pedido cancelado");
            events.add(new OrderCancelled(orderId, failureReason));
        }
    }

    private void register(String description) {
        history.add(description);
    }

    public BigDecimal totalAmount() {
        return items.stream().map(OrderItem::subtotal).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public Data data() {
        return new Data(orderId, paymentId, customer, items, cardToken, captureDeadline, state, paymentAuthorized,
                inventoryReserved, invoiceAuthorized, accessKey, captureDeadlineAt, failureReason, version);
    }

    /** Comandos gerados pela última reação, a serem enviados via outbox. */
    public List<SagaCommand> commands() { return List.copyOf(commands); }

    /** Eventos de pedido gerados pela última reação. */
    public List<OrderEvent> events() { return List.copyOf(events); }

    /** Linhas de histórico geradas pela última reação (observabilidade: "em que passo está o pedido?"). */
    public List<String> history() { return List.copyOf(history); }

    public String orderId() { return orderId; }
    public UUID paymentId() { return paymentId; }
    public SagaState state() { return state; }
    public String accessKey() { return accessKey; }
    public Instant captureDeadlineAt() { return captureDeadlineAt; }
    public String failureReason() { return failureReason; }
    public int version() { return version; }
}
