package klepaas.backend.infra.kubernetes;

import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.api.model.apps.DeploymentConditionBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import klepaas.backend.deployment.entity.DeploymentConfig;
import klepaas.backend.deployment.entity.KubernetesServiceType;
import klepaas.backend.deployment.entity.SourceRepository;
import klepaas.backend.deployment.service.RuntimeResourcePolicy;
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

    @SuppressWarnings("unchecked")
    private <T> T scopedMock(Class<?> type) {
        return (T) Mockito.mock(type, Mockito.RETURNS_DEEP_STUBS);
    }

    private KubernetesClient mockScopedClient() {
        KubernetesClient client = Mockito.mock(KubernetesClient.class, Mockito.RETURNS_DEEP_STUBS);
        Mockito.when(client.apps().deployments().inNamespace("klepaas"))
                .thenReturn(scopedMock(io.fabric8.kubernetes.client.dsl.NonNamespaceOperation.class));
        Mockito.when(client.services().inNamespace("klepaas"))
                .thenReturn(scopedMock(io.fabric8.kubernetes.client.dsl.NonNamespaceOperation.class));
        Mockito.when(client.network().v1().ingresses().inNamespace("klepaas"))
                .thenReturn(scopedMock(io.fabric8.kubernetes.client.dsl.NonNamespaceOperation.class));
        Mockito.when(client.apps().deployments().inNamespace("klepaas").withName("owner-repo"))
                .thenReturn(scopedMock(io.fabric8.kubernetes.client.dsl.RollableScalableResource.class));
        Mockito.when(client.services().inNamespace("klepaas").withName("owner-repo"))
                .thenReturn(scopedMock(io.fabric8.kubernetes.client.dsl.ServiceResource.class));
        Mockito.when(client.network().v1().ingresses().inNamespace("klepaas").withName("owner-repo"))
                .thenReturn(scopedMock(io.fabric8.kubernetes.client.dsl.Resource.class));
        Mockito.when(client.apps().deployments().inNamespace("klepaas")
                .resource(Mockito.any(io.fabric8.kubernetes.api.model.apps.Deployment.class)))
                .thenReturn(scopedMock(io.fabric8.kubernetes.client.dsl.RollableScalableResource.class));
        Mockito.when(client.services().inNamespace("klepaas")
                .resource(Mockito.any(io.fabric8.kubernetes.api.model.Service.class)))
                .thenReturn(scopedMock(io.fabric8.kubernetes.client.dsl.ServiceResource.class));
        return client;
    }

    private final KubernetesManifestGenerator generator =
            new KubernetesManifestGenerator(Mockito.mock(KubernetesClient.class), new RuntimeResourcePolicy());

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
        KubernetesManifestGenerator missingRefGenerator = new KubernetesManifestGenerator(Mockito.mock(KubernetesClient.class), new RuntimeResourcePolicy()) {
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

    @Test
    void forbiddenReferenceFailsBeforeKubernetesLookup() {
        KubernetesClient client = Mockito.mock(KubernetesClient.class);
        var policy = new RuntimeResourcePolicy();
        var repo = SourceRepository.builder().owner("owner").repoName("repo").build();
        ReflectionTestUtils.setField(repo, "id", 7L);
        var config = DeploymentConfig.builder().sourceRepository(repo)
                .envFromSecrets(List.of("other-app-secret")).imagePullSecretName("ghcr-pull")
                .domainUrl("app.example.com").build();
        var subject = new KubernetesManifestGenerator(client, policy);
        ReflectionTestUtils.setField(subject, "namespace", "klepaas");

        assertThatThrownBy(() -> subject.deploy("owner-repo", "image", config, 7L))
                .isInstanceOf(BusinessException.class);
        Mockito.verifyNoInteractions(client);
    }

    @Test
    void scalingForeignSameNameDeploymentIsRejected() {
        KubernetesClient client = mockScopedClient();
        var foreign = new DeploymentBuilder().withNewMetadata().withName("owner-repo")
                .addToLabels("klepaas.io/repository-id", "8").endMetadata().build();
        Mockito.when(client.apps().deployments().inNamespace("klepaas").withName("owner-repo").get())
                .thenReturn(foreign);
        var subject = new KubernetesManifestGenerator(client, new RuntimeResourcePolicy());
        ReflectionTestUtils.setField(subject, "namespace", "klepaas");

        assertThatThrownBy(() -> subject.scale("owner-repo", 2, 7L))
                .isInstanceOf(BusinessException.class);
        Mockito.verify(client.apps().deployments().inNamespace("klepaas").withName("owner-repo"),
                Mockito.never()).scale(Mockito.anyInt());
    }

    @Test
    void approvedPullSecretCanDeployInOwnNamespace() {
        KubernetesClient client = mockScopedClient();
        var policy = new RuntimeResourcePolicy();
        var allowed = new RuntimeResourcePolicy.AllowedReferences();
        allowed.setImagePullSecrets(List.of("ghcr-pull"));
        policy.setRepositories(Map.of(7L, allowed));
        var repo = SourceRepository.builder().owner("owner").repoName("repo").build();
        ReflectionTestUtils.setField(repo, "id", 7L);
        var config = DeploymentConfig.builder().sourceRepository(repo).minReplicas(1)
                .imagePullSecretName("ghcr-pull").domainUrl("").build();
        Mockito.when(client.apps().deployments().inNamespace("klepaas").withName("owner-repo").get())
                .thenReturn(null);
        Mockito.when(client.services().inNamespace("klepaas").withName("owner-repo").get())
                .thenReturn(null);
        Mockito.when(client.network().v1().ingresses().inNamespace("klepaas").withName("owner-repo").get())
                .thenReturn(null);
        var subject = new KubernetesManifestGenerator(client, policy);
        ReflectionTestUtils.setField(subject, "namespace", "klepaas");

        subject.deploy("owner-repo", "image", config, 7L);

        Mockito.verify(client.apps().deployments().inNamespace("klepaas"))
                .resource(Mockito.argThat(d -> "7".equals(d.getMetadata().getLabels().get("klepaas.io/repository-id"))));
    }

    @Test
    void concurrentSameNameCreateConflictNeverAppliesOverOtherRepository() {
        KubernetesClient client = mockScopedClient();
        var allowed = new RuntimeResourcePolicy.AllowedReferences();
        allowed.setImagePullSecrets(List.of("ghcr-pull"));
        var policy = new RuntimeResourcePolicy();
        policy.setRepositories(Map.of(7L, allowed));
        var repo = SourceRepository.builder().owner("owner").repoName("repo").build();
        ReflectionTestUtils.setField(repo, "id", 7L);
        var config = DeploymentConfig.builder().sourceRepository(repo)
                .imagePullSecretName("ghcr-pull").domainUrl("").build();
        Mockito.when(client.apps().deployments().inNamespace("klepaas").withName("owner-repo").get())
                .thenReturn(null);
        Mockito.when(client.services().inNamespace("klepaas").withName("owner-repo").get())
                .thenReturn(null);
        Mockito.when(client.network().v1().ingresses().inNamespace("klepaas").withName("owner-repo").get())
                .thenReturn(null);
        var deployments = client.apps().deployments().inNamespace("klepaas");
        io.fabric8.kubernetes.client.dsl.RollableScalableResource<io.fabric8.kubernetes.api.model.apps.Deployment> resource =
                scopedMock(io.fabric8.kubernetes.client.dsl.RollableScalableResource.class);
        Mockito.doReturn(resource).when(deployments)
                .resource(Mockito.any(io.fabric8.kubernetes.api.model.apps.Deployment.class));
        Mockito.doThrow(new IllegalStateException("409 AlreadyExists")).when(resource).create();
        var subject = new KubernetesManifestGenerator(client, policy);
        ReflectionTestUtils.setField(subject, "namespace", "klepaas");

        assertThatThrownBy(() -> subject.deploy("owner-repo", "image", config, 7L))
                .isInstanceOf(BusinessException.class);
        Mockito.verify(resource, Mockito.never()).serverSideApply();
        Mockito.verify(client.services().inNamespace("klepaas"), Mockito.never())
                .resource(Mockito.any(io.fabric8.kubernetes.api.model.Service.class));
    }

    @Test
    void scalePassesObservedVersionAndDoesNotRetryConflict() {
        KubernetesClient client = mockScopedClient();
        var own = new DeploymentBuilder().withNewMetadata().withName("owner-repo")
                .withResourceVersion("42").addToLabels("klepaas.io/repository-id", "7")
                .endMetadata().withNewSpec().withReplicas(1).endSpec().build();
        Mockito.when(client.apps().deployments().inNamespace("klepaas").withName("owner-repo").get())
                .thenReturn(own);
        var resource = client.apps().deployments().inNamespace("klepaas").resource(own);
        var versionedResource = resource.lockResourceVersion("42");
        Mockito.doThrow(new IllegalStateException("409 Conflict")).when(versionedResource).replace();
        Mockito.clearInvocations(resource, versionedResource);
        var subject = new KubernetesManifestGenerator(client, new RuntimeResourcePolicy());
        ReflectionTestUtils.setField(subject, "namespace", "klepaas");

        assertThatThrownBy(() -> subject.scale("owner-repo", 2, 7L))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("409 Conflict");
        Mockito.verify(resource).lockResourceVersion("42");
        Mockito.verify(versionedResource).replace();
        Mockito.verify(client.apps().deployments().inNamespace("klepaas").withName("owner-repo"), Mockito.never())
                .scale(Mockito.anyInt());
    }
}
