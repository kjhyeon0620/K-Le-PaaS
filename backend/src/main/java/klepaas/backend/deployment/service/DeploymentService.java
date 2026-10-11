package klepaas.backend.deployment.service;

import klepaas.backend.deployment.dto.*;
import klepaas.backend.deployment.entity.Deployment;
import klepaas.backend.deployment.entity.DeploymentStatus;
import klepaas.backend.deployment.entity.ScalingHistory;
import klepaas.backend.deployment.entity.SourceRepository;
import klepaas.backend.deployment.repository.DeploymentConfigRepository;
import klepaas.backend.deployment.repository.DeploymentRepository;
import klepaas.backend.deployment.repository.ScalingHistoryRepository;
import klepaas.backend.deployment.repository.SourceRepositoryRepository;
import klepaas.backend.global.exception.BusinessException;
import klepaas.backend.global.exception.ErrorCode;
import klepaas.backend.infra.CloudInfraProviderFactory;
import klepaas.backend.infra.kubernetes.DeploymentObservationReader;
import klepaas.backend.infra.kubernetes.KubernetesManifestGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DeploymentService {

    private final DeploymentRepository deploymentRepository;
    private final SourceRepositoryRepository sourceRepositoryRepository;
    private final DeploymentConfigRepository deploymentConfigRepository;
    private final ScalingHistoryRepository scalingHistoryRepository;
    private final DeploymentPipelineService pipelineService;
    private final CloudInfraProviderFactory infraProviderFactory;
    private final KubernetesManifestGenerator k8sGenerator;
    private final ResourceAccessService resourceAccessService;
    private final RuntimeResourcePolicy runtimeResourcePolicy;
    private final DeploymentPipelineStepService pipelineStepService;
    private final DeploymentObservationReader observationReader;

    public static final int MAX_LOG_LINES = 200;

    private static final List<DeploymentStatus> IN_PROGRESS_STATUSES = List.of(
            DeploymentStatus.PENDING, DeploymentStatus.UPLOADING_SOURCE,
            DeploymentStatus.BUILDING, DeploymentStatus.DEPLOYING);

    @Value("${deployment.pipeline.build-timeout:1800000}")
    private long buildTimeoutMs;

    @Value("${kubernetes.rollout.timeout-ms:120000}")
    private long rolloutTimeoutMs;


    /**
     * 같은 저장소의 배포는 하나씩만 진행한다. 진행 중 배포와 같은 요청(재시도)이면 그 배포를,
     * 이미 배포를 만든 webhook delivery면 그 배포를 돌려주고, 다른 요청이면 DEPLOYMENT_IN_PROGRESS로 거절한다.
     */
    @Transactional
    public CreateDeploymentResult createDeployment(CreateDeploymentRequest request, Long userId,
                                                   String githubDeliveryId, DeploymentOrigin origin) {
        SourceRepository repository = resourceAccessService.requireRepository(request.repositoryId(), userId);
        sourceRepositoryRepository.findByIdForUpdate(repository.getId());

        if (githubDeliveryId != null) {
            Optional<Deployment> delivered = deploymentRepository.findByGithubDeliveryId(githubDeliveryId);
            if (delivered.isPresent()) {
                return new CreateDeploymentResult(DeploymentResponse.from(delivered.get()), false);
            }
        }
        String imageUri = StringUtils.hasText(request.imageUri()) ? request.imageUri() : null;
        Optional<Deployment> inProgress = findInProgress(repository.getId());
        if (inProgress.isPresent()) {
            Deployment active = inProgress.get();
            if (isSameRequest(active, request, imageUri)) {
                return new CreateDeploymentResult(DeploymentResponse.from(active), false);
            }
            throw new BusinessException(ErrorCode.DEPLOYMENT_IN_PROGRESS,
                    "같은 저장소의 배포가 진행 중입니다: deployment_id=" + active.getId());
        }

        deploymentConfigRepository.findBySourceRepositoryId(repository.getId())
                .ifPresent(config -> runtimeResourcePolicy.validateReferences(repository, config));

        Deployment deployment = Deployment.builder()
                .sourceRepository(repository)
                .branchName(request.branchName())
                .commitHash(request.commitHash())
                .build();
        if (imageUri != null) {
            deployment.setImageUri(imageUri);
        }
        deployment.setGithubDeliveryId(githubDeliveryId);
        deployment.recordRequest(origin.source(), origin.requestedByUserId(), origin.commandLogId());
        deployment.pinApprovedConfig(origin.approvedConfigFingerprint());
        deploymentRepository.save(deployment);

        log.info("Deployment created: id={}, repo={}/{}, branch={}", deployment.getId(),
                repository.getOwner(), repository.getRepoName(), request.branchName());

        Long deploymentId = deployment.getId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    pipelineService.executePipeline(deploymentId);
                } catch (TaskRejectedException e) {
                    // 실행되지 않은 배포가 PENDING으로 남아 저장소를 막지 않게 한다
                    log.error("Deployment pipeline rejected: deploymentId={}", deploymentId, e);
                    pipelineStepService.markFailed(deploymentId, "배포 실행 대기열이 가득 찼습니다");
                }
            }
        });

        return new CreateDeploymentResult(DeploymentResponse.from(deployment), true);
    }

    // 마지막 갱신 후 파이프라인 최대 시간(빌드 + rollout + 여유)이 지난 미완료 배포는 재시작 등으로 멈춘 것으로 보고
    // 막지 않는다. 이전 프로세스의 미완료 배포는 기동 시 재시작 대조(#57)가 정리한다.
    private Optional<Deployment> findInProgress(Long repositoryId) {
        Duration window = Duration.ofMillis(buildTimeoutMs + rolloutTimeoutMs).plusMinutes(10);
        return deploymentRepository.findFirstBySourceRepositoryIdAndStatusInAndUpdatedAtAfterOrderByIdDesc(
                repositoryId, IN_PROGRESS_STATUSES, LocalDateTime.now().minus(window));
    }

    // image_uri가 없는 요청(webhook, 콘솔)은 branch·commit만 비교한다. 진행 중 배포의 image_uri는 빌드 후에 채워진다
    private boolean isSameRequest(Deployment active, CreateDeploymentRequest request, String imageUri) {
        return Objects.equals(active.getBranchName(), request.branchName())
                && Objects.equals(active.getCommitHash(), request.commitHash())
                && (imageUri == null || imageUri.equals(active.getImageUri()));
    }

    public Page<DeploymentResponse> getDeployments(Long repositoryId, Pageable pageable, Long userId) {
        resourceAccessService.requireRepository(repositoryId, userId);
        return deploymentRepository.findBySourceRepositoryId(repositoryId, pageable)
                .map(DeploymentResponse::from);
    }

    public DeploymentResponse getDeployment(Long deploymentId, Long userId) {
        Deployment deployment = resourceAccessService.requireDeployment(deploymentId, userId);
        return DeploymentResponse.from(deployment);
    }

    public DeploymentStatusResponse getDeploymentStatus(Long deploymentId, Long userId) {
        Deployment deployment = resourceAccessService.requireDeployment(deploymentId, userId);
        return DeploymentStatusResponse.from(deployment);
    }

    // 소유권 검사가 먼저다. 다른 사용자 배포면 Kubernetes를 호출하지 않는다
    public DeploymentLogResponse getDeploymentLogs(Long deploymentId, int lines, Long userId) {
        if (lines < 1 || lines > MAX_LOG_LINES) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "lines는 1~" + MAX_LOG_LINES + " 사이여야 합니다");
        }
        Deployment deployment = resourceAccessService.requireDeployment(deploymentId, userId);
        SourceRepository repo = deployment.getSourceRepository();
        String appName = repo.getOwner() + "-" + repo.getRepoName();
        return DeploymentLogResponse.of(deployment,
                observationReader.observe(appName, repo.getId(), deploymentId, lines));
    }

    @Transactional
    public void scaleDeployment(Long deploymentId, ScaleRequest request, Long userId) {
        scaleDeployment(deploymentId, request, "USER", userId);
    }

    /**
     * @return 변경 전 실제 replica 수
     */
    @Transactional
    public int scaleDeployment(Long deploymentId, ScaleRequest request, String triggeredBy, Long userId) {
        // REST의 @Valid를 거치지 않는 NLP 경로도 같은 규칙을 적용한다
        if (request.replicas() < 1) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "replicas는 1 이상이어야 합니다");
        }
        Deployment deployment = resourceAccessService.requireDeployment(deploymentId, userId);

        SourceRepository repo = deployment.getSourceRepository();
        String appName = repo.getOwner() + "-" + repo.getRepoName();

        int previousReplicas = k8sGenerator.scale(appName, request.replicas(), repo.getId());

        scalingHistoryRepository.save(
                ScalingHistory.builder()
                        .deployment(deployment)
                        .previousReplicas(previousReplicas)
                        .newReplicas(request.replicas())
                        .triggeredBy(triggeredBy)
                        .build()
        );

        log.info("Scale completed: deploymentId={}, previousReplicas={}, newReplicas={}, triggeredBy={}",
                deploymentId, previousReplicas, request.replicas(), triggeredBy);
        return previousReplicas;
    }

    // 소유권 검사가 먼저다. 다른 사용자 저장소면 Kubernetes를 호출하지 않는다
    public ReplicaStatusResponse getReplicaStatus(Long repositoryId, Long userId) {
        SourceRepository repo = resourceAccessService.requireRepository(repositoryId, userId);
        return observationReader.readReplicas(repo.getOwner() + "-" + repo.getRepoName(), repo.getId());
    }

    public Page<ScalingHistoryResponse> getScalingHistory(Long repositoryId, Pageable pageable, Long userId) {
        resourceAccessService.requireRepository(repositoryId, userId);
        return scalingHistoryRepository.findByDeploymentSourceRepositoryIdOrderByCreatedAtDesc(repositoryId, pageable)
                .map(ScalingHistoryResponse::from);
    }

    @Transactional
    public void restartDeployment(Long deploymentId, Long userId) {
        Deployment deployment = resourceAccessService.requireDeployment(deploymentId, userId);

        SourceRepository repo = deployment.getSourceRepository();
        String appName = repo.getOwner() + "-" + repo.getRepoName();
        k8sGenerator.restart(appName, repo.getId());

        log.info("Restart completed: deploymentId={}", deploymentId);
    }
}
