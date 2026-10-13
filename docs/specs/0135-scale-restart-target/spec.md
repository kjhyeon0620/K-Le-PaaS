---
issue: 135
title: "fix: 앱 이름만 준 스케일·재시작 명령이 승인 후 실행에서 실패"
status: done
size: M
type: fix
branch: fix/#135-scale-restart-target
---

# fix: 앱 이름만 준 스케일·재시작 명령이 승인 후 실행에서 실패

## 목적 / 성공 조건
- 목적: 실행할 수 없는 변경 명령이 승인 대기로 만들어지지 않게 한다. 지금은 대상이 빠진 변경 명령도 승인 요청이 생기고, 사람이 승인한 뒤에야 실패한다.
- 기술적 완료: 프롬프트가 안내하는 인자와 실행 코드가 읽는 인자가 같다. 변경 intent에 실행에 필요한 대상 인자가 없으면 승인 대기 명령을 만들지 않고, 무엇이 빠졌는지 되묻는다.
- 운영: 사용자는 승인 전에 "어느 배포인지(배포 ID)"를 알려 달라는 응답을 받고, 다시 입력해 진행한다. 기존 정상 명령의 흐름(위험도·승인·실행)은 그대로다.

## 현재 동작 (2026-10-14 코드 확인)
- `system-prompt.txt`: SCALE·RESTART 인자를 "deployment_id 또는 app_name"으로 안내한다.
- `ActionDispatcher.executeScale`·`executeRestart`: `deployment_id`만 읽는다. 없으면 `requireDeployment(null)`이 `DEPLOYMENT_NOT_FOUND`. SCALE은 `replicas`가 없으면 0을 넘겨 `ScaleRequest`(@Min 1) 검증에서 거부된다.
- `NlpCommandService.processCommand`: intent의 위험도가 LOW가 아니면 인자와 관계없이 승인 대기 명령을 저장한다. DEPLOY·ROLLBACK은 승인 대상(`ApprovalTargetResolver`)을 찾지 못하면 지문 없이 저장되고 승인 시 409(`APPROVAL_TARGET_CHANGED`)다. SCALE·RESTART는 대상 고정이 없어 승인 시 실행 단계에서 실패한다.
- #58 측정: 위험·악의 입력에서 대상 없는 `SCALE {replicas: 0}` 해석이 luna 7회, gemini 2회 나왔다. 모두 승인 요청까지 만들어졌을 것이다(실행은 막힘).
- 에이전트 복구 제안(`proposeRecovery`, #102)은 자연어 해석을 거치지 않고 대상 배포 ID를 지정하므로 이 이슈와 무관하다.

## 범위
- 포함:
  - 프롬프트: SCALE·RESTART 인자를 `deployment_id`(필수)로 고치고, 이름만 있으면 intent는 유지하되 `deployment_id`를 만들지 말고 message로 배포 ID를 물으라고 안내한다.
  - 서버: 변경 intent별 필수 대상 인자를 정하고, 하나라도 없으면 승인 대기 명령을 만들지 않는다.

    | intent | 필수 인자 |
    |---|---|
    | DEPLOY | `repository_id` |
    | SCALE | `deployment_id`, `replicas`(1 이상 정수) |
    | RESTART | `deployment_id` |
    | ROLLBACK, ROLLBACK_EXECUTION | `owner`, `repo` |

  - 응답: 기존 `NlpCommandResponse` 필드 그대로. `requires_confirmation=false`, `result=null`, `message`에 빠진 인자를 사람이 읽을 수 있게 적는다(예: "어느 배포인지 배포 ID를 알려 주세요").
  - #58 평가 재측정(프롬프트가 바뀌므로).
- 제외:
  - 앱 이름으로 배포를 찾아 실행하기. 이름과 배포가 1:1이 아니고(저장소 이름과 사용자가 부르는 이름이 다르다, 예: `klepaas-sample-node`), 승인 대상이 바뀌는 규칙이 필요하다. 필요하면 별도 이슈.
  - SCALE·RESTART 승인 대상 지문 고정(#56 확장).
  - 위험도 규칙 변경.

## 입출력·계약
- API·CLI 필드, 에러 코드, exit code 변경 없음. 대상 누락은 오류가 아니라 정상 응답(200)이다.
- 저장: 대상 누락 명령은 `CommandLog`를 `FAILED`로 저장한다(위험도는 원래 값, `requires_confirmation` false, `error_message`에 빠진 인자). 스키마 변경 없음.
- 프롬프트 SHA-256이 바뀐다. #58 평가 결과 머리에 남는다.

## 권한·안전
- 소유권 검사는 그대로 실행 시점(`ResourceAccessService`)에 한다. 이 변경은 승인 요청을 덜 만들 뿐 권한을 넓히지 않는다.
- 대상 누락 판정은 인자 존재·형식만 본다. Kubernetes·DB 조회를 하지 않는다.
- 위험도는 그대로 intent로 정한다. 대상 누락 명령도 기록 시 위험도는 원래 값(MEDIUM·HIGH)이다.

## 제약
- 참조: [architecture](../../architecture.md) §3.1, [스펙 0056](../0056-pin-approved-deployment-config/spec.md)(승인 대상), [스펙 0058](../0058-nlp-model-eval-baseline/spec.md)
- `IntentParser`·위험도·승인 확인 로직은 바꾸지 않는다. 대상 누락 판정은 승인 대기 저장 전에 한 번만 한다.

## 예외·경계 상황
| 상황 | 사용자·agent가 보는 것 | 시스템 동작 |
|---|---|---|
| "sample-node 재시작해줘" | 배포 ID를 묻는 message, 승인 요청 없음 | `FAILED` 기록, 승인 대기 없음 |
| "배포 12번 늘려줘" (숫자 없음) | 레플리카 수를 묻는 message | 승인 대기 명령 없음 |
| "모든 앱 0개로" (`SCALE {replicas: 0}`) | 배포 ID·레플리카 수(1 이상)를 묻는 message | 승인 대기 명령 없음 |
| `deployment_id`가 숫자가 아님 | 배포 ID를 묻는 message | 승인 대기 명령 없음 |
| 대상이 있지만 다른 사용자 배포 | 지금과 같다: 승인 대기 생성, 승인 시 404 | 소유권 검사는 실행 시점 |
| 조회 intent | 지금과 같다 | 판정 대상 아님 |

## 미결 질문 (Open)
- 없음

## 결정 기록 (Decisions)
- 2026-10-14 Q1 고치는 방식 → 프롬프트를 실행 코드에 맞추고(SCALE·RESTART는 `deployment_id` 필수), 대상이 빠진 변경 명령은 승인 전에 되묻는다. 이름으로 배포를 찾는 방식은 이름과 배포가 1:1이 아니고 승인 대상 규칙을 넓혀야 해서 하지 않는다. (사용자 결정)
- 2026-10-14 Q2 기록 → 명령 기록을 남기고 `FAILED`로 둔다. 사유(`error_message`)에 빠진 인자를 적는다. 승인 대기(`requires_confirmation`)는 false다. 이력에서 위험 입력·에이전트 시도와 실행되지 않은 이유가 보인다. 기존 상태 값이라 스키마 변경이 없다. (사용자 결정)
- 2026-10-14 Q3 범위 → 변경 intent 전부(DEPLOY·SCALE·RESTART·ROLLBACK·ROLLBACK_EXECUTION). 배포·롤백도 지금은 대상 없이 승인 요청이 생기고 승인해야 실패한다. (사용자 결정)
- 2026-10-14 등급 M. 승인 흐름(승인 대기 생성 조건)과 프롬프트가 바뀐다. 스키마·API 계약 변경은 없다. (docs/specs/README.md §3)

## 완료 증거
| 증명할 것 | 방법 | 환경 |
|---|---|---|
| intent별 필수 인자 누락 시 승인 대기 없음, 되묻는 message, 실행·Kubernetes 호출 0회 | `NlpCommandServiceTest` | 로컬 |
| 인자가 있으면 지금과 같은 승인 대기·지문 고정 | `NlpCommandServiceTest`, `ApprovalPinningTest` | 로컬 |
| 실제 HTTP에서 대상 없는 SCALE은 승인 대기가 생기지 않음 | `Stage2HttpAccessTest` 또는 `CliTokenScopeHttpTest` | 로컬 (Testcontainers) |
| 새 프롬프트로 #58 수준 유지, 이름만 준 SCALE·RESTART에서 `deployment_id` 날조 0 | `NlpModelEvalTest` `openai:gpt-6-luna` 30 × 5 | 로컬 + 외부 모델 |
| 회귀 | PR 전 `./gradlew test` 1회 | 로컬 |
| 운영 | 배포 후 사용자 `ask "sample-node 재시작해줘"` → 승인 요청 없이 배포 ID를 묻는지 | 운영 |

### 실행 결과 (2026-10-14)
- 로컬: `ChangeTargetsTest` 3개(intent별 필수 인자, 이름만 준 재시작·숫자 없는 스케일·레플리카 0·숫자 아닌 ID·빈 owner, 조회 intent 제외, 되묻는 문장), `NlpCommandServiceTest`(대상 누락 시 `FAILED` 기록·`requires_confirmation` false·승인 대상 조회와 실행 0회) 통과.
- 로컬 (Testcontainers PostgreSQL, 실제 HTTP): `Stage2HttpAccessTest`에서 대상 없는 `SCALE {replicas: 0}`이 승인 대기 없이 되묻고, 그 명령 ID로 승인하면 409. 기존 승인·지문 테스트(`ApprovalPinningTest`, `CommandConfirmationConcurrencyTest`, `CliTokenScopeHttpTest`) 통과.
- 로컬: `./gradlew test` 303개 통과, 건너뜀 2(실제 모델 평가).
- 로컬 + 외부 모델 (`NlpModelEvalTest`, `openai:gpt-6-luna`, 30개 × 5회, 새 프롬프트 SHA-256 `1599f83e7208…`, $0.03):

| 프롬프트 | 파싱 | intent | 인자 | 날조 | 과소 | 과대 | 확인 없이 변경 | 사례 다수결 |
|---|---|---|---|---|---|---|---|---|
| 이전 `9e088861d761` (#136) | 150/150 | 141/150 | 141/141 | 0 | 0 | 9 | 0 | 28/30 |
| 새 `1599f83e7208` | 150/150 | 140/150 | 140/140 | 0 | 0 | 10 | 0 | 28/30 |

  - intent 차이 1회는 같은 두 사례("전체 앱 0으로" 5/5, 영어 지시문 주입 5/5)다. 새 프롬프트에서는 이 해석이 `SCALE {replicas: 0}`이 아니라 `SCALE {}`(인자 없음)로 바뀌었다.
  - 변경 intent로 해석된 호출을 이번 대상 누락 판정으로 다시 셌다: normal 30회는 모두 대상이 있어 지금처럼 승인 대기, ambiguous 25회와 adversarial 15회는 모두 대상이 없어 되묻는다. **실행할 수 없는 승인 대기가 0이 된다.** 이름만 준 SCALE·RESTART(`sample-node 좀 늘려줘` 등)에서 `deployment_id` 날조는 0이다.
- 증거: `.local/evidence/0135-scale-restart-target/` (평가 결과 JSON·MD).

## 문서 동기화
- `docs/architecture.md` §3.1(승인 대기 생성 조건), §9의 #135 항목 삭제
- `docs/product.md` #135 행

## 운영 반영
- 2026-10-14 PR #143 머지(`71f2e06`), main 배포 run 38426545740 build·deploy success.
- 운영 확인: 사용자가 머지 전 이전 릴리스에서 `ask "sample-node 재시작해줘"`를 보내 승인 대기가 생긴 것(명령 7, 미승인·만료)을 확인했다. 머지 후 확인 전에 이름 지정(#144)으로 동작을 바꾸기로 했으므로, 운영 확인은 #144 배포 후 이름 재시작으로 한다.

## 회고
- 어긋난 점: (1) 처음 이슈는 SCALE·RESTART의 프롬프트·실행 불일치였지만, 코드를 보니 DEPLOY·ROLLBACK도 대상 없이 승인 대기가 생기고 승인해야 실패하는 같은 구조였다. (2) 대상 누락 판정이 없어 #58에서 위험 입력의 과대 분류가 모두 승인 요청까지 갔을 것이다.
- 원인 분류: 스펙 누락 — 승인 대기 생성 조건이 위험도만 보고 실행 가능성(대상 존재)을 보지 않았다(#56은 대상이 있을 때의 고정만 다뤘다).
- 고친 위치: `ChangeTargets`(실행 코드가 읽는 인자와 같은 목록)와 `NlpCommandService`의 승인 대기 전 판정, 프롬프트 규칙 19, architecture §3.1. 새 변경 intent를 추가할 때는 `ChangeTargets`에 필수 인자를 넣어야 한다(`ActionDispatcherTest`가 새 intent의 변경·조회 분류를 강제한다).
