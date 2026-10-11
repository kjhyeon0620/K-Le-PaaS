package klepaas.backend.ai.diagnosis;

import java.util.List;

/** 근거 기반 진단 결과 (#100). 저장하지 않는다. cited의 excerpt는 서버가 입력에서 잘라 넣은 원문이다. */
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
        List<String> warnings
) {
}
