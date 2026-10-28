package dev.victorandrade.activation.integration.provider;

import java.util.UUID;

import dev.victorandrade.activation.domain.ActivationRequest;

record ProviderActivationPayload(UUID requestId, String externalId, String customerId, String productCode) {

    static ProviderActivationPayload from(ActivationRequest request) {
        return new ProviderActivationPayload(request.getId(), request.getExternalId(), request.getCustomerId(),
                request.getProductCode());
    }
}
