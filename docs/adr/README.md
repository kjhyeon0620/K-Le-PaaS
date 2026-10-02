# Architecture Decision Records

되돌리는 비용이 큰 기술 결정과 그 이유, 그리고 결정 때문에 감수하는 점을 기록한다.
스펙의 Decisions 중에서 **여러 이슈에 영향을 주는 결정**을 여기로 옮긴다.

| ADR | 제목 | Status |
|---|---|---|
| [0001](0001-external-image-build.md) | 빌드는 프로젝트 CI, 배포는 K-Le-PaaS (외부 이미지 경로) | Accepted |
| [0002](0002-intent-risk-confirmation.md) | 자연어는 intent로 해석하고 위험도에 따라 승인 후 실행 | Accepted |
| [0003](0003-cli-token-scopes.md) | CLI 토큰 scope를 서버에서 강제 | Accepted |
| [0004](0004-flyway-forward-compatible-migrations.md) | Flyway와 이전 릴리스 호환 마이그레이션 | Accepted |
| [0005](0005-platform-release-systemd-receiver.md) | 플랫폼 자체 배포는 systemd 릴리스와 SSH receiver | Accepted |
| [0006](0006-resource-access-scope.md) | 리소스 접근은 namespace·저장소 라벨·운영자 허용 참조로 제한 | Accepted |

## 규칙

- 파일 이름은 `NNNN-<kebab-slug>.md`, 번호는 순서대로 붙인다.
- Status: `Proposed` | `Accepted` | `Superseded by NNNN` | `Deprecated`
- 결정을 뒤집을 때는 기존 ADR을 수정하지 않는다. 새 ADR을 쓰고 기존 ADR의 Status만 바꾼다.
- 결정을 만든 스펙이나 이슈를 링크한다. 운영 검증 원자료는 `.local/evidence/`에 두고 링크하지 않는다.

## 템플릿

```markdown
# ADR-NNNN: <제목>

## Status
Proposed

## Context
- 문제, 제약, 요구

## Decision
- 무엇을 하기로 했는가

## Alternatives
- 검토한 대안과 채택하지 않은 이유

## Consequences
- 장점:
- 감수하는 점:

## Revisit When
- 이 결정을 다시 검토해야 하는 조건

## References
- spec / 이슈 / PR / 문서
```
