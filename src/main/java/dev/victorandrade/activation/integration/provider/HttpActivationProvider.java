package dev.victorandrade.activation.integration.provider;

import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import dev.victorandrade.activation.application.ActivationProvider;
import dev.victorandrade.activation.application.ProviderResponse;
import dev.victorandrade.activation.application.ProviderResponse.Activated;
import dev.victorandrade.activation.application.ProviderResponse.Rejected;
import dev.victorandrade.activation.application.ProviderResponse.TemporaryFailure;
import dev.victorandrade.activation.domain.ActivationRequest;
import dev.victorandrade.activation.observability.CorrelationId;

/**
 * Calls {@code POST /provider/v1/activate}. Every attempt for the same request sends the same Idempotency-Key (the
 * request id), so a retry after an ambiguous timeout cannot activate the product twice.
 */
@Component
class HttpActivationProvider implements ActivationProvider {

    static final String ACTIVATE_PATH = "/provider/v1/activate";
    static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private static final Logger log = LoggerFactory.getLogger(HttpActivationProvider.class);

    private final RestClient restClient;
    private final Clock clock;

    HttpActivationProvider(RestClient providerRestClient, Clock clock) {
        this.restClient = providerRestClient;
        this.clock = clock;
    }

    @Override
    public ProviderResponse activate(ActivationRequest request) {
        try {
            return restClient.post()
                    .uri(ACTIVATE_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(IDEMPOTENCY_KEY, request.getId().toString())
                    .headers(headers -> {
                        if (request.getCorrelationId() != null) {
                            headers.set(CorrelationId.HEADER, request.getCorrelationId());
                        }
                    })
                    .body(ProviderActivationPayload.from(request))
                    .exchange((httpRequest, response) -> classify(response.getStatusCode(), response.getHeaders()));
        } catch (RestClientException e) {
            // Timeout or I/O error: the provider may or may not have processed the request.
            var cause = e.getMostSpecificCause().getClass().getSimpleName();
            return new TemporaryFailure("Falha de comunicação com o provedor (%s)".formatted(cause), null);
        }
    }

    private ProviderResponse classify(HttpStatusCode status, HttpHeaders headers) {
        int code = status.value();
        if (status.is2xxSuccessful()) {
            return new Activated();
        }
        if (code == 429 || code == 503) {
            var retryAfter = RetryAfter.parse(headers.getFirst(HttpHeaders.RETRY_AFTER), clock.instant());
            return new TemporaryFailure("Provedor indisponível (HTTP %d)".formatted(code), retryAfter);
        }
        if (code == 408 || status.is5xxServerError()) {
            return new TemporaryFailure("Erro no provedor (HTTP %d)".formatted(code), null);
        }
        if (code == 401 || code == 403) {
            // Our credential is wrong or expired: not the request's fault, so keep it for a retry after the fix.
            log.error("Provider rejected our credentials (HTTP {})", code);
            return new TemporaryFailure("Provedor recusou a credencial do serviço (HTTP %d)".formatted(code), null);
        }
        return new Rejected("Provedor rejeitou a solicitação (HTTP %d)".formatted(code));
    }
}
