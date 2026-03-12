package klepaas.backend.auth.service;

import klepaas.backend.auth.dto.GitHubTokenResponse;
import klepaas.backend.auth.dto.GitHubUserResponse;
import klepaas.backend.auth.dto.TokenResponse;
import klepaas.backend.auth.jwt.JwtTokenProvider;
import klepaas.backend.auth.oauth.GitHubOAuthClient;
import klepaas.backend.global.exception.EntityNotFoundException;
import klepaas.backend.global.exception.InvalidRequestException;
import klepaas.backend.user.entity.Role;
import klepaas.backend.user.entity.User;
import klepaas.backend.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private GitHubOAuthClient gitHubOAuthClient;
    @Mock
    private JwtTokenProvider jwtTokenProvider;
    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private AuthService authService;

    private User testUser;

    @BeforeEach
    void setUp() {
        testUser = User.builder()
                .email("test@github.com")
                .name("tester")
                .role(Role.USER)
                .providerId("12345")
                .build();
    }

    @Nested
    @DisplayName("getOAuthUrl")
    class GetOAuthUrl {

        @Test
        @DisplayName("성공: GitHub OAuth URL 반환")
        void successGitHub() {
            given(gitHubOAuthClient.getAuthorizationUrl(isNull(), isNull()))
                    .willReturn("https://github.com/login/oauth/authorize?client_id=test");

            String url = authService.getOAuthUrl("github", null, null);

            assertThat(url).contains("github.com/login/oauth/authorize");
        }

        @Test
        @DisplayName("실패: 지원하지 않는 프로바이더")
        void failUnsupported() {
            assertThatThrownBy(() -> authService.getOAuthUrl("google", null, null))
                    .isInstanceOf(InvalidRequestException.class);
        }
    }

    @Nested
    @DisplayName("login")
    class Login {

        @Test
        @DisplayName("성공: 기존 사용자 로그인")
        void successExistingUser() {
            var tokenResponse = new GitHubTokenResponse("ghp_token123", "bearer", "user:email");
            var githubUser = new GitHubUserResponse(12345L, "tester", "test@github.com", "tester");
            var jwtTokens = new TokenResponse("access_token", "refresh_token");

            given(gitHubOAuthClient.exchangeCode("valid_code", null)).willReturn(tokenResponse);
            given(gitHubOAuthClient.getUserInfo("ghp_token123")).willReturn(githubUser);
            given(userRepository.findByProviderId("12345")).willReturn(Optional.of(testUser));
            given(jwtTokenProvider.createTokens(any(), any(), any())).willReturn(jwtTokens);

            TokenResponse result = authService.login("valid_code", null);

            assertThat(result.accessToken()).isEqualTo("access_token");
            assertThat(result.refreshToken()).isEqualTo("refresh_token");
        }

        @Test
        @DisplayName("성공: 신규 사용자 자동 가입 후 로그인")
        void successNewUser() {
            var tokenResponse = new GitHubTokenResponse("ghp_token123", "bearer", "user:email");
            var githubUser = new GitHubUserResponse(99999L, "newuser", "new@github.com", "New User");
            var jwtTokens = new TokenResponse("access_token", "refresh_token");

            given(gitHubOAuthClient.exchangeCode("valid_code", null)).willReturn(tokenResponse);
            given(gitHubOAuthClient.getUserInfo("ghp_token123")).willReturn(githubUser);
            given(userRepository.findByProviderId("99999")).willReturn(Optional.empty());
            given(userRepository.save(any(User.class))).willReturn(testUser);
            given(jwtTokenProvider.createTokens(any(), any(), any())).willReturn(jwtTokens);

            TokenResponse result = authService.login("valid_code", null);

            assertThat(result.accessToken()).isEqualTo("access_token");
        }

        @Test
        @DisplayName("실패: 유효하지 않은 인증 코드")
        void failInvalidCode() {
            given(gitHubOAuthClient.exchangeCode("invalid_code", null)).willReturn(null);

            assertThatThrownBy(() -> authService.login("invalid_code", null))
                    .isInstanceOf(InvalidRequestException.class);
        }
    }

    @Nested
    @DisplayName("refresh")
    class Refresh {

        @Test
        @DisplayName("성공: 리프레시 토큰으로 액세스 토큰 갱신")
        void success() {
            var jwtTokens = new TokenResponse("new_access_token", "new_refresh_token");
            given(jwtTokenProvider.validateToken("valid_refresh")).willReturn(true);
            given(jwtTokenProvider.getUserId("valid_refresh")).willReturn(1L);
            given(userRepository.findById(1L)).willReturn(Optional.of(testUser));
            given(jwtTokenProvider.createTokens(any(), any(), any())).willReturn(jwtTokens);

            TokenResponse result = authService.refresh("valid_refresh");

            assertThat(result.accessToken()).isEqualTo("new_access_token");
            assertThat(result.refreshToken()).isEqualTo("new_refresh_token");
        }

        @Test
        @DisplayName("실패: 유효하지 않은 리프레시 토큰")
        void failInvalidToken() {
            given(jwtTokenProvider.validateToken("invalid_token")).willReturn(false);

            assertThatThrownBy(() -> authService.refresh("invalid_token"))
                    .isInstanceOf(InvalidRequestException.class);
        }

        @Test
        @DisplayName("실패: 사용자를 찾을 수 없음")
        void failUserNotFound() {
            given(jwtTokenProvider.validateToken("valid_refresh")).willReturn(true);
            given(jwtTokenProvider.getUserId("valid_refresh")).willReturn(999L);
            given(userRepository.findById(999L)).willReturn(Optional.empty());

            assertThatThrownBy(() -> authService.refresh("valid_refresh"))
                    .isInstanceOf(EntityNotFoundException.class);
        }
    }
}
