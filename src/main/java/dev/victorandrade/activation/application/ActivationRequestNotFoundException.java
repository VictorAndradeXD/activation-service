package dev.victorandrade.activation.application;

import java.util.UUID;

public class ActivationRequestNotFoundException extends RuntimeException {

    public ActivationRequestNotFoundException(UUID id) {
        super("Activation request %s not found".formatted(id));
    }
}
