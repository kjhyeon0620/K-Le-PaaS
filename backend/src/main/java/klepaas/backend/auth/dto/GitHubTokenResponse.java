package klepaas.backend.auth.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record GitHubTokenResponse(
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("token_type") String tokenType,
        String scope
) {
}
