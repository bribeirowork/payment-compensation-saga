package br.com.example.fiscal.infrastructure.sefaz;

import br.com.example.fiscal.application.TransientFailureException;
import br.com.example.fiscal.application.SefazTimeoutException;
import br.com.example.fiscal.application.port.out.Sefaz;
import br.com.example.fiscal.domain.AccessKey;
import br.com.example.fiscal.domain.Invoice;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;
import java.time.Instant;
import java.util.Map;

/**
 * Adapter para a SEFAZ (simulada em JSON; a real é SOAP com XML assinado).
 * Diferencia os três tipos de falha que importam para o fluxo:
 *  - rejeição de negócio (cStat) → resposta;
 *  - indisponível (5xx / conexão recusada) → TransientFailureException;
 *  - timeout de leitura → SefazTimeoutException: a nota PODE ter sido autorizada.
 */
@Component
public class HttpSefaz implements Sefaz {

    private final RestClient http;
    private final String issuerCnpj;

    public HttpSefaz(@Value("${sefaz.url}") String url,
                     @Value("${sefaz.read-timeout-ms}") int readTimeout,
                     @Value("${fiscal.issuer.cnpj}") String issuerCnpj) {
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(1_000);
        requestFactory.setReadTimeout(readTimeout);
        this.http = RestClient.builder().baseUrl(url).requestFactory(requestFactory).build();
        this.issuerCnpj = issuerCnpj;
    }

    @Override
    public AuthorizationResponse authorize(Invoice invoice) {
        var body = Map.of(
                "accessKey", invoice.accessKey().value(),
                "issuerCnpj", issuerCnpj,
                "recipient", invoice.recipient(),
                "items", invoice.items(),
                "totalAmount", invoice.totalAmount());
        Map<?, ?> response = call("autorização", () -> http.post().uri("/nfe/authorization")
                .contentType(MediaType.APPLICATION_JSON).body(body)
                .exchange((req, res) -> body(res.getStatusCode(), res.bodyTo(Map.class))));
        return forAuthorization(response);
    }

    @Override
    public AuthorizationResponse lookup(AccessKey key) {
        Map<?, ?> response = call("lookup", () -> http.get().uri("/nfe/{key}", key.value())
                .exchange((req, res) -> body(res.getStatusCode(), res.bodyTo(Map.class))));
        return forAuthorization(response);
    }

    @Override
    public EventResponse cancel(AccessKey key, String authorizationProtocol, String justification) {
        var body = Map.of("tpEvento", "110111", "nProt", authorizationProtocol, "xJust", justification);
        Map<?, ?> response = call("cancellation", () -> http.post().uri("/nfe/{key}/events", key.value())
                .contentType(MediaType.APPLICATION_JSON).body(body)
                .exchange((req, res) -> body(res.getStatusCode(), res.bodyTo(Map.class))));
        if ("135".equals(text(response, "cStat"))) {
            return new EventResponse.Registered(text(response, "protocol"));
        }
        return new EventResponse.Rejected(text(response, "cStat"), text(response, "xMotivo"));
    }

    private static AuthorizationResponse forAuthorization(Map<?, ?> r) {
        return switch (String.valueOf(r.get("status"))) {
            // CANCELLED na consulta: a nota existiu e foi autorizada antes de ser cancelada.
            case "AUTHORIZED", "CANCELLED" -> new AuthorizationResponse.Authorized(text(r, "protocol"),
                    r.get("dhRecbto") != null ? Instant.parse(text(r, "dhRecbto")) : Instant.now());
            case "NOT_FOUND" -> new AuthorizationResponse.NotFound();
            default -> new AuthorizationResponse.Rejected(text(r, "cStat"), text(r, "xMotivo"));
        };
    }

    private static Map<?, ?> body(HttpStatusCode status, Map<?, ?> body) {
        if (status.is5xxServerError()) {
            throw new TransientFailureException("SEFAZ indisponível (HTTP " + status.value() + ")");
        }
        return body == null ? Map.of() : body;
    }

    private static Map<?, ?> call(String operation, java.util.function.Supplier<Map<?, ?>> invocation) {
        try {
            return invocation.get();
        } catch (ResourceAccessException e) {
            if (e.getCause() instanceof SocketTimeoutException) {
                throw new SefazTimeoutException("Timeout na " + operation + " da SEFAZ", e);
            }
            throw new TransientFailureException("SEFAZ inacessível na " + operation + ": " + e.getMessage(), e);
        }
    }

    private static String text(Map<?, ?> map, String field) {
        var value = map.get(field);
        return value == null ? null : String.valueOf(value);
    }
}
