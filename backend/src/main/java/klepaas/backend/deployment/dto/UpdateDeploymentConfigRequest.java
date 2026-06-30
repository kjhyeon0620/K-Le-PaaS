package klepaas.backend.deployment.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import klepaas.backend.deployment.entity.BuildStrategy;
import klepaas.backend.deployment.entity.KubernetesServiceType;

import java.util.Map;

public record UpdateDeploymentConfigRequest(
        @Min(0) int minReplicas,
        @Min(1) int maxReplicas,
        Map<String, String> envVars,
        @Min(1) int containerPort,
        String domainUrl,
        BuildStrategy buildStrategy,
        String imageUriTemplate,
        String imagePullSecretName,
        KubernetesServiceType serviceType,
        @Min(30000) @Max(32767) Integer nodePort
) {
    public UpdateDeploymentConfigRequest(int minReplicas, int maxReplicas, Map<String, String> envVars,
                                         int containerPort, String domainUrl) {
        this(minReplicas, maxReplicas, envVars, containerPort, domainUrl, null, null, null, null, null);
    }
}
