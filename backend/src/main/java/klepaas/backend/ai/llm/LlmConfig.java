package klepaas.backend.ai.llm;

import klepaas.backend.ai.client.GeminiClient;
import klepaas.backend.ai.exception.AiProcessingException;
import klepaas.backend.global.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 진단 모델 선택 (#100). diagnosis.llm.provider·model 설정으로 공급사를 고른다.
 * diagnosis.llm.reasoning-effort는 OpenAI에만 적용한다 (#115).
 * 선택한 공급사 키가 없으면 앱은 뜨고, 진단 요청만 AI_001로 실패한다.
 */
@Configuration
public class LlmConfig {

    @Bean
    LlmClient diagnosisLlm(@Value("${diagnosis.llm.provider}") String provider,
                           @Value("${diagnosis.llm.model}") String model,
                           @Value("${diagnosis.llm.reasoning-effort:}") String reasoningEffort,
                           @Value("${ANTHROPIC_API_KEY:}") String anthropicKey,
                           @Value("${OPENAI_API_KEY:}") String openAiKey,
                           GeminiClient geminiClient) {
        return switch (provider) {
            case "gemini" -> new GeminiLlmClient(geminiClient, model);
            case "anthropic" -> anthropicKey.isBlank() ? missingKey(provider, model) : new AnthropicLlmClient(anthropicKey, model);
            case "openai" -> openAiKey.isBlank() ? missingKey(provider, model) : new OpenAiLlmClient(openAiKey, model, reasoningEffort);
            default -> throw new IllegalStateException("diagnosis.llm.provider는 gemini·anthropic·openai 중 하나여야 합니다: " + provider);
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
