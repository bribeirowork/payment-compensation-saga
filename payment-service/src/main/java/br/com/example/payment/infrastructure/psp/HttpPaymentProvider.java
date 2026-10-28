package br.com.example.payment.infrastructure.psp;

import br.com.example.payment.application.TransientFailureException;
import br.com.example.payment.application.port.out.PaymentProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/** Adapter HTTP para o gateway de pagamento. Recusa = resultado; 5xx/timeout = falha transitória. */
@Component
public class HttpPaymentProvider implements PaymentProvider {

    private final RestClient http;

    public HttpPaymentProvider(@Value("${psp.url}") String url) {
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(1_000);
        requestFactory.setReadTimeout(3_000);
        this.http = RestClient.builder().baseUrl(url).requestFactory(requestFactory).build();
    }

    @Override
    public Result authorize(UUID paymentId, BigDecimal amount, String cardToken, String idempotencyKey) {
        return post("/authorizations", idempotencyKey,
                Map.of("paymentId", paymentId, "amount", amount, "cardToken", cardToken));
    }

    @Override
    public Result capture(String authorizationId, String idempotencyKey) {
        return post("/authorizations/" + authorizationId + "/capture", idempotencyKey, Map.of());
    }

    @Override
    public Result voidAuthorization(String authorizationId, String idempotencyKey) {
        return post("/authorizations/" + authorizationId + "/cancellation", idempotencyKey, Map.of());
    }

    @Override
    public Result refund(String authorizationId, String idempotencyKey) {
        return post("/authorizations/" + authorizationId + "/refund", idempotencyKey, Map.of());
    }

    @Override
    public PspStatus lookup(String authorizationId) {
        try {
            Map<?, ?> body = http.get().uri("/authorizations/{id}", authorizationId).retrieve().body(Map.class);
            return PspStatus.valueOf(String.valueOf(body.get("status")));
        } catch (RuntimeException e) {
            throw new TransientFailureException("Não foi possível consultar o PSP: " + e.getMessage(), e);
        }
    }

    private Result post(String uri, String idempotencyKey, Map<String, ?> body) {
        try {
            return http.post().uri(uri)
                    .header("Idempotency-Key", idempotencyKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .exchange((request, response) -> {
                        var status = response.getStatusCode();
                        if (status.is5xxServerError()) {
                            throw new TransientFailureException("PSP indisponível (HTTP " + status.value() + ") em " + uri);
                        }
                        Map<?, ?> data = response.bodyTo(Map.class);
                        var reference = data == null ? null : (String) data.get("id");
                        if (status.is2xxSuccessful()) return new Result(true, reference, null);
                        var reason = data == null ? "HTTP " + status.value() : String.valueOf(data.get("reason"));
                        return new Result(false, reference, reason);
                    });
        } catch (ResourceAccessException e) {
            throw new TransientFailureException("Sem resposta do PSP em " + uri + ": " + e.getMessage(), e);
        }
    }
}
