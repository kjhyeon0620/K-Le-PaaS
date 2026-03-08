package klepaas.backend.ai.service;

import klepaas.backend.ai.dto.FormattedResponseDto;
import klepaas.backend.ai.dto.ParsedIntent;
import klepaas.backend.ai.entity.Intent;
import klepaas.backend.ai.entity.RiskLevel;
import klepaas.backend.deployment.dto.CreateDeploymentRequest;
import klepaas.backend.deployment.dto.ScaleRequest;
import klepaas.backend.deployment.service.DeploymentService;
import klepaas.backend.deployment.repository.DeploymentRepository;
import klepaas.backend.deployment.repository.SourceRepositoryRepository;
import klepaas.backend.deployment.entity.SourceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.ArrayList;

@Slf4j
@Component
@RequiredArgsConstructor
public class ActionDispatcher {

    private final DeploymentService deploymentService;
    private final KubectlService kubectlService;
    private final DeploymentRepository deploymentRepository;
    private final SourceRepositoryRepository sourceRepositoryRepository;

    public RiskLevel classifyRisk(Intent intent) {
        return switch (intent) {
            case DEPLOY, ROLLBACK, ROLLBACK_EXECUTION -> RiskLevel.HIGH;
            case SCALE, RESTART -> RiskLevel.MEDIUM;
            default -> RiskLevel.LOW;
        };
    }

    public Object dispatch(ParsedIntent parsedIntent, Long userId) {
        Map<String, Object> args = parsedIntent.args();
        log.info("Action 실행: intent={}, args={}", parsedIntent.intent(), args);

        return switch (parsedIntent.intent()) {
            // ─ Platform deployment operations ─
            case DEPLOY -> executeDeploy(args, userId);
            case SCALE -> executeScale(args);
            case RESTART -> executeRestart(args);
            case STATUS -> executeStatus(args);
            case LOGS -> executeLogs(args);
            case LIST_DEPLOYMENTS -> executeListDeployments(args);
            case LIST_REPOSITORIES -> executeListRepositories(userId);

            // ─ Kubernetes read operations ─
            case LIST_PODS -> kubectlService.listPods(getString(args, "namespace"));
            case POD_STATUS -> kubectlService.getPodStatus(getString(args, "namespace"), getString(args, "app_name"));
            case SERVICE_STATUS -> kubectlService.getServiceStatus(getString(args, "name"), getString(args, "namespace"));
            case DEPLOYMENT_STATUS -> kubectlService.getDeploymentStatus(getString(args, "name"), getString(args, "namespace"));
            case LIST_SERVICES -> kubectlService.listServices(getString(args, "namespace"));
            case LIST_INGRESSES -> kubectlService.listIngresses(getString(args, "namespace"));
            case LIST_NAMESPACES -> kubectlService.listNamespaces();
            case LIST_ENDPOINTS -> kubectlService.listEndpoints(getString(args, "namespace"));
            case GET_SERVICE -> kubectlService.getService(getString(args, "name"), getString(args, "namespace"));
            case GET_DEPLOYMENT -> kubectlService.getDeploymentDetail(getString(args, "name"), getString(args, "namespace"));
            case POD_LOGS -> kubectlService.getPodLogs(
                    getString(args, "pod_name") != null ? getString(args, "pod_name") : getString(args, "app_name"),
                    getString(args, "namespace"),
                    getInt(args, "lines", 100));

            // ─ Rollback operations ─
            case LIST_ROLLBACK -> executeListRollback(args, userId);
            case ROLLBACK -> executeRollback(args, userId);
            case ROLLBACK_EXECUTION -> executeRollbackConfirm(args, userId);

            // ─ Overview, help, cost ─
            case OVERVIEW -> kubectlService.getOverview();
            case LIST_COMMANDS -> kubectlService.listCommands();
            case COST_ANALYSIS -> kubectlService.getCostAnalysis();
            case HELP -> executeHelp();

            case UNKNOWN -> parsedIntent.message();
        };
    }

    // ─── Platform deployment operations ──────────────────────────────────────

    private Object executeDeploy(Map<String, Object> args, Long userId) {
        Long repositoryId = toLong(args.get("repository_id"));
        String branchName = (String) args.getOrDefault("branch_name", "main");
        String commitHash = (String) args.getOrDefault("commit_hash", "HEAD");

        var request = new CreateDeploymentRequest(repositoryId, branchName, commitHash);
        var response = deploymentService.createDeployment(request, userId);

        Map<String, Object> formatted = new LinkedHashMap<>();
        formatted.put("app_name", "deployment-" + response.id());
        formatted.put("environment", "production");
        formatted.put("status", response.status().toString());
        formatted.put("message", "배포가 시작되었습니다. 배포 ID: " + response.id());
        formatted.put("repository", String.valueOf(repositoryId));
        formatted.put("branch", branchName);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("app_name", "deployment-" + response.id());
        metadata.put("environment", "production");

        return FormattedResponseDto.of("deploy",
                "배포가 시작되었습니다. 배포 ID: " + response.id() + ", 상태: " + response.status(),
                "배포 시작",
                formatted, metadata);
    }

    private Object executeScale(Map<String, Object> args) {
        Long deploymentId = toLong(args.get("deployment_id"));
        int replicas = getInt(args, "replicas", 1);

        var deployment = deploymentRepository.findById(deploymentId).orElse(null);
        String owner = "", repo = "";
        int oldReplicas = 1;
        if (deployment != null) {
            var srcRepo = deployment.getSourceRepository();
            owner = srcRepo.getOwner();
            repo = srcRepo.getRepoName();
        }

        deploymentService.scaleDeployment(deploymentId, new ScaleRequest(replicas));

        Map<String, Object> formatted = new LinkedHashMap<>();
        formatted.put("repository", owner + "/" + repo);
        formatted.put("old_replicas", oldReplicas);
        formatted.put("new_replicas", replicas);
        formatted.put("change", replicas > oldReplicas ? "scale_up" : "scale_down");
        formatted.put("status", "completed");
        formatted.put("timestamp", java.time.Instant.now().toString());
        formatted.put("action", "scale");

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("owner", owner);
        metadata.put("repo", repo);
        metadata.put("old_replicas", oldReplicas);
        metadata.put("new_replicas", replicas);
        metadata.put("status", "completed");

        return FormattedResponseDto.of("scale",
                "스케일링 완료: 배포 ID " + deploymentId + " → " + replicas + "개 레플리카",
                "스케일링 완료",
                formatted, metadata);
    }

    private Object executeRestart(Map<String, Object> args) {
        Long deploymentId = toLong(args.get("deployment_id"));
        var deployment = deploymentRepository.findById(deploymentId).orElse(null);
        String name = "deployment-" + deploymentId;
        String namespace = "default";
        if (deployment != null) {
            var srcRepo = deployment.getSourceRepository();
            name = srcRepo.getOwner() + "-" + srcRepo.getRepoName();
        }

        deploymentService.restartDeployment(deploymentId);

        Map<String, Object> formatted = new LinkedHashMap<>();
        formatted.put("name", name);
        formatted.put("namespace", namespace);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("name", name);
        metadata.put("namespace", namespace);

        return FormattedResponseDto.of("restart",
                "재시작 완료: 배포 ID " + deploymentId,
                "재시작 완료",
                formatted, metadata);
    }

    private Object executeStatus(Map<String, Object> args) {
        Long deploymentId = toLong(args.get("deployment_id"));
        var status = deploymentService.getDeploymentStatus(deploymentId);

        Map<String, Object> formatted = new LinkedHashMap<>();
        formatted.put("name", "deployment-" + deploymentId);
        formatted.put("namespace", "default");
        formatted.put("status", status.status().toString());
        formatted.put("ready", "1/1");
        formatted.put("restarts", 0);
        formatted.put("age", "");
        formatted.put("node", "");

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("namespace", "default");
        metadata.put("is_healthy", "SUCCESS".equals(status.status().toString()));

        return FormattedResponseDto.of("status",
                "배포 ID " + deploymentId + " 상태: " + status.status(),
                "상태: " + status.status(),
                formatted, metadata);
    }

    private Object executeLogs(Map<String, Object> args) {
        Long deploymentId = toLong(args.get("deployment_id"));
        var logs = deploymentService.getDeploymentLogs(deploymentId);

        Map<String, Object> formatted = new LinkedHashMap<>();
        formatted.put("pod_name", "deployment-" + deploymentId);
        formatted.put("namespace", "default");
        formatted.put("lines", logs.logs().size());
        formatted.put("log_lines", logs.logs());
        formatted.put("total_lines", logs.logs().size());

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("namespace", "default");
        metadata.put("lines_requested", 100);
        metadata.put("lines_returned", logs.logs().size());

        return FormattedResponseDto.of("logs",
                "배포 ID " + deploymentId + " 로그",
                "로그 " + logs.logs().size() + "줄",
                formatted, metadata);
    }

    private Object executeListDeployments(Map<String, Object> args) {
        Long repositoryId = toLong(args.get("repository_id"));
        var deployments = deploymentService.getDeployments(repositoryId, PageRequest.of(0, 10));

        List<Map<String, Object>> items = new ArrayList<>();
        deployments.forEach(d -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", "deployment-" + d.id());
            item.put("namespace", "default");
            item.put("replicas", "1");
            item.put("ready", "1");
            item.put("up_to_date", "1");
            item.put("available", "1");
            item.put("age", "");
            item.put("image", "");
            items.add(item);
        });

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("namespace", "default");
        metadata.put("total", items.size());

        return FormattedResponseDto.of("list_deployments",
                "배포 목록: 총 " + items.size() + "개",
                "배포 " + items.size() + "개",
                items, metadata);
    }

    private Object executeListRepositories(Long userId) {
        Map<String, Object> formatted = new LinkedHashMap<>();
        formatted.put("message", "저장소 목록은 /api/v1/repositories API를 통해 확인하세요.");

        return FormattedResponseDto.of("list_deployments",
                "저장소 목록을 조회하려면 대시보드를 확인하세요.",
                "저장소 목록",
                formatted, null);
    }

    // ─── Rollback operations ──────────────────────────────────────────────────

    private Object executeListRollback(Map<String, Object> args, Long userId) {
        String owner = getString(args, "owner");
        String repo = getString(args, "repo");

        SourceRepository srcRepo = sourceRepositoryRepository
                .findByOwnerAndRepoName(owner, repo).orElse(null);

        if (srcRepo == null) {
            return FormattedResponseDto.of("error",
                    "저장소를 찾을 수 없습니다: " + owner + "/" + repo,
                    "오류",
                    Map.of("error", "저장소 없음"), null);
        }

        var deployments = deploymentRepository
                .findBySourceRepositoryId(srcRepo.getId(), PageRequest.of(0, 10));

        var versions = new ArrayList<Map<String, Object>>();
        var history = new ArrayList<Map<String, Object>>();

        deployments.getContent().forEach(d -> {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("steps_back", versions.size() + 1);
            v.put("commit", d.getCommitHash() != null ? d.getCommitHash().substring(0, Math.min(7, d.getCommitHash().length())) : "");
            v.put("message", "배포 #" + d.getId());
            v.put("date", d.getCreatedAt() != null ? d.getCreatedAt().toString() : "");
            v.put("can_rollback", true);
            v.put("is_current", versions.isEmpty());
            versions.add(v);

            Map<String, Object> h = new LinkedHashMap<>();
            h.put("commit", v.get("commit"));
            h.put("message", v.get("message"));
            h.put("date", v.get("date"));
            history.add(h);
        });

        Map<String, Object> current = new LinkedHashMap<>();
        if (!versions.isEmpty()) {
            current.put("commit", versions.get(0).get("commit"));
            current.put("message", versions.get(0).get("message"));
            current.put("date", versions.get(0).get("date"));
            current.put("is_rollback", false);
        }

        Map<String, Object> formatted = new LinkedHashMap<>();
        formatted.put("current", current);
        formatted.put("versions", versions);
        formatted.put("history", history);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("owner", owner);
        metadata.put("repo", repo);
        metadata.put("total_available", versions.size());
        metadata.put("total_rollbacks", history.size());

        return FormattedResponseDto.of("list_rollback",
                owner + "/" + repo + " 롤백 가능한 버전 목록입니다. 총 " + versions.size() + "개",
                "롤백 버전 " + versions.size() + "개",
                formatted, metadata);
    }

    private Object executeRollback(Map<String, Object> args, Long userId) {
        String owner = getString(args, "owner");
        String repo = getString(args, "repo");
        String commitHash = getString(args, "commit_hash");

        SourceRepository srcRepo = sourceRepositoryRepository
                .findByOwnerAndRepoName(owner, repo).orElse(null);

        if (srcRepo == null) {
            return FormattedResponseDto.of("error",
                    "저장소를 찾을 수 없습니다: " + owner + "/" + repo,
                    "오류",
                    Map.of("error", "저장소 없음"), null);
        }

        var request = new CreateDeploymentRequest(srcRepo.getId(), "main", commitHash);
        var response = deploymentService.createDeployment(request, userId);

        Map<String, Object> formatted = new LinkedHashMap<>();
        formatted.put("action_type", "rollback");
        formatted.put("action_description", "이전 버전으로 롤백");
        formatted.put("project", owner + "/" + repo);
        formatted.put("target_commit", commitHash != null ? commitHash.substring(0, Math.min(7, commitHash.length())) : "");
        formatted.put("status", "started");
        formatted.put("timestamp", java.time.Instant.now().toString());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("owner", owner);
        details.put("repo", repo);
        details.put("target_commit_full", commitHash);
        details.put("action", "rollback");
        details.put("status", "started");
        formatted.put("details", details);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("owner", owner);
        metadata.put("repo", repo);
        metadata.put("action_type", "rollback");
        metadata.put("target_commit", formatted.get("target_commit"));
        metadata.put("status", "started");

        return FormattedResponseDto.of("rollback_execution",
                owner + "/" + repo + "을(를) " + formatted.get("target_commit") + " 커밋으로 롤백을 시작합니다.",
                "롤백 시작",
                formatted, metadata);
    }

    private Object executeRollbackConfirm(Map<String, Object> args, Long userId) {
        return executeRollback(args, userId);
    }

    // ─── Help ─────────────────────────────────────────────────────────────────

    private Object executeHelp() {
        return kubectlService.listCommands();
    }

    // ─── Utilities ────────────────────────────────────────────────────────────

    private String getString(Map<String, Object> args, String key) {
        Object val = args.get(key);
        return val instanceof String s ? s : null;
    }

    private int getInt(Map<String, Object> args, String key, int defaultVal) {
        Object val = args.get(key);
        if (val instanceof Number n) return n.intValue();
        if (val instanceof String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException ignored) {}
        }
        return defaultVal;
    }

    private Long toLong(Object value) {
        if (value instanceof Number number) return number.longValue();
        if (value instanceof String str) return Long.parseLong(str);
        throw new IllegalArgumentException("Long으로 변환할 수 없는 값: " + value);
    }
}
