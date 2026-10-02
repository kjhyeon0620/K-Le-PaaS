# ADR-0002: 자연어는 intent로 해석하고 위험도에 따라 승인 후 실행

## Status
Accepted (NLP 명령 MVP, 2단계에서 승인 소유권·만료·원자적 소비 보강)

## Context
- 자연어와 agent 요청을 운영 작업으로 바꾸면서도, LLM의 오해석이 곧바로 인프라 변경으로 이어지면 안 된다.
- 같은 명령이 동시에 승인되거나 오래된 제안이 나중에 실행되는 문제를 막아야 한다.

## Decision
- 자연어는 Gemini로 구조화된 `Intent`로 해석한다. 셸 명령을 생성하거나 실행하지 않는다. 실행은 백엔드 서비스와 Fabric8 호출로만 한다.
- 위험도는 intent로 정한다. `DEPLOY`/`ROLLBACK`/`ROLLBACK_EXECUTION`은 HIGH, `SCALE`/`RESTART`는 MEDIUM, 조회는 LOW
- LOW는 즉시 실행한다. MEDIUM/HIGH는 `CommandLog`(PENDING)로 저장하고, 본인이 10분 이내에 승인할 때만 `PENDING → EXECUTING`으로 한 번만 전이해 실행한다.
- 웹, CLI, 자연어가 같은 백엔드 정책을 쓴다. CLI에 별도의 해석이나 승인 로직을 두지 않는다.

## Alternatives
- **모든 변경 REST에 승인 강제**: agent-safe의 실제 공백은 토큰이 사용자 전권을 갖는 점이었다 → ADR-0003으로 해결
- **인자 기반 위험도** (예: replicas 0은 HIGH): 모델 평가(#58) 결과를 본 뒤 판단

## Consequences
- 장점: 오해석이 변경으로 이어지기 전에 사람이 확인한다. 명령 이력으로 감사할 수 있다.
- 감수하는 점:
  - 위험도가 intent 단위라서 같은 SCALE이라도 영향 범위를 구분하지 않는다.
  - 승인 후 실행 시점에 설정을 다시 조회한다 (#56).
  - 실행 도중 프로세스가 중단되면 exactly-once를 보장하지 않는다 (`UNKNOWN`, #57).

## Revisit When
- #58 평가에서 intent 단위 위험도로 부족한 사례가 나올 때
- MCP 어댑터를 도입할 때

## References
- [architecture §3.1](../architecture.md#31-자연어-명령)
