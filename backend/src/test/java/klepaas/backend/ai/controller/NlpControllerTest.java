package klepaas.backend.ai.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import klepaas.backend.ai.dto.NlpCommandResponse;
import klepaas.backend.ai.entity.Intent;
import klepaas.backend.ai.entity.RiskLevel;
import klepaas.backend.ai.service.NlpCommandService;
import klepaas.backend.auth.config.CustomUserDetails;
import klepaas.backend.auth.config.SecurityConfig;
import klepaas.backend.auth.jwt.JwtAuthenticationFilter;
import klepaas.backend.auth.jwt.JwtTokenProvider;
import klepaas.backend.auth.token.service.CliAccessTokenService;
import klepaas.backend.user.entity.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(NlpController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class NlpControllerTest {

    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext context;

    @MockitoBean
    private NlpCommandService nlpCommandService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private CliAccessTokenService cliAccessTokenService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CustomUserDetails testUser = new CustomUserDetails(1L, "test@test.com", Role.USER);

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(context)
                .apply(springSecurity())
                .build();
    }

    @Test
    @DisplayName("POST /api/v1/nlp/command - 명령 처리 성공")
    void processCommand() throws Exception {
        var response = new NlpCommandResponse(
                1L, Intent.HELP, "도움말입니다", "도움말 결과",
                RiskLevel.LOW, false, "session-token");

        given(nlpCommandService.processCommand(eq(1L), any())).willReturn(response);

        mockMvc.perform(post("/api/v1/nlp/command")
                        .with(user(testUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("command", "도움말"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.intent").value("HELP"))
                .andExpect(jsonPath("$.data.risk_level").value("LOW"))
                .andExpect(jsonPath("$.data.requires_confirmation").value(false));
    }

    @Test
    @DisplayName("POST /api/v1/nlp/command - 위험 명령은 확인 필요 응답")
    void processHighRiskCommand() throws Exception {
        var response = new NlpCommandResponse(
                1L, Intent.DEPLOY, "배포를 실행합니다", null,
                RiskLevel.HIGH, true, "session-token");

        given(nlpCommandService.processCommand(eq(1L), any())).willReturn(response);

        mockMvc.perform(post("/api/v1/nlp/command")
                        .with(user(testUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("command", "배포해줘"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.requires_confirmation").value(true))
                .andExpect(jsonPath("$.data.risk_level").value("HIGH"));
    }

    @Test
    @DisplayName("POST /api/v1/nlp/confirm - 확인 성공")
    void confirmCommand() throws Exception {
        var response = new NlpCommandResponse(
                1L, Intent.DEPLOY, "배포가 실행되었습니다", "배포 시작됨",
                RiskLevel.HIGH, false, "session-token");

        given(nlpCommandService.confirmCommand(eq(1L), any())).willReturn(response);

        mockMvc.perform(post("/api/v1/nlp/confirm")
                        .with(user(testUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "command_log_id", 1,
                                "confirmed", true
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result").value("배포 시작됨"));
    }

    @Test
    @DisplayName("인증 없이 요청 시 401")
    void unauthorizedWithoutToken() throws Exception {
        mockMvc.perform(post("/api/v1/nlp/command")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"command\": \"도움말\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /api/v1/nlp/history - 인증 후 조회 성공")
    void getHistory() throws Exception {
        given(nlpCommandService.getHistory(eq(1L), any())).willReturn(Page.empty());

        mockMvc.perform(get("/api/v1/nlp/history")
                        .with(user(testUser)))
                .andExpect(status().isOk());
    }
}
