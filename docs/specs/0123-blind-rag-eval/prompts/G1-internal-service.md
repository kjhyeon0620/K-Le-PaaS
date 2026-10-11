작은 사내 백엔드 서비스 하나의 명세를 써줘. 업무 영역: {{DOMAIN}}.
이 회사에만 있는 서비스로, 공개 오픈소스와 같은 이름이나 동작을 쓰지 마.

이 서비스는 셸 스크립트로 흉내 낼 거라 동작이 단순해야 해: 기동할 때 환경 변수만 검사하고 로그를 남긴 뒤, 포트 하나에서 HTTP 요청을 받는다. 외부 시스템(DB, 메시지 큐 등)에는 연결하지 않는다.

아래 형식의 JSON 하나로만 답해. 설명 문장은 JSON 밖에 쓰지 마.
{
  "name": "소문자와 하이픈으로 된 서비스 이름",
  "description": "역할 한 문단",
  "port": 숫자,
  "health_path": "/로 시작하는 HTTP 헬스 체크 경로",
  "env": [
    {"name": "환경 변수 이름", "required": true 또는 false, "default": "기본값 또는 null", "description": "의미", "format": "값 형식 설명 또는 null"}
  ],
  "startup_failures": [
    {"env": "관련 환경 변수 이름", "check": "missing 또는 empty 또는 invalid_format", "valid_pattern": "invalid_format이면 올바른 값의 정규식, 아니면 null", "log_line": "그때 남기는 실제 로그 한 줄 (서비스 고유 오류 코드 포함)", "exit_code": 숫자}
  ],
  "startup_logs": ["정상 기동 중 남기는 로그 줄들"],
  "ready_log": "요청을 받기 시작할 때 남기는 로그 줄"
}
startup_failures는 5개 이상, 로그는 실제 로그처럼 써줘.
