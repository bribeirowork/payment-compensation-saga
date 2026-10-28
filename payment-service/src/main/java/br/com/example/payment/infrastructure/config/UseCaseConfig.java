package br.com.example.payment.infrastructure.config;

import br.com.example.payment.application.port.out.*;
import br.com.example.payment.application.service.CheckoutSagaService;
import br.com.example.payment.application.service.ReconciliationService;
import br.com.example.payment.application.service.PaymentService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;

/**
 * Composition root: o único lugar que conhece as implementações concretas.
 * Domínio e aplicação não têm nenhuma anotação do Spring.
 */
@Configuration
public class UseCaseConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    CheckoutSagaService checkoutSagaService(SagaRepository sagas, Inbox inbox,
                                            MessagePublisher publisher, UnitOfWork unitOfWork,
                                            Clock clock, @Value("${checkout.capture-deadline}") Duration captureDeadline) {
        return new CheckoutSagaService(sagas, inbox, publisher, unitOfWork, clock, captureDeadline);
    }

    @Bean
    PaymentService paymentService(PaymentEventStore eventStore, PaymentProvider psp,
                                      Inbox inbox, UnitOfWork unitOfWork,
                                      Clock clock) {
        return new PaymentService(eventStore, psp, inbox, unitOfWork, clock);
    }

    @Bean
    ReconciliationService reconciliationService(ReconciliationLog record, MessagePublisher publisher,
                                          CheckoutSagaService saga, UnitOfWork unitOfWork) {
        return new ReconciliationService(record, publisher, saga, unitOfWork);
    }
}
