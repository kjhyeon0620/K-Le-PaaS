package klepaas.backend.ai.dto;

import klepaas.backend.ai.entity.CommandLog;
import klepaas.backend.ai.entity.CommandStatus;
import klepaas.backend.ai.entity.Intent;
import klepaas.backend.ai.entity.RiskLevel;

import java.time.LocalDateTime;

public record CommandLogResponse(
        Long id,
        String rawCommand,
        Intent intent,
        RiskLevel riskLevel,
        CommandStatus status,
        boolean isExecuted,
        String executionResult,
        String errorMessage,
        LocalDateTime createdAt
) {
    public static CommandLogResponse from(CommandLog entity) {
        return new CommandLogResponse(
                entity.getId(),
                entity.getRawCommand(),
                entity.getInterpretedIntent(),
                entity.getRiskLevel(),
                entity.getStatus(),
                entity.isExecuted(),
                entity.getExecutionResult(),
                entity.getErrorMessage(),
                entity.getCreatedAt()
        );
    }
}
