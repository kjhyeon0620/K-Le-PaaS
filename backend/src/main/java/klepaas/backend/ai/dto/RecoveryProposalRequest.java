package klepaas.backend.ai.dto;

import jakarta.validation.constraints.Size;

/** 에이전트의 복구 제안 (#102). reason은 승인할 사람에게 보일 제안 사유다. */
public record RecoveryProposalRequest(
        @Size(max = 200) String reason
) {
}
