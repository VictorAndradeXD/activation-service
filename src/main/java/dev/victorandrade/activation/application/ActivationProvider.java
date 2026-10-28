package dev.victorandrade.activation.application;

import dev.victorandrade.activation.domain.ActivationRequest;

/** External activation provider. Implementations must not throw: every outcome maps to a {@link ProviderResponse}. */
public interface ActivationProvider {

    ProviderResponse activate(ActivationRequest request);
}
