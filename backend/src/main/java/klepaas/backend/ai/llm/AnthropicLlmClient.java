package klepaas.backend.ai.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.errors.AnthropicException;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StopReason;
import klepaas.backend.ai.exception.AiProcessingException;
import klepaas.backend.global.exception.ErrorCode;

import java.util.stream.Collectors;

/** Claude (공식 anthropic-java SDK). JSON 출력은 프롬프트 지시로 받고 서버가 검증한다. 생각 토큰은 출력 토큰에 포함돼 따로 보고되지 않는다. */
public class AnthropicLlmClient implements LlmClient {

    private final AnthropicClient client;
    private final String model;

    public AnthropicLlmClient(String apiKey, String model) {
        this(AnthropicOkHttpClient.builder().apiKey(apiKey).build(), model);
    }

    AnthropicLlmClient(AnthropicClient client, String model) {
        this.client = client;
        this.model = model;
    }

    @Override
    public String provider() {
        return "anthropic";
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public Response completeJson(String prompt) {
        long started = System.currentTimeMillis();
        Message message;
        try {
            message = client.messages().create(MessageCreateParams.builder()
                    .model(model)
                    .maxTokens(8192L)
                    .addUserMessage(prompt)
                    .build());
        } catch (AnthropicException e) {
            throw new AiProcessingException(ErrorCode.AI_API_ERROR, "Claude API 호출 실패: " + e.getClass().getSimpleName());
        }
        if (message.stopReason().filter(r -> r.equals(StopReason.REFUSAL)).isPresent()) {
            throw new AiProcessingException(ErrorCode.AI_API_ERROR, "Claude가 요청을 거부했습니다");
        }
        String text = message.content().stream()
                .flatMap(block -> block.text().stream())
                .map(t -> t.text())
                .collect(Collectors.joining());
        var usage = message.usage();
        return new Response(text, message.model().asString(),
                new Usage(usage.inputTokens() + usage.cacheReadInputTokens().orElse(0L) + usage.cacheCreationInputTokens().orElse(0L),
                        usage.outputTokens(), usage.cacheReadInputTokens().orElse(0L), null),
                System.currentTimeMillis() - started);
    }
}
