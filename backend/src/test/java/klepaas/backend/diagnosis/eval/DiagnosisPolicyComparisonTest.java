package klepaas.backend.diagnosis.eval;

import com.fasterxml.jackson.databind.JsonNode;
import klepaas.backend.diagnosis.eval.DiagnosisEval.Case;
import klepaas.backend.diagnosis.eval.DiagnosisEval.Expected;
import klepaas.backend.diagnosis.eval.SignatureRuleBaseline.State;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 저장된 진단 비교 결과(compare-*.json)의 LLM 진단과 시그니처 규칙을 같은 사례로 비교한다 (#161, 정의는 스펙 0161). 모델을 부르지 않는다.
 * DIAGNOSIS_EVAL_POLICY=<compare json>일 때만 실행한다. 정답은 저장된 score가 아니라 저장된 진단 라벨과 지금 정답 파일로 다시 판정한다.
 */
@EnabledIfEnvironmentVariable(named = "DIAGNOSIS_EVAL_POLICY", matches = ".+")
class DiagnosisPolicyComparisonTest {

    static final String HEADER = "| 변형 | 정책 | 호출 | 지원 사례 정답 | 지원 사례 보류 | 확신 오답 | 보류 사례 정답 보류 | LLM 호출 | 오류 |\n|"
            + "---|".repeat(9) + "\n";

    /** 한 결론의 판정 */
    enum Outcome { CORRECT, ABSTAINED_ON_CAUSE, CONFIDENT_WRONG, CORRECT_ABSTAIN }

    static Outcome judge(Expected e, String rootCause) {
        if (e.abstain()) return rootCause == null ? Outcome.CORRECT_ABSTAIN : Outcome.CONFIDENT_WRONG;
        if (rootCause == null) return Outcome.ABSTAINED_ON_CAUSE;
        return e.accepts(rootCause) ? Outcome.CORRECT : Outcome.CONFIDENT_WRONG;
    }

    @Test
    void comparePolicies() throws Exception {
        JsonNode saved = DiagnosisEval.JSON.readTree(Path.of(System.getenv("DIAGNOSIS_EVAL_POLICY")).toFile());
        Map<String, Case> cases = new LinkedHashMap<>();
        DiagnosisEval.loadCaseSet(saved.path("case_set").asText("base")).forEach(c -> cases.put(c.id(), c));
        StringBuilder table = new StringBuilder(HEADER);
        StringBuilder unresolved = new StringBuilder("\n규칙이 정하지 못한 사례(MATCH 아님)의 LLM 결과\n\n| 변형 | 사례 | 규칙 상태 | LLM 정답 | LLM 보류 | LLM 확신 오답 |\n|"
                + "---|".repeat(6) + "\n");
        saved.path("models").properties().forEach(v -> {
            Map<String, int[]> totals = new LinkedHashMap<>(); // 정책 → [호출, 정답, 지원 보류, 확신 오답, 보류 정답, LLM 호출, 오류]
            for (String policy : new String[]{"규칙 단독", "LLM 단독", "규칙 우선"}) totals.put(policy, new int[7]);
            v.getValue().path("cases").properties().forEach(entry -> {
                Case c = cases.get(entry.getKey());
                if (c == null) {
                    // 자동 채점에서 뺀 사례(예: #158 감사용)는 건너뛴다. 건너뛴 사례는 표 아래에 적는다
                    unresolved.append("| ").append(v.getKey()).append(" | ").append(entry.getKey()).append(" | 채점 묶음 밖 | - | - | - |\n");
                    return;
                }
                var rule = SignatureRuleBaseline.diagnose(c.input());
                String ruleAnswer = rule.state() == State.MATCH ? rule.rootCause() : null;
                int[] llmOnCase = new int[3];
                for (JsonNode run : entry.getValue()) {
                    boolean error = run.has("error");
                    JsonNode d = run.path("diagnosis");
                    String llmAnswer = error || d.path("abstain").asBoolean(true) ? null : text(d.get("root_cause"));
                    add(totals.get("규칙 단독"), judge(c.expected(), ruleAnswer), false, false);
                    add(totals.get("LLM 단독"), judge(c.expected(), llmAnswer), true, error);
                    boolean callLlm = rule.state() != State.MATCH;
                    add(totals.get("규칙 우선"), judge(c.expected(), callLlm ? llmAnswer : ruleAnswer), callLlm, callLlm && error);
                    if (callLlm) {
                        Outcome o = judge(c.expected(), llmAnswer);
                        llmOnCase[o == Outcome.CORRECT || o == Outcome.CORRECT_ABSTAIN ? 0 : o == Outcome.CONFIDENT_WRONG ? 2 : 1]++;
                    }
                }
                if (rule.state() != State.MATCH) {
                    unresolved.append("| ").append(v.getKey()).append(" | ").append(c.id()).append(" | ").append(rule.state())
                            .append(" | ").append(llmOnCase[0]).append(" | ").append(llmOnCase[1]).append(" | ").append(llmOnCase[2]).append(" |\n");
                }
            });
            totals.forEach((policy, t) -> table.append("| ").append(v.getKey()).append(" | ").append(policy)
                    .append(" | ").append(t[0]).append(" | ").append(t[1]).append(" | ").append(t[2]).append(" | ").append(t[3])
                    .append(" | ").append(t[4]).append(" | ").append(t[5]).append(" | ").append(t[6]).append(" |\n"));
        });
        System.out.println(table);
        System.out.println(unresolved);
        assertThat(table.toString().lines().count()).isGreaterThan(2);
    }

    private static void add(int[] t, Outcome o, boolean llmCall, boolean error) {
        t[0]++;
        switch (o) {
            case CORRECT -> t[1]++;
            case ABSTAINED_ON_CAUSE -> t[2]++;
            case CONFIDENT_WRONG -> t[3]++;
            case CORRECT_ABSTAIN -> t[4]++;
        }
        if (llmCall) t[5]++;
        if (error) t[6]++;
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() || !node.isTextual() || node.asText().isBlank() ? null : node.asText();
    }
}
