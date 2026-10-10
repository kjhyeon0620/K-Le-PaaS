package klepaas.backend.deployment.service;

import klepaas.backend.ai.repository.CommandLogRepository;
import klepaas.backend.deployment.dto.DeploymentDetailResponse;
import klepaas.backend.deployment.dto.DeploymentDetailResponse.AppliedConfig;
import klepaas.backend.deployment.dto.DeploymentDetailResponse.Change;
import klepaas.backend.deployment.dto.DeploymentDetailResponse.ChangeKind;
import klepaas.backend.deployment.dto.DeploymentDetailResponse.CommandSummary;
import klepaas.backend.deployment.dto.DeploymentDetailResponse.Comparison;
import klepaas.backend.deployment.dto.DeploymentDetailResponse.Explanation;
import klepaas.backend.deployment.dto.DeploymentDetailResponse.PreviousSuccess;
import klepaas.backend.deployment.dto.DeploymentDetailResponse.Requester;
import klepaas.backend.deployment.dto.DeploymentResponse;
import klepaas.backend.deployment.entity.Deployment;
import klepaas.backend.deployment.entity.DeploymentStatus;
import klepaas.backend.deployment.repository.DeploymentRepository;
import klepaas.backend.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * 배포 요청 상세 (#95). 기록된 값만 보여 주고, 기록이 없으면 비교하지 않는다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DeploymentDetailService {

    private final ResourceAccessService resourceAccessService;
    private final DeploymentRepository deploymentRepository;
    private final DeploymentConfigSnapshots configSnapshots;
    private final UserRepository userRepository;
    private final CommandLogRepository commandLogRepository;

    public DeploymentDetailResponse getDetail(Long deploymentId, Long userId) {
        Deployment deployment = resourceAccessService.requireDeployment(deploymentId, userId);
        var snapshot = configSnapshots.read(deployment.getConfigSnapshot());

        Requester requester = deployment.getRequestedByUserId() == null ? null
                : userRepository.findById(deployment.getRequestedByUserId())
                        .map(u -> new Requester(u.getId(), u.getName()))
                        .orElse(new Requester(deployment.getRequestedByUserId(), null));
        CommandSummary command = deployment.getCommandLogId() == null ? null
                : commandLogRepository.findById(deployment.getCommandLogId())
                        .map(c -> new CommandSummary(c.getId(), c.getRawCommand(),
                                c.getStatus() == null ? null : c.getStatus().name()))
                        .orElse(new CommandSummary(deployment.getCommandLogId(), null, null));

        Deployment previous = deploymentRepository.findFirstBySourceRepositoryIdAndStatusAndIdLessThanOrderByIdDesc(
                deployment.getSourceRepository().getId(), DeploymentStatus.SUCCESS, deployment.getId()).orElse(null);
        Comparison comparison;
        List<Change> changes = new ArrayList<>();
        if (previous == null) {
            comparison = Comparison.NO_PREVIOUS;
        } else {
            compare(changes, "image_uri", previous.getImageUri(), deployment.getImageUri());
            if (previous.getImageDigest() != null && deployment.getImageDigest() != null) {
                compare(changes, "image_digest", previous.getImageDigest(), deployment.getImageDigest());
            }
            var previousSnapshot = configSnapshots.read(previous.getConfigSnapshot());
            if (snapshot.isPresent() && previousSnapshot.isPresent()) {
                comparison = Comparison.AVAILABLE;
                compareConfig(changes, previousSnapshot.get(), snapshot.get());
            } else {
                comparison = Comparison.NOT_RECORDED;
            }
        }

        return new DeploymentDetailResponse(
                DeploymentResponse.from(deployment),
                requester,
                command,
                snapshot.map(DeploymentDetailService::toAppliedConfig).orElse(null),
                previous == null ? null : new PreviousSuccess(previous.getId(), previous.getCommitHash(),
                        previous.getImageUri(), previous.getImageDigest(), previous.getFinishedAt()),
                comparison,
                changes,
                deployment.getFailureKind() == null ? null : Explanation.of(deployment.getFailureKind()));
    }

    static void compareConfig(List<Change> changes, DeploymentConfigSnapshot before, DeploymentConfigSnapshot after) {
        List<Map.Entry<String, Function<DeploymentConfigSnapshot, Object>>> fields = List.of(
                Map.entry("build_strategy", DeploymentConfigSnapshot::buildStrategy),
                Map.entry("image_uri_template", DeploymentConfigSnapshot::imageUriTemplate),
                Map.entry("min_replicas", DeploymentConfigSnapshot::minReplicas),
                Map.entry("max_replicas", DeploymentConfigSnapshot::maxReplicas),
                Map.entry("container_port", DeploymentConfigSnapshot::containerPort),
                Map.entry("domain_url", DeploymentConfigSnapshot::domainUrl),
                Map.entry("service_type", DeploymentConfigSnapshot::serviceType),
                Map.entry("node_port", DeploymentConfigSnapshot::nodePort),
                Map.entry("env_from_config_maps", DeploymentConfigSnapshot::envFromConfigMaps),
                Map.entry("env_from_secrets", DeploymentConfigSnapshot::envFromSecrets),
                Map.entry("image_pull_secret_enabled", DeploymentConfigSnapshot::imagePullSecretEnabled),
                Map.entry("image_pull_secret_name", DeploymentConfigSnapshot::imagePullSecretName),
                Map.entry("health_probe", DeploymentConfigSnapshot::healthProbe),
                Map.entry("resources", DeploymentConfigSnapshot::resources));
        for (var field : fields) {
            compare(changes, field.getKey(), display(field.getValue().apply(before)), display(field.getValue().apply(after)));
        }

        // env: 값은 기록하지 않으므로 이름의 추가·삭제와 지문 비교만 한다. 지문 키가 다르면 값 변경 여부를 모른다
        Map<String, String> beforeEnv = before.envFingerprints() == null ? Map.of() : before.envFingerprints();
        Map<String, String> afterEnv = after.envFingerprints() == null ? Map.of() : after.envFingerprints();
        boolean sameKey = Objects.equals(before.fingerprintKeyId(), after.fingerprintKeyId());
        for (String name : new TreeSet<>(union(beforeEnv.keySet(), afterEnv.keySet()))) {
            String field = "env:" + name;
            if (!beforeEnv.containsKey(name)) {
                changes.add(new Change(field, null, null, ChangeKind.ADDED));
            } else if (!afterEnv.containsKey(name)) {
                changes.add(new Change(field, null, null, ChangeKind.REMOVED));
            } else if (!sameKey) {
                changes.add(new Change(field, null, null, ChangeKind.UNKNOWN));
            } else if (!beforeEnv.get(name).equals(afterEnv.get(name))) {
                changes.add(new Change(field, null, null, ChangeKind.VALUE_CHANGED));
            }
        }
    }

    private static void compare(List<Change> changes, String field, String before, String after) {
        if (!Objects.equals(before, after)) {
            changes.add(new Change(field, before, after, ChangeKind.CHANGED));
        }
    }

    private static String display(Object value) {
        return value == null ? null : value.toString();
    }

    private static TreeSet<String> union(java.util.Set<String> a, java.util.Set<String> b) {
        TreeSet<String> all = new TreeSet<>(a);
        all.addAll(b);
        return all;
    }

    private static AppliedConfig toAppliedConfig(DeploymentConfigSnapshot s) {
        return new AppliedConfig(s.buildStrategy(), s.imageUriTemplate(), s.minReplicas(), s.maxReplicas(),
                s.containerPort(), s.domainUrl(), s.serviceType(), s.nodePort(),
                s.envFingerprints() == null ? List.of() : List.copyOf(new TreeSet<>(s.envFingerprints().keySet())),
                s.envFromConfigMaps(), s.envFromSecrets(), s.imagePullSecretEnabled(), s.imagePullSecretName(),
                s.healthProbe(), s.resources());
    }
}
