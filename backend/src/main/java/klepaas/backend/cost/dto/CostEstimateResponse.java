package klepaas.backend.cost.dto;

import java.math.BigDecimal;
import java.util.List;

public record CostEstimateResponse(
        String currency,
        BigDecimal estimatedMonthlyCost,
        BigDecimal deltaMonthlyCost,
        boolean limitExceeded,
        BigDecimal monthlyBudgetLimit,
        List<CostBreakdownItemResponse> costBreakdown,
        List<String> assumptions
) {
}
