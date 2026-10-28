package dev.victorandrade.activation.integration.provider;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

class RetryAfterTest {

    private static final Instant NOW = Instant.parse("2026-10-26T14:00:00Z");

    @Test
    void parsesSeconds() {
        assertThat(RetryAfter.parse("120", NOW)).isEqualTo(Duration.ofSeconds(120));
    }

    @Test
    void parsesHttpDate() {
        assertThat(RetryAfter.parse("Mon, 26 Oct 2026 14:01:30 GMT", NOW)).isEqualTo(Duration.ofSeconds(90));
    }

    @Test
    void pastDateMeansNoWait() {
        assertThat(RetryAfter.parse("Mon, 26 Oct 2026 13:00:00 GMT", NOW)).isEqualTo(Duration.ZERO);
    }

    @Test
    void ignoresMissingOrInvalidValues() {
        assertThat(RetryAfter.parse(null, NOW)).isNull();
        assertThat(RetryAfter.parse(" ", NOW)).isNull();
        assertThat(RetryAfter.parse("-5", NOW)).isNull();
        assertThat(RetryAfter.parse("soon", NOW)).isNull();
    }
}
