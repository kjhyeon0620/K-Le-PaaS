package klepaas.backend.ai.knowledge;

import jakarta.persistence.*;
import klepaas.backend.global.entity.BaseTimeEntity;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 앱 운영 문서 (#104). 본문은 비밀값 패턴을 가린 뒤 저장한다. 고칠 때마다 revision이 오른다. */
@Getter
@Entity
@Table(name = "knowledge_documents")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class KnowledgeDocument extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long repositoryId;

    @Column(nullable = false, length = 64)
    private String slug;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @Column(nullable = false)
    private int revision;

    private Long updatedByUserId;

    public KnowledgeDocument(Long repositoryId, String slug, String title, String content, Long userId) {
        this.repositoryId = repositoryId;
        this.slug = slug;
        this.title = title;
        this.content = content;
        this.revision = 1;
        this.updatedByUserId = userId;
    }

    public void update(String title, String content, Long userId) {
        this.title = title;
        this.content = content;
        this.revision++;
        this.updatedByUserId = userId;
    }
}
