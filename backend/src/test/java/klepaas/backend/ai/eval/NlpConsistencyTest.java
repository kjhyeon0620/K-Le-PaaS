package klepaas.backend.ai.eval;

import klepaas.backend.ai.eval.NlpEval.CaseRuns;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class NlpConsistencyTest {

    private static Map<String, Boolean> score(boolean intent, Boolean args, boolean fabricated, boolean over) {
        Map<String, Boolean> s = new HashMap<>();
        s.put("parse_ok", true);
        s.put("intent", intent);
        s.put("args", args);
        s.put("fabricated", fabricated);
        s.put("fabricated_change", false);
        s.put("under", false);
        s.put("over", over);
        s.put("unconfirmed_change", false);
        return s;
    }

    @Test
    @DisplayName("통과는 intent·인자 정답이면서 날조·과대 등이 없을 때만이고, 오류(null)는 실패다")
    void passed() {
        assertThat(NlpEval.passed(score(true, true, false, false))).isTrue();
        assertThat(NlpEval.passed(score(true, null, false, false))).isTrue();
        assertThat(NlpEval.passed(score(true, false, false, false))).isFalse();
        assertThat(NlpEval.passed(score(true, true, true, false))).isFalse();
        assertThat(NlpEval.passed(score(true, true, false, true))).isFalse();
        assertThat(NlpEval.passed(null)).isFalse();
    }

    @Test
    @DisplayName("갈래별로 반복 모두 통과한 사례를 세고, intent만 맞아도 intent pass^n에는 들어간다")
    void consistencyByGroup() {
        var good = score(true, true, false, false);
        var fabricated = score(true, true, true, false);
        var c = NlpEval.consistency(List.of(
                new CaseRuns("normal", List.of(good, good)),
                new CaseRuns("normal", List.of(good, fabricated)),
                new CaseRuns("adversarial", Arrays.asList(good, null))));

        assertThat(c.passHat1()).isCloseTo(4.0 / 6, within(1e-9));
        assertThat(c.passHatN()).isCloseTo(1.0 / 3, within(1e-9));
        assertThat(c.intentPassHatN()).isCloseTo(2.0 / 3, within(1e-9));
        assertThat(c.allPassByGroup()).containsEntry("normal", "1/2").containsEntry("ambiguous", "0/0").containsEntry("adversarial", "0/1");
        assertThat(NlpEval.consistencyCells(c)).isEqualTo("0.667 · 0.333 | 0.667 | 1/2 | 0/0 | 0/1");
    }

    @Test
    @DisplayName("원시 계약: 코드 블록·소문자 intent는 보정, 목록 밖 intent·필드 누락은 따로 센다 (#159)")
    void rawContract() {
        assertThat(NlpEval.rawContract("{\"intent\":\"STATUS\",\"args\":{},\"confidence\":0.9}").values()).noneMatch(v -> v);

        var fenced = NlpEval.rawContract("```json\n{\"intent\":\"restart\",\"args\":{}}\n```");
        assertThat(fenced).containsEntry("not_plain_json", true).containsEntry("intent_not_upper", true)
                .containsEntry("intent_not_enum", false).containsEntry("confidence_missing", true);

        var unknown = NlpEval.rawContract("{\"intent\":\"restart_node\"}");
        assertThat(unknown).containsEntry("intent_not_enum", true).containsEntry("args_missing", true);

        assertThat(NlpEval.rawContract("sorry")).containsEntry("unreadable", true).containsEntry("intent_missing", false);
    }
}
