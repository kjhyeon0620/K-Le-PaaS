package klepaas.backend.ai.knowledge;

import jakarta.persistence.*;
import klepaas.backend.global.entity.BaseTimeEntity;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 사람이 원인을 확정한 실패 배포 (#104). 배포마다 하나다. AI 진단 결과를 자동으로 넣지 않는다. */
@Getter
@Entity
@Table(name = "incidents")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Incident extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private Long deploymentId;

    @Column(nullable = false)
    private Long repositoryId;

    @Column(nullable = false, length = 32)
    private String category;

    @Column(nullable = false, length = 64)
    private String rootCause;

    @Column(columnDefinition = "text")
    private String resolution;

    private Long confirmedByUserId;

    public Incident(Long deploymentId, Long repositoryId, String category, String rootCause, String resolution, Long userId) {
        this.deploymentId = deploymentId;
        this.repositoryId = repositoryId;
        update(category, rootCause, resolution, userId);
    }

    public void update(String category, String rootCause, String resolution, Long userId) {
        this.category = category;
        this.rootCause = rootCause;
        this.resolution = resolution;
        this.confirmedByUserId = userId;
    }
}
