package klepaas.backend.infra.kubernetes;

import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.api.model.apps.DeploymentConditionBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import klepaas.backend.deployment.entity.DeploymentConfig;
import klepaas.backend.deployment.entity.KubernetesServiceType;
import klepaas.backend.global.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KubernetesManifestGeneratorTest {

    private final KubernetesManifestGenerator generator =
            new KubernetesManifestGenerator(Mockito.mock(KubernetesClient.class));

    @Test
    @DisplayName("새 generation이 관측되고 replica 카운터가 맞을 때 rollout 성공으로 본다")
    void isDeploymentRolloutComplete_returnsTrueWhenObservedGenerationAndReplicasAreReady() {
        var complete = new DeploymentBuilder()
                .withNewMetadata()
                .withGeneration(2L)
                .endMetadata()
                .withNewSpec()
                .withReplicas(2)
                .endSpec()
                .withNewStatus()
                .withObservedGeneration(2L)
                .withUpdatedReplicas(2)
                .withAvailableReplicas(2)
                .withUnavailableReplicas(0)
                .withConditions(new DeploymentConditionBuilder()
                        .withType("Available")
                        .withStatus("True")
                        .build())
                .endStatus()
                .build();

        assertThat(generator.isDeploymentRolloutComplete(complete)).isTrue();
    }

    @Test
    @DisplayName("Available condition이 True여도 새 generation이 관측되지 않았으면 rollout 성공이 아니다")
    void isDeploymentRolloutComplete_returnsFalseWhenObservedGenerationIsStale() {
        var staleGeneration = new DeploymentBuilder()
                .withNewMetadata()
                .withGeneration(2L)
                .endMetadata()
                .withNewSpec()
                .withReplicas(2)
                .endSpec()
                .withNewStatus()
                .withObservedGeneration(1L)
                .withUpdatedReplicas(2)
                .withAvailableReplicas(2)
                .withUnavailableReplicas(0)
                .withConditions(new DeploymentConditionBuilder()
                        .withType("Available")
                        .withStatus("True")
                        .build())
                .endStatus()
                .build();

        assertThat(generator.isDeploymentRolloutComplete(staleGeneration)).isFalse();
    }

    @Test
    @DisplayName("Available condition이 True여도 updated replica가 부족하면 rollout 성공이 아니다")
    void isDeploymentRolloutComplete_returnsFalseWhenUpdatedReplicasAreNotReady() {
        var staleReplicaSet = new DeploymentBuilder()
                .withNewMetadata()
                .withGeneration(2L)
                .endMetadata()
                .withNewSpec()
                .withReplicas(2)
                .endSpec()
                .withNewStatus()
                .withObservedGeneration(2L)
                .withUpdatedReplicas(1)
                .withAvailableReplicas(2)
                .withUnavailableReplicas(0)
                .withConditions(new DeploymentConditionBuilder()
                        .withType("Available")
                        .withStatus("True")
                        .build())
                .endStatus()
                .build();

        assertThat(generator.isDeploymentRolloutComplete(staleReplicaSet)).isFalse();
    }

    @Test
    @DisplayName("Available condition이 True여도 unavailable replica가 남아 있으면 rollout 성공이 아니다")
    void isDeploymentRolloutComplete_returnsFalseWhenUnavailableReplicasRemain() {
        var unavailable = new DeploymentBuilder()
                .withNewMetadata()
                .withGeneration(2L)
                .endMetadata()
                .withNewSpec()
                .withReplicas(2)
                .endSpec()
                .withNewStatus()
                .withObservedGeneration(2L)
                .withUpdatedReplicas(2)
                .withAvailableReplicas(1)
                .withUnavailableReplicas(1)
                .withConditions(new DeploymentConditionBuilder()
                        .withType("Available")
                        .withStatus("True")
                        .build())
                .endStatus()
                .build();

        assertThat(generator.isDeploymentRolloutComplete(unavailable)).isFalse();
        assertThat(generator.isDeploymentRolloutComplete(null)).isFalse();
    }

    @Test
    @DisplayName("NODE_PORT 서비스 포트에는 configured nodePort를 포함한다")
    void buildServicePort_includesConfiguredNodePort_whenServiceTypeIsNodePort() {
        DeploymentConfig config = DeploymentConfig.builder()
                .minReplicas(1)
                .maxReplicas(1)
                .envVars(Map.of())
                .containerPort(8080)
                .domainUrl("iot.example.com")
                .serviceType(KubernetesServiceType.NODE_PORT)
                .nodePort(30080)
                .build();

        var servicePort = generator.buildServicePort(config);

        assertThat(servicePort.getPort()).isEqualTo(80);
        assertThat(servicePort.getTargetPort().getIntVal()).isEqualTo(8080);
        assertThat(servicePort.getNodePort()).isEqualTo(30080);
    }

    @Test
    @DisplayName("Deployment manifest에는 envVars와 envFrom ConfigMap/Secret 참조를 함께 포함한다")
    void buildDeployment_includesEnvVarsAndEnvFromSources() {
        DeploymentConfig config = DeploymentConfig.builder()
                .minReplicas(1)
                .maxReplicas(1)
                .envVars(Map.of("ENV", "prod"))
                .envFromConfigMaps(List.of("smart-sousvide-runtime-config"))
                .envFromSecrets(List.of("smart-sousvide-app-env"))
                .containerPort(3000)
                .domainUrl("iot.example.com")
                .imagePullSecretName("ghcr-pull-secret")
                .serviceType(KubernetesServiceType.NODE_PORT)
                .nodePort(30080)
                .build();
        ReflectionTestUtils.setField(generator, "namespace", "klepaas");

        var deployment = generator.buildDeployment("smart-sousvide", "ghcr.io/app:sha", config,
                Map.of("app.kubernetes.io/name", "smart-sousvide"));

        var podSpec = deployment.getSpec().getTemplate().getSpec();
        var container = podSpec.getContainers().get(0);
        assertThat(podSpec.getImagePullSecrets().get(0).getName()).isEqualTo("ghcr-pull-secret");
        assertThat(container.getPorts().get(0).getContainerPort()).isEqualTo(3000);
        assertThat(container.getEnv())
                .anySatisfy(env -> {
                    assertThat(env.getName()).isEqualTo("ENV");
                    assertThat(env.getValue()).isEqualTo("prod");
                });
        assertThat(container.getEnvFrom()).hasSize(2);
        assertThat(container.getEnvFrom().get(0).getConfigMapRef().getName())
                .isEqualTo("smart-sousvide-runtime-config");
        assertThat(container.getEnvFrom().get(1).getSecretRef().getName())
                .isEqualTo("smart-sousvide-app-env");
    }

    @Test
    @DisplayName("envFrom ConfigMap이 namespace에 없으면 명확한 배포 오류를 반환한다")
    void validateEnvFromRefs_failsWhenConfigMapIsMissing() {
        KubernetesManifestGenerator missingRefGenerator = new KubernetesManifestGenerator(Mockito.mock(KubernetesClient.class)) {
            @Override
            boolean configMapExists(String name) {
                return false;
            }
        };
        ReflectionTestUtils.setField(missingRefGenerator, "namespace", "klepaas");
        DeploymentConfig config = DeploymentConfig.builder()
                .minReplicas(1)
                .maxReplicas(1)
                .envVars(Map.of())
                .envFromConfigMaps(List.of("missing-runtime-config"))
                .containerPort(8080)
                .domainUrl("iot.example.com")
                .build();

        assertThatThrownBy(() -> missingRefGenerator.validateEnvFromRefs(config))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("ConfigMap")
                .hasMessageContaining("missing-runtime-config");
    }
}
