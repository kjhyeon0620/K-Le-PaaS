package klepaas.backend.cost.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record CostCheckRequest(
        @Valid CostResourceSpec current,
        @Valid @NotNull CostResourceSpec planned,
        @NotNull @Positive BigDecimal monthlyBudgetLimit
) {
}
