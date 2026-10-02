package klepaas.backend.deployment.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import klepaas.backend.deployment.entity.BuildStrategy;
import klepaas.backend.deployment.entity.ContainerResources;
import klepaas.backend.deployment.entity.HealthProbe;
import klepaas.backend.deployment.entity.KubernetesServiceType;

import java.util.List;
import java.util.Map;

public record UpdateDeploymentConfigRequest(
        @Min(0) int minReplicas,
        @Min(1) int maxReplicas,
        Map<String, String> envVars,
        List<String> envFromConfigMaps,
        List<String> envFromSecrets,
        @Min(1) int containerPort,
        String domainUrl,
        BuildStrategy buildStrategy,
        String imageUriTemplate,
        String imagePullSecretName,
        KubernetesServiceType serviceType,
        @Min(30000) @Max(32767) Integer nodePort,
        // 생략(null)하면 기존 값을 유지하고, 보내면 그룹 전체를 교체한다. path가 비어 있으면 probe를 해제한다.
        @Valid HealthProbe healthProbe,
        @Valid ContainerResources resources
) {
    public UpdateDeploymentConfigRequest(int minReplicas, int maxReplicas, Map<String, String> envVars,
                                         int containerPort, String domainUrl) {
        this(minReplicas, maxReplicas, envVars, null, null, containerPort, domainUrl,
                null, null, null, null, null, null, null);
    }

    public UpdateDeploymentConfigRequest(int minReplicas, int maxReplicas, Map<String, String> envVars,
                                         int containerPort, String domainUrl,
                                         BuildStrategy buildStrategy, String imageUriTemplate,
                                         String imagePullSecretName, KubernetesServiceType serviceType,
                                         Integer nodePort) {
        this(minReplicas, maxReplicas, envVars, null, null, containerPort, domainUrl,
                buildStrategy, imageUriTemplate, imagePullSecretName, serviceType, nodePort, null, null);
    }
}
