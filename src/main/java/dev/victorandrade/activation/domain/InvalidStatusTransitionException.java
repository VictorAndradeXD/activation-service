package dev.victorandrade.activation.domain;

import java.util.UUID;

public class InvalidStatusTransitionException extends RuntimeException {

    public InvalidStatusTransitionException(UUID requestId, ActivationStatus from, ActivationStatus to) {
        super("Activation request %s cannot transition from %s to %s".formatted(requestId, from, to));
    }
}
