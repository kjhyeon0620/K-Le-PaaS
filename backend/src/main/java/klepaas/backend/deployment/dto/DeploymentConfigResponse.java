package klepaas.backend.deployment.dto;

import klepaas.backend.deployment.entity.BuildStrategy;
import klepaas.backend.deployment.entity.DeploymentConfig;
import klepaas.backend.deployment.entity.KubernetesServiceType;

import java.util.List;
import java.util.Map;

public record DeploymentConfigResponse(
        Long id,
        Long repositoryId,
        int minReplicas,
        int maxReplicas,
        Map<String, String> envVars,
        List<String> envFromConfigMaps,
        List<String> envFromSecrets,
        int containerPort,
        String domainUrl,
        BuildStrategy buildStrategy,
        String imageUriTemplate,
        String imagePullSecretName,
        KubernetesServiceType serviceType,
        Integer nodePort
) {
    public DeploymentConfigResponse(Long id, Long repositoryId, int minReplicas, int maxReplicas,
                                    Map<String, String> envVars, int containerPort, String domainUrl) {
        this(id, repositoryId, minReplicas, maxReplicas, envVars, List.of(), List.of(), containerPort, domainUrl,
                BuildStrategy.KANIKO, null, null, KubernetesServiceType.CLUSTER_IP, null);
    }

    public static DeploymentConfigResponse from(DeploymentConfig entity) {
        return new DeploymentConfigResponse(
                entity.getId(),
                entity.getSourceRepository().getId(),
                entity.getMinReplicas(),
                entity.getMaxReplicas(),
                entity.getEnvVars(),
                entity.getEnvFromConfigMaps(),
                entity.getEnvFromSecrets(),
                entity.getContainerPort(),
                entity.getDomainUrl(),
                entity.getBuildStrategy(),
                entity.getImageUriTemplate(),
                entity.getImagePullSecretName(),
                entity.getServiceType(),
                entity.getNodePort()
        );
    }
}
