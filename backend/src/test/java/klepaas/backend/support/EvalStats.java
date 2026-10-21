package klepaas.backend.support;

import java.util.List;

/** 평가 하네스 공통 통계 (#156). */
public final class EvalStats {

    private EvalStats() {
    }

    /** 사례 하나의 반복 결과: runs회 중 passed회 통과 */
    public record Tally(int passed, int runs) {
    }

    /**
     * pass^k (τ-bench): 같은 사례를 k번 돌려 k번 모두 통과할 확률의 사례 평균. 사례마다 C(c,k)/C(n,k).
     * 반복이 k보다 적은 사례는 뺀다. 남는 사례가 없으면 NaN.
     */
    public static double passHatK(List<Tally> cases, int k) {
        return cases.stream().filter(t -> t.runs() >= k)
                .mapToDouble(t -> comb(t.passed(), k) / comb(t.runs(), k))
                .average().orElse(Double.NaN);
    }

    private static double comb(int n, int k) {
        if (k < 0 || k > n) return 0;
        double r = 1;
        for (int i = 1; i <= k; i++) r = r * (n - k + i) / i;
        return r;
    }

    /** 표 칸: "0.967" 형식, 값이 없으면 "-" */
    public static String fmt(double v) {
        return Double.isNaN(v) ? "-" : String.format("%.3f", v);
    }
}
