package klepaas.backend.ai.dto;

import klepaas.backend.ai.entity.Intent;
import klepaas.backend.ai.entity.RiskLevel;

public record NlpCommandResponse(
        Long commandLogId,
        Intent intent,
        String message,
        Object result,
        RiskLevel riskLevel,
        boolean requiresConfirmation,
        String sessionId,
        // 승인이 필요한 배포 명령의 승인 대상 (#56). 대상이 아니거나 확인하지 못하면 null
        ApprovalTarget approvalTarget
) {
    public NlpCommandResponse(Long commandLogId, Intent intent, String message, Object result,
                              RiskLevel riskLevel, boolean requiresConfirmation, String sessionId) {
        this(commandLogId, intent, message, result, riskLevel, requiresConfirmation, sessionId, null);
    }
}
