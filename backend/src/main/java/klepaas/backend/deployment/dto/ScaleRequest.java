package klepaas.backend.deployment.dto;

import jakarta.validation.constraints.Min;

public record ScaleRequest(
        @Min(1) int replicas
) {
}
