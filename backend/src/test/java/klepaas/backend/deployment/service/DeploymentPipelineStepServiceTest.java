package klepaas.backend.deployment.service;

import klepaas.backend.auth.service.GitHubInstallationTokenService;
import klepaas.backend.deployment.entity.CloudVendor;
import klepaas.backend.deployment.entity.Deployment;
import klepaas.backend.deployment.entity.DeploymentConfig;
import klepaas.backend.deployment.entity.SourceRepository;
import klepaas.backend.deployment.repository.DeploymentConfigRepository;
import klepaas.backend.deployment.repository.DeploymentRepository;
import klepaas.backend.deployment.repository.SourceRepositoryRepository;
import klepaas.backend.global.service.NotificationService;
import klepaas.backend.infra.CloudInfraProviderFactory;
import klepaas.backend.infra.kubernetes.KubernetesManifestGenerator;
import klepaas.backend.user.entity.Role;
import klepaas.backend.user.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class DeploymentPipelineStepServiceTest {

    @Mock
    private DeploymentRepository deploymentRepository;

    @Mock
    private SourceRepositoryRepository sourceRepositoryRepository;

    @Mock
    private DeploymentConfigRepository deploymentConfigRepository;

    @Mock
    private CloudInfraProviderFactory infraProviderFactory;

    @Mock
    private KubernetesManifestGenerator k8sGenerator;

    @Mock
    private GitHubInstallationTokenService installationTokenService;

    @Mock
    private NotificationService notificationService;

    @Mock
    private ExternalImageResolver externalImageResolver;

    @InjectMocks
    private DeploymentPipelineStepService stepService;

    @Test
    @DisplayName("K8s 배포는 manifest apply 후 rollout available 상태까지 검증한다")
    void executeK8sDeploy_waitsForRolloutAvailability() {
        SourceRepository repository = repository();
        Deployment deployment = Deployment.builder()
                .sourceRepository(repository)
                .branchName("main")
                .commitHash("abcdef1234567890")
                .build();
        DeploymentConfig config = DeploymentConfig.builder()
                .sourceRepository(repository)
                .minReplicas(1)
                .maxReplicas(1)
                .envVars(Map.of())
                .containerPort(8080)
                .domainUrl("iot.example.com")
                .build();
        String imageUri = "ghcr.io/kjhyeon0620/smart-sousvide-iot-platform/backend:sha-abcdef1234567890";
        given(deploymentRepository.findById(1L)).willReturn(Optional.of(deployment));
        given(deploymentConfigRepository.findBySourceRepositoryId(10L)).willReturn(Optional.of(config));
        given(deploymentRepository.save(any(Deployment.class))).willAnswer(invocation -> invocation.getArgument(0));

        stepService.executeK8sDeploy(1L, imageUri);

        verify(k8sGenerator).deploy("kjhyeon0620-smart-sousvide-iot-platform", imageUri, config, 10L);
        verify(k8sGenerator).waitForDeploymentAvailable("kjhyeon0620-smart-sousvide-iot-platform");
    }

    private SourceRepository repository() {
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
                .cloudVendor(CloudVendor.ON_PREMISE)
                .build();
        ReflectionTestUtils.setField(repository, "id", 10L);
        return repository;
    }
}
