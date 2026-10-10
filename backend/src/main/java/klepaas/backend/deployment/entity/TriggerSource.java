package klepaas.backend.deployment.entity;

/** 배포 요청이 들어온 경로 (#95). */
public enum TriggerSource {
    WEB,        // 웹 콘솔 (JWT)
    CLI,        // CLI 토큰 (FULL)
    CI_TOKEN,   // 저장소별 배포 전용 토큰 (DEPLOY scope)
    CI_OIDC,    // GitHub Actions OIDC
    WEBHOOK,    // GitHub push webhook
    NLP         // 자연어 명령 (승인 후 실행)
}
