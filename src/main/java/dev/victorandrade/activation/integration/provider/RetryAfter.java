package dev.victorandrade.activation.integration.provider;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/** Parses the Retry-After header, which is either a number of seconds or an HTTP date (RFC 9110). */
final class RetryAfter {

    private RetryAfter() {
    }

    /**
     * @return the requested wait, or null when the header is absent or invalid
     */
    static Duration parse(String value, Instant now) {
        if (value == null || value.isBlank()) {
            return null;
        }
        var trimmed = value.trim();
        try {
            long seconds = Long.parseLong(trimmed);
            return seconds >= 0 ? Duration.ofSeconds(seconds) : null;
        } catch (NumberFormatException notSeconds) {
            try {
                var date = ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                var wait = Duration.between(now, date);
                return wait.isNegative() ? Duration.ZERO : wait;
            } catch (DateTimeParseException notADate) {
                return null;
            }
        }
    }
}
