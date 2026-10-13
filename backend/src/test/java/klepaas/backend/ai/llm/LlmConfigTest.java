package klepaas.backend.ai.llm;

import klepaas.backend.ai.client.GeminiClient;
import klepaas.backend.ai.exception.AiProcessingException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 진단·자연어 명령 모델 빈 (#100, #136). 네트워크 호출 없이 어떤 클라이언트가 만들어지는지만 본다 */
class LlmConfigTest {

    private final LlmConfig config = new LlmConfig();
    private final GeminiClient gemini = new GeminiClient(RestClient.builder().build(), "k", "default-model");

    @Test
    @DisplayName("자연어 명령 빈은 nlp.llm 공급사·모델로 만들어지고 진단 빈과 따로 고른다")
    void nlpAndDiagnosisAreChosenSeparately() {
        LlmClient nlp = config.nlpLlm("openai", "gpt-6-luna", "", "openai-key", gemini);
        LlmClient diagnosis = config.diagnosisLlm("gemini", "gemini-3.8-flash", "", "", "", gemini);

        assertThat(nlp).isInstanceOf(OpenAiLlmClient.class);
        assertThat(nlp.model()).isEqualTo("gpt-6-luna");
        assertThat(diagnosis).isInstanceOf(GeminiLlmClient.class);
        assertThat(diagnosis.model()).isEqualTo("gemini-3.8-flash");
    }

    @Test
    @DisplayName("gemini로 되돌리면 nlp.llm.model을 쓴다 (gemini.api.model 아님)")
    void geminiUsesNlpModel() {
        LlmClient nlp = config.nlpLlm("gemini", "gemini-3.8-flash", "", "", gemini);

        assertThat(nlp).isInstanceOf(GeminiLlmClient.class);
        assertThat(nlp.model()).isEqualTo("gemini-3.8-flash");
    }

    @Test
    @DisplayName("키가 없으면 빈은 만들어지고 호출만 AI_001로 실패한다")
    void missingKeyFailsOnlyOnCall() {
        LlmClient nlp = config.nlpLlm("openai", "gpt-6-luna", "", "", gemini);

        assertThat(nlp.provider()).isEqualTo("openai");
        assertThatThrownBy(() -> nlp.completeJson("x"))
                .isInstanceOf(AiProcessingException.class)
                .hasMessageContaining("openai API 키가 설정되지 않았습니다");
    }

    @Test
    @DisplayName("알 수 없는 공급사는 어느 설정인지 밝히고 기동 시 실패한다")
    void unknownProviderFailsAtStartup() {
        assertThatThrownBy(() -> config.nlpLlm("mistral", "m", "", "", gemini))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("nlp.llm.provider");
    }
}
