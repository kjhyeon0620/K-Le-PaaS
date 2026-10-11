package klepaas.backend.deployment.dto;

import java.util.List;

/**
 * 복구 계획 (#101). configChanges는 현재 설정 → 대상 설정(env 제외), envDifferences는 env 이름·값 차이(값은 보이지 않음).
 * 실행할 수 없으면 blockedReasons가 있고 planFingerprint는 null이다.
 */
public record RecoveryPlanResponse(
        Long targetDeploymentId,
        String targetCommit,
        String image,
        String imageDigest,
        boolean digestPinned,
        List<DeploymentDetailResponse.Change> configChanges,
        List<DeploymentDetailResponse.Change> envDifferences,
        boolean executable,
        List<String> blockedReasons,
        String planFingerprint
) {
}
