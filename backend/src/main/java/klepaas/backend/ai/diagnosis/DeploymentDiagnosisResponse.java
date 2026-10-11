package klepaas.backend.ai.diagnosis;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 근거 기반 진단 결과 (#100). cited의 excerpt는 서버가 입력에서 잘라 넣은 원문이다.
 * reused는 저장한 결과를 돌려줬는지, diagnosedAt은 결과를 만든 시각이다 (#117).
 */
public record DeploymentDiagnosisResponse(
        Long deploymentId,
        String model,
        String promptVersion,
        String category,
        String rootCause,
        boolean abstain,
        String summary,
        List<String> observedFacts,
        List<String> hypotheses,
        List<String> nextChecks,
        List<DiagnosisPrompt.Citation> cited,
        List<String> warnings,
        boolean reused,
        LocalDateTime diagnosedAt
) {
    DeploymentDiagnosisResponse saved(boolean reused, LocalDateTime diagnosedAt) {
        return new DeploymentDiagnosisResponse(deploymentId, model, promptVersion, category, rootCause, abstain, summary,
                observedFacts, hypotheses, nextChecks, cited, warnings, reused, diagnosedAt);
    }
}
