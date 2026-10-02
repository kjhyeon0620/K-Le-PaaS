package klepaas.backend.deployment.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import klepaas.backend.auth.config.CustomUserDetails;
import klepaas.backend.auth.config.SecurityConfig;
import klepaas.backend.auth.jwt.JwtAuthenticationFilter;
import klepaas.backend.auth.jwt.JwtTokenProvider;
import klepaas.backend.auth.token.service.CliAccessTokenService;
import klepaas.backend.deployment.dto.CreateRepositoryRequest;
import klepaas.backend.deployment.dto.DeploymentConfigResponse;
import klepaas.backend.deployment.dto.RepositoryResponse;
import klepaas.backend.deployment.dto.UpdateDeploymentConfigRequest;
import klepaas.backend.deployment.entity.ContainerResources;
import klepaas.backend.deployment.entity.HealthProbe;
import klepaas.backend.deployment.entity.CloudVendor;
import klepaas.backend.deployment.service.RepositoryService;
import klepaas.backend.user.entity.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
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
                                "cloud_vendor", "NCP",
                                "domain_url", "custom.example.com"
                        ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.owner").value("owner"));

        ArgumentCaptor<CreateRepositoryRequest> requestCaptor = ArgumentCaptor.forClass(CreateRepositoryRequest.class);
        verify(repositoryService).createRepository(anyLong(), requestCaptor.capture());
        assertThat(requestCaptor.getValue().domainUrl()).isEqualTo("custom.example.com");
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
        verify(repositoryService).deleteRepository(1L, 1L);
    }

    @Test
    @DisplayName("GET /api/v1/repositories/{id}/config - 배포 설정 조회")
    void getDeploymentConfig() throws Exception {
        var config = new DeploymentConfigResponse(1L, 1L, 1, 3, Map.of(), List.of("runtime-config"),
                List.of("app-env"), 8080, "repo.klepaas.io", null, null, null, null, null, null, null);
        given(repositoryService.getDeploymentConfig(1L, 1L)).willReturn(config);

        mockMvc.perform(get("/api/v1/repositories/1/config")
                        .with(user(testUser)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.container_port").value(8080))
                .andExpect(jsonPath("$.data.domain_url").value("repo.klepaas.io"))
                .andExpect(jsonPath("$.data.env_from_config_maps[0]").value("runtime-config"))
                .andExpect(jsonPath("$.data.env_from_secrets[0]").value("app-env"));
    }

    @Test
    @DisplayName("PUT /api/v1/repositories/{id}/config - 배포 설정 수정")
    void updateDeploymentConfig() throws Exception {
        var updatedConfig = new DeploymentConfigResponse(1L, 1L, 2, 5, Map.of("ENV", "prod"),
                List.of("runtime-config"), List.of("app-env"), 3000, "custom.klepaas.io",
                null, null, null, null, null, null, null);
        given(repositoryService.updateDeploymentConfig(anyLong(), any(), eq(1L))).willReturn(updatedConfig);

        mockMvc.perform(put("/api/v1/repositories/1/config")
                        .with(user(testUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "min_replicas", 2,
                                "max_replicas", 5,
                                "env_vars", Map.of("ENV", "prod"),
                                "env_from_config_maps", List.of("runtime-config"),
                                "env_from_secrets", List.of("app-env"),
                                "container_port", 3000,
                                "domain_url", "custom.klepaas.io"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.min_replicas").value(2))
                .andExpect(jsonPath("$.data.container_port").value(3000))
                .andExpect(jsonPath("$.data.env_from_config_maps[0]").value("runtime-config"))
                .andExpect(jsonPath("$.data.env_from_secrets[0]").value("app-env"));
    }

    @Test
    @DisplayName("PUT /api/v1/repositories/{id}/config - health_probe·resources를 snake_case로 받는다")
    void updateDeploymentConfigReadsProbeAndResources() throws Exception {
        mockMvc.perform(put("/api/v1/repositories/1/config")
                        .with(user(testUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"min_replicas":1,"max_replicas":1,"container_port":8080,
                                 "health_probe":{"path":"/health","initial_delay_seconds":5,"startup_failure_threshold":30},
                                 "resources":{"cpu_request":"100m","memory_limit":"256Mi"}}
                                """))
                .andExpect(status().isOk());

        ArgumentCaptor<UpdateDeploymentConfigRequest> captor = ArgumentCaptor.forClass(UpdateDeploymentConfigRequest.class);
        verify(repositoryService).updateDeploymentConfig(eq(1L), captor.capture(), eq(1L));
        assertThat(captor.getValue().healthProbe()).isEqualTo(new HealthProbe("/health", null, 5, null, null, 30));
        assertThat(captor.getValue().resources()).isEqualTo(new ContainerResources("100m", null, null, "256Mi"));
    }

    @Test
    @DisplayName("PUT /api/v1/repositories/{id}/config - 잘못된 probe 경로·포트·주기와 자원 형식은 400")
    void updateDeploymentConfigRejectsInvalidProbeAndResources() throws Exception {
        for (String invalid : List.of(
                "\"health_probe\":{\"path\":\"health\"}",
                "\"health_probe\":{\"path\":\"/health\",\"port\":0}",
                "\"health_probe\":{\"path\":\"/health\",\"period_seconds\":0}",
                "\"resources\":{\"cpu_limit\":\"1core\"}",
                "\"resources\":{\"memory_limit\":\"-1Mi\"}")) {
            mockMvc.perform(put("/api/v1/repositories/1/config")
                            .with(user(testUser))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"min_replicas\":1,\"max_replicas\":1,\"container_port\":8080," + invalid + "}"))
                    .andExpect(status().isBadRequest());
        }
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
