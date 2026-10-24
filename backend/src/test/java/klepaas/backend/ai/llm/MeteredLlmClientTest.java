package klepaas.backend.ai.llm;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import klepaas.backend.ai.exception.AiProcessingException;
import klepaas.backend.global.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** LLM 지표 래퍼 (#174): 성공·실패·지연·토큰을 기록하고 결과와 예외는 바꾸지 않는다 */
class MeteredLlmClientTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private static LlmClient fake(LlmClient.Response response, RuntimeException failure) {
        return new LlmClient() {
            public String provider() { return "openai"; }
            public String model() { return "gpt-6-luna"; }
            public Response completeJson(String prompt) {
                if (failure != null) throw failure;
                return response;
            }
        };
    }

    private double count(String name, String... tags) {
        var c = registry.find(name).tags(tags).counter();
        return c == null ? 0 : c.count();
    }

    @Test
    void successRecordsCallLatencyAndReportedTokensOnly() {
        var response = new LlmClient.Response("{}", "gpt-6-luna-2026", new LlmClient.Usage(1200, 300, 1000L, null), 4500);
        LlmClient client = MeteredLlmClient.wrap("diagnosis", fake(response, null), registry);

        assertThat(client.completeJson("p")).isSameAs(response);

        assertThat(count("klepaas.llm.calls", "feature", "diagnosis", "provider", "openai", "model", "gpt-6-luna", "outcome", "success")).isEqualTo(1);
        assertThat(count("klepaas.llm.tokens", "feature", "diagnosis", "kind", "input")).isEqualTo(1200);
        assertThat(count("klepaas.llm.tokens", "feature", "diagnosis", "kind", "output")).isEqualTo(300);
        assertThat(count("klepaas.llm.tokens", "feature", "diagnosis", "kind", "cached_input")).isEqualTo(1000);
        // 공급사가 보고하지 않은 추론 토큰은 0으로 세지 않는다
        assertThat(registry.find("klepaas.llm.tokens").tags("kind", "reasoning").counter()).isNull();
        var latency = registry.find("klepaas.llm.latency").tags("feature", "diagnosis").timer();
        assertThat(latency.count()).isEqualTo(1);
        assertThat(latency.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS)).isEqualTo(4500);
    }

    @Test
    void failureCountsErrorAndRethrowsSameException() {
        var failure = new AiProcessingException(ErrorCode.AI_API_ERROR, "down");
        LlmClient client = MeteredLlmClient.wrap("nlp", fake(null, failure), registry);

        assertThatThrownBy(() -> client.completeJson("p")).isSameAs(failure);

        assertThat(count("klepaas.llm.calls", "feature", "nlp", "outcome", "error")).isEqualTo(1);
        assertThat(count("klepaas.llm.calls", "feature", "nlp", "outcome", "success")).isZero();
        assertThat(registry.find("klepaas.llm.latency").timer()).isNull();
        assertThat(registry.find("klepaas.llm.tokens").counter()).isNull();
    }
}
