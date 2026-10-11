package klepaas.backend.ai.dto;

import klepaas.backend.deployment.entity.BuildStrategy;

import java.util.List;

/**
 * 승인이 필요한 배포 명령이 적용할 대상 (#56). env는 이름만 둔다.
 * configFingerprint는 승인 고정에 쓰는 지문의 앞 8자리다. 복구 명령(#101)은 복구 계획 지문이고,
 * recoveredFromDeploymentId·blockedReasons가 있다. 실행할 수 없는 복구 계획이면 지문이 null이다.
 */
public record ApprovalTarget(
        Long repositoryId,
        String repository,
        String branch,
        String commit,
        BuildStrategy buildStrategy,
        String image,
        int minReplicas,
        int maxReplicas,
        int containerPort,
        List<String> envNames,
        String configFingerprint,
        Long recoveredFromDeploymentId,
        List<String> blockedReasons
) {
    public ApprovalTarget(Long repositoryId, String repository, String branch, String commit, BuildStrategy buildStrategy,
                          String image, int minReplicas, int maxReplicas, int containerPort, List<String> envNames,
                          String configFingerprint) {
        this(repositoryId, repository, branch, commit, buildStrategy, image, minReplicas, maxReplicas, containerPort,
                envNames, configFingerprint, null, null);
    }
}
