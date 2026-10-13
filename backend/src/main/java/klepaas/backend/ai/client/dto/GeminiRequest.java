package klepaas.backend.ai.client.dto;

import java.util.List;

public record GeminiRequest(
        List<Content> contents,
        GenerationConfig generationConfig
) {
    public record Content(
            String role,
            List<Part> parts
    ) {
    }

    public record Part(
            String text
    ) {
    }

    // responseMimeType이 null이면 보내지 않는다 (기존 자연어 명령 요청은 그대로)
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record GenerationConfig(
            double temperature,
            int maxOutputTokens,
            String responseMimeType
    ) {
        public GenerationConfig(double temperature, int maxOutputTokens) {
            this(temperature, maxOutputTokens, null);
        }
    }

    /** JSON 하나만 돌려받는 요청 (#100 진단). 사고 토큰을 고려해 출력 한도를 넉넉히 둔다 */
    public static GeminiRequest json(String prompt) {
        return new GeminiRequest(
                List.of(new Content("user", List.of(new Part(prompt)))),
                new GenerationConfig(0.1, 8192, "application/json"));
    }

    public static GeminiRequest of(String systemPrompt, String userMessage) {
        return new GeminiRequest(
                List.of(
                        new Content("user", List.of(
                                new Part(systemPrompt + "\n\n사용자 입력: " + userMessage)
                        ))
                ),
                new GenerationConfig(0.1, 1024)
        );
    }

    public static GeminiRequest withHistory(String systemPrompt, List<Content> history, String userMessage) {
        var contents = new java.util.ArrayList<>(history);
        contents.add(new Content("user", List.of(
                new Part(systemPrompt + "\n\n사용자 입력: " + userMessage)
        )));
        return new GeminiRequest(contents, new GenerationConfig(0.1, 1024));
    }
}
