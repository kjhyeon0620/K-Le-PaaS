package klepaas.backend.ai.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 모델 가격표 (#176). `llm/pricing.json`(USD/1M 토큰, 확인 날짜·출처)을 읽어 공급사가 보고한 토큰으로 비용을 계산한다.
 * 평가 하네스도 같은 파일을 쓴다. 가격표에 없는 모델(로컬 모델 등)은 비용이 없다(0이 아니다).
 */
@Component
public class LlmPricing {

    static final String RESOURCE = "llm/pricing.json";
    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

    record Price(BigDecimal input, BigDecimal cachedInput, BigDecimal output) {
    }

    private final Map<String, Price> prices;

    @Autowired
    public LlmPricing() {
        this(load());
    }

    LlmPricing(Map<String, Price> prices) {
        this.prices = prices;
    }

    /**
     * 비용 = (input − cached_input) × 입력 가격 + cached_input × 캐시 입력 가격 + output × 출력 가격 (출력은 추론 포함).
     * model은 설정한 모델 이름(LlmClient.model())이다. 가격이 없으면 비어 있다
     */
    public Optional<BigDecimal> cost(String model, LlmClient.Usage usage) {
        Price p = prices.get(model);
        if (p == null || usage == null) return Optional.empty();
        long cached = usage.cachedInputTokens() == null ? 0 : usage.cachedInputTokens();
        BigDecimal total = p.input().multiply(BigDecimal.valueOf(Math.max(0, usage.inputTokens() - cached)))
                .add(p.cachedInput().multiply(BigDecimal.valueOf(cached)))
                .add(p.output().multiply(BigDecimal.valueOf(usage.outputTokens())));
        return Optional.of(total.divide(MILLION, 6, RoundingMode.HALF_UP));
    }

    private static Map<String, Price> load() {
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            JsonNode models = new ObjectMapper().readTree(in).path("models");
            Map<String, Price> out = new HashMap<>();
            models.properties().forEach(e -> out.put(e.getKey(), new Price(e.getValue().path("input").decimalValue(),
                    e.getValue().path("cached_input").decimalValue(), e.getValue().path("output").decimalValue())));
            return Map.copyOf(out);
        } catch (IOException e) {
            throw new UncheckedIOException("가격표를 읽을 수 없습니다: " + RESOURCE, e);
        }
    }
}
