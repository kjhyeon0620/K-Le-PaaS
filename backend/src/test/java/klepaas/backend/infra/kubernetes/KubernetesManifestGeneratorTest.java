package klepaas.backend.infra.kubernetes;

import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.api.model.apps.DeploymentConditionBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import klepaas.backend.deployment.entity.ContainerResources;
import klepaas.backend.deployment.entity.DeploymentConfig;
import klepaas.backend.deployment.entity.HealthProbe;
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

    private io.fabric8.kubernetes.api.model.apps.Deployment rollout(long generation, long observed, int updated,
                                                                   int available, int unavailable,
                                                                   io.fabric8.kubernetes.api.model.apps.DeploymentCondition... conditions) {
        return new DeploymentBuilder()
                .withNewMetadata().withName("owner-repo").withGeneration(generation).endMetadata()
                .withNewSpec().withReplicas(1).endSpec()
                .withNewStatus()
                .withObservedGeneration(observed)
                .withUpdatedReplicas(updated)
                .withAvailableReplicas(available)
                .withUnavailableReplicas(unavailable)
                .withConditions(conditions)
                .endStatus()
                .build();
    }

    private io.fabric8.kubernetes.api.model.apps.DeploymentCondition condition(String type, String status, String reason) {
        return new DeploymentConditionBuilder().withType(type).withStatus(status).withReason(reason)
                .withMessage(reason + " message").build();
    }

    @Test
    @DisplayName("이 요청의 generation이 완료되면 성공이다")
    void evaluateDeployment_succeedsForAppliedGeneration() {
        var done = rollout(3, 3, 1, 1, 0, condition("Available", "True", "MinimumReplicasAvailable"));

        assertThat(generator.evaluateDeployment(done, 3).outcome()).isEqualTo(RolloutResult.Outcome.SUCCEEDED);
    }

    @Test
    @DisplayName("다른 변경으로 generation이 바뀌면 그 rollout이 완료돼도 이 요청의 성공이 아니라 대체다")
    void evaluateDeployment_isSupersededWhenGenerationChanged() {
        var otherCompleted = rollout(4, 4, 1, 1, 0, condition("Available", "True", "MinimumReplicasAvailable"));

        RolloutResult result = generator.evaluateDeployment(otherCompleted, 3);

        assertThat(result.outcome()).isEqualTo(RolloutResult.Outcome.SUPERSEDED);
        assertThat(result.reason()).contains("generation 3 → 4");
    }

    @Test
    @DisplayName("이 generation을 아직 관측하지 못했으면 이전 상태로 판정하지 않는다")
    void evaluateDeployment_waitsUntilGenerationObserved() {
        var stale = rollout(3, 2, 1, 1, 0, condition("Available", "True", "MinimumReplicasAvailable"),
                condition("Progressing", "False", "ProgressDeadlineExceeded"));

        assertThat(generator.evaluateDeployment(stale, 3)).isNull();
    }

    @Test
    @DisplayName("ProgressDeadlineExceeded는 즉시 실패다")
    void evaluateDeployment_failsOnProgressDeadlineExceeded() {
        var stuck = rollout(3, 3, 1, 0, 1, condition("Available", "True", "MinimumReplicasAvailable"),
                condition("Progressing", "False", "ProgressDeadlineExceeded"));

        RolloutResult result = generator.evaluateDeployment(stuck, 3);

        assertThat(result.outcome()).isEqualTo(RolloutResult.Outcome.FAILED);
        assertThat(result.reason()).contains("ProgressDeadlineExceeded");
    }

    @Test
    @DisplayName("진행 중이면 결론을 내지 않고, Deployment가 없으면 실패다")
    void evaluateDeployment_inProgressOrMissing() {
        var progressing = rollout(3, 3, 1, 0, 1, condition("Progressing", "True", "ReplicaSetUpdated"));

        assertThat(generator.evaluateDeployment(progressing, 3)).isNull();
        assertThat(generator.evaluateDeployment(null, 3).outcome()).isEqualTo(RolloutResult.Outcome.FAILED);
    }

    private io.fabric8.kubernetes.api.model.Pod waitingPod(String reason) {
        return new io.fabric8.kubernetes.api.model.PodBuilder()
                .withNewMetadata().withName("owner-repo-abc").endMetadata()
                .withNewStatus()
                .addNewContainerStatus()
                .withNewState().withNewWaiting().withReason(reason).withMessage("x".repeat(400)).endWaiting().endState()
                .endContainerStatus()
                .endStatus()
                .build();
    }

    @Test
    @DisplayName("새 Pod가 이미지 pull·설정 오류·반복 종료로 대기하면 즉시 실패, 일시적인 대기는 기다린다")
    void evaluatePods_failsOnUnrecoverableWaitingReasons() {
        for (String reason : List.of("ImagePullBackOff", "InvalidImageName", "ErrImageNeverPull",
                "CreateContainerConfigError", "CrashLoopBackOff")) {
            RolloutResult result = generator.evaluatePods(List.of(waitingPod(reason)), 3);
            assertThat(result.outcome()).isEqualTo(RolloutResult.Outcome.FAILED);
            assertThat(result.reason()).contains(reason, "pod=owner-repo-abc", "generation=3");
            assertThat(result.reason().length()).isLessThan(400);
        }
        assertThat(generator.evaluatePods(List.of(waitingPod("ErrImagePull")), 3)).isNull();
        assertThat(generator.evaluatePods(List.of(waitingPod("ContainerCreating")), 3)).isNull();
        assertThat(generator.evaluatePods(List.of(new io.fabric8.kubernetes.api.model.Pod()), 3)).isNull();
    }

    @Test
    @DisplayName("반복 종료 실패에는 마지막 종료 사유(OOMKilled)와 exit code를 남긴다")
    void evaluatePods_includesLastTerminationReason() {
        var pod = new io.fabric8.kubernetes.api.model.PodBuilder()
                .withNewMetadata().withName("owner-repo-abc").endMetadata()
                .withNewStatus().addNewContainerStatus()
                .withNewState().withNewWaiting().withReason("CrashLoopBackOff").withMessage("back-off").endWaiting().endState()
                .withNewLastState().withNewTerminated().withReason("OOMKilled").withExitCode(137).endTerminated().endLastState()
                .endContainerStatus().endStatus().build();

        assertThat(generator.evaluatePods(List.of(pod), 3).reason())
                .contains("CrashLoopBackOff", "마지막 종료=OOMKilled", "exit=137");
    }

    @Test
    @DisplayName("타임아웃 근거로 스케줄 불가와 Ready가 아닌 실행 중 Pod 상태를 적는다")
    void describePods_reportsUnschedulableAndNotReady() {
        var unschedulable = new io.fabric8.kubernetes.api.model.PodBuilder()
                .withNewMetadata().withName("p1").endMetadata()
                .withNewStatus().addNewCondition().withType("PodScheduled").withStatus("False")
                .withMessage("0/1 nodes are available: 1 Insufficient memory.").endCondition().endStatus().build();
        var notReady = new io.fabric8.kubernetes.api.model.PodBuilder()
                .withNewMetadata().withName("p2").endMetadata()
                .withNewStatus().addNewContainerStatus().withReady(false).withRestartCount(0)
                .withNewState().withNewRunning().endRunning().endState().endContainerStatus().endStatus().build();

        assertThat(generator.describePods(List.of(unschedulable, notReady)))
                .isEqualTo("pod=p1 스케줄 불가: 0/1 nodes are available: 1 Insufficient memory.; "
                        + "pod=p2 실행 중이나 Ready 아님 (restarts=0)");
    }

    @Test
    @DisplayName("시간 안에 결론이 없으면 타임아웃 실패로 마지막 관측 상태를 남긴다")
    void waitForRollout_timesOutWithLastObservedStatus() {
        KubernetesClient client = mockScopedClient();
        Mockito.when(client.apps().deployments().inNamespace("klepaas").withName("owner-repo").get())
                .thenReturn(rollout(3, 3, 1, 0, 1, condition("Progressing", "True", "ReplicaSetUpdated")));
        var subject = new KubernetesManifestGenerator(client, new RuntimeResourcePolicy());
        ReflectionTestUtils.setField(subject, "namespace", "klepaas");
        ReflectionTestUtils.setField(subject, "rolloutTimeoutMs", 0L);
        ReflectionTestUtils.setField(subject, "rolloutPollIntervalMs", 0L);

        RolloutResult result = subject.waitForRollout("owner-repo", 3);

        assertThat(result.outcome()).isEqualTo(RolloutResult.Outcome.FAILED);
        assertThat(result.reason()).startsWith("rollout 타임아웃(0초, generation=3)").contains("unavailable=1");
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
    @DisplayName("pull secret을 끈 설정은 imagePullSecrets 없이, 기본 설정은 기본 pull secret으로 manifest를 만든다")
    void buildDeployment_omitsImagePullSecretsWhenDisabled() {
        ReflectionTestUtils.setField(generator, "namespace", "klepaas");
        DeploymentConfig disabled = DeploymentConfig.builder().minReplicas(1).maxReplicas(1)
                .imagePullSecretName("ghcr-pull-secret").imagePullSecretEnabled(false).build();
        DeploymentConfig existing = DeploymentConfig.builder().minReplicas(1).maxReplicas(1).build();

        var withoutSecret = generator.buildDeployment("app", "img", disabled, Map.of()).getSpec().getTemplate().getSpec();
        var withDefault = generator.buildDeployment("app", "img", existing, Map.of()).getSpec().getTemplate().getSpec();

        assertThat(withoutSecret.getImagePullSecrets()).isEmpty();
        assertThat(withDefault.getImagePullSecrets()).extracting(ref -> ref.getName()).containsExactly("ncp-cr");
    }

    @Test
    @DisplayName("probe·자원 설정이 없는 기존 앱 manifest에는 probe와 resources가 없다")
    void buildDeployment_omitsProbesAndResourcesWhenUnset() {
        DeploymentConfig config = DeploymentConfig.builder().minReplicas(1).maxReplicas(1).containerPort(8080).build();
        ReflectionTestUtils.setField(generator, "namespace", "klepaas");

        var container = generator.buildDeployment("app", "img", config, Map.of())
                .getSpec().getTemplate().getSpec().getContainers().get(0);

        assertThat(container.getReadinessProbe()).isNull();
        assertThat(container.getStartupProbe()).isNull();
        assertThat(container.getLivenessProbe()).isNull();
        assertThat(container.getResources()).isNull();
    }

    @Test
    @DisplayName("probe·자원 설정은 readiness/startup probe와 requests/limits로 반영된다")
    void buildDeployment_includesProbesAndResources() {
        DeploymentConfig config = DeploymentConfig.builder()
                .minReplicas(1)
                .maxReplicas(1)
                .containerPort(3000)
                .healthProbe(new HealthProbe("/health", null, 5, 10, 3, 30))
                .resources(new ContainerResources("100m", "500m", null, "256Mi"))
                .build();
        ReflectionTestUtils.setField(generator, "namespace", "klepaas");

        var container = generator.buildDeployment("app", "img", config, Map.of())
                .getSpec().getTemplate().getSpec().getContainers().get(0);

        var readiness = container.getReadinessProbe();
        assertThat(readiness.getHttpGet().getPath()).isEqualTo("/health");
        assertThat(readiness.getHttpGet().getPort().getIntVal()).isEqualTo(3000);
        assertThat(readiness.getInitialDelaySeconds()).isEqualTo(5);
        assertThat(readiness.getPeriodSeconds()).isEqualTo(10);
        assertThat(readiness.getFailureThreshold()).isEqualTo(3);
        var startup = container.getStartupProbe();
        assertThat(startup.getHttpGet().getPath()).isEqualTo("/health");
        assertThat(startup.getFailureThreshold()).isEqualTo(30);
        assertThat(container.getResources().getRequests()).containsOnlyKeys("cpu");
        assertThat(container.getResources().getRequests().get("cpu").toString()).isEqualTo("100m");
        assertThat(container.getResources().getLimits()).containsOnlyKeys("cpu", "memory");
        assertThat(container.getResources().getLimits().get("memory").toString()).isEqualTo("256Mi");
    }

    @Test
    @DisplayName("startup 실패 임계값이 없으면 startup probe를 만들지 않고, probe 포트를 따로 지정할 수 있다")
    void buildDeployment_readinessOnlyWithExplicitPort() {
        DeploymentConfig config = DeploymentConfig.builder()
                .minReplicas(1)
                .maxReplicas(1)
                .containerPort(3000)
                .healthProbe(new HealthProbe("/ready", 9090, null, null, null, null))
                .build();
        ReflectionTestUtils.setField(generator, "namespace", "klepaas");

        var container = generator.buildDeployment("app", "img", config, Map.of())
                .getSpec().getTemplate().getSpec().getContainers().get(0);

        assertThat(container.getReadinessProbe().getHttpGet().getPort().getIntVal()).isEqualTo(9090);
        assertThat(container.getReadinessProbe().getPeriodSeconds()).isNull();
        assertThat(container.getStartupProbe()).isNull();
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

        assertThatThrownBy(() -> subject.deploy("owner-repo", "image", config, 7L, 1L))
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
        Mockito.when(client.apps().deployments().inNamespace("klepaas")
                        .resource(new io.fabric8.kubernetes.api.model.apps.Deployment()).create())
                .thenReturn(new DeploymentBuilder().withNewMetadata().withGeneration(1L).endMetadata().build());
        Mockito.clearInvocations(client.apps().deployments().inNamespace("klepaas"));
        var subject = new KubernetesManifestGenerator(client, policy);
        ReflectionTestUtils.setField(subject, "namespace", "klepaas");

        assertThat(subject.deploy("owner-repo", "image", config, 7L, 1L)).isEqualTo(1L);

        Mockito.verify(client.apps().deployments().inNamespace("klepaas"))
                .resource(Mockito.argThat(d -> "7".equals(d.getMetadata().getLabels().get("klepaas.io/repository-id"))
                        && "1".equals(d.getMetadata().getAnnotations().get("klepaas.io/deployment-id"))));
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

        assertThatThrownBy(() -> subject.deploy("owner-repo", "image", config, 7L, 1L))
                .isInstanceOf(BusinessException.class);
        Mockito.verify(resource, Mockito.never()).serverSideApply();
        Mockito.verify(resource, Mockito.never()).forceConflicts();
        Mockito.verify(client.services().inNamespace("klepaas"), Mockito.never())
                .resource(Mockito.any(io.fabric8.kubernetes.api.model.Service.class));
    }

    @Test
    @DisplayName("기존 Deployment 재배포는 관측한 resourceVersion으로 전체 교체해 설정에서 뺀 probe도 제거한다")
    void redeployReplacesWholeDeploymentWithObservedVersion() {
        KubernetesClient client = mockScopedClient();
        var own = new DeploymentBuilder().withNewMetadata().withName("owner-repo")
                .withResourceVersion("42").addToLabels("klepaas.io/repository-id", "7")
                .endMetadata().build();
        Mockito.when(client.apps().deployments().inNamespace("klepaas").withName("owner-repo").get())
                .thenReturn(own);
        Mockito.when(client.services().inNamespace("klepaas").withName("owner-repo").get()).thenReturn(null);
        Mockito.when(client.network().v1().ingresses().inNamespace("klepaas").withName("owner-repo").get())
                .thenReturn(null);
        var policy = new RuntimeResourcePolicy();
        var allowed = new RuntimeResourcePolicy.AllowedReferences();
        allowed.setImagePullSecrets(List.of("ncp-cr"));
        policy.setRepositories(Map.of(7L, allowed));
        var repo = SourceRepository.builder().owner("owner").repoName("repo").build();
        ReflectionTestUtils.setField(repo, "id", 7L);
        var config = DeploymentConfig.builder().sourceRepository(repo).minReplicas(1).domainUrl("").build();
        var deployments = client.apps().deployments().inNamespace("klepaas");
        var resource = deployments.resource(own);
        Mockito.when(resource.lockResourceVersion("42").replace())
                .thenReturn(new DeploymentBuilder().withNewMetadata().withGeneration(5L).endMetadata().build());
        Mockito.clearInvocations(deployments, resource);
        var subject = new KubernetesManifestGenerator(client, policy);
        ReflectionTestUtils.setField(subject, "namespace", "klepaas");

        assertThat(subject.deploy("owner-repo", "image:v2", config, 7L, 1L)).isEqualTo(5L);

        Mockito.verify(deployments).resource(Mockito.argThat(d -> "42".equals(d.getMetadata().getResourceVersion())
                && d.getSpec().getTemplate().getSpec().getContainers().get(0).getReadinessProbe() == null));
        Mockito.verify(resource).lockResourceVersion("42");
        Mockito.verify(resource.lockResourceVersion("42")).replace();
        Mockito.verify(resource, Mockito.never()).serverSideApply();
        Mockito.verify(resource, Mockito.never()).create();
    }

    // 서버 GET 응답처럼 managedFields(fieldsV1)를 가진 Deployment. Fabric8 7.2.0 + Jackson 2.20에서 그대로 replace()하면
    // 보내기 전 clone 단계에서 직렬화가 실패한다 (#86)
    private io.fabric8.kubernetes.api.model.apps.Deployment observedWithManagedFields() {
        return new io.fabric8.kubernetes.client.utils.KubernetesSerialization().unmarshal("""
                {"apiVersion":"apps/v1","kind":"Deployment","metadata":{"name":"owner-repo","resourceVersion":"42",
                 "labels":{"klepaas.io/repository-id":"7"},
                 "managedFields":[{"manager":"fabric8-kubernetes-client","operation":"Update","apiVersion":"apps/v1",
                   "fieldsType":"FieldsV1","fieldsV1":{"f:metadata":{"f:labels":{".":{},"f:app.kubernetes.io/name":{}}}}}]},
                 "spec":{"replicas":2,"template":{"metadata":{"labels":{"app.kubernetes.io/name":"owner-repo"}}}}}
                """, io.fabric8.kubernetes.api.model.apps.Deployment.class);
    }

    @Test
    @DisplayName("restart·scale은 managedFields를 빼고 보내 Fabric8이 복제할 수 있는 객체로 교체한다")
    void restartAndScaleSendDeploymentWithoutManagedFields() {
        for (boolean restart : new boolean[]{true, false}) {
            KubernetesClient client = mockScopedClient();
            var observed = observedWithManagedFields();
            Mockito.when(client.apps().deployments().inNamespace("klepaas").withName("owner-repo").get())
                    .thenReturn(observed);
            var deployments = client.apps().deployments().inNamespace("klepaas");
            var subject = new KubernetesManifestGenerator(client, new RuntimeResourcePolicy());
            ReflectionTestUtils.setField(subject, "namespace", "klepaas");

            if (restart) subject.restart("owner-repo", 7L); else subject.scale("owner-repo", 3, 7L);

            var sent = org.mockito.ArgumentCaptor.forClass(io.fabric8.kubernetes.api.model.apps.Deployment.class);
            Mockito.verify(deployments).resource(sent.capture());
            assertThat(sent.getValue().getMetadata().getManagedFields()).isNullOrEmpty();
            assertThat(sent.getValue().getMetadata().getResourceVersion()).isEqualTo("42");
            // Fabric8 replace()가 보내기 전에 하는 것과 같은 복제가 성공한다
            assertThat(new io.fabric8.kubernetes.client.utils.KubernetesSerialization().clone(sent.getValue())
                    .getMetadata().getName()).isEqualTo("owner-repo");
        }
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

    @Test
    void scaleReturnsPreviousReplicas() {
        KubernetesClient client = mockScopedClient();
        var own = new DeploymentBuilder().withNewMetadata().withName("owner-repo")
                .withResourceVersion("42").addToLabels("klepaas.io/repository-id", "7")
                .endMetadata().withNewSpec().withReplicas(3).endSpec().build();
        Mockito.when(client.apps().deployments().inNamespace("klepaas").withName("owner-repo").get())
                .thenReturn(own);
        var subject = new KubernetesManifestGenerator(client, new RuntimeResourcePolicy());
        ReflectionTestUtils.setField(subject, "namespace", "klepaas");

        assertThat(subject.scale("owner-repo", 5, 7L)).isEqualTo(3);
        assertThat(own.getSpec().getReplicas()).isEqualTo(5);
    }

    @Test
    void restartKeepsReplicasAndUpdatesTemplateAnnotation() {
        KubernetesClient client = mockScopedClient();
        var own = new DeploymentBuilder().withNewMetadata().withName("owner-repo")
                .withResourceVersion("42").addToLabels("klepaas.io/repository-id", "7")
                .endMetadata().withNewSpec().withReplicas(3)
                .withNewTemplate().withNewMetadata().addToAnnotations("keep", "me").endMetadata().endTemplate()
                .endSpec().build();
        Mockito.when(client.apps().deployments().inNamespace("klepaas").withName("owner-repo").get())
                .thenReturn(own);
        var resource = client.apps().deployments().inNamespace("klepaas").resource(own);
        var versionedResource = resource.lockResourceVersion("42");
        Mockito.clearInvocations(resource, versionedResource);
        var subject = new KubernetesManifestGenerator(client, new RuntimeResourcePolicy());
        ReflectionTestUtils.setField(subject, "namespace", "klepaas");

        subject.restart("owner-repo", 7L);

        assertThat(own.getSpec().getReplicas()).isEqualTo(3);
        assertThat(own.getSpec().getTemplate().getMetadata().getAnnotations())
                .containsEntry("keep", "me")
                .containsKey("kubectl.kubernetes.io/restartedAt");
        Mockito.verify(resource).lockResourceVersion("42");
        Mockito.verify(versionedResource).replace();
        Mockito.verify(client.apps().deployments().inNamespace("klepaas").withName("owner-repo"), Mockito.never())
                .scale(Mockito.anyInt());
    }

    @Test
    void restartingForeignSameNameDeploymentIsRejected() {
        KubernetesClient client = mockScopedClient();
        var foreign = new DeploymentBuilder().withNewMetadata().withName("owner-repo")
                .withResourceVersion("42").addToLabels("klepaas.io/repository-id", "8").endMetadata()
                .withNewSpec().withNewTemplate().endTemplate().endSpec().build();
        Mockito.when(client.apps().deployments().inNamespace("klepaas").withName("owner-repo").get())
                .thenReturn(foreign);
        var subject = new KubernetesManifestGenerator(client, new RuntimeResourcePolicy());
        ReflectionTestUtils.setField(subject, "namespace", "klepaas");

        assertThatThrownBy(() -> subject.restart("owner-repo", 7L))
                .isInstanceOf(BusinessException.class);
        Mockito.verify(client.apps().deployments().inNamespace("klepaas"), Mockito.never())
                .resource(Mockito.any(io.fabric8.kubernetes.api.model.apps.Deployment.class));
    }
}
