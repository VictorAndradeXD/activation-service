package dev.victorandrade.activation.application;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.jayway.jsonpath.JsonPath;

import dev.victorandrade.activation.domain.ActivationRequest;
import dev.victorandrade.activation.domain.ActivationStatus;
import dev.victorandrade.activation.support.IntegrationTest;

class ActivationProcessingIT extends IntegrationTest {

    private static final String ACTIVATE = "/provider/v1/activate";

    @Autowired
    private ActivationProcessor processor;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void successfulActivationCompletesTheRequest() throws Exception {
        provider(aResponse().withStatus(200));
        var id = create("EXT-1");

        assertThat(processor.processDueRequests()).isEqualTo(1);

        var request = reload(id);
        assertThat(request.getStatus()).isEqualTo(ActivationStatus.COMPLETED);
        assertThat(request.getAttempts()).isEqualTo(1);
        PROVIDER.verify(1, postRequestedFor(urlEqualTo(ACTIVATE))
                .withHeader("Idempotency-Key", equalTo(id.toString()))
                .withHeader("X-Correlation-Id", equalTo("corr-0000000001"))
                .withRequestBody(equalToJson("""
                        {"requestId": "%s", "externalId": "EXT-1", "customerId": "CUST-1",
                         "productCode": "INTERNET-500"}
                        """.formatted(id))));
    }

    @Test
    void clientErrorFailsTheRequestWithoutRetrying() throws Exception {
        provider(aResponse().withStatus(400));
        var id = create("EXT-1");

        processor.processDueRequests();
        clock.advance(Duration.ofHours(1));
        processor.processDueRequests();

        var request = reload(id);
        assertThat(request.getStatus()).isEqualTo(ActivationStatus.FAILED);
        assertThat(request.getLastError()).contains("HTTP 400");
        PROVIDER.verify(1, postRequestedFor(urlEqualTo(ACTIVATE)));
    }

    @Test
    void serverErrorIsRetriedAfterBackoffWithTheSameIdempotencyKey() throws Exception {
        provider(aResponse().withStatus(500));
        var id = create("EXT-1");

        processor.processDueRequests();

        var afterFailure = reload(id);
        assertThat(afterFailure.getStatus()).isEqualTo(ActivationStatus.PROCESSING);
        assertThat(afterFailure.getAttempts()).isEqualTo(1);
        // initial backoff 1s with jitter: between 0.5s and 1s
        assertThat(afterFailure.getNextAttemptAt())
                .isBetween(clock.instant().plusMillis(500), clock.instant().plusSeconds(1));
        assertThat(processor.processDueRequests()).as("not due before the backoff").isZero();

        PROVIDER.resetMappings();
        provider(aResponse().withStatus(201));
        clock.advance(Duration.ofSeconds(1));
        processor.processDueRequests();

        var request = reload(id);
        assertThat(request.getStatus()).isEqualTo(ActivationStatus.COMPLETED);
        assertThat(request.getAttempts()).isEqualTo(2);
        PROVIDER.verify(2, postRequestedFor(urlEqualTo(ACTIVATE)).withHeader("Idempotency-Key", equalTo(id.toString())));
    }

    @Test
    void failsAfterMaxAttempts() throws Exception {
        provider(aResponse().withStatus(503));
        var id = create("EXT-1");

        for (int i = 0; i < 5; i++) {
            processor.processDueRequests();
            clock.advance(Duration.ofSeconds(10));
        }

        var request = reload(id);
        assertThat(request.getStatus()).isEqualTo(ActivationStatus.FAILED);
        assertThat(request.getAttempts()).isEqualTo(3);
        assertThat(request.getLastError()).startsWith("Limite de 3 tentativas atingido");
        PROVIDER.verify(3, postRequestedFor(urlEqualTo(ACTIVATE)));
    }

    @Test
    void timeoutIsTreatedAsTemporaryAndRetriedSafely() throws Exception {
        provider(aResponse().withStatus(200).withFixedDelay(1_500)); // read timeout in tests is 500ms
        var id = create("EXT-1");

        processor.processDueRequests();

        var afterTimeout = reload(id);
        assertThat(afterTimeout.getStatus()).isEqualTo(ActivationStatus.PROCESSING);
        assertThat(afterTimeout.getLastError()).contains("HttpTimeoutException");

        PROVIDER.resetMappings();
        provider(aResponse().withStatus(200));
        clock.advance(Duration.ofSeconds(1));
        processor.processDueRequests();

        assertThat(reload(id).getStatus()).isEqualTo(ActivationStatus.COMPLETED);
        PROVIDER.verify(2, postRequestedFor(urlEqualTo(ACTIVATE)).withHeader("Idempotency-Key", equalTo(id.toString())));
    }

    @Test
    void retryAfterFromTheProviderIsRespected() throws Exception {
        provider(aResponse().withStatus(429).withHeader("Retry-After", "120"));
        var id = create("EXT-1");

        processor.processDueRequests();

        assertThat(reload(id).getNextAttemptAt()).isEqualTo(clock.instant().plusSeconds(120));
    }

    @Test
    void requestAbandonedMidCallIsReclaimedWhenTheLeaseExpires() throws Exception {
        provider(aResponse().withStatus(200));
        var id = create("EXT-1");
        // Simulates an instance that claimed the request and died before recording the provider's answer.
        transactionTemplate.executeWithoutResult(status ->
                repository.findById(id).orElseThrow().startAttempt(clock.instant(), Duration.ofSeconds(5)));

        assertThat(processor.processDueRequests()).as("still leased").isZero();
        clock.advance(Duration.ofSeconds(6));
        processor.processDueRequests();

        var request = reload(id);
        assertThat(request.getStatus()).isEqualTo(ActivationStatus.COMPLETED);
        assertThat(request.getAttempts()).isEqualTo(2);
    }

    @Test
    void finishedRequestsAreNotSentAgain() throws Exception {
        provider(aResponse().withStatus(200));
        create("EXT-1");

        processor.processDueRequests();
        clock.advance(Duration.ofHours(1));

        assertThat(processor.processDueRequests()).isZero();
        PROVIDER.verify(1, postRequestedFor(urlEqualTo(ACTIVATE)));
    }

    private void provider(ResponseDefinitionBuilder response) {
        PROVIDER.stubFor(post(ACTIVATE).willReturn(response));
    }

    private UUID create(String externalId) throws Exception {
        var result = mockMvc.perform(createRequest("erp", externalId, "CUST-1", "INTERNET-500")
                        .header("X-Correlation-Id", "corr-0000000001"))
                .andReturn();
        return UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.id"));
    }

    private ActivationRequest reload(UUID id) {
        return repository.findById(id).orElseThrow();
    }
}
