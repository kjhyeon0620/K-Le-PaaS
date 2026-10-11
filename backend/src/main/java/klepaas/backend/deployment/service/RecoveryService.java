package klepaas.backend.deployment.service;

import klepaas.backend.deployment.dto.CreateDeploymentRequest;
import klepaas.backend.deployment.dto.CreateDeploymentResult;
import klepaas.backend.deployment.dto.DeploymentResponse;
import klepaas.backend.deployment.dto.DeploymentDetailResponse.Change;
import klepaas.backend.deployment.dto.RecoveryCandidateResponse;
import klepaas.backend.deployment.dto.RecoveryPlanResponse;
import klepaas.backend.deployment.dto.UpdateDeploymentConfigRequest;
import klepaas.backend.deployment.entity.BuildStrategy;
import klepaas.backend.deployment.entity.ContainerResources;
import klepaas.backend.deployment.entity.Deployment;
import klepaas.backend.deployment.entity.DeploymentConfig;
import klepaas.backend.deployment.entity.DeploymentStatus;
import klepaas.backend.deployment.entity.HealthProbe;
import klepaas.backend.deployment.repository.DeploymentConfigRepository;
import klepaas.backend.deployment.repository.DeploymentRepository;
import klepaas.backend.global.exception.BusinessException;
import klepaas.backend.global.exception.EntityNotFoundException;
import klepaas.backend.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * 이전 성공 배포로 복구 (#101). 이미지와 env 외 배포 설정을 대상 기록으로 되돌린다.
 * env 값은 기록하지 않으므로 현재 env가 대상과 다르면 복구하지 않는다. 계획은 저장하지 않고 지문으로 승인한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecoveryService {

    private static final HealthProbe NO_PROBE = new HealthProbe("", null, null, null, null, null);
    private static final ContainerResources NO_RESOURCES = new ContainerResources(null, null, null, null);

    private final ResourceAccessService resourceAccessService;
    private final DeploymentRepository deploymentRepository;
    private final DeploymentConfigRepository deploymentConfigRepository;
    private final DeploymentConfigSnapshots configSnapshots;
    private final RepositoryService repositoryService;
    private final DeploymentService deploymentService;

    public record Plan(Deployment target, String image, boolean digestPinned, List<Change> configChanges,
                       List<Change> envDifferences, List<String> blockedReasons, String fingerprint) {
        public boolean executable() {
            return blockedReasons.isEmpty();
        }

        public Optional<DeploymentConfigSnapshot> targetConfig(DeploymentConfigSnapshots snapshots) {
            return snapshots.read(target.getConfigSnapshot());
        }
    }

    @Transactional(readOnly = true)
    public List<RecoveryCandidateResponse> candidates(Long repositoryId, Long userId) {
        resourceAccessService.requireRepository(repositoryId, userId);
        return candidateDeployments(repositoryId).stream()
                .map(d -> new RecoveryCandidateResponse(d.getId(), d.getCommitHash(), d.getImageUri(), d.getImageDigest(),
                        d.getFinishedAt(), configSnapshots.read(d.getConfigSnapshot()).isPresent()))
                .toList();
    }

    /** 소유권을 이미 확인한 저장소의 후보. commit 접두가 있으면 맞는 최신 후보, 없으면 직전 성공 배포(두 번째 최신 후보). */
    @Transactional(readOnly = true)
    public Optional<Deployment> findTarget(Long repositoryId, String commitPrefix) {
        List<Deployment> candidates = candidateDeployments(repositoryId);
        if (commitPrefix != null && !commitPrefix.isBlank()) {
            return candidates.stream().filter(d -> d.getCommitHash() != null && d.getCommitHash().startsWith(commitPrefix)).findFirst();
        }
        return candidates.size() > 1 ? Optional.of(candidates.get(1)) : Optional.empty();
    }

    @Transactional(readOnly = true)
    public RecoveryPlanResponse plan(Long targetDeploymentId, Long userId) {
        Plan plan = computePlan(resourceAccessService.requireDeployment(targetDeploymentId, userId));
        return new RecoveryPlanResponse(plan.target().getId(), plan.target().getCommitHash(), plan.image(),
                plan.target().getImageDigest(), plan.digestPinned(), plan.configChanges(), plan.envDifferences(),
                plan.executable(), plan.blockedReasons(), plan.fingerprint());
    }

    /** 소유권을 이미 확인한 대상의 복구 계획. */
    @Transactional(readOnly = true)
    public Plan computePlan(Deployment target) {
        List<String> blocked = new ArrayList<>();
        List<Change> configChanges = new ArrayList<>();
        List<Change> envDifferences = new ArrayList<>();
        Long repositoryId = target.getSourceRepository().getId();
        if (target.getStatus() != DeploymentStatus.SUCCESS || target.getImageUri() == null) {
            blocked.add("성공했고 이미지가 기록된 배포만 복구할 수 있습니다");
        }
        var targetConfig = configSnapshots.read(target.getConfigSnapshot());
        DeploymentConfig current = deploymentConfigRepository.findBySourceRepositoryId(repositoryId).orElse(null);
        if (current == null) {
            blocked.add("현재 배포 설정이 없습니다");
        }
        if (targetConfig.isEmpty()) {
            blocked.add("대상 배포의 설정 기록이 없습니다 (#95 이전 배포)");
        } else if (targetConfig.get().buildStrategy() == BuildStrategy.KANIKO) {
            blocked.add("KANIKO 빌드 경로는 이미지를 다시 빌드하므로 복구를 지원하지 않습니다");
        }
        if (current != null && targetConfig.isPresent()) {
            List<Change> all = new ArrayList<>();
            DeploymentDetailService.compareConfig(all, configSnapshots.capture(current), targetConfig.get());
            all.forEach(change -> (change.field().startsWith("env:") ? envDifferences : configChanges).add(change));
            if (!envDifferences.isEmpty()) {
                blocked.add("현재 env가 대상 배포와 다릅니다. env 값은 기록하지 않아 되돌릴 수 없으니 env를 맞춘 뒤 다시 계획하세요");
            }
        }
        boolean digestPinned = target.getImageDigest() != null && target.getImageUri() != null;
        String image = digestPinned ? pinDigest(target.getImageUri(), target.getImageDigest()) : target.getImageUri();
        String fingerprint = blocked.isEmpty()
                ? sha256(target.getId() + "|" + image + "|" + configSnapshots.fingerprint(current)) : null;
        return new Plan(target, image, digestPinned, configChanges, envDifferences, List.copyOf(blocked), fingerprint);
    }

    /**
     * 확인한 계획과 같을 때만 설정을 복원하고 대상 이미지로 배포 요청을 만든다. 한 트랜잭션이므로 배포를 만들지 못하면 설정도 되돌아간다.
     * 복원한 설정 지문을 승인 지문으로 남겨 apply 직전까지 고정한다 (#56).
     */
    @Transactional
    public CreateDeploymentResult recover(Long targetDeploymentId, String planFingerprint, Long userId, DeploymentOrigin origin) {
        Plan plan = computePlan(resourceAccessService.requireDeployment(targetDeploymentId, userId));
        if (!plan.executable()) {
            throw new BusinessException(ErrorCode.RECOVERY_BLOCKED, "복구할 수 없습니다: " + String.join("; ", plan.blockedReasons()));
        }
        if (!plan.fingerprint().equals(planFingerprint)) {
            throw new BusinessException(ErrorCode.RECOVERY_PLAN_CHANGED);
        }
        Deployment target = plan.target();
        Long repositoryId = target.getSourceRepository().getId();
        DeploymentConfigSnapshot restore = plan.targetConfig(configSnapshots).orElseThrow();
        DeploymentConfig current = deploymentConfigRepository.findBySourceRepositoryId(repositoryId)
                .orElseThrow(() -> new EntityNotFoundException(ErrorCode.DEPLOYMENT_CONFIG_NOT_FOUND));
        repositoryService.updateDeploymentConfig(repositoryId, new UpdateDeploymentConfigRequest(
                restore.minReplicas(), restore.maxReplicas(), current.getEnvVars(),
                restore.envFromConfigMaps(), restore.envFromSecrets(), restore.containerPort(), restore.domainUrl(),
                restore.buildStrategy(), restore.imageUriTemplate(), restore.imagePullSecretName(),
                restore.serviceType(), restore.nodePort(),
                restore.healthProbe() != null ? restore.healthProbe() : NO_PROBE,
                restore.resources() != null ? restore.resources() : NO_RESOURCES,
                restore.imagePullSecretEnabled()), userId);
        String restoredFingerprint = configSnapshots.fingerprint(deploymentConfigRepository.findBySourceRepositoryId(repositoryId)
                .orElseThrow(() -> new EntityNotFoundException(ErrorCode.DEPLOYMENT_CONFIG_NOT_FOUND)));

        CreateDeploymentResult result = deploymentService.createDeployment(
                new CreateDeploymentRequest(repositoryId, target.getBranchName(), target.getCommitHash(), plan.image()),
                userId, null, origin.withApprovedConfigFingerprint(restoredFingerprint));
        if (!result.created()) {
            // 같은 요청이 이미 진행 중이다. 이 실행으로 바꾼 설정은 되돌린다
            throw new BusinessException(ErrorCode.DEPLOYMENT_IN_PROGRESS,
                    "같은 저장소의 배포가 진행 중입니다: deployment_id=" + result.deployment().id());
        }
        Deployment created = deploymentRepository.findById(result.deployment().id())
                .orElseThrow(() -> new EntityNotFoundException(ErrorCode.DEPLOYMENT_NOT_FOUND));
        created.recordRecoveredFrom(target.getId());
        log.info("Recovery deployment created: deploymentId={}, recoveredFrom={}, image={}",
                created.getId(), target.getId(), plan.image());
        return new CreateDeploymentResult(DeploymentResponse.from(created), true);
    }

    private List<Deployment> candidateDeployments(Long repositoryId) {
        return deploymentRepository.findTop20BySourceRepositoryIdAndStatusAndImageUriIsNotNullOrderByIdDesc(
                repositoryId, DeploymentStatus.SUCCESS);
    }

    /** 태그·기존 digest를 빼고 관측 digest로 고정한다. 레지스트리 포트(host:5000/...)의 ':'는 태그가 아니다. */
    static String pinDigest(String image, String digest) {
        String name = image.contains("@") ? image.substring(0, image.indexOf('@')) : image;
        int colon = name.lastIndexOf(':');
        if (colon > name.lastIndexOf('/')) {
            name = name.substring(0, colon);
        }
        return name + "@" + digest;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
