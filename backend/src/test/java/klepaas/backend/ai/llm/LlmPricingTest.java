package klepaas.backend.ai.llm;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** 가격표 비용 계산 (#176): 평가 하네스와 같은 식, 가격 없는 모델은 비용 없음 */
class LlmPricingTest {

    private final LlmPricing pricing = new LlmPricing();

    @Test
    void costUsesUncachedInputCachedInputAndOutputIncludingReasoning() {
        // gpt-6-luna: 입력 0.10, 캐시 0.01, 출력 0.50 (USD/1M). (2000−1000)×0.10 + 1000×0.01 + 500×0.50 = 360 → /1M
        var usage = new LlmClient.Usage(2000, 500, 1000L, 200L);
        assertThat(pricing.cost("gpt-6-luna", usage)).contains(new BigDecimal("0.000360"));
    }

    @Test
    void missingCachedInputIsTreatedAsZero() {
        assertThat(pricing.cost("gpt-6-luna", new LlmClient.Usage(1000, 100, null, null))).contains(new BigDecimal("0.000150"));
    }

    @Test
    void unknownModelHasNoCost() {
        assertThat(pricing.cost("gemma4:26b-a4b", new LlmClient.Usage(1000, 100, null, null))).isEmpty();
    }
}
