package klepaas.backend.ai.llm;

import klepaas.backend.ai.client.GeminiClient;
import klepaas.backend.ai.client.dto.GeminiRequest;
import klepaas.backend.ai.client.dto.GeminiResponse;

/** Gemini (기존 RestClient 기반 GeminiClient). */
public class GeminiLlmClient implements LlmClient {

    private final GeminiClient client;
    private final String model;

    public GeminiLlmClient(GeminiClient client, String model) {
        this.client = client;
        this.model = model;
    }

    @Override
    public String provider() {
        return "gemini";
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public Response completeJson(String prompt) {
        long started = System.currentTimeMillis();
        GeminiResponse response = client.generate(model, GeminiRequest.json(prompt));
        var u = response.usageMetadata();
        // 출력 토큰은 다른 공급사와 같게 생각 토큰을 포함한 과금 기준으로 맞춘다
        Usage usage = u == null ? new Usage(0, 0, null, null) : new Usage(value(u.promptTokenCount()),
                value(u.candidatesTokenCount()) + value(u.thoughtsTokenCount()), u.cachedContentTokenCount(), u.thoughtsTokenCount());
        return new Response(response.extractText(), response.modelVersion() != null ? response.modelVersion() : model,
                usage, System.currentTimeMillis() - started);
    }

    private static long value(Long v) {
        return v == null ? 0 : v;
    }
}
