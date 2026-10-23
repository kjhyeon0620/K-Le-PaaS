package klepaas.backend.diagnosis.eval;

import com.fasterxml.jackson.databind.JsonNode;
import klepaas.backend.diagnosis.eval.DiagnosisEval.Case;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 저장된 진단 비교 결과(compare-*.json)의 채점 값만으로 반복 일관성·판단 보류 표를 다시 낸다 (#156). 모델을 부르지 않는다.
 * DIAGNOSIS_EVAL_RESTAT=<compare json>일 때만 실행한다. 사례 기대값은 결과의 case_set으로 지금 사례 파일에서 읽는다.
 */
@EnabledIfEnvironmentVariable(named = "DIAGNOSIS_EVAL_RESTAT", matches = ".+")
class DiagnosisConsistencyRestatTest {

    @Test
    void restatSavedResult() throws Exception {
        JsonNode saved = DiagnosisEval.JSON.readTree(Path.of(System.getenv("DIAGNOSIS_EVAL_RESTAT")).toFile());
        List<Case> cases = DiagnosisEval.loadCaseSet(saved.path("case_set").asText("base"));
        StringBuilder table = new StringBuilder(DiagnosisEval.CONSISTENCY_HEADER);
        saved.path("models").properties().forEach(variant -> {
            JsonNode perCase = variant.getValue().path("cases");
            Map<String, List<DiagnosisEval.Run>> runsById = new LinkedHashMap<>();
            for (Case c : cases) {
                List<DiagnosisEval.Run> list = new ArrayList<>();
                perCase.path(c.id()).forEach(run -> {
                    Map<String, Boolean> score = new LinkedHashMap<>();
                    run.path("score").properties().forEach(s -> score.put(s.getKey(), s.getValue().isNull() ? null : s.getValue().asBoolean()));
                    list.add(new DiagnosisEval.Run(score, run.has("error")));
                });
                runsById.put(c.id(), list);
            }
            table.append(DiagnosisEval.consistencyRows(variant.getKey(), cases, runsById, "hard".equals(saved.path("case_set").asText())));
        });
        System.out.println(table);
        assertThat(table.toString().lines().count()).isGreaterThan(2);
    }
}
