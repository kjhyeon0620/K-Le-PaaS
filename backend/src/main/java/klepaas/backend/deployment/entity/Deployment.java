package klepaas.backend.deployment.entity;

import jakarta.persistence.*;
import klepaas.backend.global.entity.BaseTimeEntity;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@Entity
@Table(name = "deployments")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Deployment extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "repository_id")
    private SourceRepository sourceRepository;

    @Column(nullable = false)
    private String branchName; // main, develop, ..

    @Column(nullable = false)
    private String commitHash;

    // Source Staging을 위한 필드
    // GitHub ZIP이 업로드된 Object Storage 경로 (예: builds/101/source.zip)
    private String storageObjectKey;

    private String externalBuildId;
    private String imageUri;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DeploymentStatus status;

    @Column(columnDefinition = "TEXT")
    private String failReason;

    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;

    @Builder
    public Deployment(SourceRepository sourceRepository, String branchName, String commitHash) {
        this.sourceRepository = sourceRepository;
        this.branchName = branchName;
        this.commitHash = commitHash;
        this.status = DeploymentStatus.PENDING; // 초기 상태
        this.startedAt = LocalDateTime.now();
    }

    // --- 비즈니스 로직 메서드 ---

    // 소스 업로드 시작
    public void startUpload() {
        this.status = DeploymentStatus.UPLOADING_SOURCE;
    }

    // 소스 업로드 완료 시
    public void markAsUploaded(String storageObjectKey) {
        this.storageObjectKey = storageObjectKey;
        this.status = DeploymentStatus.BUILDING;
    }

    // K8s 배포 시작
    public void startDeploying() {
        this.status = DeploymentStatus.DEPLOYING;
    }

    // 외부 빌드 시작 시
    public void markAsBuilding(String externalBuildId) {
        this.externalBuildId = externalBuildId;
        // 상태는 이미 BUILDING이거나 유지
    }

    public void setImageUri(String imageUri) {
        this.imageUri = imageUri;
    }

    public void updateCommitHash(String commitHash) {
        this.commitHash = commitHash;
    }

    // 배포 성공
    public void completeSuccess() {
        this.status = DeploymentStatus.SUCCESS;
        this.finishedAt = LocalDateTime.now();
    }

    // 실패 처리
    public void fail(String reason) {
        this.status = DeploymentStatus.FAILED;
        this.failReason = reason;
        this.finishedAt = LocalDateTime.now();
    }
}
