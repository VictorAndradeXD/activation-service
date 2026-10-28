package dev.victorandrade.activation.application;

import java.time.Clock;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import dev.victorandrade.activation.application.ProviderResponse.Activated;
import dev.victorandrade.activation.application.ProviderResponse.Rejected;
import dev.victorandrade.activation.application.ProviderResponse.TemporaryFailure;
import dev.victorandrade.activation.domain.ActivationRequest;
import dev.victorandrade.activation.domain.ActivationRequestRepository;
import dev.victorandrade.activation.observability.CorrelationId;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Sends due requests to the provider. The table itself works as an outbox: a committed request is PENDING until a
 * worker claims it, so nothing is lost if the application stops between the API commit and the provider call.
 * <p>
 * Each request goes through three short steps, and the HTTP call never runs inside a database transaction:
 * <ol>
 *     <li>claim (transaction): lock the row, move it to PROCESSING and lease it;</li>
 *     <li>call the provider (no transaction);</li>
 *     <li>record the outcome (transaction), unless another worker re-claimed the request meanwhile.</li>
 * </ol>
 */
@Component
public class ActivationProcessor {

    private static final Logger log = LoggerFactory.getLogger(ActivationProcessor.class);

    private final ActivationRequestRepository repository;
    private final ActivationProvider provider;
    private final TransactionTemplate transactionTemplate;
    private final ProcessingProperties properties;
    private final RetryPolicy retryPolicy;
    private final Clock clock;
    private final MeterRegistry meterRegistry;

    public ActivationProcessor(ActivationRequestRepository repository, ActivationProvider provider,
                               TransactionTemplate transactionTemplate, ProcessingProperties properties, Clock clock,
                               MeterRegistry meterRegistry) {
        this.repository = repository;
        this.provider = provider;
        this.transactionTemplate = transactionTemplate;
        this.properties = properties;
        this.retryPolicy = properties.retryPolicy();
        this.clock = clock;
        this.meterRegistry = meterRegistry;
    }

    /**
     * @return how many requests were sent to the provider in this round
     */
    public int processDueRequests() {
        int processed = 0;
        while (processed < properties.batchSize()) {
            var claimed = claimNext();
            if (claimed.isEmpty()) {
                break;
            }
            process(claimed.get());
            processed++;
        }
        return processed;
    }

    private Optional<ActivationRequest> claimNext() {
        return transactionTemplate.execute(status -> repository.lockNextDue(clock.instant())
                .map(request -> {
                    request.startAttempt(clock.instant(), properties.lease());
                    return request;
                }));
    }

    private void process(ActivationRequest claimed) {
        if (claimed.getCorrelationId() != null) {
            MDC.put(CorrelationId.MDC_KEY, claimed.getCorrelationId());
        }
        try {
            log.info("Sending request {} to provider (attempt {})", claimed.getId(), claimed.getAttempts());
            var response = provider.activate(claimed);
            meterRegistry.counter("activation.provider.responses", "outcome", outcome(response)).increment();
            record(claimed, response);
        } catch (RuntimeException e) {
            // The request stays PROCESSING; when its lease expires it is claimed again.
            log.error("Failed to process request {}", claimed.getId(), e);
        } finally {
            MDC.remove(CorrelationId.MDC_KEY);
        }
    }

    private void record(ActivationRequest claimed, ProviderResponse response) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                var request = repository.findById(claimed.getId()).orElseThrow();
                if (!request.getVersion().equals(claimed.getVersion())) {
                    log.warn("Request {} was claimed again by another worker; discarding this result", request.getId());
                    return;
                }
                apply(request, response);
            });
        } catch (OptimisticLockingFailureException e) {
            log.warn("Request {} changed concurrently; discarding this result", claimed.getId());
        }
    }

    private void apply(ActivationRequest request, ProviderResponse response) {
        var now = clock.instant();
        switch (response) {
            case Activated() -> request.complete(now);
            case Rejected(var reason) -> request.fail(reason, now);
            case TemporaryFailure(var reason, var retryAfter) when retryPolicy.canRetry(request.getAttempts()) -> {
                var delay = retryPolicy.delayAfter(request.getAttempts(), retryAfter);
                request.scheduleRetry(now.plus(delay), reason, now);
                log.warn("Request {} failed temporarily ({}); retrying in {}", request.getId(), reason, delay);
                return;
            }
            case TemporaryFailure(var reason, var ignored) ->
                    request.fail("Limite de %d tentativas atingido. Último erro: %s"
                            .formatted(request.getAttempts(), reason), now);
        }
        log.info("Request {} finished as {}", request.getId(), request.getStatus());
        meterRegistry.counter("activation.requests.finished", "status", request.getStatus().name()).increment();
    }

    private static String outcome(ProviderResponse response) {
        return switch (response) {
            case Activated ignored -> "activated";
            case Rejected ignored -> "rejected";
            case TemporaryFailure ignored -> "temporary_failure";
        };
    }
}
