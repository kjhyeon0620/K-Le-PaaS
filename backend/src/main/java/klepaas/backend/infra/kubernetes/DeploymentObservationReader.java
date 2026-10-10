package klepaas.backend.infra.kubernetes;

import io.fabric8.kubernetes.api.model.ContainerState;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.ReplicaSet;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import klepaas.backend.deployment.dto.DeploymentLogResponse;
import klepaas.backend.deployment.dto.DeploymentLogResponse.Observed;
import klepaas.backend.deployment.dto.DeploymentLogResponse.PodEvent;
import klepaas.backend.deployment.dto.DeploymentLogResponse.PodLog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 배포 요청 한 건이 적용한 Pod의 상태·로그·이벤트를 조회 시점에 읽는다 (#54).
 * 클러스터 Deployment의 {@code klepaas.io/deployment-id}가 그 요청일 때만 Pod를 읽는다. 이름·이미지로 추정하지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeploymentObservationReader {

    static final int MAX_PODS = 5;
    static final int MAX_EVENTS = 20;
    private static final int MAX_MESSAGE_LENGTH = 300;

    private final KubernetesClient kubernetesClient;

    @Value("${kubernetes.namespace:default}")
    private String namespace;

    public Observed observe(String appName, Long repositoryId, Long deploymentId, int lines) {
        try {
            Deployment deployment = kubernetesClient.apps().deployments().inNamespace(namespace).withName(appName).get();
            if (deployment == null) {
                return Observed.notCurrent("클러스터에 이 앱의 Deployment가 없습니다", null);
            }
            if (!String.valueOf(repositoryId).equals(label(deployment, "klepaas.io/repository-id"))) {
                return Observed.notCurrent("클러스터의 같은 이름 Deployment가 이 저장소 소유가 아닙니다", null);
            }
            String applied = KubernetesManifestGenerator.annotation(deployment.getMetadata(),
                    KubernetesManifestGenerator.DEPLOYMENT_ID_ANNOTATION);
            Long appliedId = parseId(applied);
            if (!String.valueOf(deploymentId).equals(applied)) {
                return Observed.notCurrent(appliedId == null
                        ? "현재 Deployment에 적용한 배포 요청 기록이 없습니다"
                        : "현재 Deployment는 배포 #" + appliedId + "이(가) 적용했습니다", appliedId);
            }

            Optional<ReplicaSet> replicaSet = KubernetesManifestGenerator.currentReplicaSet(kubernetesClient, namespace, deployment);
            if (replicaSet.isEmpty()) {
                return new Observed(DeploymentLogResponse.Observation.AVAILABLE,
                        "이 배포의 ReplicaSet을 아직 관측하지 못했습니다", deploymentId, List.of(), List.of());
            }
            List<Pod> pods = kubernetesClient.pods().inNamespace(namespace)
                    .withLabels(replicaSet.get().getSpec().getSelector().getMatchLabels()).list().getItems().stream()
                    .sorted(Comparator.comparing(p -> p.getMetadata().getName()))
                    .limit(MAX_PODS)
                    .toList();

            List<PodLog> podLogs = pods.stream().map(pod -> toPodLog(pod, appName, lines)).toList();
            List<HasMetadata> objects = new ArrayList<>(pods);
            objects.add(replicaSet.get());
            return new Observed(DeploymentLogResponse.Observation.AVAILABLE, null, deploymentId,
                    podLogs, events(objects));
        } catch (KubernetesClientException e) {
            log.warn("Deployment observation failed: app={}, deploymentId={}, error={}", appName, deploymentId, e.getMessage());
            return Observed.unavailable("Kubernetes 조회 실패: " + clientMessage(e));
        }
    }

    PodLog toPodLog(Pod pod, String containerName, int lines) {
        String name = pod.getMetadata().getName();
        ContainerStatus status = pod.getStatus() == null || pod.getStatus().getContainerStatuses() == null ? null
                : pod.getStatus().getContainerStatuses().stream()
                        .filter(c -> containerName.equals(c.getName())).findFirst().orElse(null);
        String phase = pod.getStatus() == null ? null : pod.getStatus().getPhase();
        if (status == null) {
            return new PodLog(name, phase, false, 0, null, null, null, null, null, List.of(), null,
                    "컨테이너 상태가 아직 없습니다");
        }
        ContainerState state = status.getState();
        String stateName = null, reason = null, message = null;
        if (state != null && state.getWaiting() != null) {
            stateName = "waiting"; reason = state.getWaiting().getReason(); message = state.getWaiting().getMessage();
        } else if (state != null && state.getRunning() != null) {
            stateName = "running";
        } else if (state != null && state.getTerminated() != null) {
            stateName = "terminated"; reason = state.getTerminated().getReason(); message = state.getTerminated().getMessage();
        }
        var lastTerminated = status.getLastState() == null ? null : status.getLastState().getTerminated();
        int restarts = status.getRestartCount() == null ? 0 : status.getRestartCount();

        List<String> logs = List.of();
        String logsError = null;
        try {
            logs = splitLines(kubernetesClient.pods().inNamespace(namespace).withName(name)
                    .inContainer(containerName).tailingLines(lines).getLog());
        } catch (KubernetesClientException e) {
            logsError = clientMessage(e);
        }
        List<String> previousLogs = null;
        if (restarts > 0) {
            try {
                previousLogs = splitLines(kubernetesClient.pods().inNamespace(namespace).withName(name)
                        .inContainer(containerName).terminated().tailingLines(lines).getLog());
            } catch (KubernetesClientException e) {
                logsError = logsError == null ? "직전 로그 조회 실패: " + clientMessage(e) : logsError;
            }
        }
        return new PodLog(name, phase, Boolean.TRUE.equals(status.getReady()), restarts, stateName, reason,
                truncate(message),
                lastTerminated == null ? null : lastTerminated.getReason(),
                lastTerminated == null ? null : lastTerminated.getExitCode(),
                logs, previousLogs, logsError);
    }

    // 이 요청의 Pod·ReplicaSet을 대상으로 한 이벤트만 조회한다 (namespace 전체 이벤트를 읽지 않는다)
    private List<PodEvent> events(List<HasMetadata> objects) {
        return objects.stream()
                .flatMap(o -> kubernetesClient.v1().events().inNamespace(namespace)
                        .withField("involvedObject.name", o.getMetadata().getName()).list().getItems().stream())
                .map(this::toPodEvent)
                .sorted(Comparator.comparing((PodEvent e) -> parseTime(e.lastSeen()),
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(MAX_EVENTS)
                .toList();
    }

    private PodEvent toPodEvent(Event e) {
        String lastSeen = e.getLastTimestamp() != null ? e.getLastTimestamp()
                : e.getEventTime() != null ? e.getEventTime().getTime() : e.getFirstTimestamp();
        Integer count = e.getCount() != null ? e.getCount()
                : e.getSeries() != null ? e.getSeries().getCount() : null;
        return new PodEvent(e.getType(), e.getReason(), truncate(e.getMessage()),
                e.getInvolvedObject().getKind() + "/" + e.getInvolvedObject().getName(), count, lastSeen);
    }

    private static String label(HasMetadata resource, String key) {
        return resource.getMetadata() == null || resource.getMetadata().getLabels() == null
                ? null : resource.getMetadata().getLabels().get(key);
    }

    private static Long parseId(String value) {
        try {
            return value == null ? null : Long.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static List<String> splitLines(String log) {
        return log == null || log.isEmpty() ? List.of() : Arrays.asList(log.split("\n"));
    }

    // 예외 메시지에는 Kubernetes API 서버 주소가 들어 있어 사용자에게는 Kubernetes가 돌려준 status 메시지만 준다
    static String clientMessage(KubernetesClientException e) {
        log.debug("Kubernetes client error: code={}, message={}", e.getCode(), e.getMessage());
        if (e.getStatus() != null && e.getStatus().getMessage() != null) {
            return truncate(e.getStatus().getMessage());
        }
        return e.getCode() > 0 ? "Kubernetes API 오류 (HTTP " + e.getCode() + ")" : "Kubernetes API에 연결하지 못했습니다";
    }

    private static Instant parseTime(String value) {
        try {
            return value == null ? null : Instant.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String truncate(String message) {
        if (message == null) return null;
        return message.length() <= MAX_MESSAGE_LENGTH ? message : message.substring(0, MAX_MESSAGE_LENGTH) + "…";
    }
}
