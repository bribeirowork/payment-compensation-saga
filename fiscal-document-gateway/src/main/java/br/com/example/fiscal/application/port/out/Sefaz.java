package br.com.example.fiscal.application.port.out;

import br.com.example.fiscal.domain.AccessKey;
import br.com.example.fiscal.domain.Invoice;

import java.time.Instant;

/**
 * Porta para a SEFAZ. Indisponibilidade lança TransientFailureException;
 * timeout de leitura lança SefazTimeoutException (situação desconhecida).
 */
public interface Sefaz {

    AuthorizationResponse authorize(Invoice invoice);

    AuthorizationResponse lookup(AccessKey key);

    EventResponse cancel(AccessKey key, String authorizationProtocol, String justification);

    sealed interface AuthorizationResponse {
        record Authorized(String protocol, Instant authorizedAt) implements AuthorizationResponse {}

        record Rejected(String code, String reason) implements AuthorizationResponse {}

        record NotFound() implements AuthorizationResponse {}
    }

    sealed interface EventResponse {
        record Registered(String protocol) implements EventResponse {}

        record Rejected(String code, String reason) implements EventResponse {}
    }
}
