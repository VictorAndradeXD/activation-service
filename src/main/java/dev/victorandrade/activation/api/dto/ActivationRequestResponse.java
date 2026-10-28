package dev.victorandrade.activation.api.dto;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

import dev.victorandrade.activation.domain.ActivationRequest;
import dev.victorandrade.activation.domain.ActivationStatus;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ActivationRequestResponse(
        UUID id,
        String externalId,
        String customerId,
        String productCode,
        ActivationStatus status,
        int attempts,
        String failureReason,
        Instant createdAt,
        Instant updatedAt) {

    public static ActivationRequestResponse from(ActivationRequest request) {
        return new ActivationRequestResponse(
                request.getId(),
                request.getExternalId(),
                request.getCustomerId(),
                request.getProductCode(),
                request.getStatus(),
                request.getAttempts(),
                request.getStatus() == ActivationStatus.FAILED ? request.getLastError() : null,
                request.getCreatedAt(),
                request.getUpdatedAt());
    }
}
