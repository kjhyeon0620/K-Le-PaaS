package klepaas.backend.ai.diagnosis;

import klepaas.backend.ai.llm.LlmClient;
import klepaas.backend.ai.exception.AiProcessingException;
import klepaas.backend.deployment.entity.DeploymentStatus;
import klepaas.backend.deployment.service.DeploymentDetailService;
import klepaas.backend.deployment.service.DeploymentService;
import klepaas.backend.global.exception.ErrorCode;
import klepaas.backend.global.exception.InvalidRequestException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 실패한 배포 요청의 근거 기반 읽기 전용 진단 (#100). 배포 상세·로그 API와 같은 JSON을 모델에 보내고,
 * 응답의 라벨·근거 위치를 검증한다. Kubernetes·DB 상태를 바꾸지 않고 결과를 저장하지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeploymentDiagnosisService {

    private static final int LOG_LINES = 40;

    private final DeploymentDetailService deploymentDetailService;
    private final DeploymentService deploymentService;
    private final LlmClient diagnosisLlm;
    private final JsonMapper jsonMapper;

    public DeploymentDiagnosisResponse diagnose(Long deploymentId, Long userId) {
        // 소유권 검사는 상세·로그 조회가 한다. 권한 밖이면 여기서 404로 끝나고 모델을 호출하지 않는다
        var detail = deploymentDetailService.getDetail(deploymentId, userId);
        DeploymentStatus status = detail.deployment().status();
        if (status != DeploymentStatus.FAILED && status != DeploymentStatus.UNKNOWN) {
            throw new InvalidRequestException(ErrorCode.INVALID_REQUEST, "실패하거나 판정 불가인 배포만 진단합니다");
        }
        var logs = deploymentService.getDeploymentLogs(deploymentId, LOG_LINES, userId);
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("detail", detail);
        input.put("logs", logs);
        String redacted = DiagnosisPrompt.redact(jsonMapper.writeValueAsString(input));

        LlmClient.Response response = diagnosisLlm.completeJson(DiagnosisPrompt.build(redacted));
        String text = response.text();
        DiagnosisPrompt.Diagnosis d;
        try {
            d = DiagnosisPrompt.parse(redacted, text);
        } catch (IllegalArgumentException e) {
            log.warn("Diagnosis response unreadable: deploymentId={}", deploymentId);
            throw new AiProcessingException(ErrorCode.AI_PARSE_ERROR, "진단 응답을 읽을 수 없습니다");
        }
        log.info("Diagnosis: deploymentId={}, model={}, category={}, rootCause={}, abstain={}, warnings={}, inputTokens={}, outputTokens={}, latencyMs={}",
                deploymentId, response.model(), d.category(), d.rootCause(), d.abstain(), d.warnings().size(),
                response.usage().inputTokens(), response.usage().outputTokens(), response.latencyMs());
        return new DeploymentDiagnosisResponse(deploymentId, response.model(), DiagnosisPrompt.VERSION, d.category(), d.rootCause(),
                d.abstain(), d.summary(), d.observedFacts(), d.hypotheses(), d.nextChecks(), d.cited(),
                List.copyOf(d.warnings()));
    }
}
