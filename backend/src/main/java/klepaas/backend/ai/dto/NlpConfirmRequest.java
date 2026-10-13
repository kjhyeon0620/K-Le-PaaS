package klepaas.backend.ai.dto;

import jakarta.validation.constraints.NotNull;

public record NlpConfirmRequest(
        @NotNull Long commandLogId,
        boolean confirmed
) {
}
