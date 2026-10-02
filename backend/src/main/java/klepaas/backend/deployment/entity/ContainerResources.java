package klepaas.backend.deployment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 컨테이너 CPU·메모리 requests/limits (Kubernetes quantity). 비어 있는 값은 설정하지 않는다. */
@Embeddable
public record ContainerResources(
        @Column(name = "cpu_request", length = 20) @Size(max = 20) @Pattern(regexp = CPU) String cpuRequest,
        @Column(name = "cpu_limit", length = 20) @Size(max = 20) @Pattern(regexp = CPU) String cpuLimit,
        @Column(name = "memory_request", length = 20) @Size(max = 20) @Pattern(regexp = MEMORY) String memoryRequest,
        @Column(name = "memory_limit", length = 20) @Size(max = 20) @Pattern(regexp = MEMORY) String memoryLimit
) {
    static final String CPU = "^$|^\\d+(\\.\\d+)?m?$";
    static final String MEMORY = "^$|^\\d+(\\.\\d+)?(Ki|Mi|Gi|Ti|k|M|G|T)?$";
}
