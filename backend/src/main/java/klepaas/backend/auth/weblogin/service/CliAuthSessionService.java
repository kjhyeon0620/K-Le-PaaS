package klepaas.backend.auth.weblogin.service;

import klepaas.backend.auth.token.dto.CreateCliAccessTokenRequest;
import klepaas.backend.auth.token.dto.CreateCliAccessTokenResponse;
import klepaas.backend.auth.token.service.CliAccessTokenService;
import klepaas.backend.auth.weblogin.dto.CliAuthSessionResponse;
import klepaas.backend.auth.weblogin.dto.CreateCliAuthSessionRequest;
import klepaas.backend.auth.weblogin.dto.ExchangeCliAuthSessionRequest;
import klepaas.backend.auth.weblogin.dto.ExchangeCliAuthSessionResponse;
import klepaas.backend.auth.weblogin.entity.CliAuthSession;
import klepaas.backend.auth.weblogin.entity.CliAuthSessionStatus;
import klepaas.backend.auth.weblogin.exception.CliAuthSessionNotFoundException;
import klepaas.backend.auth.weblogin.repository.CliAuthSessionRepository;
import klepaas.backend.global.exception.ErrorCode;
import klepaas.backend.global.exception.InvalidRequestException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class CliAuthSessionService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final String USER_CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int POLL_INTERVAL_SECONDS = 3;
    private static final int TOKEN_EXPIRES_DAYS = 30;

    private final CliAuthSessionRepository cliAuthSessionRepository;
    private final CliAccessTokenService cliAccessTokenService;
    private final String appBaseUrl;

    public CliAuthSessionService(
            CliAuthSessionRepository cliAuthSessionRepository,
            CliAccessTokenService cliAccessTokenService,
            @Value("${app.base-url:http://localhost:3000}") String appBaseUrl
    ) {
        this.cliAuthSessionRepository = cliAuthSessionRepository;
        this.cliAccessTokenService = cliAccessTokenService;
        this.appBaseUrl = appBaseUrl;
    }

    @Transactional
    public CliAuthSessionResponse createSession(CreateCliAuthSessionRequest request) {
        CliAuthSession session = CliAuthSession.builder()
                .id(UUID.randomUUID().toString())
                .userCode(generateUserCode())
                .status(CliAuthSessionStatus.PENDING)
                .clientName(sanitize(request.clientName(), "KLEPaaS CLI"))
                .hostname(sanitize(request.hostname(), "localhost"))
                .platform(sanitize(request.platform(), "unknown"))
                .cliVersion(sanitize(request.cliVersion(), "dev"))
                .expiresAt(LocalDateTime.now().plusMinutes(5))
                .scope(request.scope())
                .build();

        cliAuthSessionRepository.save(session);
        return CliAuthSessionResponse.from(session, POLL_INTERVAL_SECONDS, verificationUrl(session.getId()));
    }

    @Transactional
    public CliAuthSessionResponse getSession(String sessionId) {
        CliAuthSession session = cliAuthSessionRepository.findById(sessionId)
                .orElseThrow(CliAuthSessionNotFoundException::new);

        expireIfNeeded(session);
        return CliAuthSessionResponse.from(session, POLL_INTERVAL_SECONDS, verificationUrl(session.getId()));
    }

    @Transactional
    public void approve(String sessionId, Long userId) {
        CliAuthSession session = cliAuthSessionRepository.findForUpdateById(sessionId)
                .orElseThrow(CliAuthSessionNotFoundException::new);

        expireIfNeeded(session);
        if (session.getStatus() != CliAuthSessionStatus.PENDING) {
            throw new InvalidRequestException(ErrorCode.INVALID_CLI_AUTH_SESSION_STATE, "승인할 수 없는 세션 상태입니다: " + session.getStatus());
        }

        session.markApproved(userId, LocalDateTime.now());
    }

    @Transactional
    public void reject(String sessionId, Long userId) {
        CliAuthSession session = cliAuthSessionRepository.findForUpdateById(sessionId)
                .orElseThrow(CliAuthSessionNotFoundException::new);

        expireIfNeeded(session);
        if (session.getStatus() != CliAuthSessionStatus.PENDING && session.getStatus() != CliAuthSessionStatus.APPROVED) {
            throw new InvalidRequestException(ErrorCode.INVALID_CLI_AUTH_SESSION_STATE, "거부할 수 없는 세션 상태입니다: " + session.getStatus());
        }

        session.markRejected(LocalDateTime.now());
    }

    @Transactional
    public ExchangeCliAuthSessionResponse exchange(String sessionId, ExchangeCliAuthSessionRequest request) {
        CliAuthSession session = cliAuthSessionRepository.findForUpdateById(sessionId)
                .orElseThrow(CliAuthSessionNotFoundException::new);

        expireIfNeeded(session);
        if (!session.getUserCode().equalsIgnoreCase(request.userCode())) {
            throw new InvalidRequestException(ErrorCode.INVALID_CLI_AUTH_SESSION_STATE, "CLI 인증 코드가 일치하지 않습니다");
        }
        if (session.getStatus() != CliAuthSessionStatus.APPROVED) {
            throw new InvalidRequestException(ErrorCode.INVALID_CLI_AUTH_SESSION_STATE, "아직 승인되지 않은 세션입니다");
        }

        CreateCliAccessTokenResponse issued = cliAccessTokenService.createToken(
                session.getApprovedByUserId(),
                new CreateCliAccessTokenRequest(tokenName(session), TOKEN_EXPIRES_DAYS, session.getScope())
        );
        session.markConsumed(LocalDateTime.now());
        return new ExchangeCliAuthSessionResponse(issued.token(), issued.metadata());
    }

    private void expireIfNeeded(CliAuthSession session) {
        if (session.getStatus() == CliAuthSessionStatus.PENDING && session.isExpired(LocalDateTime.now())) {
            session.markExpired();
        }
    }

    private String verificationUrl(String sessionId) {
        return appBaseUrl + "/cli/authorize?session=" + sessionId;
    }

    private String tokenName(CliAuthSession session) {
        return "web-" + session.getHostname().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]", "-");
    }

    private String sanitize(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    private String generateUserCode() {
        StringBuilder builder = new StringBuilder(8);
        for (int index = 0; index < 8; index += 1) {
            builder.append(USER_CODE_CHARS.charAt(SECURE_RANDOM.nextInt(USER_CODE_CHARS.length())));
        }
        builder.insert(4, '-');
        return builder.toString();
    }
}
