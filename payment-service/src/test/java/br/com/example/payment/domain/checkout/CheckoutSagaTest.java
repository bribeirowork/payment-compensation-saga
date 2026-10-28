package br.com.example.payment.domain.checkout;

import br.com.example.payment.domain.checkout.SagaCommand.*;
import br.com.example.payment.domain.checkout.OrderEvent.OrderCancelled;
import br.com.example.payment.domain.checkout.OrderEvent.OrderConfirmed;
import br.com.example.payment.domain.checkout.ParticipantReply.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CheckoutSagaTest {

    private static final Instant NOW = Instant.parse("2026-10-28T12:00:00Z");
    private static final Duration DEADLINE = Duration.ofHours(20);

    @Test
    void happyPathEndsWithCaptureAsLastStep() {
        var saga = newSaga();
        assertThat(saga.commands()).singleElement().isInstanceOf(AuthorizePayment.class);

        saga = react(saga, new PaymentAuthorized());
        assertThat(saga.commands()).singleElement().isInstanceOf(ReserveInventory.class);

        saga = react(saga, new InventoryReserved());
        assertThat(saga.commands()).singleElement().isInstanceOf(IssueInvoice.class);

        saga = react(saga, new InvoiceAuthorized("3526...", "135000"));
        assertThat(saga.commands()).singleElement().isInstanceOf(CapturePayment.class);
        assertThat(saga.captureDeadlineAt()).isEqualTo(NOW.plus(DEADLINE));

        saga = react(saga, new PaymentCaptured());
        assertThat(saga.state()).isEqualTo(SagaState.COMPLETED);
        assertThat(saga.events()).singleElement().isInstanceOf(OrderConfirmed.class);
    }

    @Test
    void case1_sefazRejects_compensatesInReverseOrderAndCustomerIsNeverCharged() {
        var saga = until(SagaState.ISSUING_INVOICE);

        saga = react(saga, new InvoiceRejected("778", "Informado NCM inexistente"));
        assertThat(saga.state()).isEqualTo(SagaState.RELEASING_INVENTORY);
        assertThat(saga.commands()).singleElement().isInstanceOf(ReleaseReservation.class);

        saga = react(saga, new ReservationReleased());
        assertThat(saga.commands()).singleElement().isInstanceOf(CompensatePayment.class);

        saga = react(saga, new PaymentCompensated("Autorização cancelada (void)"));
        assertThat(saga.state()).isEqualTo(SagaState.CANCELLED);
        assertThat(saga.events()).singleElement().isInstanceOf(OrderCancelled.class);
    }

    @Test
    void case2_captureDeclinedAfterInvoiceCancelsInvoiceFirst() {
        var saga = until(SagaState.CAPTURING_PAYMENT);

        saga = react(saga, new CaptureDeclined("Autorização expirada no emissor"));
        assertThat(saga.state()).isEqualTo(SagaState.CANCELLING_INVOICE);
        assertThat(saga.commands()).singleElement().isInstanceOfSatisfying(CancelInvoice.class,
                c -> assertThat(c.justification()).hasSizeGreaterThanOrEqualTo(15));

        saga = react(saga, new InvoiceCancelled("135999"));
        assertThat(saga.commands()).singleElement().isInstanceOf(ReleaseReservation.class);
        saga = react(saga, new ReservationReleased());
        assertThat(saga.commands()).singleElement().isInstanceOf(CompensatePayment.class);
        saga = react(saga, new PaymentCompensated("Autorização cancelada (void)"));
        assertThat(saga.state()).isEqualTo(SagaState.CANCELLED);
    }

    @Test
    void case2_expiredCaptureDeadlineCancelsInvoiceWhileStillAllowed() {
        var saga = until(SagaState.CAPTURING_PAYMENT);

        saga.checkDeadline(NOW.plus(Duration.ofHours(19)));
        assertThat(saga.state()).isEqualTo(SagaState.CAPTURING_PAYMENT);

        saga.checkDeadline(NOW.plus(Duration.ofHours(21)));
        assertThat(saga.state()).isEqualTo(SagaState.CANCELLING_INVOICE);
        assertThat(saga.commands()).singleElement().isInstanceOf(CancelInvoice.class);
    }

    @Test
    void invoiceCancellationPastDeadlineRequiresManualIntervention() {
        var saga = until(SagaState.CAPTURING_PAYMENT);
        saga = react(saga, new CaptureDeclined("x"));

        saga = react(saga, new InvoiceCancellationRejected("501", "Prazo de cancelamento superior ao previsto"));
        assertThat(saga.state()).isEqualTo(SagaState.MANUAL_INTERVENTION);
        assertThat(saga.commands()).isEmpty();
    }

    @Test
    void case6_compensationInDlqAwaitsReconciliationAndRecoversLater() {
        var saga = until(SagaState.ISSUING_INVOICE);
        saga = react(saga, new InvoiceRejected("778", "NCM"));
        saga = react(saga, new ReservationReleased());
        assertThat(saga.state()).isEqualTo(SagaState.COMPENSATING_PAYMENT);

        saga = react(saga, new CompensationExhausted("PSP indisponível"));
        assertThat(saga.state()).isEqualTo(SagaState.AWAITING_RECONCILIATION);

        saga = react(saga, new PaymentCompensated("Autorização cancelada (void)"));
        assertThat(saga.state()).isEqualTo(SagaState.CANCELLED);
    }

    @Test
    void case4and7_duplicateOrOutOfOrderEventIsIgnoredByStateMachine() {
        var saga = until(SagaState.ISSUING_INVOICE);

        saga = react(saga, new PaymentAuthorized());       // duplicado
        saga = react(saga, new PaymentCompensated("void")); // fora de ordem
        assertThat(saga.state()).isEqualTo(SagaState.ISSUING_INVOICE);
        assertThat(saga.commands()).isEmpty();
        assertThat(saga.history()).allMatch(l -> l.startsWith("Ignorado"));
    }

    // --- helpers: simulam persistir e recarregar a saga entre uma mensagem e outra ---

    private static CheckoutSaga newSaga() {
        return CheckoutSaga.start("48213", UUID.randomUUID(), new Customer("Maria", "12345678909"),
                List.of(new OrderItem("SKU-1", "T-shirt", "61091000", 2, new BigDecimal("50.00"))),
                "tok_ok", DEADLINE);
    }

    private static CheckoutSaga react(CheckoutSaga saga, ParticipantReply response) {
        var reloaded = CheckoutSaga.restore(saga.data());
        reloaded.react(response, NOW);
        return reloaded;
    }

    private static CheckoutSaga until(SagaState target) {
        var saga = newSaga();
        List<ParticipantReply> steps = List.of(new PaymentAuthorized(), new InventoryReserved(),
                new InvoiceAuthorized("35261012345678000190550010000000011000000014", "135000"));
        for (var step : steps) {
            if (saga.state() == target) break;
            saga = react(saga, step);
        }
        assertThat(saga.state()).isEqualTo(target);
        return CheckoutSaga.restore(saga.data());
    }
}
