package klepaas.backend.ai.knowledge;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

/** 지식 API 요청·응답 (#104) */
public final class KnowledgeDtos {

    public record PutDocumentRequest(@NotBlank @Size(max = 200) String title,
                                     @NotBlank @Size(max = KnowledgeService.MAX_DOCUMENT_CHARS) String content) {
    }

    public record IncidentRequest(@NotBlank String category, @NotBlank String rootCause, @Size(max = 2000) String resolution) {
    }

    public record DocumentSummary(String slug, String title, int revision, long chunks, LocalDateTime updatedAt) {
    }

    public record IncidentSummary(Long deploymentId, String category, String rootCause, String resolution,
                                  long chunks, LocalDateTime updatedAt) {
    }

    public record KnowledgeListResponse(Long repositoryId, List<DocumentSummary> documents, List<IncidentSummary> incidents) {
    }

    /** 진단 입력에 넣는 검색 결과. text는 저장 전에 비밀값 패턴을 가린 본문이다 */
    public record KnowledgeItem(String source, String ref, String title, String repository, String version,
                                LocalDateTime updatedAt, String text, double similarity) {
    }

    /** 진단 응답에서 모델이 인용한 지식 */
    public record KnowledgeCitation(String source, String ref, String title, String repository, String version,
                                    LocalDateTime updatedAt, String excerpt) {
    }

    private KnowledgeDtos() {
    }
}
