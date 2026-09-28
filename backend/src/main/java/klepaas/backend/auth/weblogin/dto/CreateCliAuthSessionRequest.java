package klepaas.backend.auth.weblogin.dto;

import jakarta.validation.constraints.NotBlank;
import klepaas.backend.auth.token.entity.CliTokenScope;

public record CreateCliAuthSessionRequest(
        @NotBlank String clientName,
        @NotBlank String hostname,
        @NotBlank String platform,
        @NotBlank String cliVersion,
        CliTokenScope scope
) {
}
