package klepaas.backend.deployment.entity;

import jakarta.persistence.*;
import klepaas.backend.global.entity.BaseTimeEntity;
import klepaas.backend.user.entity.User;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "repositories")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SourceRepository extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String owner; // 예: "klepaas"

    @Column(nullable = false)
    private String repoName; // 예: "backend"

    @Column(nullable = false)
    private String gitUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CloudVendor cloudVendor;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    // NCP SourceBuild 프로젝트 ID (최초 빌드 시 생성 후 캐싱)
    private String externalBuildProjectId;

    @Builder
    public SourceRepository(User user, String owner, String repoName, String gitUrl, CloudVendor cloudVendor) {
        this.user = user;
        this.owner = owner;
        this.repoName = repoName;
        this.gitUrl = gitUrl;
        this.cloudVendor = cloudVendor;
    }

    // 도메인 로직: 클라우드 벤더 변경 (마이그레이션 시 사용)
    public void switchCloudVendor(CloudVendor newVendor) {
        this.cloudVendor = newVendor;
    }

    public void assignBuildProjectId(String projectId) {
        this.externalBuildProjectId = projectId;
    }
}
