package klepaas.backend.ai.llm;

/**
 * 진단 모델 호출 (#100). 공급사를 바꿔도 같은 계약이다: 프롬프트를 보내고 JSON 텍스트와 사용량을 받는다.
 * 사용량은 각 API 응답이 보고한 값이다(추정 아님). 보고하지 않는 항목은 null이다.
 * 호출 실패·거부는 AiProcessingException(AI_001)으로 던진다.
 */
public interface LlmClient {

    /** 요청 하나의 최대 출력(생각 포함) 토큰. 평균이 아니라 모델이 길게 늘어질 때의 비용 상한이다 (#115) */
    long MAX_OUTPUT_TOKENS = 2048;

    String provider();

    String model();

    Response completeJson(String prompt);

    /** model은 응답이 보고한 실제 모델 이름이다. */
    record Response(String text, String model, Usage usage, long latencyMs) {
    }

    /**
     * 과금 기준 사용량. 입력은 캐시 입력을 포함하고, 출력은 생각(추론) 토큰을 포함한다.
     * cachedInputTokens·reasoningTokens는 그중 따로 보고된 양이며, 보고하지 않는 공급사는 null이다.
     */
    record Usage(long inputTokens, long outputTokens, Long cachedInputTokens, Long reasoningTokens) {
    }
}
