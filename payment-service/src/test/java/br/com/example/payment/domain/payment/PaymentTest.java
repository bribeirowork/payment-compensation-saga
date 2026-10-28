package br.com.example.payment.domain.payment;

import br.com.example.payment.domain.payment.PaymentEvent.*;
import br.com.example.payment.domain.shared.DomainException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentTest {

    private static final UUID ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-28T12:00:00Z");

    @Test
    void rehydratesTheArticleStreamUpToCancelled() {
        // v1..v5 exatamente como na seção 1 do artigo
        var events = List.<PaymentEvent>of(
                new PaymentInitiated(ID, "48213", new BigDecimal("199.90"), "tok_ok", NOW),
                new PaymentAuthorized(ID, "auth_1", NOW),
                new CaptureRequested(ID, NOW),
                new CaptureDeclined(ID, "Autorização expirada no emissor", NOW),
                new AuthorizationVoided(ID, "Captura recusada", NOW));

        var payment = Payment.rehydrate(events);

        assertThat(payment.status()).isEqualTo(PaymentStatus.CANCELLED);
        assertThat(payment.version()).isEqualTo(5);
        assertThat(payment.newEvents()).isEmpty();
    }

    @Test
    void undoingIsRecordingANewFact() {
        var payment = authorized();
        payment.requestCapture(NOW);
        payment.registerCaptureDecline("Declined", NOW);

        assertThat(payment.compensationAction()).isEqualTo(CompensationAction.VOID_AUTHORIZATION);
        payment.registerAuthorizationVoid("Captura recusada", NOW);

        assertThat(payment.newEvents()).extracting(e -> e.getClass().getSimpleName())
                .containsExactly("CaptureRequested", "CaptureDeclined", "AuthorizationVoided");
        assertThat(payment.version()).isEqualTo(5);
        assertThat(payment.persistedVersion()).isEqualTo(2);
    }

    @Test
    void completedCaptureIsOnlyUndoneByRefund() {
        var payment = authorized();
        payment.requestCapture(NOW);
        payment.registerCapture(NOW);

        assertThat(payment.compensationAction()).isEqualTo(CompensationAction.REFUND);
        assertThatThrownBy(() -> payment.registerAuthorizationVoid("x", NOW))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void unansweredCaptureRequiresQueryingPspBeforeCompensating() {
        var payment = authorized();
        payment.requestCapture(NOW);

        assertThat(payment.compensationAction()).isEqualTo(CompensationAction.QUERY_PSP);
    }

    @Test
    void requestingCaptureTwiceDoesNotDuplicateTheEvent() {
        var payment = authorized();
        payment.requestCapture(NOW);
        payment.requestCapture(NOW);

        assertThat(payment.newEvents()).hasSize(1);
    }

    private static Payment authorized() {
        var payment = Payment.rehydrate(List.of(
                new PaymentInitiated(ID, "48213", new BigDecimal("199.90"), "tok_ok", NOW),
                new PaymentAuthorized(ID, "auth_1", NOW)));
        assertThat(payment.status()).isEqualTo(PaymentStatus.AUTHORIZED);
        return payment;
    }
}
