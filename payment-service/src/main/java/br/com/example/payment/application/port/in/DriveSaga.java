package br.com.example.payment.application.port.in;

import br.com.example.payment.domain.checkout.ParticipantReply;

import java.util.UUID;

public interface DriveSaga {

    void process(UUID messageId, String orderId, ParticipantReply response);

    /** Timers da saga: prazos fiscais viram timers de forma natural na orquestração. */
    void checkDeadlines();
}
