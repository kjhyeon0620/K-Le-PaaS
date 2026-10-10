package klepaas.backend.deployment.dto;

import klepaas.backend.deployment.entity.Deployment;
import klepaas.backend.deployment.entity.DeploymentStatus;

import java.util.List;

/**
 * 배포 한 건의 실패 원인과, 그 요청이 적용한 Pod의 조회 시점 관측값 (#54). 로그는 저장하지 않는다.
 */
public record DeploymentLogResponse(
        Long deploymentId,
        DeploymentStatus status,
        String failReason,
        Observation observation,
        String observationMessage,
        Long appliedDeploymentId,
        List<PodLog> pods,
        List<PodEvent> events
) {

    public enum Observation {
        AVAILABLE,      // 클러스터 Deployment를 이 요청이 적용했다
        NOT_CURRENT,    // 다른 요청이 적용했거나, 적용 기록이 없거나, Deployment가 없다
        UNAVAILABLE     // Kubernetes 조회 실패
    }

    public record PodLog(
            String name,
            String phase,
            boolean ready,
            int restartCount,
            String state,
            String stateReason,
            String stateMessage,
            String lastTerminationReason,
            Integer lastExitCode,
            List<String> logs,
            List<String> previousLogs,
            String logsError
    ) {
    }

    // lastSeen은 Kubernetes가 준 RFC 3339 UTC 문자열 그대로다
    public record PodEvent(String type, String reason, String message, String object, Integer count, String lastSeen) {
    }

    public record Observed(Observation observation, String message, Long appliedDeploymentId,
                           List<PodLog> pods, List<PodEvent> events) {

        public static Observed notCurrent(String message, Long appliedDeploymentId) {
            return new Observed(Observation.NOT_CURRENT, message, appliedDeploymentId, List.of(), List.of());
        }

        public static Observed unavailable(String message) {
            return new Observed(Observation.UNAVAILABLE, message, null, List.of(), List.of());
        }
    }

    public static DeploymentLogResponse of(Deployment deployment, Observed observed) {
        return new DeploymentLogResponse(deployment.getId(), deployment.getStatus(), deployment.getFailReason(),
                observed.observation(), observed.message(), observed.appliedDeploymentId(),
                observed.pods(), observed.events());
    }
}
