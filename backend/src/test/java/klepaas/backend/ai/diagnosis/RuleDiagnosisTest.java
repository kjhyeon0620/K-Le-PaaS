package klepaas.backend.ai.diagnosis;

import klepaas.backend.diagnosis.eval.DiagnosisEval;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 규칙 결과 문구 (#170): 평가 사례에서 MATCH하는 모든 규칙이 관측값을 담은 결과를 만들고, 구체 원인은 규칙 판정과 같다 */
class RuleDiagnosisTest {

    @Test
    void everyMatchedRuleProducesObservedResult() throws Exception {
        var cases = new ArrayList<>(DiagnosisEval.loadCases());
        cases.addAll(DiagnosisEval.loadCaseSet("blind-test"));
        cases.addAll(DiagnosisEval.loadCaseSet("test"));
        Set<String> rules = new HashSet<>();
        for (var c : cases) {
            var out = RuleDiagnosis.diagnose(7L, c.input().toString());
            if (out.response() == null) continue;
            var r = out.response();
            rules.add(out.rule().rule());
            assertThat(r.rootCause()).as(c.id()).isEqualTo(out.rule().rootCause());
            assertThat(r.summary()).as(c.id()).isNotBlank();
            assertThat(r.nextChecks()).as(c.id()).isNotEmpty();
            assertThat(r.cited()).as(c.id()).isNotEmpty().allSatisfy(ct -> assertThat(ct.excerpt()).isNotBlank());
            assertThat(r.observedFacts()).as(c.id()).isNotEmpty().noneMatch(f -> f.startsWith("{"));
        }
        assertThat(rules).containsExactlyInAnyOrder("R-IMG-REF", "R-IMG-PULL", "R-SCHED-CPU", "R-ENV", "R-PROBE-PORT", "R-PROBE-PROTO");
    }
}
