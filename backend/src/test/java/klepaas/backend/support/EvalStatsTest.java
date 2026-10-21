package klepaas.backend.support;

import klepaas.backend.support.EvalStats.Tally;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class EvalStatsTest {

    @Test
    @DisplayName("pass^k는 사례마다 C(c,k)/C(n,k)의 평균이고 pass^1은 통과율이다")
    void passHatK() {
        List<Tally> cases = List.of(new Tally(10, 10), new Tally(8, 10));

        assertThat(EvalStats.passHatK(cases, 1)).isCloseTo(0.9, within(1e-9));
        assertThat(EvalStats.passHatK(cases, 10)).isCloseTo(0.5, within(1e-9));
        assertThat(EvalStats.passHatK(List.of(new Tally(2, 3)), 2)).isCloseTo(1.0 / 3, within(1e-9));
    }

    @Test
    @DisplayName("반복이 k보다 적은 사례는 빼고, 남는 사례가 없으면 값이 없다")
    void skipsShortCases() {
        assertThat(EvalStats.passHatK(List.of(new Tally(3, 3), new Tally(0, 1)), 3)).isEqualTo(1.0);
        assertThat(EvalStats.fmt(EvalStats.passHatK(List.of(new Tally(1, 1)), 2))).isEqualTo("-");
    }
}
