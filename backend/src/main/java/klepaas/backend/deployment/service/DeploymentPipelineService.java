package klepaas.backend.deployment.service;

import klepaas.backend.deployment.entity.BuildStrategy;
import klepaas.backend.deployment.entity.Deployment;
import klepaas.backend.deployment.repository.DeploymentRepository;
import klepaas.backend.global.exception.BusinessException;
import klepaas.backend.global.exception.ErrorCode;
import klepaas.backend.global.websocket.WebSocketNotificationService;
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

    private final DeploymentPipelineStepService stepService;
    private final DeploymentRepository deploymentRepository;
    private final CloudInfraProviderFactory infraProviderFactory;
    private final WebSocketNotificationService wsNotificationService;

    @Value("${deployment.pipeline.poll-initial-interval:10000}")
    private long pollInitialInterval;

    @Value("${deployment.pipeline.poll-max-interval:60000}")
    private long pollMaxInterval;

    @Value("${deployment.pipeline.build-timeout:1800000}")
    private long buildTimeout;

    /**
     * 비동기 배포 파이프라인 실행.
     * WS 알림은 트랜잭션 외부에서 전송 (각 step 메서드 호출 전/후).
     */
    @Async("deployExecutor")
    public void executePipeline(Long deploymentId) {
        log.info("Pipeline started: deploymentId={}", deploymentId);
        Long userId = deploymentRepository.findUserIdByDeploymentId(deploymentId).orElse(null);

        try {
            BuildStrategy buildStrategy = stepService.getBuildStrategy(deploymentId);
            String imageUri = switch (buildStrategy) {
                case KANIKO -> executeKanikoBuild(deploymentId, userId);
                case GITHUB_ACTIONS_GHCR, PREBUILT_IMAGE -> resolveExternalImage(deploymentId, userId);
            };

            notifyWs(deploymentId, userId, "DEPLOYING", "in_progress", 70, "Kubernetes에 배포 중...");
            stepService.executeK8sDeploy(deploymentId, imageUri);

            stepService.markSuccess(deploymentId);
            notifyWs(deploymentId, userId, "SUCCESS", "completed", 100, "배포가 완료되었습니다.");
            log.info("Pipeline completed successfully: deploymentId={}", deploymentId);

        } catch (Exception e) {
            log.error("Pipeline failed: deploymentId={}, error={}", deploymentId, e.getMessage(), e);
            stepService.markFailed(deploymentId, e.getMessage());
            notifyWs(deploymentId, userId, "FAILED", "failed", 0, "배포 실패: " + e.getMessage());
        }
    }

    private String executeKanikoBuild(Long deploymentId, Long userId) {
        notifyWs(deploymentId, userId, "UPLOADING", "in_progress", 10, "소스 코드 업로드 중...");
        String storageKey = stepService.executeUpload(deploymentId);

        notifyWs(deploymentId, userId, "BUILDING", "in_progress", 30, "컨테이너 이미지 빌드 중...");
        BuildResult buildResult = stepService.executeBuildTrigger(deploymentId, storageKey);
        BuildStatusResult statusResult = pollBuildStatus(deploymentId, buildResult);
        return statusResult.imageUri();
    }

    private String resolveExternalImage(Long deploymentId, Long userId) {
        notifyWs(deploymentId, userId, "BUILDING", "in_progress", 30, "외부 컨테이너 이미지 확인 중...");
        return stepService.resolveExternalImage(deploymentId);
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

    private void notifyWs(Long deploymentId, Long userId, String stage, String status, int progress, String message) {
        try {
            wsNotificationService.sendDeploymentUpdate(deploymentId, userId, stage, status, progress, message);
        } catch (Exception e) {
            log.warn("WebSocket notify failed: deploymentId={}, stage={}", deploymentId, stage, e);
        }
    }
}
