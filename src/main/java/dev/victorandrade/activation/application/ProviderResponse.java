package dev.victorandrade.activation.application;

import java.time.Duration;

/** What the provider call meant for the request, independent of the transport details. */
public sealed interface ProviderResponse {

    /** The provider activated the product (or had already done so for this idempotency key). */
    record Activated() implements ProviderResponse {
    }

    /** The provider refused the request; sending it again would fail the same way. */
    record Rejected(String reason) implements ProviderResponse {
    }

    /**
     * The outcome is unknown or the provider is temporarily unavailable; a new attempt may succeed.
     *
     * @param retryAfter minimum wait requested by the provider (Retry-After), or null
     */
    record TemporaryFailure(String reason, Duration retryAfter) implements ProviderResponse {
    }
}
