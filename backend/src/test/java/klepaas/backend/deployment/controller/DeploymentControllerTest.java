package klepaas.backend.deployment.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import klepaas.backend.auth.config.CustomUserDetails;
import klepaas.backend.auth.config.SecurityConfig;
import klepaas.backend.auth.jwt.JwtAuthenticationFilter;
import klepaas.backend.auth.jwt.JwtTokenProvider;
import klepaas.backend.auth.token.service.CliAccessTokenService;
import klepaas.backend.deployment.dto.DeploymentResponse;
import klepaas.backend.deployment.dto.DeploymentStatusResponse;
import klepaas.backend.deployment.entity.DeploymentStatus;
import klepaas.backend.deployment.service.DeploymentService;
import klepaas.backend.user.entity.Role;
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

import java.time.LocalDateTime;
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

@WebMvcTest(DeploymentController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class DeploymentControllerTest {

    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext context;

    @MockitoBean
    private DeploymentService deploymentService;

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
    @DisplayName("POST /api/v1/deployments - 배포 생성 성공")
    void createDeployment() throws Exception {
        var response = new DeploymentResponse(
                1L, 1L, "owner/repo", "main", "abc1234", "registry.example.com/owner-repo:abc1234",
                DeploymentStatus.PENDING, null,
                LocalDateTime.now(), null, LocalDateTime.now());

        given(deploymentService.createDeployment(any(), eq(1L))).willReturn(response);

        mockMvc.perform(post("/api/v1/deployments")
                        .with(user(testUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "repository_id", 1,
                                "branch_name", "main",
                                "commit_hash", "abc1234"
                        ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.image_uri").value("registry.example.com/owner-repo:abc1234"));
    }

    @Test
    @DisplayName("GET /api/v1/deployments/{id}/status - 배포 상태 조회")
    void getDeploymentStatus() throws Exception {
        var response = new DeploymentStatusResponse(1L, DeploymentStatus.BUILDING, null);
        given(deploymentService.getDeploymentStatus(1L)).willReturn(response);

        mockMvc.perform(get("/api/v1/deployments/1/status")
                        .with(user(testUser)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("BUILDING"));
    }

    @Test
    @DisplayName("인증 없이 요청 시 401 Unauthorized")
    void unauthorizedWithoutToken() throws Exception {
        mockMvc.perform(post("/api/v1/deployments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }
}
