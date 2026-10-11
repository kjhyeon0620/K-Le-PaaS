package klepaas.backend.ai.knowledge;

import jakarta.persistence.*;
import klepaas.backend.global.entity.BaseTimeEntity;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 검색 단위 (#104). repositoryId가 null이면 모든 앱이 검색하는 플랫폼 runbook이다.
 * 임베딩은 float32 배열(little-endian bytea)이고, 검색할 때 앱에서 코사인 유사도를 계산한다.
 */
@Getter
@Entity
@Table(name = "knowledge_chunks")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class KnowledgeChunk extends BaseTimeEntity {

    public enum Source { DOCUMENT, INCIDENT, PLATFORM }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long repositoryId;

    /** 사건 조각이면 그 배포. 진단 중인 배포 자신의 사건은 검색하지 않는다 */
    private Long deploymentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Source sourceType;

    @Column(nullable = false, length = 200)
    private String sourceRef;

    @Column(nullable = false, length = 200)
    private String sourceTitle;

    @Column(length = 200)
    private String sourceVersion;

    private LocalDateTime sourceUpdatedAt;

    @Column(nullable = false)
    private int chunkIndex;

    @Column(nullable = false, columnDefinition = "text")
    private String text;

    @Column(nullable = false)
    private byte[] embedding;

    @Column(nullable = false, length = 100)
    private String embeddingModel;

    @Column(nullable = false, length = 64)
    private String contentHash;

    public KnowledgeChunk(Long repositoryId, Long deploymentId, Source sourceType, String sourceRef, String sourceTitle,
                          String sourceVersion, LocalDateTime sourceUpdatedAt, int chunkIndex, String text,
                          byte[] embedding, String embeddingModel, String contentHash) {
        this.repositoryId = repositoryId;
        this.deploymentId = deploymentId;
        this.sourceType = sourceType;
        this.sourceRef = sourceRef;
        this.sourceTitle = sourceTitle;
        this.sourceVersion = sourceVersion;
        this.sourceUpdatedAt = sourceUpdatedAt;
        this.chunkIndex = chunkIndex;
        this.text = text;
        this.embedding = embedding;
        this.embeddingModel = embeddingModel;
        this.contentHash = contentHash;
    }
}
