package br.com.example.payment.application.port.in;

import br.com.example.payment.application.port.out.ReconciliationLog.PendingItem;

import java.util.List;

/** Rotina de conciliação para o que caiu na DLQ (Caso 6). */
public interface Reconcile {

    void registerPermanentFailure(String topic, String key, String type, String message, String error);

    List<PendingItem> pending();

    /** Devolve a mensagem original ao tópico principal (ex.: depois que o gateway voltou). */
    void reprocess(long pendingItemId);
}
