package klepaas.backend.ai.llm;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.sun.net.httpserver.HttpServer;
import klepaas.backend.ai.client.GeminiClient;
import klepaas.backend.ai.exception.AiProcessingException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 공급사별 진단 모델 클라이언트가 같은 계약(텍스트·과금 기준 사용량·오류)으로 동작한다 (#100). 실제 API 대신 로컬 HTTP 서버. */
class LlmClientContractTest {

    private HttpServer server;
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private final AtomicReference<String> requestTarget = new AtomicReference<>();
    private final AtomicReference<String> geminiKeyHeader = new AtomicReference<>();

    private String serve(int status, String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            requestTarget.set(exchange.getRequestURI().toString());
            geminiKeyHeader.set(exchange.getRequestHeaders().getFirst("x-goog-api-key"));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void geminiCountsThoughtsAsBilledOutput() throws Exception {
        String base = serve(200, """
                {"candidates":[{"content":{"parts":[{"text":"{\\"abstain\\":true}"}],"role":"model"}}],
                 "usageMetadata":{"promptTokenCount":1200,"candidatesTokenCount":300,"thoughtsTokenCount":500,"cachedContentTokenCount":100},
                 "modelVersion":"gemini-2.5-flash"}""");
        var client = new GeminiLlmClient(new GeminiClient(RestClient.builder().baseUrl(base).build(), "k", "default-model"), "gemini-2.5-flash");

        var r = client.completeJson("prompt");

        assertThat(r.text()).isEqualTo("{\"abstain\":true}");
        assertThat(r.model()).isEqualTo("gemini-2.5-flash");
        assertThat(r.usage()).isEqualTo(new LlmClient.Usage(1200, 800, 100L, 500L));
        assertThat(requestBody.get()).contains("application/json").contains("prompt");
        // 키는 헤더로만 보낸다 (URL에 남지 않음)
        assertThat(geminiKeyHeader.get()).isEqualTo("k");
        assertThat(requestTarget.get()).isEqualTo("/v1beta/models/gemini-2.5-flash:generateContent");
    }

    @Test
    void anthropicReportsCacheInInputAndRejectsRefusal() throws Exception {
        String base = serve(200, """
                {"id":"msg_1","type":"message","role":"assistant","model":"claude-haiku-5-5","stop_reason":"end_turn",
                 "content":[{"type":"text","text":"{\\"abstain\\":true}"}],
                 "usage":{"input_tokens":1000,"output_tokens":400,"cache_read_input_tokens":200,"cache_creation_input_tokens":0}}""");
        var client = new AnthropicLlmClient(AnthropicOkHttpClient.builder().baseUrl(base).apiKey("k").maxRetries(0).build(), "claude-haiku-5-5");

        var r = client.completeJson("prompt");

        assertThat(r.text()).isEqualTo("{\"abstain\":true}");
        assertThat(r.model()).isEqualTo("claude-haiku-5-5");
        assertThat(r.usage()).isEqualTo(new LlmClient.Usage(1200, 400, 200L, null));
        stop();

        String refusal = serve(200, """
                {"id":"msg_2","type":"message","role":"assistant","model":"claude-haiku-5-5","stop_reason":"refusal",
                 "content":[],"usage":{"input_tokens":10,"output_tokens":0}}""");
        var refusing = new AnthropicLlmClient(AnthropicOkHttpClient.builder().baseUrl(refusal).apiKey("k").maxRetries(0).build(), "claude-haiku-5-5");
        assertThatThrownBy(() -> refusing.completeJson("prompt")).isInstanceOf(AiProcessingException.class);
    }

    @Test
    void openAiReportsCachedAndReasoningTokens() throws Exception {
        String base = serve(200, """
                {"id":"c1","object":"chat.completion","created":1,"model":"gpt-5-mini",
                 "choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"{\\"abstain\\":true}"}}],
                 "usage":{"prompt_tokens":900,"completion_tokens":600,"total_tokens":1500,
                          "prompt_tokens_details":{"cached_tokens":300},"completion_tokens_details":{"reasoning_tokens":450}}}""");
        var client = new OpenAiLlmClient(OpenAIOkHttpClient.builder().baseUrl(base).apiKey("k").maxRetries(0).build(), "gpt-5-mini");

        var r = client.completeJson("prompt");

        assertThat(r.text()).isEqualTo("{\"abstain\":true}");
        assertThat(r.usage()).isEqualTo(new LlmClient.Usage(900, 600, 300L, 450L));
        assertThat(requestBody.get()).contains("json_object");
    }

    @Test
    void apiErrorsBecomeAiProcessingException() throws Exception {
        String base = serve(500, "{\"error\":{\"message\":\"boom\"}}");
        var anthropic = new AnthropicLlmClient(AnthropicOkHttpClient.builder().baseUrl(base).apiKey("k").maxRetries(0).build(), "m");
        var openai = new OpenAiLlmClient(OpenAIOkHttpClient.builder().baseUrl(base).apiKey("k").maxRetries(0).build(), "m");

        assertThatThrownBy(() -> anthropic.completeJson("p")).isInstanceOf(AiProcessingException.class);
        assertThatThrownBy(() -> openai.completeJson("p")).isInstanceOf(AiProcessingException.class);
    }
}
