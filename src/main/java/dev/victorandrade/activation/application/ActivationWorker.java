package dev.victorandrade.activation.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "app.processing", name = "enabled", havingValue = "true", matchIfMissing = true)
class ActivationWorker {

    private final ActivationProcessor processor;

    ActivationWorker(ActivationProcessor processor) {
        this.processor = processor;
    }

    @Scheduled(fixedDelayString = "${app.processing.poll-interval:1s}")
    void poll() {
        processor.processDueRequests();
    }
}
