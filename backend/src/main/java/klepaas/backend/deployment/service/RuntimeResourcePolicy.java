package klepaas.backend.deployment.service;

import klepaas.backend.deployment.entity.DeploymentConfig;
import klepaas.backend.deployment.entity.SourceRepository;
import klepaas.backend.global.exception.BusinessException;
import klepaas.backend.global.exception.ErrorCode;
import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "kubernetes.runtime-resources")
public class RuntimeResourcePolicy {
    private Map<Long, AllowedReferences> repositories = Map.of();

    @Value("${kubernetes.image-pull-secret:ncp-cr}")
    private String defaultImagePullSecretName = "ncp-cr";

    @Getter
    @Setter
    public static class AllowedReferences {
        private List<String> configMaps = List.of();
        private List<String> secrets = List.of();
        private List<String> imagePullSecrets = List.of();
    }

    public void validateReferences(SourceRepository repository, DeploymentConfig config) {
        validateReferences(repository, config.getEnvFromConfigMaps(), config.getEnvFromSecrets(),
                resolveImagePullSecretName(config));
    }

    public String resolveImagePullSecretName(DeploymentConfig config) {
        String name = config.getImagePullSecretName();
        return name != null && !name.isBlank() ? name : defaultImagePullSecretName;
    }

    public void validateReferences(SourceRepository repository, List<String> configMaps,
                                   List<String> secrets, String imagePullSecretName) {
        AllowedReferences allowed = repository == null || repository.getId() == null
                ? null : repositories.get(repository.getId());
        if ((configMaps != null && !configMaps.isEmpty()
                    && (allowed == null || !allowed.getConfigMaps().containsAll(configMaps)))
                || (secrets != null && !secrets.isEmpty()
                    && (allowed == null || !allowed.getSecrets().containsAll(secrets)))
                || (imagePullSecretName != null && !imagePullSecretName.isBlank()
                    && (allowed == null || !allowed.getImagePullSecrets().contains(imagePullSecretName)))) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "허용되지 않은 Kubernetes 리소스 참조입니다");
        }
    }
}
