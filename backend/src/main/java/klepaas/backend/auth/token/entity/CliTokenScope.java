package klepaas.backend.auth.token.entity;

/**
 * CLI 토큰이 호출할 수 있는 API 범위. 서버의 SecurityConfig가 "SCOPE_" 권한으로 강제한다.
 */
public enum CliTokenScope {
    /** 조회(GET)와 비용 계산만 허용 */
    READ_ONLY,
    /** 발급한 사용자와 같은 권한 */
    FULL;

    public String authority() {
        return "SCOPE_" + name();
    }
}
