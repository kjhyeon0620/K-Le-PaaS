package klepaas.backend.ai.dto;

import klepaas.backend.deployment.entity.BuildStrategy;

import java.util.List;

/**
 * 승인이 필요한 배포 명령이 적용할 대상 (#56). env는 이름만 둔다.
 * configFingerprint는 승인 고정에 쓰는 설정 지문의 앞 8자리다.
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
        String configFingerprint
) {
}
