package klepaas.backend.auth.token.entity;

/**
 * CLI 토큰이 호출할 수 있는 API 범위. 서버의 SecurityConfig가 "SCOPE_" 권한으로 강제한다.
 */
public enum CliTokenScope {
    /** 조회(GET)와 비용 계산만 허용 */
    READ_ONLY,
    /** READ_ONLY + 자연어 명령 생성. MEDIUM/HIGH 명령은 사람이 FULL 권한으로 승인해야 실행된다 */
    PROPOSE_ONLY,
    /** 지정 저장소 1개의 배포 생성(POST /api/v1/deployments)만 허용. CI 콜백용 */
    DEPLOY,
    /** 발급한 사용자와 같은 권한 */
    FULL;

    public String authority() {
        return "SCOPE_" + name();
    }
}
