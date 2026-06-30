package klepaas.backend.deployment.service;

import klepaas.backend.deployment.entity.Deployment;
import klepaas.backend.deployment.entity.DeploymentConfig;
import klepaas.backend.deployment.entity.SourceRepository;
import klepaas.backend.global.exception.BusinessException;
import klepaas.backend.global.exception.ErrorCode;
import org.springframework.stereotype.Component;

@Component
public class ExternalImageResolver {

    public String resolve(Deployment deployment, DeploymentConfig config) {
        if (hasText(deployment.getImageUri())) {
            return deployment.getImageUri();
        }
        if ("HEAD".equals(deployment.getCommitHash())) {
            throw new BusinessException(
                    ErrorCode.INVALID_REQUEST,
                    "외부 이미지 배포는 HEAD 대신 확정 commitHash 또는 imageUri가 필요합니다"
            );
        }

        String template = config.getImageUriTemplate();
        if (!hasText(template)) {
            throw new BusinessException(
                    ErrorCode.INVALID_REQUEST,
                    "외부 이미지 배포는 imageUri 또는 imageUriTemplate 설정이 필요합니다"
            );
        }

        SourceRepository repository = deployment.getSourceRepository();
        String commitHash = deployment.getCommitHash();
        String shortCommitHash = commitHash.substring(0, Math.min(7, commitHash.length()));

        return template
                .replace("{owner}", repository.getOwner().toLowerCase())
                .replace("{repoName}", repository.getRepoName())
                .replace("{commitHash}", commitHash)
                .replace("{shortCommitHash}", shortCommitHash);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
