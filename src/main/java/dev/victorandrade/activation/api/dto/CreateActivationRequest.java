package dev.victorandrade.activation.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Validation rules: all fields required, at most 64 characters, letters, digits and {@code . _ : -} only.
 */
public record CreateActivationRequest(
        @NotBlank(message = "é obrigatório")
        @Size(max = 64, message = "deve ter no máximo 64 caracteres")
        @Pattern(regexp = IDENTIFIER, message = IDENTIFIER_MESSAGE)
        String externalId,

        @NotBlank(message = "é obrigatório")
        @Size(max = 64, message = "deve ter no máximo 64 caracteres")
        @Pattern(regexp = IDENTIFIER, message = IDENTIFIER_MESSAGE)
        String customerId,

        @NotBlank(message = "é obrigatório")
        @Size(max = 64, message = "deve ter no máximo 64 caracteres")
        @Pattern(regexp = IDENTIFIER, message = IDENTIFIER_MESSAGE)
        String productCode) {

    static final String IDENTIFIER = "[A-Za-z0-9._:-]*";
    static final String IDENTIFIER_MESSAGE = "deve conter apenas letras, números e . _ : -";
}
