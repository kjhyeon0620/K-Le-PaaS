package klepaas.backend.deployment.service;

import klepaas.backend.auth.config.GitHubAppConfig;
import klepaas.backend.auth.oauth.GitHubAppClient;
import klepaas.backend.deployment.dto.*;
import klepaas.backend.deployment.entity.BuildStrategy;
import klepaas.backend.deployment.entity.CloudVendor;
import klepaas.backend.deployment.entity.DeploymentConfig;
import klepaas.backend.deployment.entity.SourceRepository;
import klepaas.backend.deployment.repository.DeploymentConfigRepository;
import klepaas.backend.deployment.repository.SourceRepositoryRepository;
import klepaas.backend.global.exception.DuplicateResourceException;
import klepaas.backend.global.exception.EntityNotFoundException;
import klepaas.backend.global.exception.ErrorCode;
import klepaas.backend.global.exception.GitHubAppInstallationRequiredException;
import klepaas.backend.global.exception.GitHubAppNotInstalledException;
import klepaas.backend.user.entity.User;
import klepaas.backend.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RepositoryService {

    private final SourceRepositoryRepository sourceRepositoryRepository;
    private final DeploymentConfigRepository deploymentConfigRepository;
    private final UserRepository userRepository;
    private final GitHubAppClient gitHubAppClient;
    private final GitHubAppConfig gitHubAppConfig;

    @Transactional
    public RepositoryResponse createRepository(Long userId, CreateRepositoryRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException(ErrorCode.USER_NOT_FOUND));

        sourceRepositoryRepository.findByOwnerAndRepoName(request.owner(), request.repoName())
                .ifPresent(r -> {
                    throw new DuplicateResourceException(ErrorCode.REPOSITORY_ALREADY_EXISTS);
                });

        checkGitHubAppInstalled(request.owner(), request.repoName());

        SourceRepository repository = SourceRepository.builder()
                .user(user)
                .owner(request.owner())
                .repoName(request.repoName())
                .gitUrl(request.gitUrl())
                .cloudVendor(request.cloudVendor())
                .build();
        sourceRepositoryRepository.save(repository);

        DeploymentConfig defaultConfig = DeploymentConfig.builder()
                .sourceRepository(repository)
                .minReplicas(1)
                .maxReplicas(1)
                .envVars(new HashMap<>())
                .containerPort(8080)
                .domainUrl(request.repoName() + ".klepaas.io")
                .buildStrategy(defaultBuildStrategy(request.cloudVendor()))
                .build();
        deploymentConfigRepository.save(defaultConfig);

        log.info("Repository created: {}/{} (id={})", request.owner(), request.repoName(), repository.getId());
        return RepositoryResponse.from(repository);
    }

    private void checkGitHubAppInstalled(String owner, String repoName) {
        try {
            gitHubAppClient.getInstallationId(owner, repoName);
        } catch (GitHubAppNotInstalledException e) {
            String installUrl = "https://github.com/apps/" + gitHubAppConfig.getAppSlug() + "/installations/new";
            throw new GitHubAppInstallationRequiredException(owner, repoName, installUrl);
        }
    }

    public List<RepositoryResponse> getRepositories(Long userId) {
        return sourceRepositoryRepository.findAllByUserId(userId).stream()
                .map(RepositoryResponse::from)
                .toList();
    }

    public RepositoryResponse getRepository(Long repositoryId) {
        SourceRepository repository = sourceRepositoryRepository.findById(repositoryId)
                .orElseThrow(() -> new EntityNotFoundException(ErrorCode.REPOSITORY_NOT_FOUND));
        return RepositoryResponse.from(repository);
    }

    @Transactional
    public void deleteRepository(Long repositoryId) {
        SourceRepository repository = sourceRepositoryRepository.findById(repositoryId)
                .orElseThrow(() -> new EntityNotFoundException(ErrorCode.REPOSITORY_NOT_FOUND));

        deploymentConfigRepository.findBySourceRepositoryId(repositoryId)
                .ifPresent(deploymentConfigRepository::delete);

        sourceRepositoryRepository.delete(repository);
        log.info("Repository deleted: id={}", repositoryId);
    }

    public DeploymentConfigResponse getDeploymentConfig(Long repositoryId) {
        sourceRepositoryRepository.findById(repositoryId)
                .orElseThrow(() -> new EntityNotFoundException(ErrorCode.REPOSITORY_NOT_FOUND));

        DeploymentConfig config = deploymentConfigRepository.findBySourceRepositoryId(repositoryId)
                .orElseThrow(() -> new EntityNotFoundException(ErrorCode.DEPLOYMENT_CONFIG_NOT_FOUND));
        return DeploymentConfigResponse.from(config);
    }

    @Transactional
    public DeploymentConfigResponse updateDeploymentConfig(Long repositoryId,
                                                            UpdateDeploymentConfigRequest request) {
        sourceRepositoryRepository.findById(repositoryId)
                .orElseThrow(() -> new EntityNotFoundException(ErrorCode.REPOSITORY_NOT_FOUND));

        DeploymentConfig config = deploymentConfigRepository.findBySourceRepositoryId(repositoryId)
                .orElseThrow(() -> new EntityNotFoundException(ErrorCode.DEPLOYMENT_CONFIG_NOT_FOUND));

        config.updateConfig(
                request.minReplicas(),
                request.maxReplicas(),
                request.envVars(),
                request.containerPort(),
                request.domainUrl(),
                request.buildStrategy(),
                request.imageUriTemplate(),
                request.imagePullSecretName()
        );

        log.info("DeploymentConfig updated: repositoryId={}", repositoryId);
        return DeploymentConfigResponse.from(config);
    }

    private BuildStrategy defaultBuildStrategy(CloudVendor cloudVendor) {
        return switch (cloudVendor) {
            case NCP, AWS -> BuildStrategy.KANIKO;
            case ON_PREMISE -> BuildStrategy.GITHUB_ACTIONS_GHCR;
        };
    }

}
