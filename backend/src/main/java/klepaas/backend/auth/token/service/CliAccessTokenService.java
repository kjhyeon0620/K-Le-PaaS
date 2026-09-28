package klepaas.backend.auth.token.service;

import klepaas.backend.auth.config.CustomUserDetails;
import klepaas.backend.auth.token.dto.CliAccessTokenResponse;
import klepaas.backend.auth.token.dto.CreateCliAccessTokenRequest;
import klepaas.backend.auth.token.dto.CreateCliAccessTokenResponse;
import klepaas.backend.auth.token.entity.CliAccessToken;
import klepaas.backend.auth.token.exception.CliAccessTokenNotFoundException;
import klepaas.backend.auth.token.repository.CliAccessTokenRepository;
import klepaas.backend.global.exception.EntityNotFoundException;
import klepaas.backend.global.exception.ErrorCode;
import klepaas.backend.global.exception.InvalidRequestException;
import klepaas.backend.user.entity.User;
import klepaas.backend.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CliAccessTokenService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32;

    private final CliAccessTokenRepository cliAccessTokenRepository;
    private final UserRepository userRepository;

    @Transactional
    public CreateCliAccessTokenResponse createToken(Long userId, CreateCliAccessTokenRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException(ErrorCode.USER_NOT_FOUND));

        String rawToken = generateToken();
        CliAccessToken entity = CliAccessToken.builder()
                .user(user)
                .name(request.name().trim())
                .tokenHash(hash(rawToken))
                .tokenPrefix(rawToken.substring(0, Math.min(rawToken.length(), 12)))
                .expiresAt(LocalDateTime.now().plusDays(request.expiresInDays()))
                .scope(request.scope())
                .build();

        cliAccessTokenRepository.save(entity);
        log.info("CLI access token created: userId={}, tokenId={}, scope={}", userId, entity.getId(), entity.getScope());
        return new CreateCliAccessTokenResponse(rawToken, CliAccessTokenResponse.from(entity));
    }

    public List<CliAccessTokenResponse> listTokens(Long userId) {
        return cliAccessTokenRepository.findAllByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(CliAccessTokenResponse::from)
                .toList();
    }

    @Transactional
    public void revokeToken(Long userId, Long tokenId) {
        CliAccessToken token = cliAccessTokenRepository.findById(tokenId)
                .orElseThrow(CliAccessTokenNotFoundException::new);

        if (!token.getUser().getId().equals(userId)) {
            throw new CliAccessTokenNotFoundException();
        }

        token.revoke(LocalDateTime.now());
        log.info("CLI access token revoked: userId={}, tokenId={}", userId, tokenId);
    }

    @Transactional
    public CustomUserDetails authenticate(String rawToken) {
        CliAccessToken token = cliAccessTokenRepository.findByTokenHash(hash(rawToken))
                .orElseThrow(() -> new InvalidRequestException(ErrorCode.INVALID_CLI_TOKEN));

        if (!token.isUsableAt(LocalDateTime.now())) {
            throw new InvalidRequestException(ErrorCode.INVALID_CLI_TOKEN, "만료되었거나 폐기된 CLI 토큰입니다");
        }

        token.markUsed(LocalDateTime.now());
        User user = token.getUser();
        return new CustomUserDetails(user.getId(), user.getEmail(), user.getRole(), token.getScope());
    }

    private String generateToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return "kpa_cli_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm is required", e);
        }
    }
}
