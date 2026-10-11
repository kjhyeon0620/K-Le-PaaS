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

    // apply 응답의 식별자. 이전 배포·apply 도중 중단은 null이며 추정하지 않는다.
    private String appliedResourceUid;
    private Long appliedGeneration;

    // 이 배포를 만든 GitHub webhook delivery. 재전송된 push가 다시 배포하지 않게 unique로 둔다
    @Column(length = 64, unique = true)
    private String githubDeliveryId;

    // 요청 기록 (#95). 이전 배포는 null이고 "기록 없음"으로 보인다
    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private TriggerSource triggerSource;

    // 사람이 요청했을 때의 사용자. CI·webhook은 null
    private Long requestedByUserId;

    // 자연어 명령으로 승인·실행된 배포의 명령 기록
    private Long commandLogId;

    // 승인한 배포 설정 지문 (#56). 있으면 apply 직전 적용할 설정과 같아야 한다. 직접 요청 배포는 null
    @Column(length = 64)
    private String approvedConfigFingerprint;

    // 복구 배포가 되돌린 이전 성공 배포 (#101). 일반 배포는 null
    private Long recoveredFromDeploymentId;

    // 적용한 배포 설정 JSON. env 값은 저장하지 않는다 (DeploymentConfigSnapshot)
    @Column(columnDefinition = "TEXT")
    private String configSnapshot;

    // 성공 판정 시 새 Pod에서 관측한 이미지 digest (sha256:...)
    private String imageDigest;

    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private FailureKind failureKind;

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
        this.status = DeploymentStatus.BUILDING;
    }

    public void recordRequest(TriggerSource triggerSource, Long requestedByUserId, Long commandLogId) {
        this.triggerSource = triggerSource;
        this.requestedByUserId = requestedByUserId;
        this.commandLogId = commandLogId;
    }

    public void pinApprovedConfig(String fingerprint) {
        this.approvedConfigFingerprint = fingerprint;
    }

    public void recordRecoveredFrom(Long deploymentId) {
        this.recoveredFromDeploymentId = deploymentId;
    }

    public void recordAppliedConfig(String configSnapshot) {
        this.configSnapshot = configSnapshot;
    }

    public void recordAppliedResource(String resourceUid, long generation) {
        this.appliedResourceUid = resourceUid;
        this.appliedGeneration = generation;
    }

    public void setGithubDeliveryId(String githubDeliveryId) {
        this.githubDeliveryId = githubDeliveryId;
    }

    public void setImageUri(String imageUri) {
        this.imageUri = imageUri;
    }

    public void updateCommitHash(String commitHash) {
        this.commitHash = commitHash;
    }

    // 배포 성공
    public void completeSuccess() {
        completeSuccess(null);
    }

    public void completeSuccess(String imageDigest) {
        this.imageDigest = imageDigest;
        this.status = DeploymentStatus.SUCCESS;
        this.finishedAt = LocalDateTime.now();
    }

    // 다른 변경으로 대체됨 (이 요청의 rollout 결과를 판정할 수 없다)
    public void cancel(String reason) {
        this.failureKind = FailureKind.SUPERSEDED;
        this.status = DeploymentStatus.CANCELED;
        this.failReason = reason;
        this.finishedAt = LocalDateTime.now();
    }

    // 실패 처리
    public void fail(String reason) {
        fail(reason, FailureKind.OTHER);
    }

    public void fail(String reason, FailureKind failureKind) {
        this.failureKind = failureKind;
        this.status = DeploymentStatus.FAILED;
        this.failReason = reason;
        this.finishedAt = LocalDateTime.now();
    }
}
