package klepaas.backend.ai.diagnosis;

import java.time.LocalDateTime;
import java.util.List;

import klepaas.backend.ai.knowledge.KnowledgeDtos.KnowledgeCitation;
import klepaas.backend.ai.knowledge.KnowledgeDtos.RetrievedKnowledge;

/**
 * 근거 기반 진단 결과 (#100). cited의 excerpt는 서버가 입력에서 잘라 넣은 원문이다.
 * reused는 저장한 결과를 돌려줬는지, diagnosedAt은 결과를 만든 시각이다 (#117).
 * knowledge는 모델이 인용한 검색 지식(문서·사건·플랫폼 runbook)이다. 관측 근거는 cited에 남는다 (#104).
 * retrievedKnowledge는 검색돼 입력에 들어간 지식 전체와 유사도·인용 여부다. 결과와 함께 이력에 저장된다 (#125).
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
        List<KnowledgeCitation> knowledge,
        List<RetrievedKnowledge> retrievedKnowledge,
        boolean reused,
        LocalDateTime diagnosedAt
) {
    DeploymentDiagnosisResponse saved(boolean reused, LocalDateTime diagnosedAt) {
        return new DeploymentDiagnosisResponse(deploymentId, model, promptVersion, category, rootCause, abstain, summary,
                observedFacts, hypotheses, nextChecks, cited, warnings, knowledge == null ? List.of() : knowledge,
                retrievedKnowledge == null ? List.of() : retrievedKnowledge, reused, diagnosedAt);
    }
}
