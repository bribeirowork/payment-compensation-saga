package br.com.example.inventory.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

@Component
public class Messages {

    private final ObjectMapper json;

    public Messages(ObjectMapper json) {
        this.json = json;
    }

    public Envelope create(String type, String orderId, Instant now, Object payload) {
        return new Envelope(UUID.randomUUID(), type, orderId, now, json.valueToTree(payload));
    }

    public String serialize(Object object) {
        try {
            return json.writeValueAsString(object);
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao serializar " + object, e);
        }
    }

    public Envelope read(String message) {
        try {
            var envelope = json.readValue(message, Envelope.class);
            if (envelope.id() == null || envelope.type() == null) {
                throw new InvalidMessageException("Envelope sem id ou tipo: " + message);
            }
            return envelope;
        } catch (InvalidMessageException e) {
            throw e;
        } catch (Exception e) {
            throw new InvalidMessageException("Mensagem ilegível: " + message, e);
        }
    }

    public static String text(JsonNode payload, String field) {
        var value = payload.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    /** Tópico original, sem o sufixo dos retry topics (ex.: pagamento.comandos-retry-2). */
    public static String originalTopic(ConsumerRecord<String, String> record) {
        var topic = header(record, KafkaHeaders.DLT_ORIGINAL_TOPIC);
        if (topic == null) topic = record.topic().replaceFirst("-dlq$", "");
        return topic.replaceFirst("-retry(-\\d+)?$", "");
    }

    public static String error(ConsumerRecord<String, String> record) {
        var error = header(record, KafkaHeaders.DLT_EXCEPTION_MESSAGE);
        return error != null ? error : "unknown";
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
