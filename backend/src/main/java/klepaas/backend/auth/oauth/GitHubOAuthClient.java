package klepaas.backend.auth.oauth;

import com.fasterxml.jackson.annotation.JsonProperty;
import klepaas.backend.auth.dto.GitHubTokenResponse;
import klepaas.backend.auth.dto.GitHubUserResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

@Slf4j
@Component
public class GitHubOAuthClient {

    private final String clientId;
    private final String clientSecret;
    private final String redirectUri;
    private final RestClient restClient;

    public GitHubOAuthClient(
            @Value("${github.client-id}") String clientId,
            @Value("${github.client-secret}") String clientSecret,
            @Value("${github.redirect-uri}") String redirectUri) {
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.redirectUri = redirectUri;
        this.restClient = RestClient.create();
    }

    public String getAuthorizationUrl(String redirectUriOverride) {
        String resolvedRedirectUri = resolveRedirectUri(redirectUriOverride);
        return UriComponentsBuilder.fromUriString("https://github.com/login/oauth/authorize")
                .queryParam("client_id", clientId)
                .queryParam("redirect_uri", resolvedRedirectUri)
                .queryParam("scope", "user:email,read:user")
                .build()
                .toUriString();
    }

    public GitHubTokenResponse exchangeCode(String code, String redirectUriOverride) {
        String resolvedRedirectUri = resolveRedirectUri(redirectUriOverride);
        return restClient.post()
                .uri("https://github.com/login/oauth/access_token")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(new TokenRequest(clientId, clientSecret, code, resolvedRedirectUri))
                .retrieve()
                .body(GitHubTokenResponse.class);
    }

    public GitHubUserResponse getUserInfo(String accessToken) {
        return restClient.get()
                .uri("https://api.github.com/user")
                .header("Authorization", "Bearer " + accessToken)
                .header("Accept", "application/vnd.github+json")
                .retrieve()
                .body(GitHubUserResponse.class);
    }

    private record TokenRequest(
            @JsonProperty("client_id") String clientId,
            @JsonProperty("client_secret") String clientSecret,
            String code,
            @JsonProperty("redirect_uri") String redirectUri
    ) {
    }

    private String resolveRedirectUri(String redirectUriOverride) {
        if (redirectUriOverride != null && !redirectUriOverride.isBlank()) {
            return redirectUriOverride;
        }
        return redirectUri;
    }
}
