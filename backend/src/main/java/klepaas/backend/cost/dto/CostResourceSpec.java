package klepaas.backend.cost.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import klepaas.backend.deployment.entity.CloudVendor;

import java.math.BigDecimal;

public record CostResourceSpec(
        @NotNull CloudVendor cloudVendor,
        @NotBlank String environment,
        @Min(1) int replicas,
        @Min(100) int cpuMillicores,
        @Min(128) int memoryMb,
        @Min(1) int storageGb,
        boolean loadBalancer,
        @DecimalMin(value = "0.0") BigDecimal outboundTrafficGb
) {
    public BigDecimal safeOutboundTrafficGb() {
        return outboundTrafficGb == null ? BigDecimal.ZERO : outboundTrafficGb;
    }
}
