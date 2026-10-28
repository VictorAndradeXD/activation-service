package dev.victorandrade.activation.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

class RetryPolicyTest {

    private final RetryPolicy policy = new RetryPolicy(5, Duration.ofSeconds(2), Duration.ofSeconds(30));

    @RepeatedTest(20)
    void delayGrowsExponentiallyWithJitter() {
        assertThat(policy.delayAfter(1, null)).isBetween(Duration.ofSeconds(1), Duration.ofSeconds(2));
        assertThat(policy.delayAfter(2, null)).isBetween(Duration.ofSeconds(2), Duration.ofSeconds(4));
        assertThat(policy.delayAfter(3, null)).isBetween(Duration.ofSeconds(4), Duration.ofSeconds(8));
    }

    @RepeatedTest(20)
    void delayIsCappedAtMaxBackoff() {
        assertThat(policy.delayAfter(30, null)).isBetween(Duration.ofSeconds(15), Duration.ofSeconds(30));
    }

    @Test
    void retryAfterLongerThanBackoffWins() {
        assertThat(policy.delayAfter(1, Duration.ofMinutes(2))).isEqualTo(Duration.ofMinutes(2));
    }

    @RepeatedTest(10)
    void retryAfterShorterThanBackoffDoesNotShortenIt() {
        assertThat(policy.delayAfter(3, Duration.ofMillis(1))).isGreaterThanOrEqualTo(Duration.ofSeconds(4));
    }

    @Test
    void stopsRetryingAtMaxAttempts() {
        assertThat(policy.canRetry(4)).isTrue();
        assertThat(policy.canRetry(5)).isFalse();
    }
}
