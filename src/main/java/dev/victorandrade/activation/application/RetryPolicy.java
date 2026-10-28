package dev.victorandrade.activation.application;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Exponential backoff with jitter. Half of the delay is fixed and half is random, so retries from many requests that
 * failed together spread out instead of hitting the provider at the same instant.
 */
public record RetryPolicy(int maxAttempts, Duration initialBackoff, Duration maxBackoff) {

    public boolean canRetry(int attemptsMade) {
        return attemptsMade < maxAttempts;
    }

    /**
     * @param attemptsMade attempts already made (1 after the first failure)
     * @param retryAfter   minimum delay requested by the provider, or null
     */
    public Duration delayAfter(int attemptsMade, Duration retryAfter) {
        long exponential = initialBackoff.toMillis() << Math.min(attemptsMade - 1, 20);
        long capped = Math.min(exponential, maxBackoff.toMillis());
        long half = capped / 2;
        var delay = Duration.ofMillis(half + ThreadLocalRandom.current().nextLong(capped - half + 1));
        return retryAfter != null && retryAfter.compareTo(delay) > 0 ? retryAfter : delay;
    }
}
