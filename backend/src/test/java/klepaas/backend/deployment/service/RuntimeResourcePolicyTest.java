package klepaas.backend.deployment.service;

import klepaas.backend.deployment.entity.SourceRepository;
import klepaas.backend.deployment.entity.DeploymentConfig;
import klepaas.backend.global.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuntimeResourcePolicyTest {
    @Test
    void onlyOperatorConfiguredRepositoryReferencesAreAllowed() {
        var policy = new RuntimeResourcePolicy();
        var approved = new RuntimeResourcePolicy.AllowedReferences();
        approved.setConfigMaps(List.of("runtime"));
        approved.setSecrets(List.of("app-env"));
        approved.setImagePullSecrets(List.of("ghcr-pull"));
        policy.setRepositories(Map.of(7L, approved));
        var repository = SourceRepository.builder().owner("a").repoName("b").build();
        ReflectionTestUtils.setField(repository, "id", 7L);

        assertThatCode(() -> policy.validateReferences(repository, List.of("runtime"), List.of("app-env"), "ghcr-pull"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> policy.validateReferences(repository, List.of("foreign"), List.of(), "ghcr-pull"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> policy.validateReferences(repository, List.of(), List.of("foreign"), "ghcr-pull"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> policy.validateReferences(repository, List.of(), List.of(), "foreign-pull"))
                .isInstanceOf(BusinessException.class);
        policy.setRepositories(Map.of());
        assertThatCode(() -> policy.validateReferences(repository, List.of(), List.of(), null))
                .doesNotThrowAnyException();
        var configUsingDefaultPullSecret = DeploymentConfig.builder().sourceRepository(repository).build();
        assertThatThrownBy(() -> policy.validateReferences(repository, configUsingDefaultPullSecret))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> policy.validateReferences(repository, List.of("runtime"), List.of(), "ghcr-pull"))
                .isInstanceOf(BusinessException.class);
        // pull secret을 끈 설정은 기본 pull secret도 참조하지 않으므로 허용이 없어도 통과한다 (#74)
        var configWithoutPullSecret = DeploymentConfig.builder().sourceRepository(repository)
                .imagePullSecretName("ghcr-pull").imagePullSecretEnabled(false).build();
        assertThat(policy.resolveImagePullSecretName(configWithoutPullSecret)).isNull();
        assertThatCode(() -> policy.validateReferences(repository, configWithoutPullSecret)).doesNotThrowAnyException();
    }
}
