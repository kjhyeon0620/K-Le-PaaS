package klepaas.backend.ai.dto;

import jakarta.validation.constraints.NotBlank;

public record NlpCommandRequest(
        @NotBlank String command,
        String sessionId
) {
}
