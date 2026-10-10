package klepaas.backend.deployment.service;

import klepaas.backend.ai.entity.CommandStatus;
import klepaas.backend.ai.repository.CommandLogRepository;
import klepaas.backend.deployment.entity.Deployment;
import klepaas.backend.deployment.entity.DeploymentStatus;
import klepaas.backend.deployment.entity.FailureKind;
import klepaas.backend.deployment.repository.DeploymentRepository;
import klepaas.backend.global.exception.EntityNotFoundException;
import klepaas.backend.infra.kubernetes.KubernetesManifestGenerator;
import klepaas.backend.infra.kubernetes.RolloutResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 재시작 대조 (#57). 이전 프로세스가 끝내지 못한 배포·명령을 기동 시 한 번 정리한다.
 * 대상 id는 웹 서버가 요청을 받기 전에 확정하므로 이 프로세스가 시작한 작업은 섞이지 않는다.
 * Kubernetes는 조회만 하고, 결과는 이전 상태가 그대로일 때만 기록한다. 아무것도 다시 실행하지 않는다.
 * 기록에 실패한 행은 그대로 두며 기동·readiness를 막지 않는다.
 */
@Slf4j
@Service
public class InterruptedWorkReconciler implements SmartInitializingSingleton {

    private static final List<DeploymentStatus> UNFINISHED = List.of(
            DeploymentStatus.PENDING, DeploymentStatus.UPLOADING_SOURCE,
            DeploymentStatus.BUILDING, DeploymentStatus.DEPLOYING);
    private static final String PREFIX = "재시작 대조: ";

    private final DeploymentRepository deployments;
    private final CommandLogRepository commands;
    private final ResourceAccessService resourceAccessService;
    private final KubernetesManifestGenerator k8s;
    private final TransactionTemplate tx;

    private List<Long> deploymentIds = List.of();
    private List<Long> commandIds = List.of();

    public InterruptedWorkReconciler(DeploymentRepository deployments, CommandLogRepository commands,
                                     ResourceAccessService resourceAccessService, KubernetesManifestGenerator k8s,
                                     PlatformTransactionManager transactionManager) {
        this.deployments = deployments;
        this.commands = commands;
        this.resourceAccessService = resourceAccessService;
        this.k8s = k8s;
        this.tx = new TransactionTemplate(transactionManager);
    }

    @Override
    public void afterSingletonsInstantiated() {
        deploymentIds = deployments.findIdsByStatusIn(UNFINISHED);
        commandIds = commands.findIdsByStatus(CommandStatus.EXECUTING);
        log.info("Restart reconciliation targets: deployments={}, commands={}", deploymentIds, commandIds);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void reconcile() {
        deploymentIds.forEach(this::reconcileDeployment);
        commandIds.forEach(this::reconcileCommand);
        deploymentIds = List.of();
        commandIds = List.of();
    }

    void reconcileDeployment(Long id) {
        try {
            Target target = readTarget(id);
            if (target == null) return;
            Verdict verdict = judge(target);
            Integer updated = tx.execute(s -> deployments.finishIfStatus(id, target.status(), verdict.status(),
                    verdict.kind(), verdict.reason() == null ? null : PREFIX + verdict.reason(), verdict.digest(), LocalDateTime.now()));
            log.info("Restart reconciliation: deploymentId={}, {} -> {}, recorded={}, reason={}",
                    id, target.status(), verdict.status(), updated != null && updated == 1, verdict.reason());
        } catch (Exception e) {
            log.error("Restart reconciliation failed, leaving deployment unchanged: deploymentId={}", id, e);
        }
    }

    void reconcileCommand(Long id) {
        try {
            Integer updated = tx.execute(s -> commands.finish(id, CommandStatus.EXECUTING, CommandStatus.UNKNOWN, false, null,
                    PREFIX + "실행 중 백엔드가 재시작되어 결과를 확인할 수 없음. 다시 실행하지 않음"));
            log.info("Restart reconciliation: commandId={}, EXECUTING -> UNKNOWN, recorded={}", id, updated != null && updated == 1);
        } catch (Exception e) {
            log.error("Restart reconciliation failed, leaving command unchanged: commandId={}", id, e);
        }
    }

    // 소유권은 Kubernetes 조회 전에 확인한다. 확인하지 못하면 owned=false로 두고 조회하지 않는다.
    // 바깥 트랜잭션 없이 읽는다 (권한 거절 예외가 공유 트랜잭션을 rollback-only로 만들지 않게)
    private Target readTarget(Long id) {
        Deployment deployment = deployments.findById(id).orElse(null);
        if (deployment == null || !UNFINISHED.contains(deployment.getStatus())) return null;
        Long ownerId = deployments.findUserIdByDeploymentId(id).orElse(null);
        boolean owned;
        try {
            resourceAccessService.requireDeployment(id, ownerId);
            owned = true;
        } catch (EntityNotFoundException e) {
            owned = false;
        }
        var repo = deployment.getSourceRepository();
        return new Target(id, deployment.getStatus(), owned, repo.getId(), repo.getOwner() + "-" + repo.getRepoName(),
                deployment.getImageUri(), deployment.getAppliedResourceUid(), deployment.getAppliedGeneration());
    }

    private Verdict judge(Target t) {
        if (t.status() != DeploymentStatus.DEPLOYING) {
            return new Verdict(DeploymentStatus.FAILED, FailureKind.OTHER, "백엔드 재시작으로 배포 파이프라인이 "
                    + t.status() + " 단계에서 중단됨. 외부 빌드의 완료·취소 여부는 확인하지 않았고 다시 실행하지 않음", null);
        }
        if (t.resourceUid() == null || t.generation() == null) {
            return Verdict.unknown("적용 근거(UID·generation)가 기록되지 않아 이 요청의 결과를 판정할 수 없음 "
                    + "(apply 전후 중단 또는 이전 릴리스의 배포)");
        }
        if (!t.owned()) {
            return Verdict.unknown("저장소 소유권을 확인하지 못해 Kubernetes를 조회하지 않음");
        }
        RolloutResult result = k8s.observeOnce(t.appName(), t.repositoryId(), t.id(), t.imageUri(),
                t.resourceUid(), t.generation());
        return switch (result.outcome()) {
            case SUCCEEDED -> new Verdict(DeploymentStatus.SUCCESS, null, null, result.imageDigest());
            case FAILED -> new Verdict(DeploymentStatus.FAILED, result.failureKind(), result.reason(), null);
            case SUPERSEDED -> new Verdict(DeploymentStatus.CANCELED, FailureKind.SUPERSEDED, result.reason(), null);
            case UNKNOWN -> Verdict.unknown(result.reason());
        };
    }

    private record Target(Long id, DeploymentStatus status, boolean owned, Long repositoryId, String appName,
                          String imageUri, String resourceUid, Long generation) {
    }

    private record Verdict(DeploymentStatus status, FailureKind kind, String reason, String digest) {
        static Verdict unknown(String reason) {
            return new Verdict(DeploymentStatus.UNKNOWN, null, reason, null);
        }
    }
}
