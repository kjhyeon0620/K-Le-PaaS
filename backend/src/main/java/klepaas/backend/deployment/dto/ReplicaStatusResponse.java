package klepaas.backend.deployment.dto;

/**
 * 저장소 앱의 현재 Deployment replica 관측값 (#90). 관측할 수 없으면 숫자는 null이다.
 */
public record ReplicaStatusResponse(
        Long repositoryId,
        Observation observation,
        String observationMessage,
        Integer desired,
        Integer ready,
        Integer available,
        Integer updated
) {

    public enum Observation {
        AVAILABLE,   // 이 저장소의 Deployment를 읽었다
        NOT_FOUND,   // Deployment가 없거나 다른 저장소 소유
        UNAVAILABLE  // Kubernetes 조회 실패
    }

    public static ReplicaStatusResponse unobserved(Long repositoryId, Observation observation, String message) {
        return new ReplicaStatusResponse(repositoryId, observation, message, null, null, null, null);
    }
}
