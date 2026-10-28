package br.com.example.fiscal.infrastructure.config;

import br.com.example.fiscal.application.port.out.*;
import br.com.example.fiscal.application.service.InvoiceService;
import br.com.example.fiscal.domain.Issuer;
import br.com.example.fiscal.infrastructure.messaging.Topics;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;

import java.time.Clock;
import java.time.Duration;

/** Composition root do gateway fiscal. */
@Configuration
public class FiscalConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    Issuer issuer(@Value("${fiscal.issuer.cnpj}") String cnpj,
                      @Value("${fiscal.issuer.uf-code}") String ufCode,
                      @Value("${fiscal.issuer.series}") int series) {
        return new Issuer(cnpj, ufCode, series);
    }

    @Bean
    InvoiceService invoiceService(InvoiceRepository invoices, Sefaz sefaz, FiscalEventPublisher publisher,
                                        Inbox inbox, UnitOfWork unitOfWork,
                                        Clock clock, Issuer issuer,
                                        @Value("${fiscal.cancellation-deadline}") Duration cancellationDeadline) {
        return new InvoiceService(invoices, sefaz, publisher, inbox, unitOfWork, clock, issuer,
                cancellationDeadline);
    }

    @Bean
    KafkaAdmin.NewTopics topics() {
        return new KafkaAdmin.NewTopics(
                TopicBuilder.name(Topics.FISCAL_COMMANDS).partitions(3).replicas(1).build(),
                TopicBuilder.name(Topics.FISCAL_EVENTS).partitions(3).replicas(1).build());
    }
}
