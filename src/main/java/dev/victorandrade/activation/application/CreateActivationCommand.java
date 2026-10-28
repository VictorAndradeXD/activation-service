package dev.victorandrade.activation.application;

public record CreateActivationCommand(
        String externalId,
        String customerId,
        String productCode,
        String clientId,
        String correlationId) {
}
