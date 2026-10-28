package dev.victorandrade.activation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ActivationRequestTest {

    private static final Instant NOW = Instant.parse("2026-10-26T14:03:11Z");
    private static final Duration LEASE = Duration.ofSeconds(30);

    private ActivationRequest newRequest() {
        return ActivationRequest.create("EXT-1", "CUST-1", "INTERNET-500", "erp", "corr-1", NOW);
    }

    @Test
    void newRequestIsPendingAndImmediatelyDue() {
        var request = newRequest();

        assertThat(request.getId()).isNotNull();
        assertThat(request.getStatus()).isEqualTo(ActivationStatus.PENDING);
        assertThat(request.getAttempts()).isZero();
        assertThat(request.getNextAttemptAt()).isEqualTo(NOW);
        assertThat(request.getCreatedAt()).isEqualTo(NOW).isEqualTo(request.getUpdatedAt());
    }

    @Test
    void startAttemptMovesToProcessingAndLeasesTheRequest() {
        var request = newRequest();
        var later = NOW.plusSeconds(1);

        request.startAttempt(later, LEASE);

        assertThat(request.getStatus()).isEqualTo(ActivationStatus.PROCESSING);
        assertThat(request.getAttempts()).isEqualTo(1);
        assertThat(request.getNextAttemptAt()).isEqualTo(later.plus(LEASE));
        assertThat(request.getUpdatedAt()).isEqualTo(later);
    }

    @Test
    void completeClearsScheduleAndError() {
        var request = newRequest();
        request.startAttempt(NOW, LEASE);
        request.scheduleRetry(NOW.plusSeconds(5), "HTTP 500", NOW);
        request.startAttempt(NOW.plusSeconds(5), LEASE);

        request.complete(NOW.plusSeconds(6));

        assertThat(request.getStatus()).isEqualTo(ActivationStatus.COMPLETED);
        assertThat(request.getAttempts()).isEqualTo(2);
        assertThat(request.getNextAttemptAt()).isNull();
        assertThat(request.getLastError()).isNull();
    }

    @Test
    void scheduleRetryKeepsProcessingAndRecordsTheReason() {
        var request = newRequest();
        request.startAttempt(NOW, LEASE);
        var retryAt = NOW.plusSeconds(10);

        request.scheduleRetry(retryAt, "HTTP 503", NOW);

        assertThat(request.getStatus()).isEqualTo(ActivationStatus.PROCESSING);
        assertThat(request.getNextAttemptAt()).isEqualTo(retryAt);
        assertThat(request.getLastError()).isEqualTo("HTTP 503");
    }

    @Test
    void failTruncatesLongReasons() {
        var request = newRequest();
        request.startAttempt(NOW, LEASE);

        request.fail("x".repeat(1_000), NOW);

        assertThat(request.getStatus()).isEqualTo(ActivationStatus.FAILED);
        assertThat(request.getLastError()).hasSize(ActivationRequest.MAX_ERROR_LENGTH);
        assertThat(request.getNextAttemptAt()).isNull();
    }

    @Nested
    class InvalidTransitions {

        @Test
        void pendingCannotCompleteWithoutBeingProcessed() {
            var request = newRequest();

            assertThatThrownBy(() -> request.complete(NOW)).isInstanceOf(InvalidStatusTransitionException.class);
            assertThatThrownBy(() -> request.fail("x", NOW)).isInstanceOf(InvalidStatusTransitionException.class);
            assertThatThrownBy(() -> request.scheduleRetry(NOW, "x", NOW))
                    .isInstanceOf(InvalidStatusTransitionException.class);
        }

        @Test
        void completedIsFinal() {
            var request = newRequest();
            request.startAttempt(NOW, LEASE);
            request.complete(NOW);

            assertThatThrownBy(() -> request.startAttempt(NOW, LEASE))
                    .isInstanceOf(InvalidStatusTransitionException.class)
                    .hasMessageContaining("from COMPLETED to PROCESSING");
            assertThatThrownBy(() -> request.fail("x", NOW)).isInstanceOf(InvalidStatusTransitionException.class);
            assertThat(request.getStatus()).isEqualTo(ActivationStatus.COMPLETED);
        }

        @Test
        void failedIsFinal() {
            var request = newRequest();
            request.startAttempt(NOW, LEASE);
            request.fail("HTTP 400", NOW);

            assertThatThrownBy(() -> request.startAttempt(NOW, LEASE))
                    .isInstanceOf(InvalidStatusTransitionException.class);
            assertThatThrownBy(() -> request.complete(NOW)).isInstanceOf(InvalidStatusTransitionException.class);
        }

        @ParameterizedTest
        @EnumSource(ActivationStatus.class)
        void nothingGoesBackToPending(ActivationStatus from) {
            assertThat(from.canTransitionTo(ActivationStatus.PENDING)).isFalse();
        }
    }

    @Test
    void sameSubmissionComparesTheFullPayloadAndOwner() {
        var request = newRequest();

        assertThat(request.isSameSubmission("CUST-1", "INTERNET-500", "erp")).isTrue();
        assertThat(request.isSameSubmission("CUST-1", "INTERNET-1000", "erp")).isFalse();
        assertThat(request.isSameSubmission("CUST-2", "INTERNET-500", "erp")).isFalse();
        assertThat(request.isSameSubmission("CUST-1", "INTERNET-500", "crm")).isFalse();
    }
}
