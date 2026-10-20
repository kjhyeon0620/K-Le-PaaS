package klepaas.backend.ai.llm;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.errors.OpenAIException;
import com.openai.models.ReasoningEffort;
import com.openai.models.ResponseFormatJsonObject;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import klepaas.backend.ai.exception.AiProcessingException;
import klepaas.backend.global.exception.ErrorCode;

/**
 * OpenAI (공식 openai-java SDK, Chat Completions JSON 모드). reasoningEffort가 비어 있으면 모델 기본값을 쓴다 (#115).
 * 같은 형식을 받는 OpenAI 호환 서버(Ollama·LM Studio·vLLM 등)는 {@link #compatible}로 만든다 (#150).
 */
public class OpenAiLlmClient implements LlmClient {

    public static final String COMPATIBLE = "openai-compatible";

    /** SDK가 키를 요구한다. 키를 받지 않는 로컬 서버는 이 값을 무시한다 (#150 Q3) */
    private static final String NO_KEY = "not-needed";

    private final OpenAIClient client;
    private final String provider;
    private final String model;
    private final String reasoningEffort;

    public OpenAiLlmClient(String apiKey, String model, String reasoningEffort) {
        this(OpenAIOkHttpClient.builder().apiKey(apiKey).build(), "openai", model, reasoningEffort);
    }

    OpenAiLlmClient(OpenAIClient client, String model, String reasoningEffort) {
        this(client, "openai", model, reasoningEffort);
    }

    OpenAiLlmClient(OpenAIClient client, String provider, String model, String reasoningEffort) {
        this.client = client;
        this.provider = provider;
        this.model = model;
        this.reasoningEffort = reasoningEffort == null || reasoningEffort.isBlank() ? null : reasoningEffort;
    }

    /** baseUrl은 `/v1`까지 포함한 주소다 (예: Ollama `http://localhost:11434/v1`) */
    public static OpenAiLlmClient compatible(String baseUrl, String model, String reasoningEffort) {
        return new OpenAiLlmClient(OpenAIOkHttpClient.builder().baseUrl(baseUrl).apiKey(NO_KEY).build(),
                COMPATIBLE, model, reasoningEffort);
    }

    @Override
    public String provider() {
        return provider;
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public Response completeJson(String prompt) {
        long started = System.currentTimeMillis();
        ChatCompletion completion;
        try {
            var params = ChatCompletionCreateParams.builder()
                    .model(model)
                    .maxCompletionTokens(MAX_OUTPUT_TOKENS)
                    .responseFormat(ResponseFormatJsonObject.builder().build())
                    .addUserMessage(prompt);
            if (reasoningEffort != null) params.reasoningEffort(ReasoningEffort.of(reasoningEffort));
            completion = client.chat().completions().create(params.build());
        } catch (OpenAIException e) {
            throw new AiProcessingException(ErrorCode.AI_API_ERROR, provider + " API 호출 실패: " + e.getClass().getSimpleName());
        }
        String text = completion.choices().isEmpty() ? "" : completion.choices().get(0).message().content().orElse("");
        var usage = completion.usage();
        return new Response(text, completion.model(), new Usage(
                usage.map(u -> u.promptTokens()).orElse(0L),
                usage.map(u -> u.completionTokens()).orElse(0L),
                usage.flatMap(u -> u.promptTokensDetails()).flatMap(d -> d.cachedTokens()).orElse(null),
                usage.flatMap(u -> u.completionTokensDetails()).flatMap(d -> d.reasoningTokens()).orElse(null)),
                System.currentTimeMillis() - started);
    }
}
