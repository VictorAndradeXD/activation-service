package dev.victorandrade.activation.domain;

/**
 * Lifecycle of an activation request.
 *
 * <pre>
 * PENDING ──► PROCESSING ──► COMPLETED
 *                │  ▲   └──► FAILED
 *                └──┘ (new attempt after a temporary failure)
 * </pre>
 */
public enum ActivationStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED;

    public boolean canTransitionTo(ActivationStatus target) {
        return switch (this) {
            case PENDING -> target == PROCESSING;
            case PROCESSING -> target == PROCESSING || target == COMPLETED || target == FAILED;
            case COMPLETED, FAILED -> false;
        };
    }

    public boolean isFinal() {
        return this == COMPLETED || this == FAILED;
    }
}
