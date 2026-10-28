package br.com.example.payment.application.service;

import br.com.example.payment.application.port.in.Reconcile;
import br.com.example.payment.application.port.in.DriveSaga;
import br.com.example.payment.application.port.out.MessagePublisher;
import br.com.example.payment.application.port.out.ReconciliationLog;
import br.com.example.payment.application.port.out.ReconciliationLog.PendingItem;
import br.com.example.payment.application.port.out.UnitOfWork;
import br.com.example.payment.domain.checkout.ParticipantReply.CompensationExhausted;
import br.com.example.payment.domain.shared.DomainException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;

/**
 * Observabilidade da compensação: depois de N tentativas, a mensagem vai para a DLQ,
 * gera alerta e entra numa rotina de conciliação com o gateway.
 */
public class ReconciliationService implements Reconcile {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final ReconciliationLog record;
    private final MessagePublisher publisher;
    private final DriveSaga saga;
    private final UnitOfWork unitOfWork;

    public ReconciliationService(ReconciliationLog record, MessagePublisher publisher, DriveSaga saga,
                              UnitOfWork unitOfWork) {
        this.record = record;
        this.publisher = publisher;
        this.saga = saga;
        this.unitOfWork = unitOfWork;
    }

    @Override
    public void registerPermanentFailure(String topic, String key, String type, String message, String error) {
        unitOfWork.execute(() -> {
            long id = record.register(topic, key, type, message, error);
            // Em produção: métrica + alerta no PagerDuty/Opsgenie. Aqui, um log bem visível.
            log.error("ALERTA: {} do pedido {} esgotou as tentativas e foi para a DLQ (pendência #{}): {}",
                    type, key, id, error);
            if ("CompensatePayment".equals(type)) {
                saga.process(UUID.randomUUID(), key, new CompensationExhausted(error));
            }
        });
    }

    @Override
    public List<PendingItem> pending() {
        return record.pending();
    }

    @Override
    public void reprocess(long pendingItemId) {
        unitOfWork.execute(() -> {
            var pendingItem = record.find(pendingItemId)
                    .orElseThrow(() -> new DomainException("Pendência " + pendingItemId + " não encontrada"));
            publisher.resendOriginal(pendingItem.topic(), pendingItem.key(), pendingItem.message());
            record.markReprocessed(pendingItemId);
            log.info("Pendência #{} ({}) reenviada para {}", pendingItemId, pendingItem.type(), pendingItem.topic());
        });
    }
}
