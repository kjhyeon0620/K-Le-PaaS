package klepaas.backend.cost.service;

import klepaas.backend.cost.dto.CostCheckRequest;
import klepaas.backend.cost.dto.CostDiffRequest;
import klepaas.backend.cost.dto.CostPlanRequest;
import klepaas.backend.cost.dto.CostResourceSpec;
import klepaas.backend.deployment.entity.CloudVendor;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class CostEstimationServiceTest {

    private final CostEstimationService costEstimationService = new CostEstimationService();

    @Test
    void planReturnsPositiveCostBreakdown() {
        var response = costEstimationService.plan(new CostPlanRequest(sampleSpec(CloudVendor.NCP, 2)));

        assertEquals("KRW", response.currency());
        assertTrue(response.estimatedMonthlyCost().compareTo(BigDecimal.ZERO) > 0);
        assertFalse(response.costBreakdown().isEmpty());
        assertFalse(response.limitExceeded());
    }

    @Test
    void diffReturnsPositiveDeltaForScaledUpDeployment() {
        var response = costEstimationService.diff(new CostDiffRequest(
                sampleSpec(CloudVendor.NCP, 1),
                sampleSpec(CloudVendor.NCP, 3)
        ));

        assertTrue(response.deltaMonthlyCost().compareTo(BigDecimal.ZERO) > 0);
    }

    @Test
    void checkFlagsBudgetExceededWhenPlannedCostIsAboveLimit() {
        var response = costEstimationService.check(new CostCheckRequest(
                null,
                sampleSpec(CloudVendor.AWS, 4),
                BigDecimal.valueOf(10000)
        ));

        assertTrue(response.limitExceeded());
        assertEquals(BigDecimal.valueOf(10000).setScale(0), response.monthlyBudgetLimit());
    }

    private CostResourceSpec sampleSpec(CloudVendor cloudVendor, int replicas) {
        return new CostResourceSpec(
                cloudVendor,
                "dev",
                replicas,
                1000,
                2048,
                20,
                true,
                BigDecimal.valueOf(100)
        );
    }
}
