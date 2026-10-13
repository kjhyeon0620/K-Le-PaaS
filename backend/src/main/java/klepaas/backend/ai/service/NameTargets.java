package klepaas.backend.ai.service;

import klepaas.backend.ai.entity.Intent;
import klepaas.backend.deployment.entity.DeploymentStatus;
import klepaas.backend.deployment.entity.SourceRepository;
import klepaas.backend.deployment.repository.DeploymentRepository;
import klepaas.backend.deployment.repository.SourceRepositoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 재시작·스케일·롤백(목록 포함)의 앱 이름을 요청 사용자 저장소 하나로 해석한다 (#144, 스펙 0144 "이름 해석 규칙").
 * 해석한 대상은 인자에 고정해 승인 후 다시 찾지 않는다. 다른 사용자 저장소는 찾지 않고 존재도 드러내지 않는다.
 * DB만 조회하고 Kubernetes를 호출하지 않는다.
 */
@Component
@RequiredArgsConstructor
class NameTargets {

    private static final Set<Intent> BY_DEPLOYMENT = Set.of(Intent.SCALE, Intent.RESTART);
    // 롤백 목록(LIST_ROLLBACK, 조회)도 같은 규칙으로 저장소를 찾는다
    private static final Set<Intent> BY_REPOSITORY = Set.of(Intent.ROLLBACK, Intent.ROLLBACK_EXECUTION, Intent.LIST_ROLLBACK);

    private final SourceRepositoryRepository repositories;
    private final DeploymentRepository deployments;

    /** 해석 결과. question이 있으면 정하지 못한 것이고 args는 원래 값이다 */
    record Result(Map<String, Object> args, String question) {
        static Result keep(Map<String, Object> args) {
            return new Result(args, null);
        }
    }

    Result resolve(Intent intent, Map<String, Object> args, Long userId) {
        Map<String, Object> given = args == null ? Map.of() : args;
        boolean byDeployment = BY_DEPLOYMENT.contains(intent);
        if (!byDeployment && !BY_REPOSITORY.contains(intent)) return Result.keep(given);
        // ID가 있으면 ID를 우선한다 (#135 경로 그대로)
        if (byDeployment && given.get("deployment_id") != null) return Result.keep(given);

        String name = text(given.get("app_name"));
        if (name == null) name = text(given.get("repo"));
        if (name == null) return Result.keep(given); // 이름도 없으면 ChangeTargets가 되묻는다
        String owner = text(given.get("owner"));
        int slash = name.indexOf('/');
        if (slash > 0) {
            owner = name.substring(0, slash);
            name = name.substring(slash + 1);
        }

        List<SourceRepository> own = repositories.findAllByUserId(userId);
        List<SourceRepository> found = match(own, owner, name);
        if (found.size() != 1) return new Result(given, question(own, found, name));
        SourceRepository repo = found.get(0);

        Map<String, Object> resolved = new LinkedHashMap<>(given);
        if (byDeployment) {
            var latest = deployments.findFirstBySourceRepositoryIdAndStatusOrderByIdDesc(repo.getId(), DeploymentStatus.SUCCESS);
            if (latest.isEmpty()) {
                return new Result(given, fullName(repo) + "에는 성공한 배포가 없어 재시작·스케일할 대상이 없습니다.");
            }
            resolved.put("deployment_id", latest.get().getId());
        } else {
            resolved.put("owner", repo.getOwner());
            resolved.put("repo", repo.getRepoName());
        }
        return new Result(resolved, null);
    }

    /** 정확 일치(owner 포함 가능) → 없으면 저장소 이름에 이름이 들어 있는 것 */
    static List<SourceRepository> match(List<SourceRepository> own, String owner, String name) {
        String n = name.toLowerCase(Locale.ROOT);
        String o = owner == null ? null : owner.toLowerCase(Locale.ROOT);
        List<SourceRepository> exact = own.stream()
                .filter(r -> r.getRepoName().toLowerCase(Locale.ROOT).equals(n))
                .filter(r -> o == null || r.getOwner().toLowerCase(Locale.ROOT).equals(o))
                .toList();
        if (!exact.isEmpty() || o != null) return exact;
        return own.stream().filter(r -> r.getRepoName().toLowerCase(Locale.ROOT).contains(n)).toList();
    }

    private static String question(List<SourceRepository> own, List<SourceRepository> found, String name) {
        if (!found.isEmpty()) {
            return "'" + name + "'에 해당하는 저장소가 여러 개입니다. 다음 중 어느 저장소인가요: " + names(found);
        }
        if (own.isEmpty()) return "등록된 저장소가 없습니다. 저장소를 먼저 등록하세요.";
        return "'" + name + "' 이름의 저장소가 없습니다. 등록된 저장소: " + names(own);
    }

    private static String names(List<SourceRepository> repos) {
        return String.join(", ", repos.stream().map(NameTargets::fullName).toList());
    }

    private static String fullName(SourceRepository repo) {
        return repo.getOwner() + "/" + repo.getRepoName();
    }

    private static String text(Object value) {
        return value instanceof String s && !s.isBlank() ? s.trim() : null;
    }
}
