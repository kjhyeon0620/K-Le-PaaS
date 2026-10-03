package klepaas.backend.deployment.service;

import klepaas.backend.auth.oidc.GitHubActionsIdentity;
import klepaas.backend.deployment.dto.CreateDeploymentRequest;
import klepaas.backend.deployment.entity.Deployment;
import klepaas.backend.deployment.entity.SourceRepository;
import klepaas.backend.deployment.repository.DeploymentRepository;
import klepaas.backend.deployment.repository.SourceRepositoryRepository;
import klepaas.backend.global.exception.BusinessException;
import klepaas.backend.global.exception.EntityNotFoundException;
import klepaas.backend.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ResourceAccessService {
    private final SourceRepositoryRepository repositories;
    private final DeploymentRepository deployments;

    public SourceRepository requireRepository(Long repositoryId, Long userId) {
        if (repositoryId == null || userId == null) {
            throw new EntityNotFoundException(ErrorCode.REPOSITORY_NOT_FOUND);
        }
        SourceRepository repository = repositories.findById(repositoryId)
                .orElseThrow(() -> new EntityNotFoundException(ErrorCode.REPOSITORY_NOT_FOUND));
        if (repository.getUser() == null || !userId.equals(repository.getUser().getId())) {
            throw new EntityNotFoundException(ErrorCode.REPOSITORY_NOT_FOUND);
        }
        return repository;
    }

    /**
     * GitHub Actions OIDC 주체의 배포 요청이 토큰과 맞으면 대상 저장소 소유자 ID를 돌려준다.
     * 같은 GitHub 저장소를 여러 사용자가 등록할 수 있으므로 요청의 repository_id로 저장소를 정하고 claim과 대조한다.
     * 저장소가 없을 때도 같은 403으로 거절해 다른 사용자 저장소의 존재를 드러내지 않는다.
     */
    public Long requireGitHubActionsOwner(GitHubActionsIdentity identity, CreateDeploymentRequest request) {
        SourceRepository repository = request.repositoryId() == null
                ? null : repositories.findById(request.repositoryId()).orElse(null);
        boolean matches = repository != null && repository.getUser() != null
                && (repository.getOwner() + "/" + repository.getRepoName()).equalsIgnoreCase(identity.repository())
                && identity.branch().equals(request.branchName())
                && identity.sha().toLowerCase(Locale.ROOT).startsWith(request.commitHash().toLowerCase(Locale.ROOT));
        if (!matches) {
            throw new BusinessException(ErrorCode.CLI_TOKEN_SCOPE_DENIED);
        }
        return repository.getUser().getId();
    }

    public Deployment requireDeployment(Long deploymentId, Long userId) {
        if (deploymentId == null || userId == null) {
            throw new EntityNotFoundException(ErrorCode.DEPLOYMENT_NOT_FOUND);
        }
        Deployment deployment = deployments.findById(deploymentId)
                .orElseThrow(() -> new EntityNotFoundException(ErrorCode.DEPLOYMENT_NOT_FOUND));
        SourceRepository repository = deployment.getSourceRepository();
        if (repository == null || repository.getUser() == null
                || !userId.equals(repository.getUser().getId())) {
            throw new EntityNotFoundException(ErrorCode.DEPLOYMENT_NOT_FOUND);
        }
        return deployment;
    }
}
