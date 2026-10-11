package klepaas.backend.deployment.dto;

import jakarta.validation.constraints.NotBlank;

/** 확인한 복구 계획의 지문 (#101). 계획이 바뀌었으면 실행하지 않는다. */
public record RecoverDeploymentRequest(@NotBlank String planFingerprint) {
}
