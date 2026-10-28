package dev.victorandrade.activation.application;

import org.springframework.stereotype.Component;

import dev.victorandrade.activation.domain.ActivationRequestRepository;
import dev.victorandrade.activation.domain.ActivationStatus;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;

/**
 * Number of unfinished requests by status. Final statuses are exposed as counters (activation.requests.finished)
 * instead, since counting an ever-growing table on every scrape would be expensive.
 */
@Component
class BacklogMetrics implements MeterBinder {

    private final ActivationRequestRepository repository;

    BacklogMetrics(ActivationRequestRepository repository) {
        this.repository = repository;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        for (var status : new ActivationStatus[] {ActivationStatus.PENDING, ActivationStatus.PROCESSING}) {
            Gauge.builder("activation.requests.backlog", repository, r -> r.countByStatus(status))
                    .description("Activation requests not yet finished")
                    .tag("status", status.name())
                    .register(registry);
        }
    }
}
