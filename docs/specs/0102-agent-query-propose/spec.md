---
issue: 102
title: CLI·API 기반 에이전트 상태 조회와 변경 제안
status: done
size: M
type: feat
branch: feat/#102-agent-query-propose
---

# CLI·API 기반 에이전트 상태 조회와 변경 제안

## 목적 / 성공 조건
- 목적: Claude Code·Codex 같은 코딩 에이전트가 기존 CLI/API로 앱 상태와 실패 근거를 읽고, 복구를 제안만 하게 한다. 실행은 사람이 승인한다. MCP(#103) 전에 흐름과 권한을 검증한다.
- 기술적 완료: PROPOSE_ONLY 토큰으로 조회·진단·복구 제안이 되고, 제안은 승인 대기 명령만 만든다. 사람이 FULL로 승인하면 #101 복구가 실행된다.
- 운영: 에이전트 사용 절차와 예제가 문서에 있고, 로컬 k3s에서 실제로 돌려 확인한다.

## 현재 동작
- 조회: 앱 목록(`GET /repositories`, CLI 명령은 없음), 배포 목록·요청 상세·로그(`deployments list|get|logs`, `GET /deployments/{id}/detail`), 진단(`POST /deployments/{id}/diagnosis`), 복구 후보·계획(`GET .../recovery-candidates`, `.../recovery-plan`). 모두 READ_ONLY 이상이고 CLI는 `--json`을 지원한다.
- 제안: PROPOSE_ONLY는 `POST /nlp/command`로 자연어 명령을 만들 수 있다. "owner/app을 abc1234로 롤백"은 Gemini가 ROLLBACK으로 해석하면 #101 복구 계획 지문을 고정한 승인 대기 명령이 된다. 해석이 틀리면 다른 대상이 될 수 있고, 같은 commit의 성공 배포가 여럿이면 최신 것이 대상이 된다.
- 승인: `POST /nlp/confirm`(FULL, CLI `klepaas confirm <id>`). 10분 안에 본인만, 실행 시 계획 지문을 다시 비교한다(#56).

## 범위
- 포함: 구조화된 복구 제안 API, CLI 명령(`deployments propose-recovery`, `repositories list`), ROLLBACK 명령의 대상 배포 ID 지정, 에이전트 사용 문서와 예제, 로컬 k3s 실증.
- 제외: MCP 서버(#103), 에이전트의 승인·실행 권한, 콘솔의 승인 대기 목록·승인 버튼(지금도 없음, 승인은 CLI), 복구 외 변경 제안(scale·restart·배포), 승인 유효 시간 변경.

## 동작
1. `POST /api/v1/deployments/{targetId}/recovery-proposal` 본문 `{"reason": "..."}`(선택, 200자 이하. raw_command 255자 안에 들어가게 한다).
   - 권한: PROPOSE_ONLY·FULL. READ_ONLY·DEPLOY는 403 `CLI_005`.
   - 소유권: 대상 배포가 본인 저장소가 아니면 404(존재를 드러내지 않음).
   - 복구 계획을 계산해 실행할 수 없으면 409 `DEPLOY_005`, 명령을 만들지 않는다.
   - 실행할 수 있으면 승인 대기 명령(CommandLog)을 만든다: intent `ROLLBACK`, 위험도 HIGH, 인자 `{owner, repo, commit_hash, target_deployment_id}`, 계획 지문 고정, raw_command는 `복구 제안: 배포 #<id> (<commit 7자리>) — <reason>`.
   - 아무것도 실행하지 않는다. 응답은 `nlp/command`와 같은 형식(`command_log_id`, `requires_confirmation=true`, `approval_target`)이다.
2. ROLLBACK 인자에 `target_deployment_id`가 있으면 승인 대상 계산과 실행이 commit 대신 그 배포를 쓴다. 없으면 지금처럼 commit으로 찾는다(자연어 명령).
3. 사람은 `klepaas confirm <command_log_id>`(FULL)로 승인한다. 승인 시 계획 지문을 다시 비교하고 바뀌었으면 409 `AI_006`로 실행하지 않는다(지금과 같음). 실행된 배포는 `recovered_from_deployment_id`와 명령 ID가 남는다.
4. 승인 유효 시간은 기존과 같은 10분이다.

## 입출력·계약
- 새 endpoint 1개(위). `SecurityConfig`에 PROPOSE_ONLY·FULL 규칙을 추가한다.
- CLI: `klepaas repositories list`(앱 목록, 기존 API), `klepaas deployments propose-recovery <target-deployment-id> [--reason <text>]`. 사람용 출력은 승인 대상과 `klepaas confirm <id>` 안내, `--json`은 응답 그대로.
- 문서: `docs/CLI_REFERENCE.md`에 에이전트 사용 절차(토큰 발급 → 조회 → 진단 → 복구 계획 → 제안 → 사람 승인)를 추가한다.
- 에러 코드·마이그레이션·설정 키 변화 없음.

## 결정 기록 (Decisions)
- 2026-10-11 Q1 → 사용자 결정(권장안): 자연어가 아니라 구조화된 제안 API를 둔다. 해석 오류가 없고, 기존 승인 대기 명령·confirm·이력을 재사용해 테이블을 추가하지 않는다. #103 MCP 도구가 그대로 쓴다.
- 2026-10-11 Q2 → 사용자 결정(권장안): 승인 유효 시간은 기존 10분을 유지한다. 설정이 바뀌면 지문으로 거절되므로 에이전트가 다시 제안하면 된다.
- 2026-10-11 Q3 → 사용자 결정(권장안): 로컬 k3s + 패키징 JAR로 실증한다. 운영에는 영향을 주지 않는다.
- 2026-10-11 (구현 중 사실 정정 → 사용자 결정, 권장안) 스펙이 CLI `repositories list`가 있다고 적었으나 없었다. 에이전트가 저장소 ID를 얻는 출발점이라 기존 API를 쓰는 CLI 조회 명령을 추가한다.
- 2026-10-11 기본값: 제안 사유(`reason`)를 받아 raw_command에 남긴다. 사람이 승인할 때 에이전트가 왜 제안했는지 이력에서 보기 위해서다.

## 완료 증거
| 증명할 것 | 방법 | 환경 |
|---|---|---|
| 제안은 승인 대기 명령만 만들고 배포·설정을 바꾸지 않음, 실행 불가 계획은 409, 권한 밖은 404 | 서비스 테스트 | 로컬 |
| scope: PROPOSE_ONLY·FULL만 제안, READ_ONLY·DEPLOY 403, PROPOSE_ONLY confirm 403 | HTTP 권한 테스트 | 로컬 |
| target_deployment_id로 같은 commit의 다른 배포가 아닌 지정 배포를 복구 | 서비스 테스트 | 로컬 |
| CLI 명령·출력 | CLI 테스트 | 로컬 |
| 에이전트 예제: PROPOSE_ONLY로 조회·진단·계획·제안, FULL로 승인 후 복구 배포 성공, 권한 밖 조회 404 | 패키징 JAR + 임시 k3s | 로컬 실제 클러스터 |
| 회귀 | PR 전 `./gradlew test`, CLI 테스트 1회 | 로컬 |

### 실행 결과 (2026-10-11)
- 로컬: `NlpCommandServiceTest` — 제안은 지정 배포의 계획 지문을 고정한 PENDING ROLLBACK만 저장(인자 `target_deployment_id`, 사유가 raw_command에 남음), 실행·모델 호출 없음. 실행 불가 계획은 409 `DEPLOY_005`, 권한 밖 배포는 404이고 둘 다 저장하지 않음.
- 로컬: `ActionDispatcherTest` — `target_deployment_id`가 있으면 commit 검색 없이 그 배포로 복구.
- 로컬: `CliTokenScopeHttpTest`(실제 HTTP, Testcontainers PostgreSQL) — 제안은 READ_ONLY·DEPLOY 403, PROPOSE_ONLY는 scope 통과(설정 기록 없는 배포라 409, 사유 201자 400), 다른 사용자의 PROPOSE_ONLY 404.
- 로컬: CLI 테스트 9개(`repositories list`, `propose-recovery` 요청·출력 포함), `npx tsc --noEmit`.
- 로컬 실제 클러스터 (패키징 JAR + PostgreSQL 16 + Docker 임시 k3s v1.30.6, CLI는 실제 `node frontend/cli/index.mjs --json`): 사람이 v1(redis:7-alpine) 성공 배포 → 설정 변경 + 없는 이미지로 실패 배포. 에이전트(PROPOSE_ONLY)가 `repositories list` → `deployments list|get|logs` → `deployments diagnose`(실제 OpenAI 1회, IMAGE / image_not_pullable) → `recovery-plan` → `propose-recovery --reason`. 제안 뒤 배포 행 수와 Kubernetes Deployment generation이 그대로이고 명령은 PENDING. 에이전트의 `recover`·`confirm`은 거절(exit 2). 다른 사용자의 에이전트는 조회·제안 거절(exit 1), 앱 목록은 빈 배열. 사람(FULL)이 `confirm` → 새 배포 SUCCESS, `recovered_from_deployment_id`=v1, trigger NLP, Kubernetes replicas 1/1·이미지 redis:7-alpine·적용 요청 ID 일치, 명령 SUCCEEDED.
- 로컬: `./gradlew test` 264개 통과(건너뜀 1: 외부 모델 비교).
- 한계: import한 이미지라 digest 고정 pull은 검증하지 않았다(#101과 같은 제약). 운영에서는 하지 않았다.
- 증거: `.local/evidence/0102-agent-query-propose/` (기동 로그, agent-k3s-result.json의 CLI 실행 기록).

## 운영 반영
- 마이그레이션·설정 변경 없음. 배포 후 운영 확인은 PROPOSE_ONLY 토큰 발급이 필요해 사용자 요청 시 한다.

## 회고
- 어긋난 점: 스펙의 현재 동작에 CLI `repositories list`가 있다고 적었으나 없었다. 에이전트 흐름의 출발점(저장소 ID)이 비어 있어 사용자 승인 후 추가했다.
- 원인 분류: 스펙 작성 시 CLI 명령 목록을 확인하지 않고 API 목록으로 추정함.
- 고친 위치: 이 스펙 결정 기록. 실증 스크립트가 실제 CLI로 전 과정을 돌려 같은 누락을 다시 잡는다.
