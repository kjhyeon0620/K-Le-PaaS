package klepaas.backend.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record OAuthLoginRequest(
        @NotBlank String code,
        String redirectUri
) {
}
