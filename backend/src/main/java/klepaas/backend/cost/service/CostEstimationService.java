package klepaas.backend.cost.service;

import klepaas.backend.cost.dto.*;
import klepaas.backend.deployment.entity.CloudVendor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

@Service
public class CostEstimationService {

    private static final String CURRENCY = "KRW";

    public CostEstimateResponse plan(CostPlanRequest request) {
        CostEstimate planned = estimate(request.planned());
        return toResponse(planned, BigDecimal.ZERO, false, null);
    }

    public CostEstimateResponse explain(CostPlanRequest request) {
        return plan(request);
    }

    public CostEstimateResponse diff(CostDiffRequest request) {
        CostEstimate current = estimateOrEmpty(request.current());
        CostEstimate planned = estimate(request.planned());
        BigDecimal delta = normalize(planned.totalCost().subtract(current.totalCost()));
        return toResponse(planned, delta, false, null);
    }

    public CostEstimateResponse check(CostCheckRequest request) {
        CostEstimate current = estimateOrEmpty(request.current());
        CostEstimate planned = estimate(request.planned());
        BigDecimal delta = normalize(planned.totalCost().subtract(current.totalCost()));
        boolean limitExceeded = planned.totalCost().compareTo(normalize(request.monthlyBudgetLimit())) > 0;
        return toResponse(planned, delta, limitExceeded, normalize(request.monthlyBudgetLimit()));
    }

    private CostEstimate estimateOrEmpty(CostResourceSpec spec) {
        if (spec == null) {
            return new CostEstimate(BigDecimal.ZERO, List.of(), List.of("현재 비용 기준이 없어 0원으로 비교했습니다."));
        }
        return estimate(spec);
    }

    private CostEstimate estimate(CostResourceSpec spec) {
        PricingModel pricing = pricingModel(spec.cloudVendor());

        BigDecimal replicas = BigDecimal.valueOf(spec.replicas());
        BigDecimal vcpu = BigDecimal.valueOf(spec.cpuMillicores()).divide(BigDecimal.valueOf(1000), 3, RoundingMode.HALF_UP);
        BigDecimal memoryGiB = BigDecimal.valueOf(spec.memoryMb()).divide(BigDecimal.valueOf(1024), 3, RoundingMode.HALF_UP);
        BigDecimal storageGb = BigDecimal.valueOf(spec.storageGb());
        BigDecimal outboundTrafficGb = spec.safeOutboundTrafficGb();

        BigDecimal computeCost = normalize(vcpu.multiply(pricing.cpuPerVcpuMonth()).multiply(replicas));
        BigDecimal memoryCost = normalize(memoryGiB.multiply(pricing.memoryPerGiBMonth()).multiply(replicas));
        BigDecimal storageCost = normalize(storageGb.multiply(pricing.storagePerGbMonth()));
        BigDecimal loadBalancerCost = spec.loadBalancer() ? pricing.loadBalancerPerMonth() : BigDecimal.ZERO;
        BigDecimal networkCost = normalize(outboundTrafficGb.multiply(pricing.networkPerGbMonth()));

        List<CostBreakdownItemResponse> breakdown = new ArrayList<>();
        breakdown.add(new CostBreakdownItemResponse(
                "compute",
                "Compute vCPU",
                computeCost,
                "vCPU-month",
                formatQuantity(vcpu.multiply(replicas)),
                "replicas=" + spec.replicas() + ", vendor=" + spec.cloudVendor().name()
        ));
        breakdown.add(new CostBreakdownItemResponse(
                "memory",
                "Memory",
                memoryCost,
                "GiB-month",
                formatQuantity(memoryGiB.multiply(replicas)),
                "memory_mb=" + spec.memoryMb() + ", replicas=" + spec.replicas()
        ));
        breakdown.add(new CostBreakdownItemResponse(
                "storage",
                "Persistent Storage",
                storageCost,
                "GB-month",
                formatQuantity(storageGb),
                "storage_gb=" + spec.storageGb()
        ));
        breakdown.add(new CostBreakdownItemResponse(
                "load_balancer",
                "Load Balancer",
                normalize(loadBalancerCost),
                "unit-month",
                spec.loadBalancer() ? "1" : "0",
                spec.loadBalancer() ? "외부 LB 1개 기준" : "LB 미사용"
        ));
        breakdown.add(new CostBreakdownItemResponse(
                "network",
                "Outbound Traffic",
                networkCost,
                "GB",
                formatQuantity(outboundTrafficGb),
                "월 외부 전송량 기준"
        ));

        List<String> assumptions = List.of(
                spec.cloudVendor().name() + " 온디맨드 기준 단가를 사용합니다.",
                "월 730시간 기준으로 계산합니다.",
                "환경(" + spec.environment() + ")에 따른 별도 할인/예약 인스턴스는 반영하지 않습니다."
        );

        BigDecimal total = normalize(
                computeCost
                        .add(memoryCost)
                        .add(storageCost)
                        .add(loadBalancerCost)
                        .add(networkCost)
        );

        return new CostEstimate(total, breakdown, assumptions);
    }

    private CostEstimateResponse toResponse(
            CostEstimate estimate,
            BigDecimal deltaMonthlyCost,
            boolean limitExceeded,
            BigDecimal monthlyBudgetLimit
    ) {
        return new CostEstimateResponse(
                CURRENCY,
                estimate.totalCost(),
                normalize(deltaMonthlyCost),
                limitExceeded,
                monthlyBudgetLimit,
                estimate.breakdown(),
                estimate.assumptions()
        );
    }

    private PricingModel pricingModel(CloudVendor cloudVendor) {
        return switch (cloudVendor) {
            case NCP -> new PricingModel(
                    BigDecimal.valueOf(30000),
                    BigDecimal.valueOf(4000),
                    BigDecimal.valueOf(120),
                    BigDecimal.valueOf(18000),
                    BigDecimal.valueOf(110)
            );
            case AWS -> new PricingModel(
                    BigDecimal.valueOf(45000),
                    BigDecimal.valueOf(5500),
                    BigDecimal.valueOf(150),
                    BigDecimal.valueOf(25000),
                    BigDecimal.valueOf(140)
            );
            case ON_PREMISE -> new PricingModel(
                    BigDecimal.valueOf(15000),
                    BigDecimal.valueOf(2000),
                    BigDecimal.valueOf(50),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO
            );
        };
    }

    private BigDecimal normalize(BigDecimal value) {
        return value.setScale(0, RoundingMode.HALF_UP);
    }

    private String formatQuantity(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private record PricingModel(
            BigDecimal cpuPerVcpuMonth,
            BigDecimal memoryPerGiBMonth,
            BigDecimal storagePerGbMonth,
            BigDecimal loadBalancerPerMonth,
            BigDecimal networkPerGbMonth
    ) {
    }

    private record CostEstimate(
            BigDecimal totalCost,
            List<CostBreakdownItemResponse> breakdown,
            List<String> assumptions
    ) {
    }
}
