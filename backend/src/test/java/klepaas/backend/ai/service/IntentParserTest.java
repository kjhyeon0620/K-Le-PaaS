package klepaas.backend.ai.service;

import klepaas.backend.ai.dto.ParsedIntent;
import klepaas.backend.ai.entity.Intent;
import klepaas.backend.ai.exception.AiProcessingException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IntentParserTest {

    private IntentParser intentParser;

    @BeforeEach
    void setUp() {
        intentParser = new IntentParser();
    }

    @Test
    @DisplayName("JSON 직접 응답 파싱 성공")
    void parseDirectJson() {
        String response = """
                {"intent": "SCALE", "args": {"deployment_id": 1, "replicas": 3}, "confidence": 0.95, "message": "스케일링합니다"}
                """;

        ParsedIntent result = intentParser.parse(response);

        assertThat(result.intent()).isEqualTo(Intent.SCALE);
        assertThat(result.args()).containsEntry("deployment_id", 1);
        assertThat(result.args()).containsEntry("replicas", 3);
        assertThat(result.confidence()).isEqualTo(0.95);
        assertThat(result.message()).isEqualTo("스케일링합니다");
    }

    @Test
    @DisplayName("마크다운 코드블록 내 JSON 파싱 성공")
    void parseMarkdownCodeBlock() {
        String response = """
                ```json
                {"intent": "DEPLOY", "args": {"repository_id": 5, "branch_name": "main"}, "confidence": 0.9, "message": "배포합니다"}
                ```
                """;

        ParsedIntent result = intentParser.parse(response);

        assertThat(result.intent()).isEqualTo(Intent.DEPLOY);
        assertThat(result.args()).containsEntry("repository_id", 5);
    }

    @Test
    @DisplayName("코드블록 없는 마크다운 파싱 성공")
    void parseCodeBlockWithoutJsonLabel() {
        String response = """
                ```
                {"intent": "HELP", "args": {}, "confidence": 1.0, "message": "도움말"}
                ```
                """;

        ParsedIntent result = intentParser.parse(response);

        assertThat(result.intent()).isEqualTo(Intent.HELP);
    }

    @Test
    @DisplayName("알 수 없는 Intent는 UNKNOWN으로 처리")
    void parseUnknownIntent() {
        String response = """
                {"intent": "INVALID_INTENT", "args": {}, "confidence": 0.3, "message": "알 수 없음"}
                """;

        ParsedIntent result = intentParser.parse(response);

        assertThat(result.intent()).isEqualTo(Intent.UNKNOWN);
    }

    @Test
    @DisplayName("빈 응답은 예외 발생")
    void parseEmptyResponse() {
        assertThatThrownBy(() -> intentParser.parse(""))
                .isInstanceOf(AiProcessingException.class);
    }

    @Test
    @DisplayName("JSON이 아닌 응답은 예외 발생")
    void parseNonJsonResponse() {
        assertThatThrownBy(() -> intentParser.parse("이것은 JSON이 아닙니다"))
                .isInstanceOf(AiProcessingException.class);
    }
}
