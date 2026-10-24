package klepaas.backend.ai.llm;

import klepaas.backend.ai.client.GeminiClient;
import klepaas.backend.ai.exception.AiProcessingException;
import klepaas.backend.global.exception.ErrorCode;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 모델 선택. 진단(#100)은 diagnosis.llm.*, 자연어 명령(#136)은 nlp.llm.*로 공급사·모델을 따로 고른다.
 * openai-compatible은 *.llm.base-url의 OpenAI 호환 서버(로컬 모델 등)를 부르고 키를 쓰지 않는다 (#150).
 * 두 빈은 이름(diagnosisLlm·nlpLlm)으로 주입한다. diagnosis.llm.reasoning-effort는 OpenAI 진단에만 적용한다 (#115).
 * 선택한 공급사 키가 없으면 앱은 뜨고, 그 모델을 쓰는 요청만 AI_001로 실패한다.
 * 두 빈은 호출 수·지연·토큰 지표(MeteredLlmClient, #174)로 감싼다.
 */
@Configuration
public class LlmConfig {

    @Bean
    LlmClient diagnosisLlm(@Value("${diagnosis.llm.provider}") String provider,
                           @Value("${diagnosis.llm.model}") String model,
                           @Value("${diagnosis.llm.reasoning-effort:}") String reasoningEffort,
                           @Value("${diagnosis.llm.base-url:}") String baseUrl,
                           @Value("${ANTHROPIC_API_KEY:}") String anthropicKey,
                           @Value("${OPENAI_API_KEY:}") String openAiKey,
                           GeminiClient geminiClient, MeterRegistry meterRegistry) {
        return MeteredLlmClient.wrap("diagnosis",
                create("diagnosis.llm", provider, model, reasoningEffort, baseUrl, anthropicKey, openAiKey, geminiClient), meterRegistry);
    }

    /** 자연어 명령 해석 (#136). 기본값은 #58 비교로 정했다 (스펙 0058·0136). reasoning effort는 두지 않는다 */
    @Bean
    LlmClient nlpLlm(@Value("${nlp.llm.provider}") String provider,
                     @Value("${nlp.llm.model}") String model,
                     @Value("${nlp.llm.base-url:}") String baseUrl,
                     @Value("${ANTHROPIC_API_KEY:}") String anthropicKey,
                     @Value("${OPENAI_API_KEY:}") String openAiKey,
                     GeminiClient geminiClient, MeterRegistry meterRegistry) {
        return MeteredLlmClient.wrap("nlp", create("nlp.llm", provider, model, "", baseUrl, anthropicKey, openAiKey, geminiClient), meterRegistry);
    }

    static LlmClient create(String setting, String provider, String model, String reasoningEffort, String baseUrl,
                            String anthropicKey, String openAiKey, GeminiClient geminiClient) {
        return switch (provider) {
            case "gemini" -> new GeminiLlmClient(geminiClient, model);
            case "anthropic" -> anthropicKey.isBlank() ? missingKey(provider, model) : new AnthropicLlmClient(anthropicKey, model);
            case "openai" -> openAiKey.isBlank() ? missingKey(provider, model) : new OpenAiLlmClient(openAiKey, model, reasoningEffort);
            case OpenAiLlmClient.COMPATIBLE -> {
                if (baseUrl == null || baseUrl.isBlank())
                    throw new IllegalStateException(setting + ".base-url이 비어 있습니다 (" + setting + ".provider=" + provider + ")");
                yield OpenAiLlmClient.compatible(baseUrl, model, reasoningEffort);
            }
            default -> throw new IllegalStateException(setting + ".provider는 gemini·anthropic·openai·openai-compatible 중 하나여야 합니다: " + provider);
        };
    }

    private static LlmClient missingKey(String provider, String model) {
        return new LlmClient() {
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
                throw new AiProcessingException(ErrorCode.AI_API_ERROR, provider + " API 키가 설정되지 않았습니다");
            }
        };
    }
}
