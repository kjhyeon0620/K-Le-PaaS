package klepaas.backend.cost.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public record CostDiffRequest(
        @Valid CostResourceSpec current,
        @Valid @NotNull CostResourceSpec planned
) {
}
