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
import klepaas.backend.infra.dto.BuildStatusResult;
import klepaas.backend.infra.kubernetes.KubernetesManifestGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeploymentPipelineService {

    private final DeploymentRepository deploymentRepository;
    private final SourceRepositoryRepository sourceRepositoryRepository;
    private final DeploymentConfigRepository deploymentConfigRepository;
    private final CloudInfraProviderFactory infraProviderFactory;
    private final KubernetesManifestGenerator k8sGenerator;
    private final GitHubInstallationTokenService installationTokenService;
    private final NotificationService notificationService;

    @Value("${deployment.pipeline.poll-initial-interval:10000}")
    private long pollInitialInterval;

    @Value("${deployment.pipeline.poll-max-interval:60000}")
    private long pollMaxInterval;

    @Value("${deployment.pipeline.build-timeout:1800000}")
    private long buildTimeout;

    /**
     * 비동기 배포 파이프라인 실행.
     * 새 스레드에서 실행되므로 Controller 트랜잭션과 분리됨.
     */
    @Async("deployExecutor")
    public void executePipeline(Long deploymentId) {
        log.info("Pipeline started: deploymentId={}", deploymentId);

        try {
            // 1. 소스 업로드
            String storageKey = stepService.executeUpload(deploymentId);

            // 2. 빌드 트리거
            BuildResult buildResult = stepService.executeBuildTrigger(deploymentId, storageKey);

            // 3. 빌드 폴링
            BuildStatusResult statusResult = pollBuildStatus(deploymentId, buildResult);

            // 4. K8s 배포
            executeK8sDeploy(deploymentId, statusResult.imageUri());

            // 5. 성공 처리
            markSuccess(deploymentId);
            log.info("Pipeline completed successfully: deploymentId={}", deploymentId);

        } catch (Exception e) {
            log.error("Pipeline failed: deploymentId={}, error={}", deploymentId, e.getMessage(), e);
            markFailed(deploymentId, e.getMessage());
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    protected String executeUpload(Long deploymentId) {
        Deployment deployment = getDeployment(deploymentId);
        notificationService.notifyDeploymentStarted(deployment);
        deployment.startUpload();
        deploymentRepository.save(deployment);

        SourceRepository repo = deployment.getSourceRepository();
        String installationToken = installationTokenService.getInstallationToken(
                repo.getOwner(), repo.getRepoName());

        CloudInfraProvider provider = infraProviderFactory.getProvider(
                repo.getCloudVendor());

        String storageKey = provider.uploadSourceToStorage(installationToken, deployment);
        deployment.markAsUploaded(storageKey);
        deploymentRepository.save(deployment);

        log.info("Upload completed: deploymentId={}, storageKey={}", deploymentId, storageKey);
        return storageKey;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    protected BuildResult executeBuildTrigger(Long deploymentId, String storageKey) {
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

    private BuildStatusResult pollBuildStatus(Long deploymentId, BuildResult buildResult) {
        Deployment deployment = getDeployment(deploymentId);
        CloudInfraProvider provider = infraProviderFactory.getProvider(
                deployment.getSourceRepository().getCloudVendor());

        long interval = pollInitialInterval;
        long elapsed = 0;

        while (elapsed < buildTimeout) {
            try {
                Thread.sleep(interval);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BusinessException(ErrorCode.BUILD_FAILED, "빌드 폴링 중단됨");
            }

            elapsed += interval;
            BuildStatusResult status = provider.getBuildStatus(buildResult.trackingUrl(), buildResult.externalBuildId());

            log.debug("Build polling: deploymentId={}, elapsed={}ms, status={}", deploymentId, elapsed, status.message());

            if (status.completed()) {
                if (status.success()) {
                    // imageUri는 API 응답에 없으므로 triggerBuild 시점에 계산된 buildResult.imageUri() 사용
                    log.info("Build succeeded: deploymentId={}, imageUri={}", deploymentId, buildResult.imageUri());
                    return new BuildStatusResult(true, true, buildResult.imageUri(), status.message());
                }
                throw new BusinessException(ErrorCode.BUILD_FAILED, "빌드 실패: " + status.message());
            }

            // Exponential backoff: 10s → 20s → 40s → 60s (cap)
            interval = Math.min(interval * 2, pollMaxInterval);
        }

        throw new BusinessException(ErrorCode.BUILD_TIMEOUT, "빌드 타임아웃: " + buildTimeout + "ms 초과");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    protected void executeK8sDeploy(Long deploymentId, String imageUri) {
        Deployment deployment = getDeployment(deploymentId);
        deployment.startDeploying();
        deploymentRepository.save(deployment);

        SourceRepository repo = deployment.getSourceRepository();
        String appName = repo.getOwner() + "-" + repo.getRepoName();

        DeploymentConfig config = deploymentConfigRepository.findBySourceRepositoryId(repo.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.DEPLOYMENT_CONFIG_NOT_FOUND));

        k8sGenerator.deploy(appName, imageUri, config, repo.getId());
        log.info("K8s deploy completed: deploymentId={}, app={}", deploymentId, appName);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    protected void markSuccess(Long deploymentId) {
        Deployment deployment = getDeployment(deploymentId);
        notificationService.notifyDeploymentSuccess(deployment);
        deployment.completeSuccess();
        deploymentRepository.save(deployment);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    protected void markFailed(Long deploymentId, String reason) {
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
