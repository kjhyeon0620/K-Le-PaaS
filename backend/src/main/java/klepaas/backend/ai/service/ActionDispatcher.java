package klepaas.backend.ai.service;

import klepaas.backend.ai.dto.FormattedResponseDto;
import klepaas.backend.ai.dto.ParsedIntent;
import klepaas.backend.ai.entity.Intent;
import klepaas.backend.ai.entity.RiskLevel;
import klepaas.backend.deployment.dto.CreateDeploymentRequest;
import klepaas.backend.deployment.dto.ScaleRequest;
import klepaas.backend.deployment.service.DeploymentOrigin;
import klepaas.backend.deployment.service.DeploymentService;
import klepaas.backend.deployment.service.ResourceAccessService;
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
    private final ResourceAccessService resourceAccessService;

    public RiskLevel classifyRisk(Intent intent) {
        return switch (intent) {
            case DEPLOY, ROLLBACK, ROLLBACK_EXECUTION -> RiskLevel.HIGH;
            case SCALE, RESTART -> RiskLevel.MEDIUM;
            default -> RiskLevel.LOW;
        };
    }

    public Object dispatch(ParsedIntent parsedIntent, Long userId) {
        return dispatch(parsedIntent, userId, null);
    }

    /** commandLogId: 이 실행을 승인한 명령 기록. 배포 요청에 연결된다 (#95) */
    public Object dispatch(ParsedIntent parsedIntent, Long userId, Long commandLogId) {
        Map<String, Object> args = parsedIntent.args();
        log.info("Action 실행: intent={}, args={}", parsedIntent.intent(), args);

        return switch (parsedIntent.intent()) {
            // ─ Platform deployment operations ─
            case DEPLOY -> executeDeploy(args, userId, commandLogId);
            case SCALE -> executeScale(args, userId);
            case RESTART -> executeRestart(args, userId);
            case STATUS -> executeStatus(args, userId);
            case LOGS -> executeLogs(args, userId);
            case LIST_DEPLOYMENTS -> executeListDeployments(args, userId);
            case LIST_REPOSITORIES -> executeListRepositories(userId);

            // ─ Kubernetes read operations ─
            case LIST_PODS -> kubectlService.listPods(getString(args, "namespace"), userId);
            case POD_STATUS -> kubectlService.getPodStatus(getString(args, "namespace"), getString(args, "app_name"), userId);
            case SERVICE_STATUS -> kubectlService.getServiceStatus(getString(args, "name"), getString(args, "namespace"), userId);
            case DEPLOYMENT_STATUS -> kubectlService.getDeploymentStatus(getString(args, "name"), getString(args, "namespace"), userId);
            case LIST_SERVICES -> kubectlService.listServices(getString(args, "namespace"), userId);
            case LIST_INGRESSES -> kubectlService.listIngresses(getString(args, "namespace"), userId);
            case LIST_NAMESPACES -> kubectlService.listNamespaces(userId);
            case LIST_ENDPOINTS -> kubectlService.listEndpoints(getString(args, "namespace"), userId);
            case GET_SERVICE -> kubectlService.getService(getString(args, "name"), getString(args, "namespace"), userId);
            case GET_DEPLOYMENT -> kubectlService.getDeploymentDetail(getString(args, "name"), getString(args, "namespace"), userId);
            case POD_LOGS -> kubectlService.getPodLogs(
                    getString(args, "pod_name") != null ? getString(args, "pod_name") : getString(args, "app_name"),
                    getString(args, "namespace"),
                    getInt(args, "lines", 100), userId);

            // ─ Rollback operations ─
            case LIST_ROLLBACK -> executeListRollback(args, userId);
            case ROLLBACK -> executeRollback(args, userId, commandLogId);
            case ROLLBACK_EXECUTION -> executeRollbackConfirm(args, userId, commandLogId);

            // ─ Overview, help, cost ─
            case OVERVIEW -> kubectlService.getOverview(userId);
            case LIST_COMMANDS -> kubectlService.listCommands();
            case COST_ANALYSIS -> kubectlService.getCostAnalysis(userId);
            case HELP -> executeHelp();

            case UNKNOWN -> parsedIntent.message();
        };
    }

    // ─── Platform deployment operations ──────────────────────────────────────

    private Object executeDeploy(Map<String, Object> args, Long userId, Long commandLogId) {
        Long repositoryId = toLong(args.get("repository_id"));
        String branchName = (String) args.getOrDefault("branch_name", "main");
        String commitHash = (String) args.getOrDefault("commit_hash", "HEAD");

        var request = new CreateDeploymentRequest(repositoryId, branchName, commitHash);
        var response = deploymentService.createDeployment(request, userId, null,
                DeploymentOrigin.nlp(userId, commandLogId)).deployment();

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

    private Object executeScale(Map<String, Object> args, Long userId) {
        Long deploymentId = toLong(args.get("deployment_id"));
        // 누락 시 1로 추정하지 않고 0을 넘겨 서비스 검증에서 거부되게 한다
        int replicas = getInt(args, "replicas", 0);

        var deployment = resourceAccessService.requireDeployment(deploymentId, userId);
        String owner = "", repo = "";
        if (deployment != null) {
            var srcRepo = deployment.getSourceRepository();
            owner = srcRepo.getOwner();
            repo = srcRepo.getRepoName();
        }

        int oldReplicas = deploymentService.scaleDeployment(deploymentId, new ScaleRequest(replicas), "NLP", userId);

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

    private Object executeRestart(Map<String, Object> args, Long userId) {
        Long deploymentId = toLong(args.get("deployment_id"));
        var deployment = resourceAccessService.requireDeployment(deploymentId, userId);
        String name = "deployment-" + deploymentId;
        String namespace = kubectlService.getDefaultNamespace();
        if (deployment != null) {
            var srcRepo = deployment.getSourceRepository();
            name = srcRepo.getOwner() + "-" + srcRepo.getRepoName();
        }

        deploymentService.restartDeployment(deploymentId, userId);

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

    private Object executeStatus(Map<String, Object> args, Long userId) {
        Long deploymentId = toLong(args.get("deployment_id"));
        var status = deploymentService.getDeploymentStatus(deploymentId, userId);
        var repository = resourceAccessService.requireDeployment(deploymentId, userId).getSourceRepository();
        // 추정값 대신 실제 Pod 집계를 사용하고, 조회 실패 시 unknown으로 표시한다
        Map<String, Object> pods = kubectlService.summarizeRepositoryPods(repository.getId());

        Map<String, Object> formatted = new LinkedHashMap<>();
        formatted.put("name", repository.getOwner() + "-" + repository.getRepoName());
        formatted.put("namespace", pods.get("namespace"));
        formatted.put("status", status.status().toString());
        formatted.put("ready", pods.get("ready"));
        formatted.put("restarts", pods.get("restarts"));
        formatted.put("age", "");
        formatted.put("node", "");

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("namespace", pods.get("namespace"));
        metadata.put("is_healthy", "SUCCESS".equals(status.status().toString())
                && Boolean.TRUE.equals(pods.get("all_ready")));
        if (pods.containsKey("error")) metadata.put("pod_status_error", pods.get("error"));

        return FormattedResponseDto.of("status",
                "배포 ID " + deploymentId + " 상태: " + status.status() + ", Pod ready: " + pods.get("ready"),
                "상태: " + status.status(),
                formatted, metadata);
    }

    private Object executeLogs(Map<String, Object> args, Long userId) {
        Long deploymentId = toLong(args.get("deployment_id"));
        var logs = deploymentService.getDeploymentLogs(deploymentId, 100, userId);
        // 이 배포 요청이 적용한 첫 Pod의 로그. 이 요청의 Pod가 아니면(관측 상태) 빈 로그와 이유를 준다
        var pod = logs.pods().isEmpty() ? null : logs.pods().get(0);
        List<String> lines = pod == null ? List.of() : pod.logs();

        Map<String, Object> formatted = new LinkedHashMap<>();
        formatted.put("pod_name", pod == null ? null : pod.name());
        formatted.put("namespace", kubectlService.getDefaultNamespace());
        formatted.put("lines", lines.size());
        formatted.put("log_lines", lines);
        formatted.put("total_lines", lines.size());

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("namespace", kubectlService.getDefaultNamespace());
        metadata.put("lines_requested", 100);
        metadata.put("lines_returned", lines.size());
        metadata.put("deployment_status", logs.status());
        metadata.put("fail_reason", logs.failReason());
        metadata.put("observation", logs.observation());
        if (logs.observationMessage() != null) metadata.put("observation_message", logs.observationMessage());
        if (pod != null && pod.logsError() != null) metadata.put("logs_error", pod.logsError());

        return FormattedResponseDto.of("logs",
                "배포 ID " + deploymentId + " 로그" + (logs.observationMessage() == null ? "" : " (" + logs.observationMessage() + ")"),
                "로그 " + lines.size() + "줄",
                formatted, metadata);
    }

    private Object executeListDeployments(Map<String, Object> args, Long userId) {
        Long repositoryId = toLong(args.get("repository_id"));
        var deployments = deploymentService.getDeployments(repositoryId, PageRequest.of(0, 10), userId);

        List<Map<String, Object>> items = new ArrayList<>();
        deployments.forEach(d -> {
            Map<String, Object> item = new LinkedHashMap<>();
            // Kubernetes 상태가 아닌 배포 이력 레코드이므로 저장된 값만 반환한다
            item.put("id", d.id());
            item.put("name", "deployment-" + d.id());
            item.put("status", d.status().toString());
            item.put("branch", d.branchName());
            item.put("commit", d.commitHash());
            item.put("image", d.imageUri());
            item.put("fail_reason", d.failReason());
            item.put("created_at", d.createdAt() == null ? null : d.createdAt().toString());
            items.add(item);
        });

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("namespace", kubectlService.getDefaultNamespace());
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

        resourceAccessService.requireRepository(srcRepo.getId(), userId);

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

    private Object executeRollback(Map<String, Object> args, Long userId, Long commandLogId) {
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

        resourceAccessService.requireRepository(srcRepo.getId(), userId);

        var request = new CreateDeploymentRequest(srcRepo.getId(), "main", commitHash);
        var response = deploymentService.createDeployment(request, userId, null,
                DeploymentOrigin.nlp(userId, commandLogId)).deployment();

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

    private Object executeRollbackConfirm(Map<String, Object> args, Long userId, Long commandLogId) {
        return executeRollback(args, userId, commandLogId);
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
