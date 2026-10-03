package klepaas.backend.deployment.dto;

/** created가 false면 같은 요청으로 이미 접수된 배포를 돌려준 것이다. */
public record CreateDeploymentResult(DeploymentResponse deployment, boolean created) {
}
