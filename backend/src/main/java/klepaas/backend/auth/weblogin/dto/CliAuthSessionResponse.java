package klepaas.backend.auth.weblogin.dto;

import klepaas.backend.auth.token.entity.CliTokenScope;
import klepaas.backend.auth.weblogin.entity.CliAuthSession;
import klepaas.backend.auth.weblogin.entity.CliAuthSessionStatus;

import java.time.LocalDateTime;

public record CliAuthSessionResponse(
        String sessionId,
        String userCode,
        CliAuthSessionStatus status,
        String clientName,
        String hostname,
        String platform,
        String cliVersion,
        CliTokenScope scope,
        LocalDateTime expiresAt,
        int pollIntervalSeconds,
        String verificationUrl
) {
    public static CliAuthSessionResponse from(CliAuthSession session, int pollIntervalSeconds, String verificationUrl) {
        return new CliAuthSessionResponse(
                session.getId(),
                session.getUserCode(),
                session.getStatus(),
                session.getClientName(),
                session.getHostname(),
                session.getPlatform(),
                session.getCliVersion(),
                session.getScope(),
                session.getExpiresAt(),
                pollIntervalSeconds,
                verificationUrl
        );
    }
}
