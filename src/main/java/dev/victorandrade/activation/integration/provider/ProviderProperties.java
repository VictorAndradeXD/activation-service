package dev.victorandrade.activation.integration.provider;

import java.net.URI;
import java.time.Duration;

import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * @param apiKey credential for the provider, injected from the environment (secret manager in production); never
 *               logged
 */
@Validated
@ConfigurationProperties(prefix = "app.provider")
public record ProviderProperties(
        @NotNull URI baseUrl,
        @DefaultValue("2s") @NotNull Duration connectTimeout,
        @DefaultValue("5s") @NotNull Duration readTimeout,
        String apiKey) {
}
