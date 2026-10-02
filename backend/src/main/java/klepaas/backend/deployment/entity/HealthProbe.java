package klepaas.backend.deployment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * HTTP readiness probe. startupFailureThreshold가 있으면 같은 경로로 startup probe도 만든다
 * (최대 기동 대기 = periodSeconds × startupFailureThreshold). 비어 있는 값은 Kubernetes 기본값을 쓴다.
 */
@Embeddable
public record HealthProbe(
        @Column(name = "health_probe_path")
        @Size(max = 255) @Pattern(regexp = "^$|^/\\S*$", message = "path는 /로 시작해야 합니다") String path,
        @Column(name = "health_probe_port") @Min(1) @Max(65535) Integer port,
        @Column(name = "health_probe_initial_delay_seconds") @Min(0) Integer initialDelaySeconds,
        @Column(name = "health_probe_period_seconds") @Min(1) Integer periodSeconds,
        @Column(name = "health_probe_failure_threshold") @Min(1) Integer failureThreshold,
        @Column(name = "health_probe_startup_failure_threshold") @Min(1) Integer startupFailureThreshold
) {
}
