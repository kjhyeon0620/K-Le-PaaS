package klepaas.backend.deployment.dto;

import klepaas.backend.deployment.entity.ScalingHistory;

import java.time.LocalDateTime;

public record ScalingHistoryResponse(
        Long id,
        Long deploymentId,
        int previousReplicas,
        int newReplicas,
        String triggeredBy,
        LocalDateTime createdAt
) {
    public static ScalingHistoryResponse from(ScalingHistory scalingHistory) {
        return new ScalingHistoryResponse(
                scalingHistory.getId(),
                scalingHistory.getDeployment().getId(),
                scalingHistory.getPreviousReplicas(),
                scalingHistory.getNewReplicas(),
                scalingHistory.getTriggeredBy(),
                scalingHistory.getCreatedAt()
        );
    }
}
