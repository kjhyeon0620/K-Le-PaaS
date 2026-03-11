package klepaas.backend.auth.service;

import klepaas.backend.auth.dto.GitHubTokenResponse;
import klepaas.backend.auth.dto.GitHubUserResponse;
import klepaas.backend.auth.dto.TokenResponse;
import klepaas.backend.auth.jwt.JwtTokenProvider;
import klepaas.backend.auth.oauth.GitHubOAuthClient;
import klepaas.backend.global.exception.EntityNotFoundException;
import klepaas.backend.global.exception.ErrorCode;
import klepaas.backend.global.exception.InvalidRequestException;
import klepaas.backend.user.entity.Role;
import klepaas.backend.user.entity.User;
import klepaas.backend.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final GitHubOAuthClient gitHubOAuthClient;
    private final JwtTokenProvider jwtTokenProvider;
    private final UserRepository userRepository;

    public String getOAuthUrl(String provider, String redirectUri) {
        if (!"github".equalsIgnoreCase(provider)) {
            throw new InvalidRequestException(ErrorCode.INVALID_REQUEST, "지원하지 않는 OAuth 프로바이더: " + provider);
        }
        return gitHubOAuthClient.getAuthorizationUrl(redirectUri);
    }

    @Transactional
    public TokenResponse login(String code, String redirectUri) {
        GitHubTokenResponse tokenResponse = gitHubOAuthClient.exchangeCode(code, redirectUri);
        if (tokenResponse == null || tokenResponse.accessToken() == null) {
            throw new InvalidRequestException(ErrorCode.INVALID_REQUEST, "GitHub 인증 코드가 유효하지 않습니다");
        }

        GitHubUserResponse githubUser = gitHubOAuthClient.getUserInfo(tokenResponse.accessToken());

        String providerId = githubUser.id().toString();
        User user = userRepository.findByProviderId(providerId)
                .orElseGet(() -> {
                    User newUser = User.builder()
                            .email(githubUser.email() != null ? githubUser.email() : githubUser.login() + "@github.com")
                            .name(githubUser.name() != null ? githubUser.name() : githubUser.login())
                            .role(Role.USER)
                            .providerId(providerId)
                            .build();
                    log.info("New user created: {} ({})", newUser.getName(), newUser.getEmail());
                    return userRepository.save(newUser);
                });

        TokenResponse jwtTokens = jwtTokenProvider.createTokens(user.getId(), user.getEmail(), user.getRole());
        user.updateRefreshToken(jwtTokens.refreshToken());
        userRepository.save(user);
        return jwtTokens;
    }

    @Transactional
    public TokenResponse refresh(String refreshToken) {
        if (!jwtTokenProvider.validateToken(refreshToken)) {
            throw new InvalidRequestException(ErrorCode.INVALID_REQUEST, "유효하지 않은 리프레시 토큰입니다");
        }

        Long userId = jwtTokenProvider.getUserId(refreshToken);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException(ErrorCode.USER_NOT_FOUND));

        jwtTokenProvider.validateRefreshToken(refreshToken, user);

        TokenResponse jwtTokens = jwtTokenProvider.createTokens(user.getId(), user.getEmail(), user.getRole());
        user.updateRefreshToken(jwtTokens.refreshToken());
        userRepository.save(user);
        return jwtTokens;
    }

    @Transactional
    public void logout(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException(ErrorCode.USER_NOT_FOUND));
        user.revokeRefreshToken();
        userRepository.save(user);
    }
}
