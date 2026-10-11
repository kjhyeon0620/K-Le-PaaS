package klepaas.backend.deployment.dto;

import klepaas.backend.deployment.entity.Deployment;
import klepaas.backend.deployment.entity.DeploymentStatus;
import klepaas.backend.deployment.entity.FailureKind;
import klepaas.backend.deployment.entity.TriggerSource;

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
        LocalDateTime createdAt,
        // 요청 기록 (#95). 이전 배포는 null
        TriggerSource triggerSource,
        Long requestedByUserId,
        Long commandLogId,
        String imageDigest,
        FailureKind failureKind,
        // 복구 배포가 되돌린 이전 성공 배포 (#101)
        Long recoveredFromDeploymentId
) {
    public DeploymentResponse(Long id, Long repositoryId, String repositoryName, String branchName, String commitHash,
                              String imageUri, DeploymentStatus status, String failReason, LocalDateTime startedAt,
                              LocalDateTime finishedAt, LocalDateTime createdAt, TriggerSource triggerSource,
                              Long requestedByUserId, Long commandLogId, String imageDigest, FailureKind failureKind) {
        this(id, repositoryId, repositoryName, branchName, commitHash, imageUri, status, failReason, startedAt,
                finishedAt, createdAt, triggerSource, requestedByUserId, commandLogId, imageDigest, failureKind, null);
    }

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
                entity.getCreatedAt(),
                entity.getTriggerSource(),
                entity.getRequestedByUserId(),
                entity.getCommandLogId(),
                entity.getImageDigest(),
                entity.getFailureKind(),
                entity.getRecoveredFromDeploymentId()
        );
    }
}
