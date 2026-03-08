package klepaas.backend.deployment.service;

import klepaas.backend.deployment.entity.Deployment;
import klepaas.backend.deployment.repository.DeploymentRepository;
import klepaas.backend.global.exception.BusinessException;
import klepaas.backend.global.exception.ErrorCode;
import klepaas.backend.infra.CloudInfraProvider;
import klepaas.backend.infra.CloudInfraProviderFactory;
import klepaas.backend.infra.dto.BuildResult;
import klepaas.backend.infra.dto.BuildStatusResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeploymentPipelineService {

    private final DeploymentRepository deploymentRepository;
    private final CloudInfraProviderFactory infraProviderFactory;
    private final DeploymentPipelineStepService stepService;

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
            stepService.executeK8sDeploy(deploymentId, statusResult.imageUri());

            // 5. 성공 처리
            stepService.markSuccess(deploymentId);
            log.info("Pipeline completed successfully: deploymentId={}", deploymentId);

        } catch (Exception e) {
            log.error("Pipeline failed: deploymentId={}, error={}", deploymentId, e.getMessage(), e);
            stepService.markFailed(deploymentId, e.getMessage());
        }
    }

    private BuildStatusResult pollBuildStatus(Long deploymentId, BuildResult buildResult) {
        Deployment deployment = deploymentRepository.findById(deploymentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.DEPLOYMENT_NOT_FOUND));
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
}
