package klepaas.backend.deployment.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import klepaas.backend.auth.config.CustomUserDetails;
import klepaas.backend.auth.config.SecurityConfig;
import klepaas.backend.auth.jwt.JwtAuthenticationFilter;
import klepaas.backend.auth.jwt.JwtTokenProvider;
import klepaas.backend.auth.token.service.CliAccessTokenService;
import klepaas.backend.deployment.dto.DeploymentConfigResponse;
import klepaas.backend.deployment.dto.RepositoryResponse;
import klepaas.backend.deployment.entity.CloudVendor;
import klepaas.backend.deployment.service.RepositoryService;
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
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(RepositoryController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class RepositoryControllerTest {

    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext context;

    @MockitoBean
    private RepositoryService repositoryService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private CliAccessTokenService cliAccessTokenService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final CustomUserDetails testUser = new CustomUserDetails(1L, "test@test.com", Role.USER);

    private final RepositoryResponse sampleRepo = new RepositoryResponse(
            1L, "owner", "repo", "https://github.com/owner/repo",
            CloudVendor.NCP, 1L, LocalDateTime.now(), LocalDateTime.now());

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(context)
                .apply(springSecurity())
                .build();
    }

    @Test
    @DisplayName("POST /api/v1/repositories - 레포지토리 생성 성공")
    void createRepository() throws Exception {
        given(repositoryService.createRepository(any(), any())).willReturn(sampleRepo);

        mockMvc.perform(post("/api/v1/repositories")
                        .with(user(testUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "owner", "owner",
                                "repo_name", "repo",
                                "git_url", "https://github.com/owner/repo",
                                "cloud_vendor", "NCP"
                        ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.owner").value("owner"));
    }

    @Test
    @DisplayName("GET /api/v1/repositories - 레포지토리 목록 조회")
    void getRepositories() throws Exception {
        given(repositoryService.getRepositories(any())).willReturn(List.of(sampleRepo));

        mockMvc.perform(get("/api/v1/repositories")
                        .with(user(testUser)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].owner").value("owner"));
    }

    @Test
    @DisplayName("DELETE /api/v1/repositories/{id} - 레포지토리 삭제")
    void deleteRepository() throws Exception {
        mockMvc.perform(delete("/api/v1/repositories/1")
                        .with(user(testUser)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("저장소가 삭제되었습니다"));
    }

    @Test
    @DisplayName("GET /api/v1/repositories/{id}/config - 배포 설정 조회")
    void getDeploymentConfig() throws Exception {
        var config = new DeploymentConfigResponse(1L, 1L, 1, 3, Map.of(), 8080, "repo.klepaas.io");
        given(repositoryService.getDeploymentConfig(1L)).willReturn(config);

        mockMvc.perform(get("/api/v1/repositories/1/config")
                        .with(user(testUser)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.container_port").value(8080))
                .andExpect(jsonPath("$.data.domain_url").value("repo.klepaas.io"));
    }

    @Test
    @DisplayName("PUT /api/v1/repositories/{id}/config - 배포 설정 수정")
    void updateDeploymentConfig() throws Exception {
        var updatedConfig = new DeploymentConfigResponse(1L, 1L, 2, 5, Map.of("ENV", "prod"), 3000, "custom.klepaas.io");
        given(repositoryService.updateDeploymentConfig(anyLong(), any())).willReturn(updatedConfig);

        mockMvc.perform(put("/api/v1/repositories/1/config")
                        .with(user(testUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "min_replicas", 2,
                                "max_replicas", 5,
                                "env_vars", Map.of("ENV", "prod"),
                                "container_port", 3000,
                                "domain_url", "custom.klepaas.io"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.min_replicas").value(2))
                .andExpect(jsonPath("$.data.container_port").value(3000));
    }

    @Test
    @DisplayName("인증 없이 요청 시 401 Unauthorized")
    void unauthorizedWithoutToken() throws Exception {
        mockMvc.perform(post("/api/v1/repositories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }
}
