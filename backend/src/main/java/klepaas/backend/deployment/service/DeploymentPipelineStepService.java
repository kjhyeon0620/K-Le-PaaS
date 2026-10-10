package klepaas.backend.deployment.service;

import klepaas.backend.auth.service.GitHubInstallationTokenService;
import klepaas.backend.deployment.entity.BuildStrategy;
import klepaas.backend.deployment.entity.Deployment;
import klepaas.backend.deployment.entity.DeploymentConfig;
import klepaas.backend.deployment.entity.FailureKind;
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
import klepaas.backend.infra.kubernetes.RolloutResult;
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
    private final ExternalImageResolver externalImageResolver;
    private final DeploymentConfigSnapshots configSnapshots;

    @Transactional(readOnly = true)
    public BuildStrategy getBuildStrategy(Long deploymentId) {
        Deployment deployment = getDeployment(deploymentId);
        DeploymentConfig config = deploymentConfigRepository.findBySourceRepositoryId(
                        deployment.getSourceRepository().getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.DEPLOYMENT_CONFIG_NOT_FOUND));
        return config.getBuildStrategy();
    }

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
    public String resolveExternalImage(Long deploymentId) {
        Deployment deployment = getDeployment(deploymentId);
        DeploymentConfig config = deploymentConfigRepository.findBySourceRepositoryId(
                        deployment.getSourceRepository().getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.DEPLOYMENT_CONFIG_NOT_FOUND));

        String imageUri = externalImageResolver.resolve(deployment, config);
        deployment.markAsBuilding("external-image");
        deployment.setImageUri(imageUri);
        deploymentRepository.save(deployment);

        log.info("External image resolved: deploymentId={}, imageUri={}", deploymentId, imageUri);
        return imageUri;
    }

    /**
     * apply 전에 DEPLOYING, 배포할 이미지, 적용할 배포 설정 스냅샷(#95)을 커밋한다.
     * 이후 apply·대기가 실패하거나 백엔드가 멈춰도 이 요청이 무엇을 배포하려 했는지 남는다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void startK8sDeploy(Long deploymentId, String imageUri) {
        Deployment deployment = getDeployment(deploymentId);
        deployment.startDeploying();
        deployment.setImageUri(imageUri);
        deploymentConfigRepository.findBySourceRepositoryId(deployment.getSourceRepository().getId())
                .ifPresent(config -> deployment.recordAppliedConfig(
                        configSnapshots.toJson(configSnapshots.capture(config))));
        deploymentRepository.save(deployment);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AppliedRollout applyK8sManifests(Long deploymentId, String imageUri) {
        Deployment deployment = getDeployment(deploymentId);
        SourceRepository repo = deployment.getSourceRepository();
        String appName = repo.getOwner() + "-" + repo.getRepoName();

        DeploymentConfig config = deploymentConfigRepository.findBySourceRepositoryId(repo.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.DEPLOYMENT_CONFIG_NOT_FOUND));

        var applied = k8sGenerator.deploy(appName, imageUri, config, repo.getId(), deploymentId);
        deployment.recordAppliedResource(applied.resourceUid(), applied.generation());
        deploymentRepository.save(deployment);
        log.info("K8s manifests applied: deploymentId={}, app={}, generation={}", deploymentId, appName, applied.generation());
        return new AppliedRollout(appName, applied.generation());
    }

    // 트랜잭션 밖에서 기다린다 (최대 rollout 타임아웃 동안 DB 연결을 잡지 않는다)
    public RolloutResult awaitK8sRollout(AppliedRollout applied) {
        return k8sGenerator.waitForRollout(applied.appName(), applied.generation());
    }

    public record AppliedRollout(String appName, long generation) {
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markSuccess(Long deploymentId) {
        markSuccess(deploymentId, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markSuccess(Long deploymentId, String imageDigest) {
        Deployment deployment = getDeployment(deploymentId);
        notificationService.notifyDeploymentSuccess(deployment);
        deployment.completeSuccess(imageDigest);
        deploymentRepository.save(deployment);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markCanceled(Long deploymentId, String reason) {
        Deployment deployment = getDeployment(deploymentId);
        notificationService.notifyDeploymentFailed(deployment, reason);
        deployment.cancel(reason);
        deploymentRepository.save(deployment);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(Long deploymentId, String reason) {
        markFailed(deploymentId, reason, FailureKind.OTHER);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(Long deploymentId, String reason, FailureKind failureKind) {
        try {
            Deployment deployment = getDeployment(deploymentId);
            notificationService.notifyDeploymentFailed(deployment, reason);
            deployment.fail(reason, failureKind);
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
