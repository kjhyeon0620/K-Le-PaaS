package klepaas.backend.deployment.entity;

/** 실패·취소된 배포의 종류 (#95). 판정 시점에 정하고, 설명은 종류별 고정 문구다. */
public enum FailureKind {
    IMAGE_PULL,          // 이미지를 받지 못함 (ImagePullBackOff, InvalidImageName, ErrImageNeverPull)
    CRASH_LOOP,          // 컨테이너가 반복 종료 (CrashLoopBackOff)
    CONFIG_ERROR,        // 컨테이너 설정 오류 (CreateContainerConfigError)
    READINESS_TIMEOUT,   // 타임아웃까지 실행 중이나 Ready가 아님
    UNSCHEDULABLE,       // 타임아웃까지 스케줄되지 못함
    ROLLOUT_TIMEOUT,     // 그 밖의 rollout 타임아웃
    PROGRESS_DEADLINE,   // Kubernetes ProgressDeadlineExceeded
    DEPLOYMENT_MISSING,  // 대기 중 Deployment가 사라짐
    SUPERSEDED,          // 다른 변경으로 대체됨 (CANCELED)
    BUILD_FAILED,        // 빌드·이미지 결정 단계 실패
    APPLY_FAILED,        // manifest 적용 실패
    OTHER
}
