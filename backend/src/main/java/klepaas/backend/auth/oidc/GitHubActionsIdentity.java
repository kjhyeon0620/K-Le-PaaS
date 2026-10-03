package klepaas.backend.auth.oidc;

/** 검증을 통과한 GitHub Actions OIDC 토큰의 claim. repository는 "owner/repo". */
public record GitHubActionsIdentity(String repository, String ref, String sha) {

    public String branch() {
        return ref.startsWith("refs/heads/") ? ref.substring("refs/heads/".length()) : ref;
    }
}
