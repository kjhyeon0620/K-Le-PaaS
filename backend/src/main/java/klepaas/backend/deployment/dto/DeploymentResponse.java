package klepaas.backend.deployment.dto;

import klepaas.backend.deployment.entity.Deployment;
import klepaas.backend.deployment.entity.DeploymentStatus;

import java.time.LocalDateTime;

public record DeploymentResponse(
        Long id,
        Long repositoryId,
        String repositoryName,
        String branchName,
        String commitHash,
        String imageUri,
        DeploymentStatus status,
        String failReason,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        LocalDateTime createdAt
) {
    public static DeploymentResponse from(Deployment entity) {
        return new DeploymentResponse(
                entity.getId(),
                entity.getSourceRepository().getId(),
                entity.getSourceRepository().getOwner() + "/" + entity.getSourceRepository().getRepoName(),
                entity.getBranchName(),
                entity.getCommitHash(),
                entity.getImageUri(),
                entity.getStatus(),
                entity.getFailReason(),
                entity.getStartedAt(),
                entity.getFinishedAt(),
                entity.getCreatedAt()
        );
    }
}
