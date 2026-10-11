package klepaas.backend.ai.diagnosis;

import klepaas.backend.ai.llm.LlmClient;
import klepaas.backend.ai.exception.AiProcessingException;
import klepaas.backend.deployment.dto.DeploymentDetailResponse;
import klepaas.backend.deployment.dto.DeploymentLogResponse;
import klepaas.backend.deployment.entity.DeploymentStatus;
import klepaas.backend.deployment.entity.FailureKind;
import klepaas.backend.deployment.service.DeploymentDetailService;
import klepaas.backend.deployment.service.DeploymentService;
import klepaas.backend.global.exception.ErrorCode;
import klepaas.backend.global.exception.InvalidRequestException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import klepaas.backend.ai.knowledge.KnowledgeDtos.KnowledgeCitation;
import klepaas.backend.ai.knowledge.KnowledgeDtos.KnowledgeItem;
import klepaas.backend.ai.knowledge.KnowledgeService;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 실패한 배포 요청의 근거 기반 읽기 전용 진단 (#100). 배포 상세·로그 API와 같은 JSON을 모델에 보내고,
 * 응답의 라벨·근거 위치를 검증한다. Kubernetes·배포 상태를 바꾸지 않는다.
 * 결과는 이력으로 저장하고 같은 배포를 다시 진단하면 최근 결과를 돌려준다. 근거 없는 입력은 모델을 부르지 않는다 (#117).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeploymentDiagnosisService {

    static final String RULE_MODEL = "rule";
    static final String NO_EVIDENCE_VERSION = "no-evidence-v1";
    private static final int LOG_LINES = 40;
    // 실패 사유가 원인을 가리키지 않는 종류. 이 경우 Pod·이벤트·설정 변경이 없으면 판단할 근거가 없다
    private static final Set<FailureKind> GENERIC_FAILURES =
            EnumSet.of(FailureKind.ROLLOUT_TIMEOUT, FailureKind.PROGRESS_DEADLINE, FailureKind.DEPLOYMENT_MISSING);

    private final DeploymentDetailService deploymentDetailService;
    private final DeploymentService deploymentService;
    private final LlmClient diagnosisLlm;
    private final JsonMapper jsonMapper;
    private final DeploymentDiagnosisRepository diagnosisRepository;
    private final KnowledgeService knowledgeService;
    private static final java.util.regex.Pattern KNOWLEDGE_POINTER = java.util.regex.Pattern.compile("^/knowledge/(\\d+)(/|$)");

    public DeploymentDiagnosisResponse diagnose(Long deploymentId, Long userId, boolean refresh) {
        // 소유권 검사는 상세·로그 조회가 한다. 권한 밖이면 여기서 404로 끝나고 저장 결과도 모델도 쓰지 않는다
        var detail = deploymentDetailService.getDetail(deploymentId, userId);
        DeploymentStatus status = detail.deployment().status();
        if (status != DeploymentStatus.FAILED && status != DeploymentStatus.UNKNOWN) {
            throw new InvalidRequestException(ErrorCode.INVALID_REQUEST, "실패하거나 판정 불가인 배포만 진단합니다");
        }
        if (!refresh) {
            var saved = diagnosisRepository.findFirstByDeploymentIdOrderByIdDesc(deploymentId).filter(this::reusable);
            if (saved.isPresent()) {
                return jsonMapper.readValue(saved.get().getResult(), DeploymentDiagnosisResponse.class)
                        .saved(true, saved.get().getCreatedAt());
            }
        }
        var logs = deploymentService.getDeploymentLogs(deploymentId, LOG_LINES, userId);
        if (noEvidence(detail, logs)) {
            return save(new DeploymentDiagnosisResponse(deploymentId, RULE_MODEL, NO_EVIDENCE_VERSION, null, null, true,
                    "Pod·이벤트·설정 변경이 관측되지 않고 실패 종류가 원인을 가리키지 않아 원인을 판단할 수 없습니다.",
                    List.of(), List.of(), List.of("배포 대상 Pod와 Kubernetes 이벤트를 직접 확인합니다."), List.of(),
                    List.of("관측 근거가 없어 모델을 호출하지 않았습니다"), List.of(), false, null), RULE_MODEL, null);
        }
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("detail", detail);
        input.put("logs", logs);
        String redacted = DiagnosisPrompt.redact(jsonMapper.writeValueAsString(input));
        List<String> warnings = new ArrayList<>();
        List<KnowledgeItem> knowledge = searchKnowledge(detail, deploymentId, redacted, warnings);
        String modelInput = knowledge.isEmpty() ? redacted : DiagnosisPrompt.withKnowledge(redacted, knowledge);
        String version = knowledge.isEmpty() ? DiagnosisPrompt.VERSION : DiagnosisPrompt.RAG_VERSION;

        LlmClient.Response response = diagnosisLlm.completeJson(DiagnosisPrompt.build(modelInput, false, !knowledge.isEmpty()));
        String text = response.text();
        DiagnosisPrompt.Diagnosis d;
        try {
            d = DiagnosisPrompt.parse(modelInput, text);
        } catch (IllegalArgumentException e) {
            log.warn("Diagnosis response unreadable: deploymentId={}", deploymentId);
            throw new AiProcessingException(ErrorCode.AI_PARSE_ERROR, "진단 응답을 읽을 수 없습니다");
        }
        // 지식 인용(/knowledge/i/...)은 knowledge로, 관측 근거는 cited로 나눈다
        List<DiagnosisPrompt.Citation> observed = new ArrayList<>();
        Map<Integer, KnowledgeCitation> cited = new LinkedHashMap<>();
        for (DiagnosisPrompt.Citation c : d.cited()) {
            var m = KNOWLEDGE_POINTER.matcher(c.pointer());
            if (!m.find()) {
                observed.add(c);
                continue;
            }
            KnowledgeItem item = knowledge.get(Integer.parseInt(m.group(1)));
            cited.putIfAbsent(Integer.parseInt(m.group(1)), new KnowledgeCitation(item.source(), item.ref(), item.title(),
                    item.repository(), item.version(), item.updatedAt(),
                    item.text().length() > 200 ? item.text().substring(0, 200) + "…" : item.text()));
        }
        warnings.addAll(0, d.warnings());
        log.info("Diagnosis: deploymentId={}, model={}, version={}, knowledge={}/{}, category={}, rootCause={}, abstain={}, warnings={}, inputTokens={}, cachedInputTokens={}, outputTokens={}, reasoningTokens={}, latencyMs={}",
                deploymentId, response.model(), version, cited.size(), knowledge.size(), d.category(), d.rootCause(), d.abstain(), warnings.size(),
                response.usage().inputTokens(), response.usage().cachedInputTokens(), response.usage().outputTokens(),
                response.usage().reasoningTokens(), response.latencyMs());
        return save(new DeploymentDiagnosisResponse(deploymentId, response.model(), version, d.category(), d.rootCause(),
                d.abstain(), d.summary(), d.observedFacts(), d.hypotheses(), d.nextChecks(), observed,
                List.copyOf(warnings), List.copyOf(cited.values()), false, null), diagnosisLlm.model(), response);
    }

    /** 지식 검색 실패는 진단을 막지 않는다. 지식 없이 진단하고 warning을 남긴다 */
    private List<KnowledgeItem> searchKnowledge(DeploymentDetailResponse detail, Long deploymentId, String redacted, List<String> warnings) {
        try {
            return knowledgeService.search(detail.deployment().repositoryId(), detail.deployment().repositoryName(), deploymentId, redacted);
        } catch (RuntimeException e) {
            log.warn("Knowledge search failed: deploymentId={}, error={}", deploymentId, e.getClass().getSimpleName());
            warnings.add("지식 검색에 실패해 지식 없이 진단했습니다");
            return List.of();
        }
    }

    /** 호출 생략 결과는 모델과 무관하다. 모델 결과는 지금 설정 모델·프롬프트로 만든 것만 쓴다 */
    private boolean reusable(DeploymentDiagnosis saved) {
        return NO_EVIDENCE_VERSION.equals(saved.getPromptVersion())
                || ((DiagnosisPrompt.VERSION.equals(saved.getPromptVersion()) || DiagnosisPrompt.RAG_VERSION.equals(saved.getPromptVersion()))
                && diagnosisLlm.model().equals(saved.getRequestedModel()));
    }

    static boolean noEvidence(DeploymentDetailResponse detail, DeploymentLogResponse logs) {
        FailureKind kind = detail.deployment().failureKind();
        return isEmpty(logs.pods()) && isEmpty(logs.events()) && isEmpty(detail.changes())
                && (kind == null || GENERIC_FAILURES.contains(kind));
    }

    private static boolean isEmpty(List<?> list) {
        return list == null || list.isEmpty();
    }

    private DeploymentDiagnosisResponse save(DeploymentDiagnosisResponse result, String requestedModel, LlmClient.Response response) {
        var usage = response == null ? null : response.usage();
        var saved = diagnosisRepository.save(new DeploymentDiagnosis(result.deploymentId(), requestedModel, result.promptVersion(),
                jsonMapper.writeValueAsString(result),
                usage == null ? null : usage.inputTokens(), usage == null ? null : usage.cachedInputTokens(),
                usage == null ? null : usage.outputTokens(), usage == null ? null : usage.reasoningTokens(),
                response == null ? null : response.latencyMs()));
        return result.saved(false, saved.getCreatedAt());
    }
}
