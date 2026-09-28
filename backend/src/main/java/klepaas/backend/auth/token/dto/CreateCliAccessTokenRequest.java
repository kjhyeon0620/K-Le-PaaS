package klepaas.backend.auth.token.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import klepaas.backend.auth.token.entity.CliTokenScope;

public record CreateCliAccessTokenRequest(
        @NotBlank String name,
        @Min(1) @Max(365) int expiresInDays,
        CliTokenScope scope
) {
    public CreateCliAccessTokenRequest(String name, int expiresInDays) {
        this(name, expiresInDays, null);
    }
}
