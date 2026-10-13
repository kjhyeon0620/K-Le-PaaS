package klepaas.backend.auth.dto;

public record GitHubUserResponse(
        Long id,
        String login,
        String email,
        String name
) {
}
