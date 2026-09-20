package klepaas.backend.global.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // Common
    ENTITY_NOT_FOUND(HttpStatus.NOT_FOUND, "COMMON_001", "요청한 리소스를 찾을 수 없습니다"),
    DUPLICATE_RESOURCE(HttpStatus.CONFLICT, "COMMON_002", "이미 존재하는 리소스입니다"),
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "COMMON_003", "잘못된 요청입니다"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "COMMON_004", "서버 내부 오류가 발생했습니다"),

    // Repository
    REPOSITORY_NOT_FOUND(HttpStatus.NOT_FOUND, "REPO_001", "저장소를 찾을 수 없습니다"),
    REPOSITORY_ALREADY_EXISTS(HttpStatus.CONFLICT, "REPO_002", "이미 등록된 저장소입니다"),

    // Deployment
    DEPLOYMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "DEPLOY_001", "배포를 찾을 수 없습니다"),
    DEPLOYMENT_CONFIG_NOT_FOUND(HttpStatus.NOT_FOUND, "DEPLOY_002", "배포 설정을 찾을 수 없습니다"),

    // User
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "USER_001", "사용자를 찾을 수 없습니다"),

    // Infrastructure
    SOURCE_UPLOAD_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "INFRA_001", "소스 업로드에 실패했습니다"),
    BUILD_TRIGGER_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "INFRA_002", "빌드 트리거에 실패했습니다"),
    BUILD_TIMEOUT(HttpStatus.INTERNAL_SERVER_ERROR, "INFRA_003", "빌드 시간이 초과되었습니다"),
    BUILD_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "INFRA_004", "빌드에 실패했습니다"),
    DEPLOY_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "INFRA_005", "배포에 실패했습니다"),
    NCP_API_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "INFRA_006", "NCP API 호출에 실패했습니다"),

    // AI / NLP
    AI_API_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "AI_001", "AI API 호출에 실패했습니다"),
    AI_PARSE_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "AI_002", "AI 응답 파싱에 실패했습니다"),
    COMMAND_LOG_NOT_FOUND(HttpStatus.NOT_FOUND, "AI_003", "명령 기록을 찾을 수 없습니다"),
    SESSION_NOT_FOUND(HttpStatus.NOT_FOUND, "AI_004", "세션을 찾을 수 없습니다"),
    COMMAND_CONFIRMATION_CONFLICT(HttpStatus.CONFLICT, "AI_005", "명령을 확인할 수 없습니다"),

    // CLI Token
    CLI_TOKEN_NOT_FOUND(HttpStatus.NOT_FOUND, "CLI_001", "CLI 토큰을 찾을 수 없습니다"),
    INVALID_CLI_TOKEN(HttpStatus.UNAUTHORIZED, "CLI_002", "유효하지 않은 CLI 토큰입니다"),
    CLI_AUTH_SESSION_NOT_FOUND(HttpStatus.NOT_FOUND, "CLI_003", "CLI 로그인 세션을 찾을 수 없습니다"),
    INVALID_CLI_AUTH_SESSION_STATE(HttpStatus.CONFLICT, "CLI_004", "CLI 로그인 세션 상태가 올바르지 않습니다"),

    // GitHub App
    GITHUB_APP_NOT_INSTALLED(HttpStatus.UNPROCESSABLE_ENTITY, "GH_001", "GitHub App이 저장소에 설치되지 않았습니다"),
    GITHUB_APP_TOKEN_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "GH_002", "GitHub App 토큰 발급에 실패했습니다"),
    GITHUB_APP_INSTALLATION_REQUIRED(HttpStatus.PRECONDITION_REQUIRED, "GH_003", "GitHub App 설치가 필요합니다");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
