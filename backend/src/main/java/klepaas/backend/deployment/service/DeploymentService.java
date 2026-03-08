package klepaas.backend.deployment.service;

import klepaas.backend.deployment.dto.*;
import klepaas.backend.deployment.entity.Deployment;
import klepaas.backend.deployment.entity.DeploymentConfig;
import klepaas.backend.deployment.entity.ScalingHistory;
import klepaas.backend.deployment.entity.SourceRepository;
import klepaas.backend.deployment.repository.DeploymentConfigRepository;
import klepaas.backend.deployment.repository.DeploymentRepository;
import klepaas.backend.deployment.repository.ScalingHistoryRepository;
import klepaas.backend.deployment.repository.SourceRepositoryRepository;
import klepaas.backend.global.exception.EntityNotFoundException;
import klepaas.backend.global.exception.ErrorCode;
import klepaas.backend.infra.CloudInfraProviderFactory;
import klepaas.backend.infra.kubernetes.KubernetesManifestGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

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

    @Transactional
    public DeploymentResponse createDeployment(CreateDeploymentRequest request) {
        return createDeployment(request, null);
    }

    @Transactional
    public DeploymentResponse createDeployment(CreateDeploymentRequest request, Long userId) {
        SourceRepository repository = sourceRepositoryRepository.findById(request.repositoryId())
                .orElseThrow(() -> new EntityNotFoundException(ErrorCode.REPOSITORY_NOT_FOUND));

        Deployment deployment = Deployment.builder()
                .sourceRepository(repository)
                .branchName(request.branchName())
                .commitHash(request.commitHash())
                .build();
        deploymentRepository.save(deployment);

        log.info("Deployment created: id={}, repo={}/{}, branch={}", deployment.getId(),
                repository.getOwner(), repository.getRepoName(), request.branchName());

        Long deploymentId = deployment.getId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                pipelineService.executePipeline(deploymentId);
            }
        });

        return DeploymentResponse.from(deployment);
    }

    public Page<DeploymentResponse> getDeployments(Long repositoryId, Pageable pageable) {
        return deploymentRepository.findBySourceRepositoryId(repositoryId, pageable)
                .map(DeploymentResponse::from);
    }

    public DeploymentResponse getDeployment(Long deploymentId) {
        Deployment deployment = deploymentRepository.findById(deploymentId)
                .orElseThrow(() -> new EntityNotFoundException(ErrorCode.DEPLOYMENT_NOT_FOUND));
        return DeploymentResponse.from(deployment);
    }

    public DeploymentStatusResponse getDeploymentStatus(Long deploymentId) {
        Deployment deployment = deploymentRepository.findById(deploymentId)
                .orElseThrow(() -> new EntityNotFoundException(ErrorCode.DEPLOYMENT_NOT_FOUND));
        return DeploymentStatusResponse.from(deployment);
    }

    public DeploymentLogResponse getDeploymentLogs(Long deploymentId) {
        deploymentRepository.findById(deploymentId)
                .orElseThrow(() -> new EntityNotFoundException(ErrorCode.DEPLOYMENT_NOT_FOUND));

        // TODO: Phase 5+ - NCP에서 실제 빌드/배포 로그 조회
        return new DeploymentLogResponse(deploymentId, List.of("로그 조회 기능은 향후 구현 예정입니다."));
    }

    @Transactional
    public void scaleDeployment(Long deploymentId, ScaleRequest request) {
        scaleDeployment(deploymentId, request, "USER");
    }

    @Transactional
    public void scaleDeployment(Long deploymentId, ScaleRequest request, String triggeredBy) {
        Deployment deployment = deploymentRepository.findById(deploymentId)
                .orElseThrow(() -> new EntityNotFoundException(ErrorCode.DEPLOYMENT_NOT_FOUND));
        DeploymentConfig deploymentConfig = deploymentConfigRepository
                .findBySourceRepositoryId(deployment.getSourceRepository().getId())
                .orElseThrow(() -> new EntityNotFoundException(ErrorCode.DEPLOYMENT_CONFIG_NOT_FOUND));

        SourceRepository repo = deployment.getSourceRepository();
        String appName = repo.getOwner() + "-" + repo.getRepoName();
        int previousReplicas = deploymentConfig.getMinReplicas();

        k8sGenerator.scale(appName, request.replicas());

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
    }

    public Page<ScalingHistoryResponse> getScalingHistory(Long repositoryId, Pageable pageable) {
        return scalingHistoryRepository.findByDeploymentSourceRepositoryIdOrderByCreatedAtDesc(repositoryId, pageable)
                .map(ScalingHistoryResponse::from);
    }

    @Transactional
    public void restartDeployment(Long deploymentId) {
        Deployment deployment = deploymentRepository.findById(deploymentId)
                .orElseThrow(() -> new EntityNotFoundException(ErrorCode.DEPLOYMENT_NOT_FOUND));

        SourceRepository repo = deployment.getSourceRepository();
        String appName = repo.getOwner() + "-" + repo.getRepoName();
        k8sGenerator.scale(appName, 0);
        k8sGenerator.scale(appName, 1);

        log.info("Restart completed: deploymentId={}", deploymentId);
    }
}
