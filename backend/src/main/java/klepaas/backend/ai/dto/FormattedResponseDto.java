package klepaas.backend.ai.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 프론트엔드 NLPResponse(FormattedResponse) 타입과 일치하는 구조화된 응답 DTO.
 * 프론트엔드의 lib/types/nlp-response.ts FormattedResponse 인터페이스와 동일한 형태.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FormattedResponseDto(
        String type,
        String message,
        String summary,
        DataWrapper data,
        Object metadata
) {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DataWrapper(Object formatted, Object raw) {}

    public static FormattedResponseDto of(String type, String message, String summary,
                                           Object formatted, Object metadata) {
        return new FormattedResponseDto(type, message, summary,
                new DataWrapper(formatted, null), metadata);
    }
}
