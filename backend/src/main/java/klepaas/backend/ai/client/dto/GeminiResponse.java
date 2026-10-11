package klepaas.backend.ai.client.dto;

import java.util.List;

public record GeminiResponse(
        List<Candidate> candidates,
        // 사용량과 실제 모델 버전 (#100 비용 비교). 이름은 API 그대로(camelCase)다
        @com.fasterxml.jackson.annotation.JsonProperty("usageMetadata") UsageMetadata usageMetadata,
        @com.fasterxml.jackson.annotation.JsonProperty("modelVersion") String modelVersion
) {
    public GeminiResponse(List<Candidate> candidates) {
        this(candidates, null, null);
    }

    public record UsageMetadata(
            @com.fasterxml.jackson.annotation.JsonProperty("promptTokenCount") Long promptTokenCount,
            @com.fasterxml.jackson.annotation.JsonProperty("candidatesTokenCount") Long candidatesTokenCount,
            @com.fasterxml.jackson.annotation.JsonProperty("thoughtsTokenCount") Long thoughtsTokenCount,
            @com.fasterxml.jackson.annotation.JsonProperty("cachedContentTokenCount") Long cachedContentTokenCount
    ) {
    }

    public record Candidate(
            Content content
    ) {
    }

    public record Content(
            List<Part> parts,
            String role
    ) {
    }

    public record Part(
            String text
    ) {
    }

    public String extractText() {
        if (candidates == null || candidates.isEmpty()) {
            return "";
        }
        Candidate candidate = candidates.get(0);
        if (candidate.content() == null || candidate.content().parts() == null || candidate.content().parts().isEmpty()) {
            return "";
        }
        return candidate.content().parts().get(0).text();
    }
}
