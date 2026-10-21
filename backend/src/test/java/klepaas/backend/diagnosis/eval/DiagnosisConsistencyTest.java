package klepaas.backend.diagnosis.eval;

import klepaas.backend.diagnosis.eval.DiagnosisEval.CaseRuns;
import klepaas.backend.diagnosis.eval.DiagnosisEval.Expected;
import klepaas.backend.diagnosis.eval.DiagnosisEval.Run;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class DiagnosisConsistencyTest {

    private static final Expected SHOULD_ABSTAIN = new Expected(null, null, List.of(), true, List.of(), null);
    private static final Expected HAS_CAUSE = new Expected("IMAGE", "image_not_pullable", List.of(), false, List.of(), null);

    private static Run run(Boolean rootCause, boolean abstainMatches) {
        Map<String, Boolean> s = new HashMap<>();
        s.put("category", rootCause);
        s.put("root_cause", rootCause);
        s.put("evidence_valid", true);
        s.put("evidence_supported", null);
        s.put("abstain", abstainMatches);
        s.put("injection", null);
        return new Run(s, false);
    }

    @Test
    @DisplayName("판단 보류는 기대값으로 실제 보류를 되돌려 세고, 잘못 보류·잘못된 단정을 나눈다")
    void abstainAndWrongAssert() {
        var c = DiagnosisEval.consistency(List.of(
                // 보류해야 함: 2회 보류, 1회 단정
                new CaseRuns(SHOULD_ABSTAIN, List.of(run(null, true), run(null, true), run(null, false))),
                // 원인 있음: 1회 정답, 1회 틀린 원인 단정, 1회 잘못 보류
                new CaseRuns(HAS_CAUSE, List.of(run(true, true), run(false, true), run(null, false)))));

        assertThat(c.repeat()).isEqualTo(3);
        assertThat(c.abstained()).isEqualTo(3);
        assertThat(c.abstainCorrect()).isEqualTo(2);
        assertThat(c.shouldAbstain()).isEqualTo(3);
        assertThat(c.wrongAbstain()).isEqualTo(1);
        assertThat(c.wrongAssert()).isEqualTo(2);
        assertThat(c.passHat1()).isCloseTo(3.0 / 6, within(1e-9));
        assertThat(c.passHatN()).isZero();
        assertThat(DiagnosisEval.consistencyCells(c)).isEqualTo("0.500 · 0.000 | 0.000 | 2/3 | 2/3 | 1 | 2");
    }

    @Test
    @DisplayName("호출 실패는 점수가 맞아도 통과로 세지 않는다")
    void errorIsNotAPass() {
        Run ok = run(true, true);
        var c = DiagnosisEval.consistency(List.of(new CaseRuns(HAS_CAUSE, List.of(ok, new Run(ok.score(), true)))));

        assertThat(c.passHat1()).isCloseTo(0.5, within(1e-9));
        assertThat(c.rootPassHatN()).isZero();
    }
}
