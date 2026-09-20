package klepaas.backend.deployment.service;

import klepaas.backend.deployment.entity.Deployment;
import klepaas.backend.deployment.entity.SourceRepository;
import klepaas.backend.deployment.repository.DeploymentRepository;
import klepaas.backend.deployment.repository.SourceRepositoryRepository;
import klepaas.backend.global.exception.EntityNotFoundException;
import klepaas.backend.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
