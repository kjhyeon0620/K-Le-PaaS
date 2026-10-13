package klepaas.backend.ai.service;

/**
 * 자연어 명령 해석에 보내는 문자열 (#136). #58 평가가 이 문자열로 모델을 비교했으므로 운영과 평가가 같은 함수를 쓴다.
 * 이전 GeminiRequest.of와 같은 모양이다: 지시문 뒤에 사용자 입력을 이어 붙인다.
 */
public final class NlpPrompt {

    private NlpPrompt() {
    }

    public static String build(String systemPrompt, String command) {
        return systemPrompt + "\n\n사용자 입력: " + command;
    }
}
