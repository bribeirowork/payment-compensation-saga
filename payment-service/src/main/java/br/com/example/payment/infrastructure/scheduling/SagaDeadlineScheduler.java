package br.com.example.payment.infrastructure.scheduling;

import br.com.example.payment.application.port.in.DriveSaga;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** O relógio da saga. Em produção, prefira um scheduler distribuído (ex.: ShedLock, Quartz em cluster). */
@Component
public class SagaDeadlineScheduler {

    private final DriveSaga saga;

    public SagaDeadlineScheduler(DriveSaga saga) {
        this.saga = saga;
    }

    @Scheduled(fixedDelayString = "${checkout.deadline-check-interval-ms:5000}")
    public void check() {
        saga.checkDeadlines();
    }
}
