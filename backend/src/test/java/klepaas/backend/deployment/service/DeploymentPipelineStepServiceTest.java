package klepaas.backend.deployment.service;

import klepaas.backend.auth.service.GitHubInstallationTokenService;
import klepaas.backend.deployment.entity.CloudVendor;
import klepaas.backend.deployment.entity.Deployment;
import klepaas.backend.deployment.entity.DeploymentConfig;
import klepaas.backend.deployment.entity.DeploymentStatus;
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
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

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
    @DisplayName("apply 전에 DEPLOYING과 이미지를 저장하고, apply는 요청 ID와 함께 적용한 generation을 돌려준다")
    void startAndApplyK8sDeploy_recordsImageBeforeApplyAndReturnsGeneration() {
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

        given(k8sGenerator.deploy("kjhyeon0620-smart-sousvide-iot-platform", imageUri, config, 10L, 1L)).willReturn(5L);

        stepService.startK8sDeploy(1L, imageUri);
        assertThat(deployment.getStatus()).isEqualTo(DeploymentStatus.DEPLOYING);
        assertThat(deployment.getImageUri()).isEqualTo(imageUri);
        verify(deploymentRepository).save(deployment);
        verifyNoInteractions(k8sGenerator);

        var applied = stepService.applyK8sManifests(1L, imageUri);

        assertThat(applied).isEqualTo(new DeploymentPipelineStepService.AppliedRollout(
                "kjhyeon0620-smart-sousvide-iot-platform", 5L));
    }

    @Test
    @DisplayName("대체된 배포는 CANCELED와 사유로 기록한다")
    void markCanceled_recordsSupersededReason() {
        Deployment deployment = Deployment.builder().sourceRepository(repository()).branchName("main")
                .commitHash("abcdef1234567890").build();
        given(deploymentRepository.findById(1L)).willReturn(Optional.of(deployment));

        stepService.markCanceled(1L, "다른 변경으로 대체됨: generation 3 → 4");

        assertThat(deployment.getStatus()).isEqualTo(DeploymentStatus.CANCELED);
        assertThat(deployment.getFailReason()).isEqualTo("다른 변경으로 대체됨: generation 3 → 4");
        assertThat(deployment.getFinishedAt()).isNotNull();
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
