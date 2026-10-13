package klepaas.backend.deployment.dto;

import klepaas.backend.deployment.entity.Deployment;
import klepaas.backend.deployment.entity.DeploymentStatus;

public record DeploymentStatusResponse(
        Long deploymentId,
        DeploymentStatus status,
        String failReason
) {
    public static DeploymentStatusResponse from(Deployment entity) {
        return new DeploymentStatusResponse(
                entity.getId(),
                entity.getStatus(),
                entity.getFailReason()
        );
    }
}
