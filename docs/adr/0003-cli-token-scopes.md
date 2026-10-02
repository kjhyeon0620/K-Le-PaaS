# ADR-0003: CLI 토큰 scope를 서버에서 강제

## Status
Accepted (#49 PR #63, #50 PR #64)

## Context
- CLI 토큰이 발급한 사용자의 전권을 가졌다. 모니터링 스크립트, AI agent, CI가 모두 전체 권한 토큰을 써야 했다.
- 승인 흐름(ADR-0002)이 있어도, 전권 토큰을 가진 agent는 스스로 승인할 수 있었다.

## Decision
- 토큰에 scope를 둔다: `READ_ONLY`, `PROPOSE_ONLY`, `DEPLOY`, `FULL`
- 규칙은 `SecurityConfig`에서 URL과 메서드 단위로 강제한다. `DEPLOY`의 저장소 일치 여부만 `DeploymentController`에서 확인한다.
- `PROPOSE_ONLY`는 자연어 명령 생성만 할 수 있고 승인은 403이다. 실행은 사람이 웹이나 FULL 권한으로 승인한다.
- `DEPLOY`는 발급할 때 본인 소유 저장소 1개를 지정해야 하고, CLI 웹 로그인으로는 요청할 수 없다.
- 기존 토큰은 `FULL`로 이관한다 (동작 변경 없음).

## Alternatives
- **클라이언트(CLI)에서 제한**: 우회할 수 있어 채택하지 않았다.
- **역할 기반 사용자 분리**: 1인 다중 용도 사용에 맞지 않는다.

## Consequences
- 장점: agent에게는 제안만, CI에게는 지정 저장소 배포만 허용된다. 범위를 벗어나면 403 `CLI_005`
- 감수하는 점: URL 패턴 기반이라 새 endpoint를 추가할 때 규칙을 함께 검토해야 한다 (기본은 `anyRequest → FULL`).

## Revisit When
- GitHub Actions OIDC(#65)로 CI 인증을 바꿀 때 `DEPLOY` 토큰의 역할을 재검토
- MCP 어댑터를 추가할 때

## References
- [architecture §4.1](../architecture.md#41-토큰-scope-adr-0003)
- [CLI_REFERENCE.md](../CLI_REFERENCE.md)
