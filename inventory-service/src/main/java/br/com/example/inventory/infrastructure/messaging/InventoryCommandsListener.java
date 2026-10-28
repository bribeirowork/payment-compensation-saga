package br.com.example.inventory.infrastructure.messaging;

import br.com.example.inventory.application.port.in.ManageInventory;
import br.com.example.inventory.domain.Reservation;
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

/** estoque.comandos → estoque.comandos-retry-0..N → estoque.comandos-dlq. */
@Component
public class InventoryCommandsListener {

    private static final Logger log = LoggerFactory.getLogger(InventoryCommandsListener.class);

    private final ManageInventory inventory;
    private final Messages messages;
    private final ObjectMapper json;

    public InventoryCommandsListener(ManageInventory inventory, Messages messages, ObjectMapper json) {
        this.inventory = inventory;
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
    @KafkaListener(id = "inventory-commands", groupId = "inventory-service", topics = Topics.INVENTORY_COMMANDS)
    public void onCommand(ConsumerRecord<String, String> record) {
        var envelope = messages.read(record.value());
        switch (envelope.type()) {
            case "ReserveInventory" -> inventory.reserve(envelope.id(), envelope.orderId(),
                    List.of(json.convertValue(envelope.payload().get("items"), Reservation.Item[].class)));
            case "ReleaseReservation" -> inventory.release(envelope.id(), envelope.orderId());
            default -> throw new InvalidMessageException("Comando desconhecido: " + envelope.type());
        }
    }

    @DltHandler
    public void onDlq(ConsumerRecord<String, String> record) {
        // Em produção: métrica + alerta + rotina de reprocessamento, como no payment-service.
        log.error("ALERTA: comando de estoque na DLQ (pedido {}): {} | {}", record.key(), Messages.error(record),
                record.value());
    }
}
