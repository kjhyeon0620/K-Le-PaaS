package klepaas.backend.deployment.service;

import klepaas.backend.deployment.entity.BuildStrategy;
import klepaas.backend.deployment.entity.CloudVendor;
import klepaas.backend.deployment.entity.Deployment;
import klepaas.backend.deployment.entity.SourceRepository;
import klepaas.backend.deployment.repository.DeploymentRepository;
import klepaas.backend.global.websocket.WebSocketNotificationService;
import klepaas.backend.infra.CloudInfraProvider;
import klepaas.backend.infra.CloudInfraProviderFactory;
import klepaas.backend.infra.dto.BuildResult;
import klepaas.backend.infra.dto.BuildStatusResult;
import klepaas.backend.user.entity.Role;
import klepaas.backend.user.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.mockito.BDDMockito.given;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class DeploymentPipelineServiceTest {

    @Mock
    private DeploymentPipelineStepService stepService;

    @Mock
    private DeploymentRepository deploymentRepository;

    @Mock
    private CloudInfraProviderFactory infraProviderFactory;

    @Mock
    private WebSocketNotificationService wsNotificationService;

    @Mock
    private CloudInfraProvider cloudInfraProvider;

    @InjectMocks
    private DeploymentPipelineService deploymentPipelineService;

    private Deployment ncpDeployment;
    private Deployment onPremiseDeployment;

    @BeforeEach
    void setUp() {
        User user = User.builder()
                .email("test@example.com")
                .name("tester")
                .role(Role.USER)
                .providerId("12345")
                .build();

        SourceRepository ncpRepository = SourceRepository.builder()
                .user(user)
                .owner("testowner")
                .repoName("testrepo")
                .gitUrl("https://github.com/testowner/testrepo")
                .cloudVendor(CloudVendor.NCP)
                .build();

        SourceRepository onPremiseRepository = SourceRepository.builder()
                .user(user)
                .owner("kjhyeon0620")
                .repoName("smart-sousvide-iot-platform")
                .gitUrl("https://github.com/kjhyeon0620/smart-sousvide-iot-platform")
                .cloudVendor(CloudVendor.ON_PREMISE)
                .build();

        ncpDeployment = Deployment.builder()
                .sourceRepository(ncpRepository)
                .branchName("main")
                .commitHash("abcdef1234567890")
                .build();

        onPremiseDeployment = Deployment.builder()
                .sourceRepository(onPremiseRepository)
                .branchName("main")
                .commitHash("abcdef1234567890")
                .build();

        ReflectionTestUtils.setField(deploymentPipelineService, "pollInitialInterval", 1L);
        ReflectionTestUtils.setField(deploymentPipelineService, "pollMaxInterval", 1L);
        ReflectionTestUtils.setField(deploymentPipelineService, "buildTimeout", 100L);
    }

    @Test
    @DisplayName("KANIKO 전략은 기존 upload/build/poll/deploy 흐름을 유지한다")
    void executePipeline_usesKanikoFlow_whenBuildStrategyIsKaniko() {
        // Given
        BuildResult buildResult = new BuildResult(
                "klepaas-build-1",
                "default",
                "registry.example.com/testowner-testrepo:abcdef1"
        );
        given(deploymentRepository.findUserIdByDeploymentId(1L)).willReturn(Optional.of(10L));
        given(stepService.getBuildStrategy(1L)).willReturn(BuildStrategy.KANIKO);
        given(stepService.executeUpload(1L)).willReturn("builds/1/source.zip");
        given(stepService.executeBuildTrigger(1L, "builds/1/source.zip")).willReturn(buildResult);
        given(deploymentRepository.findById(1L)).willReturn(Optional.of(ncpDeployment));
        given(infraProviderFactory.getProvider(CloudVendor.NCP)).willReturn(cloudInfraProvider);
        given(cloudInfraProvider.getBuildStatus("default", "klepaas-build-1"))
                .willReturn(new BuildStatusResult(true, true, null, "success"));

        // When
        deploymentPipelineService.executePipeline(1L);

        // Then
        verify(stepService).executeUpload(1L);
        verify(stepService).executeBuildTrigger(1L, "builds/1/source.zip");
        verify(stepService).executeK8sDeploy(1L, "registry.example.com/testowner-testrepo:abcdef1");
        verify(stepService).markSuccess(1L);
    }

    @Test
    @DisplayName("GITHUB_ACTIONS_GHCR 전략은 source upload와 Kaniko build를 건너뛰고 외부 이미지를 배포한다")
    void executePipeline_usesExternalImageFlow_whenBuildStrategyIsGitHubActionsGhcr() {
        // Given
        String imageUri = "ghcr.io/kjhyeon0620/smart-sousvide-iot-platform/backend:sha-abcdef1234567890";
        given(deploymentRepository.findUserIdByDeploymentId(2L)).willReturn(Optional.of(10L));
        given(stepService.getBuildStrategy(2L)).willReturn(BuildStrategy.GITHUB_ACTIONS_GHCR);
        given(stepService.resolveExternalImage(2L)).willReturn(imageUri);

        // When
        deploymentPipelineService.executePipeline(2L);

        // Then
        verify(stepService, never()).executeUpload(2L);
        verify(stepService, never()).executeBuildTrigger(eq(2L), anyString());
        verify(stepService).resolveExternalImage(2L);
        verify(stepService).executeK8sDeploy(2L, imageUri);
        verify(stepService).markSuccess(2L);
    }
}
