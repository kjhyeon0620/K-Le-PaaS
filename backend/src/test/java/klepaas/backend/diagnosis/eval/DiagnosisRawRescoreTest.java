package klepaas.backend.diagnosis.eval;

import com.fasterxml.jackson.databind.JsonNode;
import klepaas.backend.diagnosis.eval.DiagnosisEval.Case;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 저장된 진단 비교 결과(compare-*.json)의 모델 원문을 서버 보정 없이 다시 채점한다 (#159). 모델을 부르지 않는다.
 * DIAGNOSIS_EVAL_RAW=<compare json>일 때만 실행한다. 사례 기대값은 결과의 case_set(없으면 base)으로 지금 사례 파일에서 읽는다.
 */
@EnabledIfEnvironmentVariable(named = "DIAGNOSIS_EVAL_RAW", matches = ".+")
class DiagnosisRawRescoreTest {

    static final String HEADER = "| 변형 | 호출 | 오류 | JSON 실패 | 라벨 목록 밖 | 범주-원인 불일치 | 무효 근거 (호출·위치) | 보류인데 라벨 "
            + "| 원시 단정 | 원시 확신 오답 | 원시 구체 원인 정답 | 서버가 보류로 바꾼 단정 (맞음·틀림) | 보정 후 구체 원인 정답 "
            + "| 원시 근거 충분 | 보정 후 근거 충분 | 원시 묶음 밖 인용 |\n|"
            + "---|".repeat(16) + "\n";

    @Test
    void rescoreRawOutputs() throws Exception {
        JsonNode saved = DiagnosisEval.JSON.readTree(Path.of(System.getenv("DIAGNOSIS_EVAL_RAW")).toFile());
        Map<String, Case> cases = DiagnosisEval.loadCaseSet(saved.path("case_set").asText("base")).stream()
                .collect(Collectors.toMap(Case::id, Function.identity()));
        StringBuilder table = new StringBuilder(HEADER);
        saved.path("models").properties().forEach(v -> table.append(row(v.getKey(), v.getValue(), cases)));
        System.out.println(table);
        assertThat(table.toString().lines().count()).isGreaterThan(2);
    }

    static String row(String variant, JsonNode summary, Map<String, Case> cases) {
        boolean checkPointers = !variant.contains("input=trim");
        int calls = 0, errors = 0, jsonFail = 0, outOfList = 0, relation = 0, invalidCalls = 0, invalidPointers = 0,
                abstainWithLabel = 0, asserted = 0, wrong = 0, rawCorrect = 0, flippedRight = 0, flippedWrong = 0, postCorrect = 0,
                rawSufficient = 0, rawJudged = 0, postSufficient = 0, postJudged = 0, unrelated = 0;
        var perCase = summary.path("cases").properties();
        for (var entry : (Iterable<Map.Entry<String, JsonNode>>) perCase::iterator) {
            Case c = cases.get(entry.getKey());
            if (c == null) continue; // 자동 채점에서 뺀 사례(예: #158 감사용)
            int knowledge = summary.path("retrieved").path(c.id()).size();
            for (JsonNode run : entry.getValue()) {
                calls++;
                if (run.has("error") || !run.hasNonNull("raw")) {
                    errors++;
                    continue;
                }
                if (run.path("score").path("root_cause").asBoolean(false)) postCorrect++;
                RawDiagnosis.Verdict r = RawDiagnosis.judge(c, run.path("raw").asText(), knowledge, checkPointers);
                if (!r.json()) jsonFail++;
                if (!r.labelsInList()) outOfList++;
                if (!r.relationOk()) relation++;
                if (r.invalidPointers() > 0) invalidCalls++;
                invalidPointers += r.invalidPointers();
                if (r.abstainWithLabel()) abstainWithLabel++;
                if (r.asserted()) asserted++;
                if (r.confidentWrong()) wrong++;
                if (r.asserted() && !r.confidentWrong()) rawCorrect++;
                if (r.sufficient() != null) {
                    rawJudged++;
                    if (r.sufficient()) rawSufficient++;
                    unrelated += r.unrelated();
                }
                Boolean post = postSufficient(c, run.path("diagnosis"));
                if (post != null) {
                    postJudged++;
                    if (post) postSufficient++;
                }
                if (r.asserted() && run.path("diagnosis").path("abstain").asBoolean(false)) {
                    if (r.confidentWrong()) flippedWrong++;
                    else flippedRight++;
                }
            }
        }
        return "| " + variant + " | " + calls + " | " + errors + " | " + jsonFail + " | " + outOfList + " | " + relation
                + " | " + (checkPointers ? invalidCalls + " · " + invalidPointers : "-") + " | " + abstainWithLabel
                + " | " + asserted + " | " + wrong + " | " + rawCorrect + " | " + flippedRight + " · " + flippedWrong
                + " | " + postCorrect + " | " + (checkPointers ? fraction(rawSufficient, rawJudged) + " | " + fraction(postSufficient, postJudged) : "- | -")
                + " | " + (checkPointers ? String.valueOf(unrelated) : "-") + " |\n";
    }

    /** 서버 보정 후 진단(저장된 diagnosis)이 단정했을 때 남은 인용으로 근거 묶음을 덮는가. 해당 없으면 null */
    static Boolean postSufficient(Case c, JsonNode diagnosis) {
        if (c.expected().abstain() || diagnosis.path("abstain").asBoolean(true) || !diagnosis.hasNonNull("root_cause")) return null;
        List<String> cited = new ArrayList<>();
        diagnosis.path("cited").forEach(p -> cited.add(p.isTextual() ? p.asText() : p.path("pointer").asText()));
        return DiagnosisEval.evidenceSufficient(c.expected(), cited);
    }

    private static String fraction(int n, int of) {
        return of == 0 ? "-" : n + "/" + of;
    }
}
