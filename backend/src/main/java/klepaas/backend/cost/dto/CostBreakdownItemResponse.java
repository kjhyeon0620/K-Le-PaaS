package klepaas.backend.cost.dto;

import java.math.BigDecimal;

public record CostBreakdownItemResponse(
        String key,
        String label,
        BigDecimal monthlyCost,
        String unit,
        String quantity,
        String detail
) {
}
