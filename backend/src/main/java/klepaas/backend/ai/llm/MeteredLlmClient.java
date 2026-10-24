package klepaas.backend.ai.llm;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;

/**
 * LLM 호출 지표 (#174). 모든 공급사 클라이언트를 감싸 호출 수(성공·실패), 성공 호출 지연, 공급사가 보고한 토큰을 기능별로 센다.
 * 프롬프트·응답·사용자 정보는 태그에 넣지 않는다. 결과와 예외는 바꾸지 않는다.
 */
final class MeteredLlmClient implements LlmClient {

    private final LlmClient delegate;
    private final MeterRegistry registry;
    private final String feature;

    private MeteredLlmClient(LlmClient delegate, MeterRegistry registry, String feature) {
        this.delegate = delegate;
        this.registry = registry;
        this.feature = feature;
    }

    /** feature는 diagnosis·nlp 같은 기능 이름 */
    static LlmClient wrap(String feature, LlmClient delegate, MeterRegistry registry) {
        return new MeteredLlmClient(delegate, registry, feature);
    }

    /** 감싼 공급사 클라이언트 (설정 테스트용) */
    LlmClient delegate() {
        return delegate;
    }

    @Override
    public String provider() {
        return delegate.provider();
    }

    @Override
    public String model() {
        return delegate.model();
    }

    @Override
    public Response completeJson(String prompt) {
        Response response;
        try {
            response = delegate.completeJson(prompt);
        } catch (RuntimeException e) {
            calls("error").increment();
            throw e;
        }
        calls("success").increment();
        Timer.builder("klepaas.llm.latency").description("LLM 성공 호출 지연")
                .tags("feature", feature, "provider", provider(), "model", model())
                .register(registry).record(Duration.ofMillis(response.latencyMs()));
        Usage u = response.usage();
        if (u != null) {
            tokens("input", u.inputTokens());
            tokens("output", u.outputTokens());
            if (u.cachedInputTokens() != null) tokens("cached_input", u.cachedInputTokens());
            if (u.reasoningTokens() != null) tokens("reasoning", u.reasoningTokens());
        }
        return response;
    }

    private Counter calls(String outcome) {
        return Counter.builder("klepaas.llm.calls").description("LLM 호출 수")
                .tags("feature", feature, "provider", provider(), "model", model(), "outcome", outcome)
                .register(registry);
    }

    /** 공급사가 보고한 과금 기준 토큰. 입력은 캐시 입력을, 출력은 추론 토큰을 포함한다(LlmClient.Usage) */
    private void tokens(String kind, long amount) {
        Counter.builder("klepaas.llm.tokens").description("공급사가 보고한 LLM 토큰")
                .tags("feature", feature, "provider", provider(), "model", model(), "kind", kind)
                .register(registry).increment(amount);
    }
}
