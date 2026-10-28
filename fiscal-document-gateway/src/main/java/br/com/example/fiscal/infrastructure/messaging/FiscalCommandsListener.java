package br.com.example.fiscal.infrastructure.messaging;

import br.com.example.fiscal.application.port.in.ManageInvoices;
import br.com.example.fiscal.application.port.in.ManageInvoices.IssuanceRequest;
import br.com.example.fiscal.domain.Recipient;
import br.com.example.fiscal.domain.InvoiceItem;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.TopicSuffixingStrategy;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;

import java.util.List;

import static br.com.example.fiscal.infrastructure.messaging.Messages.text;

/**
 * fiscal.comandos → fiscal.comandos-retry-0..N → fiscal.comandos-dlq.
 * O timeout da SEFAZ (Caso 3) usa exatamente esse caminho: a próxima tentativa chega alguns
 * segundos depois e começa consultando a situação pela chave de acesso.
 */
@Component
public class FiscalCommandsListener {

    private static final Logger log = LoggerFactory.getLogger(FiscalCommandsListener.class);

    private final ManageInvoices invoices;
    private final Messages messages;
    private final ObjectMapper json;

    public FiscalCommandsListener(ManageInvoices invoices, Messages messages, ObjectMapper json) {
        this.invoices = invoices;
        this.messages = messages;
        this.json = json;
    }

    @RetryableTopic(
            attempts = "${app.kafka.attempts}",
            backoff = @Backoff(delayExpression = "${app.kafka.initial-backoff-ms}", multiplier = 2.0, maxDelay = 30_000),
            retryTopicSuffix = "-retry",
            dltTopicSuffix = "-dlq",
            topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,
            exclude = InvalidMessageException.class,
            traversingCauses = "true")
    @KafkaListener(id = "fiscal-commands", groupId = "fiscal-document-gateway", topics = Topics.FISCAL_COMMANDS)
    public void onCommand(ConsumerRecord<String, String> record) {
        var envelope = messages.read(record.value());
        var p = envelope.payload();
        switch (envelope.type()) {
            case "IssueInvoice" -> invoices.issue(envelope.id(), new IssuanceRequest(
                    envelope.orderId(),
                    json.convertValue(p.get("recipient"), Recipient.class),
                    List.of(json.convertValue(p.get("items"), InvoiceItem[].class)),
                    p.get("totalAmount").decimalValue()));
            case "CancelInvoice" -> invoices.cancel(envelope.id(), envelope.orderId(), text(p, "justification"));
            default -> throw new InvalidMessageException("Comando desconhecido: " + envelope.type());
        }
    }

    @DltHandler
    public void onDlq(ConsumerRecord<String, String> record) {
        var error = Messages.error(record);
        Envelope envelope;
        try {
            envelope = messages.read(record.value());
        } catch (InvalidMessageException e) {
            log.error("ALERTA: mensagem ilegível na DLQ: {}", record.value());
            return;
        }
        switch (envelope.type()) {
            case "IssueInvoice" -> invoices.issuanceExhausted(envelope.orderId(), error);
            case "CancelInvoice" -> invoices.cancellationExhausted(envelope.id(), envelope.orderId(), error);
            default -> log.error("ALERTA: {} na DLQ: {}", envelope.type(), error);
        }
    }
}
