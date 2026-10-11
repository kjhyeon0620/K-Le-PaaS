package klepaas.backend.ai.service;

import klepaas.backend.ai.dto.ApprovalTarget;
import klepaas.backend.ai.entity.Intent;
import klepaas.backend.deployment.entity.SourceRepository;
import klepaas.backend.deployment.repository.DeploymentConfigRepository;
import klepaas.backend.deployment.repository.SourceRepositoryRepository;
import klepaas.backend.deployment.service.DeploymentConfigSnapshots;
import klepaas.backend.deployment.service.ResourceAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 승인이 필요한 배포 명령의 대상 저장소 설정과 지문 (#56). 소유권을 확인한 저장소의 설정만 읽는다.
 * 대상을 확인하지 못하면 비어 있다 (저장소 없음·권한 없음·설정 없음·인자 오류). 다른 사용자 저장소의 존재를 드러내지 않는다.
 */
@Component
@RequiredArgsConstructor
public class ApprovalTargetResolver {

    private static final Set<Intent> PINNED = Set.of(Intent.DEPLOY, Intent.ROLLBACK, Intent.ROLLBACK_EXECUTION);

    private final ResourceAccessService resourceAccessService;
    private final SourceRepositoryRepository sourceRepositoryRepository;
    private final DeploymentConfigRepository deploymentConfigRepository;
    private final DeploymentConfigSnapshots configSnapshots;

    public boolean isPinned(Intent intent) {
        return PINNED.contains(intent);
    }

    public record Resolved(ApprovalTarget target, String fingerprint) {
    }

    // 바깥 트랜잭션 없이 읽는다. 권한 거절 예외가 공유 트랜잭션을 rollback-only로 만들지 않게 한다
    public Optional<Resolved> resolve(Intent intent, Map<String, Object> args, Long userId) {
        if (!isPinned(intent) || args == null) return Optional.empty();
        try {
            SourceRepository repo = intent == Intent.DEPLOY
                    ? resourceAccessService.requireRepository(toLong(args.get("repository_id")), userId)
                    : resourceAccessService.requireRepository(sourceRepositoryRepository
                            .findByOwnerAndRepoName(text(args.get("owner")), text(args.get("repo")))
                            .map(SourceRepository::getId).orElse(null), userId);
            // ActionDispatcher가 배포 요청을 만들 때 쓰는 기본값과 같다
            String branch = intent == Intent.DEPLOY ? textOr(args.get("branch_name"), "main") : "main";
            String commit = intent == Intent.DEPLOY ? textOr(args.get("commit_hash"), "HEAD") : text(args.get("commit_hash"));
            return deploymentConfigRepository.findBySourceRepositoryId(repo.getId()).map(config -> {
                String fingerprint = configSnapshots.fingerprint(config);
                return new Resolved(new ApprovalTarget(repo.getId(), repo.getOwner() + "/" + repo.getRepoName(),
                        branch, commit, config.getBuildStrategy(), config.getImageUriTemplate(),
                        config.getMinReplicas(), config.getMaxReplicas(), config.getContainerPort(),
                        config.getEnvVars().keySet().stream().sorted().toList(), fingerprint.substring(0, 8)),
                        fingerprint);
            });
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static String text(Object value) {
        return value instanceof String s ? s : null;
    }

    private static String textOr(Object value, String fallback) {
        return value instanceof String s ? s : fallback;
    }

    private static Long toLong(Object value) {
        if (value instanceof Number number) return number.longValue();
        if (value instanceof String s) return Long.parseLong(s);
        return null;
    }
}
