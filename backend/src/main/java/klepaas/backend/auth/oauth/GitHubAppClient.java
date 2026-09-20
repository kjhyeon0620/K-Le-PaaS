package klepaas.backend.auth.oauth;

import klepaas.backend.global.exception.BusinessException;
import klepaas.backend.global.exception.ErrorCode;
import klepaas.backend.global.exception.GitHubAppNotInstalledException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

@Slf4j
@Component
public class GitHubAppClient {

    private final RestClient githubClient;
    private final GitHubAppJwtProvider jwtProvider;

    public GitHubAppClient(@Qualifier("githubClient") RestClient githubClient,
                           GitHubAppJwtProvider jwtProvider) {
        this.githubClient = githubClient;
        this.jwtProvider = jwtProvider;
    }

    public Long getInstallationId(String owner, String repo) {
        return getInstallation(owner, repo).id();
    }

    public InstallationAccount getInstallationAccount(String owner, String repo) {
        return getInstallation(owner, repo).account();
    }

    private InstallationResponse getInstallation(String owner, String repo) {
        try {
            return githubClient.get()
                    .uri("/repos/{owner}/{repo}/installation", owner, repo)
                    .header("Authorization", "Bearer " + jwtProvider.generateAppJwt())
                    .retrieve()
                    .body(InstallationResponse.class);
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().value() == 404) {
                throw new GitHubAppNotInstalledException(owner, repo);
            }
            throw new BusinessException(ErrorCode.GITHUB_APP_TOKEN_FAILED,
                    "GitHub App installation 조회 실패: " + e.getMessage());
        }
    }

    public String createInstallationToken(Long installationId) {
        try {
            InstallationTokenResponse response = githubClient.post()
                    .uri("/app/installations/{id}/access_tokens", installationId)
                    .header("Authorization", "Bearer " + jwtProvider.generateAppJwt())
                    .retrieve()
                    .body(InstallationTokenResponse.class);
            return response.token();
        } catch (HttpClientErrorException e) {
            throw new BusinessException(ErrorCode.GITHUB_APP_TOKEN_FAILED,
                    "GitHub App installation token 발급 실패: " + e.getMessage());
        }
    }

    private record InstallationResponse(Long id, InstallationAccount account) {}
    public record InstallationAccount(Long id, String type) {}

    private record InstallationTokenResponse(String token) {}
}
