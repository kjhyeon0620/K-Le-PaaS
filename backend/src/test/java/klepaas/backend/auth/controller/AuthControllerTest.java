package klepaas.backend.auth.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import klepaas.backend.auth.config.SecurityConfig;
import klepaas.backend.auth.dto.TokenResponse;
import klepaas.backend.auth.jwt.JwtAuthenticationFilter;
import klepaas.backend.auth.jwt.JwtTokenProvider;
import klepaas.backend.auth.token.service.CliAccessTokenService;
import klepaas.backend.auth.service.AuthService;
import klepaas.backend.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class AuthControllerTest {

    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext context;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private CliAccessTokenService cliAccessTokenService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(context)
                .apply(springSecurity())
                .build();
    }

    @Test
    @DisplayName("GET /api/v1/auth/oauth2/url/github - OAuth URL 조회 (public)")
    void getOAuthUrl() throws Exception {
        given(authService.getOAuthUrl("github", null, null))
                .willReturn("https://github.com/login/oauth/authorize?client_id=test");

        mockMvc.perform(get("/api/v1/auth/oauth2/url/github"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.url").value("https://github.com/login/oauth/authorize?client_id=test"));
    }

    @Test
    @DisplayName("POST /api/v1/auth/oauth2/login - OAuth 로그인 성공 (public)")
    void login() throws Exception {
        var tokenResponse = new TokenResponse("access_token_123", "refresh_token_456");
        given(authService.login(eq("valid_code"), any())).willReturn(tokenResponse);

        mockMvc.perform(post("/api/v1/auth/oauth2/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("code", "valid_code"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.access_token").value("access_token_123"))
                .andExpect(jsonPath("$.data.refresh_token").value("refresh_token_456"));
    }

    @Test
    @DisplayName("POST /api/v1/auth/refresh - 토큰 갱신 성공 (public)")
    void refresh() throws Exception {
        var tokenResponse = new TokenResponse("new_access_token", "refresh_token_456");
        given(authService.refresh("refresh_token_456")).willReturn(tokenResponse);

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("refresh_token", "refresh_token_456"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.access_token").value("new_access_token"));
    }

    @Test
    @DisplayName("POST /api/v1/auth/oauth2/login - 코드 없이 요청 시 400")
    void loginWithoutCode() throws Exception {
        mockMvc.perform(post("/api/v1/auth/oauth2/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }
}
