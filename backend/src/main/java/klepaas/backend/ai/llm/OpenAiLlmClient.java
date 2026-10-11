package klepaas.backend.ai.llm;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.errors.OpenAIException;
import com.openai.models.ResponseFormatJsonObject;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import klepaas.backend.ai.exception.AiProcessingException;
import klepaas.backend.global.exception.ErrorCode;

/** OpenAI (공식 openai-java SDK, Chat Completions JSON 모드). */
public class OpenAiLlmClient implements LlmClient {

    private final OpenAIClient client;
    private final String model;

    public OpenAiLlmClient(String apiKey, String model) {
        this(OpenAIOkHttpClient.builder().apiKey(apiKey).build(), model);
    }

    OpenAiLlmClient(OpenAIClient client, String model) {
        this.client = client;
        this.model = model;
    }

    @Override
    public String provider() {
        return "openai";
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
            completion = client.chat().completions().create(ChatCompletionCreateParams.builder()
                    .model(model)
                    .maxCompletionTokens(8192L)
                    .responseFormat(ResponseFormatJsonObject.builder().build())
                    .addUserMessage(prompt)
                    .build());
        } catch (OpenAIException e) {
            throw new AiProcessingException(ErrorCode.AI_API_ERROR, "OpenAI API 호출 실패: " + e.getClass().getSimpleName());
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
