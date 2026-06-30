package klepaas.backend.webhook.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import klepaas.backend.deployment.entity.BuildStrategy;
import klepaas.backend.deployment.entity.CloudVendor;
import klepaas.backend.deployment.entity.DeploymentConfig;
import klepaas.backend.deployment.entity.SourceRepository;
import klepaas.backend.deployment.repository.DeploymentConfigRepository;
import klepaas.backend.deployment.repository.SourceRepositoryRepository;
import klepaas.backend.deployment.service.DeploymentService;
import klepaas.backend.user.entity.Role;
import klepaas.backend.user.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class GitHubWebhookServiceTest {

    @Mock
    private SourceRepositoryRepository sourceRepositoryRepository;

    @Mock
    private DeploymentConfigRepository deploymentConfigRepository;

    @Mock
    private DeploymentService deploymentService;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private GitHubWebhookService gitHubWebhookService;

    @Test
    @DisplayName("GITHUB_ACTIONS_GHCR 저장소는 raw push webhook에서 배포를 시작하지 않는다")
    void handlePushEvent_skipsDeployment_whenRepositoryUsesGitHubActionsGhcr() throws Exception {
        SourceRepository repository = repository(CloudVendor.ON_PREMISE);
        DeploymentConfig config = DeploymentConfig.builder()
                .sourceRepository(repository)
                .minReplicas(1)
                .maxReplicas(1)
                .envVars(Map.of())
                .containerPort(8080)
                .domainUrl("iot.example.com")
                .buildStrategy(BuildStrategy.GITHUB_ACTIONS_GHCR)
                .imageUriTemplate("ghcr.io/{owner}/{repoName}/backend:sha-{commitHash}")
                .build();
        String payload = """
                {
                  "ref": "refs/heads/main",
                  "after": "abcdef1234567890",
                  "deleted": false,
                  "repository": {
                    "name": "smart-sousvide-iot-platform",
                    "owner": {"login": "kjhyeon0620"}
                  }
                }
                """;
        given(sourceRepositoryRepository.findByOwnerAndRepoName("kjhyeon0620", "smart-sousvide-iot-platform"))
                .willReturn(Optional.of(repository));
        given(deploymentConfigRepository.findBySourceRepositoryId(1L)).willReturn(Optional.of(config));

        gitHubWebhookService.handlePushEvent(payload);

        verify(deploymentService, never()).createDeployment(any(), any());
    }

    @Test
    @DisplayName("ON_PREMISE 저장소는 배포 설정 조회가 실패해도 raw push webhook에서 배포를 시작하지 않는다")
    void handlePushEvent_skipsDeployment_whenOnPremiseConfigIsMissing() throws Exception {
        SourceRepository repository = repository(CloudVendor.ON_PREMISE);
        String payload = """
                {
                  "ref": "refs/heads/main",
                  "after": "abcdef1234567890",
                  "deleted": false,
                  "repository": {
                    "name": "smart-sousvide-iot-platform",
                    "owner": {"login": "kjhyeon0620"}
                  }
                }
                """;
        given(sourceRepositoryRepository.findByOwnerAndRepoName("kjhyeon0620", "smart-sousvide-iot-platform"))
                .willReturn(Optional.of(repository));
        given(deploymentConfigRepository.findBySourceRepositoryId(1L)).willReturn(Optional.empty());

        gitHubWebhookService.handlePushEvent(payload);

        verify(deploymentService, never()).createDeployment(any(), any());
    }

    private SourceRepository repository(CloudVendor cloudVendor) {
        User user = User.builder()
                .email("test@example.com")
                .name("tester")
                .role(Role.USER)
                .providerId("12345")
                .build();
        SourceRepository repository = SourceRepository.builder()
                .user(user)
                .owner("kjhyeon0620")
                .repoName("smart-sousvide-iot-platform")
                .gitUrl("https://github.com/kjhyeon0620/smart-sousvide-iot-platform")
                .cloudVendor(cloudVendor)
                .build();
        ReflectionTestUtils.setField(repository, "id", 1L);
        return repository;
    }
}
