package klepaas.backend.deployment.dto;

import java.time.LocalDateTime;

/** 복구 후보 (#101): 같은 저장소의 이미지가 기록된 성공 배포. configRecorded가 false면 복구할 수 없다. */
public record RecoveryCandidateResponse(
        Long deploymentId,
        String commitHash,
        String imageUri,
        String imageDigest,
        LocalDateTime finishedAt,
        boolean configRecorded
) {
}
