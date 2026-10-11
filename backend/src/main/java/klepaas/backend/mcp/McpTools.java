package klepaas.backend.mcp;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import klepaas.backend.ai.diagnosis.DeploymentDiagnosisService;
import klepaas.backend.ai.service.NlpCommandService;
import klepaas.backend.auth.config.CustomUserDetails;
import klepaas.backend.auth.token.entity.CliTokenScope;
import klepaas.backend.deployment.service.DeploymentDetailService;
import klepaas.backend.deployment.service.DeploymentService;
import klepaas.backend.deployment.service.RecoveryService;
import klepaas.backend.deployment.service.RepositoryService;
import klepaas.backend.global.exception.BusinessException;
import klepaas.backend.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * MCP 도구 (#103). 각 도구는 대응 REST endpoint와 같은 서비스 메서드를 인증한 사용자 ID로 호출한다.
 * 소유권 검사·입력 검증·에러 코드는 서비스가 하므로 복제하지 않는다. scope는 REST 규칙(SecurityConfig)과 같게 도구별로 확인한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class McpTools {

    static final String USER = "klepaas.user";
    // SecurityConfig: GET·진단은 READ_ONLY 이상, 복구 제안은 PROPOSE_ONLY 이상
    static final Set<CliTokenScope> READ = Set.of(CliTokenScope.READ_ONLY, CliTokenScope.PROPOSE_ONLY, CliTokenScope.FULL);
    static final Set<CliTokenScope> PROPOSE = Set.of(CliTokenScope.PROPOSE_ONLY, CliTokenScope.FULL);

    private final RepositoryService repositoryService;
    private final DeploymentService deploymentService;
    private final DeploymentDetailService deploymentDetailService;
    private final DeploymentDiagnosisService deploymentDiagnosisService;
    private final RecoveryService recoveryService;
    private final NlpCommandService nlpCommandService;
    private final JsonMapper jsonMapper;

    List<SyncToolSpecification> specifications() {
        return List.of(
                tool("list_repositories", "내 앱(저장소) 목록. 읽기 전용. deployment 조회에 쓸 repository id를 여기서 얻는다.",
                        schema(Map.of(), List.of()), READ, true,
                        (user, args) -> repositoryService.getRepositories(user.getUserId())),
                tool("list_deployments", "저장소의 배포 요청 목록(최신순). 읽기 전용.",
                        schema(Map.of("repository_id", integer("저장소 ID", 1, null),
                                "page", integer("페이지 (0부터, 기본 0)", 0, null),
                                "size", integer("페이지 크기 (기본 20)", 1, 100)), List.of("repository_id")), READ, true,
                        (user, args) -> deploymentService.getDeployments(longArg(args, "repository_id"),
                                PageRequest.of(intArg(args, "page", 0), intArg(args, "size", 20), Sort.by(Sort.Direction.DESC, "createdAt")),
                                user.getUserId())),
                tool("get_deployment_detail", "배포 요청 상세: 실패 종류·사유, 적용 설정(env는 이름만), 직전 성공 배포 대비 변경. 읽기 전용.",
                        schema(Map.of("deployment_id", integer("배포 ID", 1, null)), List.of("deployment_id")), READ, true,
                        (user, args) -> deploymentDetailService.getDetail(longArg(args, "deployment_id"), user.getUserId())),
                tool("get_deployment_logs", "그 배포 요청이 적용한 Pod의 상태·최근 로그·Kubernetes 이벤트. 읽기 전용.",
                        schema(Map.of("deployment_id", integer("배포 ID", 1, null),
                                "lines", integer("Pod별 최근 로그 줄 수 (기본 100)", 1, 200)), List.of("deployment_id")), READ, true,
                        (user, args) -> deploymentService.getDeploymentLogs(longArg(args, "deployment_id"),
                                intArg(args, "lines", 100), user.getUserId())),
                tool("diagnose_deployment", "실패·판정 불가 배포의 근거 기반 AI 진단(실험). 인프라를 바꾸지 않는다. 저장된 최근 결과를 돌려주고 refresh=true면 새로 진단한다(모델 호출 비용).",
                        schema(Map.of("deployment_id", integer("배포 ID", 1, null),
                                "refresh", Map.of("type", "boolean", "description", "새로 진단 (기본 false)")), List.of("deployment_id")), READ, true,
                        (user, args) -> deploymentDiagnosisService.diagnose(longArg(args, "deployment_id"), user.getUserId(),
                                Boolean.TRUE.equals(args.get("refresh")))),
                tool("get_recovery_plan", "이전 성공 배포로 복구하는 계획: 배포할 이미지, 되돌릴 설정, env 차이, 실행 가능 여부. 읽기 전용.",
                        schema(Map.of("target_deployment_id", integer("복구 대상(이전 성공) 배포 ID", 1, null)), List.of("target_deployment_id")), READ, true,
                        (user, args) -> recoveryService.plan(longArg(args, "target_deployment_id"), user.getUserId())),
                tool("propose_recovery", "이전 성공 배포로 복구하는 승인 대기 명령만 만든다. 아무것도 실행하지 않으며, 사람이 10분 안에 klepaas confirm <command_log_id>로 승인해야 실행된다.",
                        schema(Map.of("target_deployment_id", integer("복구 대상(이전 성공) 배포 ID", 1, null),
                                "reason", Map.of("type", "string", "maxLength", 200, "description", "승인할 사람에게 보일 제안 사유")),
                                List.of("target_deployment_id")), PROPOSE, false,
                        (user, args) -> nlpCommandService.proposeRecovery(user.getUserId(), longArg(args, "target_deployment_id"),
                                args.get("reason") instanceof String reason ? reason : null)));
    }

    private SyncToolSpecification tool(String name, String description, Map<String, Object> inputSchema, Set<CliTokenScope> scopes,
                                       boolean readOnly, BiFunction<CustomUserDetails, Map<String, Object>, Object> call) {
        var tool = McpSchema.Tool.builder().name(name).description(description).inputSchema(inputSchema)
                .annotations(McpSchema.ToolAnnotations.builder().readOnlyHint(readOnly).destructiveHint(false).build())
                .build();
        return SyncToolSpecification.builder().tool(tool)
                .callHandler((context, request) -> call(context, request, scopes, call))
                .build();
    }

    private McpSchema.CallToolResult call(McpTransportContext context, McpSchema.CallToolRequest request, Set<CliTokenScope> scopes,
                                          BiFunction<CustomUserDetails, Map<String, Object>, Object> call) {
        try {
            if (!(context.get(USER) instanceof CustomUserDetails user) || user.getUserId() == null || !scopes.contains(user.getScope())) {
                throw new BusinessException(ErrorCode.CLI_TOKEN_SCOPE_DENIED);
            }
            Object result = call.apply(user, request.arguments() == null ? Map.of() : request.arguments());
            return McpSchema.CallToolResult.builder().addTextContent(jsonMapper.writeValueAsString(result)).isError(false).build();
        } catch (BusinessException e) {
            return error(e.getErrorCode().getCode(), e.getMessage());
        } catch (RuntimeException e) {
            log.error("MCP tool failed: tool={}", request.name(), e);
            return error(ErrorCode.INTERNAL_ERROR.getCode(), ErrorCode.INTERNAL_ERROR.getMessage());
        }
    }

    private McpSchema.CallToolResult error(String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        return McpSchema.CallToolResult.builder().addTextContent(jsonMapper.writeValueAsString(body)).isError(true).build();
    }

    private static Map<String, Object> schema(Map<String, Object> properties, List<String> required) {
        return Map.of("type", "object", "properties", properties, "required", required, "additionalProperties", false);
    }

    private static Map<String, Object> integer(String description, Integer minimum, Integer maximum) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "integer");
        schema.put("description", description);
        if (minimum != null) schema.put("minimum", minimum);
        if (maximum != null) schema.put("maximum", maximum);
        return schema;
    }

    private static Long longArg(Map<String, Object> args, String name) {
        return args.get(name) instanceof Number n ? n.longValue() : null;
    }

    private static int intArg(Map<String, Object> args, String name, int fallback) {
        return args.get(name) instanceof Number n ? n.intValue() : fallback;
    }
}
