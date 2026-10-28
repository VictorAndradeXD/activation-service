package dev.victorandrade.activation.application;

/** The externalId was already used for a request with different data. */
public class DuplicateActivationRequestException extends RuntimeException {

    private final String externalId;

    public DuplicateActivationRequestException(String externalId) {
        super("externalId %s already used with different data".formatted(externalId));
        this.externalId = externalId;
    }

    public String getExternalId() {
        return externalId;
    }
}
