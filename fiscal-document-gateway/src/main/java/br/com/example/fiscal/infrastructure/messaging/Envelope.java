package br.com.example.fiscal.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/**
 * Formato de toda mensagem trafegada entre os serviços.
 * O {@code id} é a identidade da mensagem: é ele que os consumidores registram no inbox.
 */
public record Envelope(UUID id, String type, String orderId, Instant occurredAt, JsonNode payload) {
}
