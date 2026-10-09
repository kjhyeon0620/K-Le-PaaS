package klepaas.backend.infra.kubernetes;

import io.fabric8.kubernetes.api.model.EventBuilder;
import io.fabric8.kubernetes.api.model.EventListBuilder;
import io.fabric8.kubernetes.api.model.KubernetesResourceList;
import io.fabric8.kubernetes.api.model.PodListBuilder;
import io.fabric8.kubernetes.api.model.StatusBuilder;
import io.fabric8.kubernetes.api.model.apps.ReplicaSetListBuilder;
import io.fabric8.kubernetes.client.dsl.FilterWatchListDeletable;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.PodResource;
import io.fabric8.kubernetes.client.dsl.RollableScalableResource;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.api.model.apps.ReplicaSetBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import klepaas.backend.deployment.dto.DeploymentLogResponse.Observation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@SuppressWarnings({"rawtypes", "unchecked"})
class DeploymentObservationReaderTest {

    private final KubernetesClient client = Mockito.mock(KubernetesClient.class, Mockito.RETURNS_DEEP_STUBS);
    private final DeploymentObservationReader reader = reader(client);
    // Fabric8 DSL의 제네릭 반환값은 deep stub이 따라가지 못해 단계마다 mock을 둔다
    private final NonNamespaceOperation deployments = deep(NonNamespaceOperation.class);
    private final RollableScalableResource deploymentResource = deep(RollableScalableResource.class);
    private final NonNamespaceOperation replicaSets = deep(NonNamespaceOperation.class);
    private final NonNamespaceOperation pods = deep(NonNamespaceOperation.class);
    private final NonNamespaceOperation events = deep(NonNamespaceOperation.class);

    {
        when(client.apps().deployments().inNamespace("klepaas")).thenReturn(deployments);
        when(deployments.withName("o-r")).thenReturn(deploymentResource);
        when(client.apps().replicaSets().inNamespace("klepaas")).thenReturn(replicaSets);
        when(client.pods().inNamespace("klepaas")).thenReturn(pods);
        when(client.v1().events().inNamespace("klepaas")).thenReturn(events);
    }

    @SuppressWarnings("unchecked")
    private static <T> T deep(Class<?> type) {
        return (T) Mockito.mock(type, Mockito.RETURNS_DEEP_STUBS);
    }

    private PodResource podResource(String name) {
        PodResource resource = deep(PodResource.class);
        when(pods.withName(name)).thenReturn(resource);
        return resource;
    }

    @SuppressWarnings("unchecked")
    private void listed(NonNamespaceOperation op, String field, String value, KubernetesResourceList<?> list) {
        FilterWatchListDeletable filter = deep(FilterWatchListDeletable.class);
        when(field == null ? op.withLabel("app.kubernetes.io/name", value) : op.withField(field, value)).thenReturn(filter);
        when(filter.list()).thenReturn(list);
    }

    private static DeploymentObservationReader reader(KubernetesClient client) {
        var reader = new DeploymentObservationReader(client);
        ReflectionTestUtils.setField(reader, "namespace", "klepaas");
        return reader;
    }

    private Deployment deployment(String repoId, String appliedId) {
        Deployment deployment = new DeploymentBuilder().withNewMetadata().withName("o-r").withUid("dep-uid")
                .addToLabels("klepaas.io/repository-id", repoId)
                .addToAnnotations("deployment.kubernetes.io/revision", "3")
                .endMetadata().build();
        if (appliedId != null) deployment.getMetadata().getAnnotations().put("klepaas.io/deployment-id", appliedId);
        return deployment;
    }

    private void currentDeployment(Deployment deployment) {
        when(deploymentResource.get()).thenReturn(deployment);
    }

    @Test
    @DisplayName("다른 요청이 적용한 Deployment면 Pod를 읽지 않고 NOT_CURRENT와 적용 요청 ID를 준다")
    void notCurrentWhenAnotherRequestApplied() {
        currentDeployment(deployment("7", "43"));

        var observed = reader.observe("o-r", 7L, 42L, 100);

        assertThat(observed.observation()).isEqualTo(Observation.NOT_CURRENT);
        assertThat(observed.appliedDeploymentId()).isEqualTo(43L);
        assertThat(observed.message()).contains("#43");
        assertThat(observed.pods()).isEmpty();
        Mockito.verifyNoInteractions(pods);
    }

    @Test
    @DisplayName("적용 기록이 없거나, Deployment가 없거나, 다른 저장소 소유면 NOT_CURRENT")
    void notCurrentWithoutRecordOrOwnership() {
        currentDeployment(deployment("7", null));
        assertThat(reader.observe("o-r", 7L, 42L, 100).observation()).isEqualTo(Observation.NOT_CURRENT);

        currentDeployment(deployment("8", "42"));
        assertThat(reader.observe("o-r", 7L, 42L, 100).observation()).isEqualTo(Observation.NOT_CURRENT);

        currentDeployment(null);
        assertThat(reader.observe("o-r", 7L, 42L, 100).observation()).isEqualTo(Observation.NOT_CURRENT);
        Mockito.verifyNoInteractions(pods);
    }

    @Test
    @DisplayName("Kubernetes 조회가 실패하면 빈 정상 결과가 아니라 UNAVAILABLE")
    void unavailableWhenClusterReadFails() {
        when(deploymentResource.get())
                .thenThrow(new KubernetesClientException("Failed to connect to https://10.0.0.1:6443/apis/apps/v1"));

        var observed = reader.observe("o-r", 7L, 42L, 100);

        assertThat(observed.observation()).isEqualTo(Observation.UNAVAILABLE);
        assertThat(observed.message()).isEqualTo("Kubernetes 조회 실패: Kubernetes API에 연결하지 못했습니다")
                .doesNotContain("10.0.0.1");
    }

    @Test
    @DisplayName("이 요청이 적용했으면 현재 revision ReplicaSet의 Pod 로그와 그 Pod·ReplicaSet 이벤트를 준다")
    void availableReadsCurrentReplicaSetPodsAndEvents() {
        currentDeployment(deployment("7", "42"));
        var rs = new ReplicaSetBuilder().withNewMetadata().withName("o-r-abc")
                .addToAnnotations("deployment.kubernetes.io/revision", "3")
                .addNewOwnerReference().withUid("dep-uid").endOwnerReference().endMetadata()
                .withNewSpec().withNewSelector().addToMatchLabels("pod-template-hash", "abc").endSelector().endSpec()
                .build();
        var oldRs = new ReplicaSetBuilder(rs).editMetadata().withName("o-r-old")
                .addToAnnotations("deployment.kubernetes.io/revision", "2").endMetadata().build();
        listed(replicaSets, null, "o-r", new ReplicaSetListBuilder().withItems(oldRs, rs).build());
        Pod pod = new PodBuilder().withNewMetadata().withName("o-r-abc-1").endMetadata()
                .withNewStatus().withPhase("Running")
                .addNewContainerStatus().withName("o-r").withReady(false).withRestartCount(2)
                .withNewState().withNewWaiting().withReason("CrashLoopBackOff").endWaiting().endState()
                .withNewLastState().withNewTerminated().withReason("OOMKilled").withExitCode(137).endTerminated().endLastState()
                .endContainerStatus().endStatus().build();
        FilterWatchListDeletable podFilter = deep(FilterWatchListDeletable.class);
        when(pods.withLabels(Map.of("pod-template-hash", "abc"))).thenReturn(podFilter);
        when(podFilter.list()).thenReturn(new PodListBuilder().withItems(pod).build());
        var container = podResource("o-r-abc-1").inContainer("o-r");
        when(container.tailingLines(50).getLog()).thenReturn("");
        when(container.terminated().tailingLines(50).getLog()).thenReturn("starting\nout of memory");
        listed(events, "involvedObject.name", "o-r-abc-1", new EventListBuilder().withItems(new EventBuilder().withType("Warning").withReason("BackOff").withMessage("Back-off restarting")
                        .withCount(4).withLastTimestamp("2026-10-10T01:00:00Z")
                        .withNewInvolvedObject().withKind("Pod").withName("o-r-abc-1").endInvolvedObject().build()).build());
        listed(events, "involvedObject.name", "o-r-abc", new EventListBuilder().build());

        var observed = reader.observe("o-r", 7L, 42L, 50);

        assertThat(observed.observation()).isEqualTo(Observation.AVAILABLE);
        assertThat(observed.appliedDeploymentId()).isEqualTo(42L);
        assertThat(observed.pods()).singleElement().satisfies(p -> {
            assertThat(p.name()).isEqualTo("o-r-abc-1");
            assertThat(p.stateReason()).isEqualTo("CrashLoopBackOff");
            assertThat(p.lastTerminationReason()).isEqualTo("OOMKilled");
            assertThat(p.lastExitCode()).isEqualTo(137);
            assertThat(p.logs()).isEmpty();
            assertThat(p.previousLogs()).containsExactly("starting", "out of memory");
        });
        assertThat(observed.events()).singleElement().satisfies(e -> {
            assertThat(e.object()).isEqualTo("Pod/o-r-abc-1");
            assertThat(e.count()).isEqualTo(4);
        });
    }

    @Test
    @DisplayName("Pod 하나의 로그 조회 실패는 그 Pod의 logs_error로만 남는다")
    void podLogFailureIsReportedPerPod() {
        Pod pod = new PodBuilder().withNewMetadata().withName("p1").endMetadata()
                .withNewStatus().withPhase("Pending")
                .addNewContainerStatus().withName("o-r").withReady(false).withRestartCount(0)
                .withNewState().withNewWaiting().withReason("ImagePullBackOff").withMessage("Back-off pulling image").endWaiting().endState()
                .endContainerStatus().endStatus().build();
        when(podResource("p1").inContainer("o-r").tailingLines(100).getLog())
                .thenThrow(new KubernetesClientException(new StatusBuilder().withCode(400)
                        .withMessage("container \"o-r\" in pod \"p1\" is waiting to start: trying and failing to pull image").build()));

        var log = reader.toPodLog(pod, "o-r", 100);

        assertThat(log.state()).isEqualTo("waiting");
        assertThat(log.stateReason()).isEqualTo("ImagePullBackOff");
        assertThat(log.logs()).isEmpty();
        assertThat(log.previousLogs()).isNull();
        assertThat(log.logsError()).startsWith("container \"o-r\" in pod \"p1\" is waiting to start").doesNotContain("https://");
    }
}
