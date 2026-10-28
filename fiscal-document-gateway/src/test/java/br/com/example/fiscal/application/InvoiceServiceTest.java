package br.com.example.fiscal.application;

import br.com.example.fiscal.application.port.in.ManageInvoices.IssuanceRequest;
import br.com.example.fiscal.application.port.out.*;
import br.com.example.fiscal.application.service.InvoiceService;
import br.com.example.fiscal.domain.*;
import br.com.example.fiscal.domain.FiscalEvent.InvoiceAuthorized;
import br.com.example.fiscal.domain.FiscalEvent.InvoiceCancelled;
import br.com.example.fiscal.domain.FiscalEvent.InvoiceCancellationRejected;
import br.com.example.fiscal.domain.FiscalEvent.InvoiceRejected;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Casos de uso do gateway fiscal com adapters em memória: sem Spring, sem Kafka, sem banco. */
class InvoiceServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-28T12:00:00Z");

    private final InMemoryInvoices invoices = new InMemoryInvoices();
    private final SefazFake sefaz = new SefazFake();
    private final List<FiscalEvent> published = new ArrayList<>();
    private final InMemoryInbox inbox = new InMemoryInbox();
    private final MutableClock clock = new MutableClock(NOW);

    private final InvoiceService service = new InvoiceService(invoices, sefaz, published::add, inbox,
            new NoTransaction(), clock, new Issuer("12345678000195", "35", 1), Duration.ofHours(24));

    @Test
    void case3_sefazTimeoutQueriesByKeyAndDoesNotReissue() {
        sefaz.timeoutOnFirstAuthorization = true;
        var message = UUID.randomUUID();

        // 1ª tentativa: a SEFAZ autoriza, mas a resposta não chega.
        assertThatThrownBy(() -> service.issue(message, request("61091000")))
                .isInstanceOf(SefazTimeoutException.class);
        assertThat(invoices.byOrder("P1").orElseThrow().status()).isEqualTo(InvoiceStatus.STATUS_UNKNOWN);
        var originalKey = invoices.byOrder("P1").orElseThrow().accessKey();

        // 2ª tentativa (retry topic): consulta pela chave e descobre que já está autorizada.
        service.issue(message, request("61091000"));

        var invoice = invoices.byOrder("P1").orElseThrow();
        assertThat(invoice.status()).isEqualTo(InvoiceStatus.AUTHORIZED);
        assertThat(invoice.accessKey()).isEqualTo(originalKey);
        assertThat(sefaz.sentAuthorizations).isEqualTo(1);   // nenhuma reemissão
        assertThat(sefaz.lookups).isEqualTo(1);
        assertThat(published).singleElement().isInstanceOf(InvoiceAuthorized.class);
    }

    @Test
    void case1_sefazRejectionIsPublished() {
        service.issue(UUID.randomUUID(), request(SefazFake.NCM_NONEXISTENT));

        assertThat(invoices.byOrder("P1").orElseThrow().status()).isEqualTo(InvoiceStatus.REJECTED);
        assertThat(published).singleElement().isInstanceOfSatisfying(InvoiceRejected.class,
                e -> assertThat(e.code()).isEqualTo("778"));
    }

    @Test
    void case4_duplicateCommandDoesNotIssueTwice() {
        var message = UUID.randomUUID();
        service.issue(message, request("61091000"));
        service.issue(message, request("61091000"));

        assertThat(sefaz.sentAuthorizations).isEqualTo(1);
        assertThat(published).hasSize(1);
    }

    @Test
    void cancellationWithinDeadlineRegistersEvent110111() {
        service.issue(UUID.randomUUID(), request("61091000"));
        clock.now = NOW.plus(Duration.ofHours(2));

        service.cancel(UUID.randomUUID(), "P1", "Cancelamento por falha no pagamento");

        var invoice = invoices.byOrder("P1").orElseThrow();
        assertThat(invoice.status()).isEqualTo(InvoiceStatus.CANCELLED);
        assertThat(invoice.events()).singleElement().extracting(SefazEvent::code).isEqualTo("110111");
        assertThat(published).last().isInstanceOf(InvoiceCancelled.class);
    }

    @Test
    void cancellationPastDeadlineSkipsSefazAndNotifiesSaga() {
        service.issue(UUID.randomUUID(), request("61091000"));
        clock.now = NOW.plus(Duration.ofHours(25));

        service.cancel(UUID.randomUUID(), "P1", "Cancelamento por falha no pagamento");

        assertThat(sefaz.cancellations).isZero();
        assertThat(published).last().isInstanceOfSatisfying(InvoiceCancellationRejected.class,
                e -> assertThat(e.code()).isEqualTo("501"));
    }

    private static IssuanceRequest request(String ncm) {
        return new IssuanceRequest("P1", new Recipient("Maria", "12345678909"),
                List.of(new InvoiceItem("SKU-1", "T-shirt", ncm, 1, new BigDecimal("50.00"))), new BigDecimal("50.00"));
    }

    // --- fakes ---

    static class SefazFake implements Sefaz {
        static final String NCM_NONEXISTENT = "00000000";
        boolean timeoutOnFirstAuthorization;
        int sentAuthorizations;
        int lookups;
        int cancellations;
        private final Map<String, String> authorizedKeys = new HashMap<>();

        @Override
        public AuthorizationResponse authorize(Invoice invoice) {
            sentAuthorizations++;
            if (invoice.items().get(0).ncm().equals(NCM_NONEXISTENT)) {
                return new AuthorizationResponse.Rejected("778", "Informado NCM inexistente");
            }
            authorizedKeys.put(invoice.accessKey().value(), "135" + sentAuthorizations);
            if (timeoutOnFirstAuthorization && sentAuthorizations == 1) {
                throw new SefazTimeoutException("timeout", null);
            }
            return new AuthorizationResponse.Authorized(authorizedKeys.get(invoice.accessKey().value()), NOW);
        }

        @Override
        public AuthorizationResponse lookup(AccessKey key) {
            lookups++;
            var protocol = authorizedKeys.get(key.value());
            return protocol == null ? new AuthorizationResponse.NotFound()
                    : new AuthorizationResponse.Authorized(protocol, NOW);
        }

        @Override
        public EventResponse cancel(AccessKey key, String authorizationProtocol, String justification) {
            cancellations++;
            return new EventResponse.Registered("135999");
        }
    }

    static class InMemoryInvoices implements InvoiceRepository {
        private final Map<String, Invoice.Data> byOrder = new HashMap<>();
        private long sequence;

        @Override
        public Optional<Invoice> byOrder(String orderId) {
            return Optional.ofNullable(byOrder.get(orderId)).map(Invoice::restore);
        }

        @Override
        public long nextNumber() {
            return ++sequence;
        }

        @Override
        public void insert(Invoice invoice) {
            byOrder.put(invoice.orderId(), invoice.data());
        }

        @Override
        public void update(Invoice invoice) {
            var d = invoice.data();
            byOrder.put(invoice.orderId(), new Invoice.Data(d.id(), d.orderId(), d.series(), d.number(),
                    d.accessKey(), d.recipient(), d.items(), d.totalAmount(), d.status(), d.protocol(),
                    d.authorizedAt(), d.rejectionCode(), d.rejectionReason(), d.events(), d.version() + 1));
        }
    }

    static class InMemoryInbox implements Inbox {
        private final Set<String> processed = new HashSet<>();

        @Override
        public boolean alreadyProcessed(String consumer, UUID messageId) {
            return processed.contains(consumer + messageId);
        }

        @Override
        public boolean registerIfNew(String consumer, UUID messageId) {
            return processed.add(consumer + messageId);
        }
    }

    static class NoTransaction implements UnitOfWork {
        @Override
        public void execute(Runnable work) {
            work.run();
        }

        @Override
        public <T> T compute(Supplier<T> work) {
            return work.get();
        }
    }

    static class MutableClock extends Clock {
        Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
