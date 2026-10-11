# K-Le-PaaS MCP 서버

Claude Code, Codex 같은 AI 에이전트가 [MCP](https://modelcontextprotocol.io)로 K-Le-PaaS에 연결해 배포 상태를 조회하고, 실패 원인을 확인하고, 복구를 **제안**할 수 있다. 제안을 실행하는 것은 항상 사람이다.

- 서버 주소: `<API_BASE>/api/v1/mcp` (Streamable HTTP)
- 인증: K-Le-PaaS CLI 토큰을 `Authorization: Bearer <token>`으로 보낸다
- 할 수 있는 것: 앱·배포 조회, 배포 로그와 Kubernetes 이벤트 확인, AI 진단, 복구 계획 확인, 복구 제안
- 할 수 없는 것: 승인, 배포 실행, 재시작, 스케일, 설정 변경

`<API_BASE>`는 CLI의 `KLEPAAS_BASE_URL`과 같은 API 주소다 (로컬 개발 서버는 `http://localhost:8080`).

## 1. 토큰 준비

에이전트에는 **제안 전용(`propose-only`) 토큰**을 쓴다. 조회와 제안만 되고 승인·실행은 막혀 있다.

```bash
# 웹 승인 화면에서 "제안 전용" 권한을 확인하고 승인한다
klepaas auth login --web --scope propose-only
```

웹 콘솔 `Settings > CLI Tokens`에서 발급할 수도 있다. 토큰은 설정 파일에 직접 쓰지 말고 환경변수로 넘긴다.

```bash
export KLEPAAS_TOKEN="kpa_cli_..."
```

| 토큰 권한 | MCP 사용 |
|---|---|
| `propose-only` (권장) | 모든 도구 |
| `read-only` | 조회 도구만. `propose_recovery`는 거절(`CLI_005`) |
| `full` | 모든 도구. 에이전트에는 권장하지 않음 |
| 배포 전용(`DEPLOY`) | 연결 불가 (403) |

## 2. 클라이언트 연결

### Claude Code

```bash
claude mcp add --transport http klepaas "<API_BASE>/api/v1/mcp" \
  --header "Authorization: Bearer $KLEPAAS_TOKEN"
```

### Codex

`~/.codex/config.toml`:

```toml
[mcp_servers.klepaas]
url = "<API_BASE>/api/v1/mcp"
bearer_token_env_var = "KLEPAAS_TOKEN"
```

### 그 밖의 클라이언트

Streamable HTTP MCP를 지원하고 요청 헤더를 지정할 수 있는 클라이언트면 된다. 주소와 `Authorization: Bearer` 헤더만 설정한다. 서버는 세션을 쓰지 않는다.

### 연결 확인

도구 7개가 보이면 정상이다.

```bash
curl -s -X POST "<API_BASE>/api/v1/mcp" \
  -H "Authorization: Bearer $KLEPAAS_TOKEN" \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
```

## 3. 도구

모든 도구는 같은 기능의 REST API와 같은 결과(JSON, snake_case)를 돌려준다. 내 저장소와 배포만 보인다.

| 도구 | 하는 일 | 입력 | 변경 |
|---|---|---|---|
| `list_repositories` | 내 앱(저장소) 목록 | 없음 | 없음 |
| `list_deployments` | 저장소의 배포 요청 목록 (최신순) | `repository_id`, `page`(기본 0), `size`(기본 20, 최대 100) | 없음 |
| `get_deployment_detail` | 배포 요청 상세: 실패 종류·사유, 적용 설정(env는 이름만), 직전 성공 배포 대비 변경 | `deployment_id` | 없음 |
| `get_deployment_logs` | 그 배포가 적용한 Pod의 상태·최근 로그·Kubernetes 이벤트 | `deployment_id`, `lines`(기본 100, 1~200) | 없음 |
| `diagnose_deployment` | 실패한 배포의 근거 기반 AI 진단 (실험). 앱 운영 문서·확정 사건·플랫폼 runbook을 검색해 근거로 쓰고(`knowledge`), 저장된 결과가 있으면 그것을 돌려준다 | `deployment_id`, `refresh`(기본 false) | 없음 (`refresh`는 모델 호출 비용) |
| `get_recovery_plan` | 이전 성공 배포로 되돌리는 계획: 이미지, 되돌릴 설정, env 차이, 실행 가능 여부 | `target_deployment_id` | 없음 |
| `propose_recovery` | 이전 성공 배포로 복구하는 **승인 대기 명령**을 만든다 | `target_deployment_id`, `reason`(선택, 200자 이하) | 승인 대기 명령 1건 (실행 없음) |

`propose_recovery` 결과 예:

```json
{
  "command_log_id": 21,
  "intent": "ROLLBACK",
  "message": "복구 제안을 만들었습니다. 사람이 10분 안에 승인해야 실행됩니다.",
  "risk_level": "HIGH",
  "requires_confirmation": true,
  "approval_target": {
    "repository": "owner/app",
    "commit": "abcdef1",
    "image": "ghcr.io/owner/app@sha256:...",
    "recovered_from_deployment_id": 40,
    "config_fingerprint": "a8fa5589",
    "blocked_reasons": []
  }
}
```

## 4. 제안 승인 (사람)

에이전트가 만든 제안은 사람이 전체 권한으로 승인해야 실행된다.

```bash
klepaas confirm 21          # 승인 대상을 보고 확인
klepaas deployments wait <새 배포 id>
```

- 제안은 10분 안에 승인해야 한다. 지나면 만료되고, 에이전트가 다시 제안하면 된다.
- 제안 뒤에 설정이 바뀌면 계획이 달라져 승인이 거절된다(`AI_006`). 바뀐 내용으로 다시 제안해야 한다.
- 승인하지 않으면 아무것도 바뀌지 않는다.

## 5. 사용 예

에이전트에 이렇게 요청하면 된다. 도구 이름은 알려 주지 않아도 된다.

```text
내 앱 중에 최근 배포가 실패한 게 있는지 찾아서, 왜 실패했는지 근거와 함께 알려줘.
이전에 정상이던 버전으로 되돌릴 수 있으면 복구를 제안까지만 해줘. 직접 실행하지는 마.
```

에이전트는 보통 `list_repositories` → `list_deployments` → `get_deployment_detail` / `get_deployment_logs` / `diagnose_deployment` → `get_recovery_plan` → `propose_recovery` 순서로 진행하고, 승인할 `command_log_id`를 알려 준다.

## 6. 오류와 문제 해결

업무 오류는 도구 결과의 `isError: true`와 `{"code", "message"}`로 온다.

| 증상 | 원인 | 조치 |
|---|---|---|
| HTTP 401 | 토큰 없음·만료·폐기 | 토큰을 다시 발급하고 환경변수를 확인 |
| HTTP 403 | 배포 전용(CI) 토큰 | `propose-only` 토큰 사용 |
| HTTP 404 (연결 시) | 주소가 백엔드 API가 아님 | `<API_BASE>`가 CLI의 API 주소와 같은지 확인 |
| `CLI_005` | 토큰 권한 밖 (예: read-only로 제안) | `propose-only` 토큰 사용 |
| `DEPLOY_001` / `REPO_001` | 없는 배포·저장소, 또는 다른 사용자 것 | ID 확인 (다른 사용자 자원은 존재를 드러내지 않는다) |
| `DEPLOY_005` | 복구할 수 없는 계획 (설정 기록 없음, KANIKO 빌드, env 차이) | `get_recovery_plan`의 `blocked_reasons` 확인 |
| `COMMON_003` | 진단 대상이 아님 (성공·진행 중 배포) | 실패하거나 판정 불가인 배포만 진단 가능 |
| `AI_001` / `AI_002` | 진단 모델 호출 실패·응답 해석 실패 | 잠시 뒤 다시 시도. 로그·상세로 직접 확인 |
| 입력 오류 | 필수 값 누락, 범위·길이 초과 | 도구 입력 스키마 확인 |

## 7. 보안 메모

- 토큰은 그 사용자의 저장소·배포에만 접근한다. 다른 사용자 자원은 조회·제안 모두 막힌다.
- 로그와 진단 입력에서 비밀값 패턴은 가린다. env는 이름만 보이고 값은 보이지 않는다.
- 에이전트가 로그 속 지시문을 따르지 않도록 진단 프롬프트가 막지만, 제안 내용은 승인 전에 사람이 확인한다.
- 승인·실행 도구는 일부러 두지 않았다. 권한이 넓은 `full` 토큰을 에이전트에 주지 않는다.

관련 문서: [CLI 레퍼런스의 AI agent 사용 절차](CLI_REFERENCE.md#ai-agent-사용-절차-102), [구조·권한](architecture.md), 설계 기록 [스펙 0103](specs/0103-mcp-server/spec.md).
