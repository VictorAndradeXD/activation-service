package dev.victorandrade.activation.application;

import java.time.Duration;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * @param lease how long a claimed request stays invisible to other workers. Must be longer than the provider
 *              connect + read timeouts, otherwise a slow call could be duplicated by another worker.
 */
@Validated
@ConfigurationProperties(prefix = "app.processing")
public record ProcessingProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("1s") @NotNull Duration pollInterval,
        @DefaultValue("20") @Min(1) int batchSize,
        @DefaultValue("30s") @NotNull Duration lease,
        @DefaultValue("5") @Min(1) int maxAttempts,
        @DefaultValue("2s") @NotNull Duration initialBackoff,
        @DefaultValue("5m") @NotNull Duration maxBackoff) {

    public RetryPolicy retryPolicy() {
        return new RetryPolicy(maxAttempts, initialBackoff, maxBackoff);
    }
}
