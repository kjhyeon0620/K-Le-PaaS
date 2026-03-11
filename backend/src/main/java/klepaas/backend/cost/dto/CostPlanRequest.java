package klepaas.backend.cost.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public record CostPlanRequest(
        @Valid @NotNull CostResourceSpec planned
) {
}
