package klepaas.backend.deployment.service;

import klepaas.backend.auth.service.GitHubInstallationTokenService;
import klepaas.backend.deployment.entity.Deployment;
import klepaas.backend.deployment.entity.DeploymentConfig;
import klepaas.backend.deployment.entity.SourceRepository;
import klepaas.backend.deployment.repository.DeploymentConfigRepository;
import klepaas.backend.deployment.repository.DeploymentRepository;
import klepaas.backend.deployment.repository.SourceRepositoryRepository;
import klepaas.backend.global.exception.BusinessException;
import klepaas.backend.global.exception.ErrorCode;
import klepaas.backend.global.service.NotificationService;
import klepaas.backend.infra.CloudInfraProvider;
import klepaas.backend.infra.CloudInfraProviderFactory;
import klepaas.backend.infra.dto.BuildResult;
import klepaas.backend.infra.kubernetes.KubernetesManifestGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeploymentPipelineStepService {

    private final DeploymentRepository deploymentRepository;
    private final SourceRepositoryRepository sourceRepositoryRepository;
    private final DeploymentConfigRepository deploymentConfigRepository;
    private final CloudInfraProviderFactory infraProviderFactory;
    private final KubernetesManifestGenerator k8sGenerator;
    private final GitHubInstallationTokenService installationTokenService;
    private final NotificationService notificationService;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String executeUpload(Long deploymentId) {
        Deployment deployment = getDeployment(deploymentId);
        notificationService.notifyDeploymentStarted(deployment);
        deployment.startUpload();
        deploymentRepository.save(deployment);

        SourceRepository repo = deployment.getSourceRepository();
        String installationToken = installationTokenService.getInstallationToken(
                repo.getOwner(), repo.getRepoName());

        CloudInfraProvider provider = infraProviderFactory.getProvider(repo.getCloudVendor());

        String storageKey = provider.uploadSourceToStorage(installationToken, deployment);
        deployment.markAsUploaded(storageKey);
        deploymentRepository.save(deployment);

        log.info("Upload completed: deploymentId={}, storageKey={}", deploymentId, storageKey);
        return storageKey;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BuildResult executeBuildTrigger(Long deploymentId, String storageKey) {
        Deployment deployment = getDeployment(deploymentId);
        CloudInfraProvider provider = infraProviderFactory.getProvider(
                deployment.getSourceRepository().getCloudVendor());

        BuildResult buildResult = provider.triggerBuild(storageKey, deployment);
        deployment.markAsBuilding(buildResult.externalBuildId());
        deploymentRepository.save(deployment);

        // SourceRepository에 projectId 캐싱 (triggerBuild에서 설정됨)
        sourceRepositoryRepository.save(deployment.getSourceRepository());

        log.info("Build triggered: deploymentId={}, buildId={}", deploymentId, buildResult.externalBuildId());
        return buildResult;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void executeK8sDeploy(Long deploymentId, String imageUri) {
        Deployment deployment = getDeployment(deploymentId);
        deployment.startDeploying();
        deployment.setImageUri(imageUri);
        deploymentRepository.save(deployment);

        SourceRepository repo = deployment.getSourceRepository();
        String appName = repo.getOwner() + "-" + repo.getRepoName();

        DeploymentConfig config = deploymentConfigRepository.findBySourceRepositoryId(repo.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.DEPLOYMENT_CONFIG_NOT_FOUND));

        k8sGenerator.deploy(appName, imageUri, config, repo.getId());
        log.info("K8s deploy completed: deploymentId={}, app={}", deploymentId, appName);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markSuccess(Long deploymentId) {
        Deployment deployment = getDeployment(deploymentId);
        notificationService.notifyDeploymentSuccess(deployment);
        deployment.completeSuccess();
        deploymentRepository.save(deployment);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(Long deploymentId, String reason) {
        try {
            Deployment deployment = getDeployment(deploymentId);
            notificationService.notifyDeploymentFailed(deployment, reason);
            deployment.fail(reason);
            deploymentRepository.save(deployment);
        } catch (Exception e) {
            log.error("Failed to mark deployment as failed: deploymentId={}", deploymentId, e);
        }
    }

    private Deployment getDeployment(Long deploymentId) {
        return deploymentRepository.findById(deploymentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.DEPLOYMENT_NOT_FOUND));
    }
}
