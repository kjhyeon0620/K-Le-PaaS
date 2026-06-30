package klepaas.backend.deployment.service;

import klepaas.backend.deployment.entity.BuildStrategy;
import klepaas.backend.deployment.entity.CloudVendor;
import klepaas.backend.deployment.entity.Deployment;
import klepaas.backend.deployment.entity.DeploymentConfig;
import klepaas.backend.deployment.entity.SourceRepository;
import klepaas.backend.global.exception.BusinessException;
import klepaas.backend.user.entity.Role;
import klepaas.backend.user.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExternalImageResolverTest {

    private final ExternalImageResolver resolver = new ExternalImageResolver();

    @Test
    @DisplayName("요청 imageUri가 있으면 config 템플릿보다 우선한다")
    void resolve_returnsRequestedImageUri_whenDeploymentAlreadyHasImageUri() {
        // Given
        Deployment deployment = deployment("abcdef1234567890");
        deployment.setImageUri("ghcr.io/kjhyeon0620/smart-sousvide-iot-platform/backend:sha-abcdef1234567890");
        DeploymentConfig config = config("ghcr.io/other/repo:sha-{commitHash}");

        // When
        String imageUri = resolver.resolve(deployment, config);

        // Then
        assertThat(imageUri)
                .isEqualTo("ghcr.io/kjhyeon0620/smart-sousvide-iot-platform/backend:sha-abcdef1234567890");
    }

    @Test
    @DisplayName("config 템플릿으로 commitHash와 shortCommitHash placeholder를 치환한다")
    void resolve_replacesTemplatePlaceholders_whenTemplateIsConfigured() {
        // Given
        Deployment deployment = deployment("abcdef1234567890");
        DeploymentConfig config = config("ghcr.io/{owner}/{repoName}/backend:sha-{commitHash}-{shortCommitHash}");

        // When
        String imageUri = resolver.resolve(deployment, config);

        // Then
        assertThat(imageUri)
                .isEqualTo("ghcr.io/kjhyeon0620/smart-sousvide-iot-platform/backend:sha-abcdef1234567890-abcdef1");
    }

    @Test
    @DisplayName("외부 이미지 전략에서 HEAD는 명시 imageUri 없이 사용할 수 없다")
    void resolve_throwsBusinessException_whenCommitHashIsHeadWithoutImageUri() {
        // Given
        Deployment deployment = deployment("HEAD");
        DeploymentConfig config = config("ghcr.io/{owner}/{repoName}:sha-{commitHash}");

        assertThatThrownBy(() -> resolver.resolve(deployment, config))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("외부 이미지 전략에서 imageUri와 템플릿이 모두 없으면 사용할 수 없다")
    void resolve_throwsBusinessException_whenImageUriAndTemplateAreMissing() {
        Deployment deployment = deployment("abcdef1234567890");
        DeploymentConfig config = config(null);

        assertThatThrownBy(() -> resolver.resolve(deployment, config))
                .isInstanceOf(BusinessException.class);
    }

    private Deployment deployment(String commitHash) {
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
        return Deployment.builder()
                .sourceRepository(repository)
                .branchName("main")
                .commitHash(commitHash)
                .build();
    }

    private DeploymentConfig config(String imageUriTemplate) {
        return DeploymentConfig.builder()
                .minReplicas(1)
                .maxReplicas(1)
                .envVars(Map.of())
                .containerPort(8080)
                .domainUrl("iot.example.com")
                .buildStrategy(BuildStrategy.GITHUB_ACTIONS_GHCR)
                .imageUriTemplate(imageUriTemplate)
                .build();
    }
}
