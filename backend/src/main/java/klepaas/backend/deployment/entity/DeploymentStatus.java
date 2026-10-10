package klepaas.backend.deployment.entity;

public enum DeploymentStatus {
    PENDING,          // 1. 배포 요청 접수
    UPLOADING_SOURCE, // 2. GitHub -> Object Storage 스트리밍 중 (New!)
    BUILDING,         // 3. 클라우드 빌드 진행 중 (SourceBuild/CodeBuild)
    DEPLOYING,        // 4. Kubernetes 배포 중
    SUCCESS,          // 5. 완료
    FAILED,           // 6. 실패
    CANCELED,         // 7. 취소됨
    UNKNOWN          // 판정 불가. 재시작 대조(#57)만 기록한다.
}
