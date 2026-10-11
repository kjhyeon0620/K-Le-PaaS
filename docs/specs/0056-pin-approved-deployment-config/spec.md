---
issue: 56
title: 승인한 배포 설정 버전 고정과 변경 시 재승인
status: done
size: L
type: feat
branch: feat/#56-pin-approved-deployment-config
---

# 승인한 배포 설정 버전 고정과 변경 시 재승인

## 목적 / 성공 조건
- 목적: 자연어 명령으로 승인한 배포가 승인 당시의 배포 설정으로만 적용된다. 승인 대기 중이나 승인 후 빌드 중에 설정이 바뀌면 승인하지 않은 설정이 적용되지 않는다.
- 기술적 완료: 승인이 필요한 배포 명령은 생성 시 대상 저장소 설정의 지문을 저장한다. confirm 시점과 apply 직전에 현재 설정 지문과 비교해 다르면 실행·적용하지 않는다. 설정이 그대로면 기존처럼 실행된다.
- 운영: 사용자는 확인 응답에서 무엇을 승인하는지(저장소·브랜치·commit·이미지·레플리카·포트·env 이름·설정 지문)를 본다. 설정이 바뀌어 거절되면 이유를 보고 같은 명령을 다시 보내 새 설정으로 승인한다.

## 현재 동작 (2026-10-11 코드 확인)
- `NlpCommandService.processCommand`는 intent 인자만 `CommandLog.intent_args`에 저장한다. `confirmCommand`는 `claim`(PENDING → EXECUTING 조건부 update) 후 저장된 인자로 `ActionDispatcher.dispatch`를 실행한다.
- DEPLOY·ROLLBACK·ROLLBACK_EXECUTION은 `DeploymentService.createDeployment`를 호출한다. 파이프라인은 `startK8sDeploy`에서 설정 스냅샷(#95)을 기록하고 `applyK8sManifests`에서 설정을 다시 읽어 적용한다. 승인 당시 설정과 비교하지 않는다.
- SCALE·RESTART는 배포 설정을 읽어 적용하지 않는다 (SCALE은 replica만 바꾼다).
- 확인 응답 `NlpCommandResponse`에는 intent·message·risk만 있고 승인 대상 설정이 없다. CLI `ask`는 Command Log·Intent·Risk·Needs Confirm만 출력한다.

## 범위
- 포함
  - 대상 intent: DEPLOY, ROLLBACK, ROLLBACK_EXECUTION 중 승인이 필요한 명령.
  - 명령 생성 시 대상 저장소 소유권 확인 후 현재 배포 설정 지문 저장, 응답에 승인 대상 요약.
  - confirm 시 지문 비교, 불일치·지문 없음이면 실행하지 않고 명령 FAILED + 409.
  - 승인 배포 요청에 승인 지문 저장, apply 직전 적용할 설정과 비교해 다르면 적용하지 않고 FAILED.
  - 웹 채팅·CLI `ask`·`confirm`에 승인 대상 표시, 409 이유 표시.
- 제외
  - SCALE·RESTART 승인 고정 (설정을 적용하지 않음).
  - 웹·CLI·CI에서 직접 요청한 배포 (승인 단계가 없음). 이들은 지금처럼 apply 시점 설정을 쓴다.
  - 같은 명령의 재승인 API (사용자 결정: 새 명령으로 다시 승인).
  - 과거 설정 값으로 되돌려 적용 (#101).
  - 승인 대상 이미지 digest 고정. 이미지는 요청 인자(commit·지정 이미지)와 설정의 템플릿으로 표시만 한다.

## 입출력·계약
- 설정 지문: `DeploymentConfigSnapshots.capture(config)`의 JSON을 SHA-256으로 해시한 64자리 hex. env 값은 기존처럼 HMAC 지문으로만 들어가므로 값이 바뀌어도 지문이 바뀐다. 레플리카·포트·probe·자원·envFrom·pull secret·이미지 템플릿 변경도 포함한다. 표시는 앞 8자리.
- API `POST /api/v1/nlp/command`·`POST /api/v1/nlp/confirm` 응답: 기존 필드 유지, `approval_target` 추가 (대상 intent가 아니면 null).
  - `repository_id`, `repository`(`owner/repo`), `branch`, `commit`, `build_strategy`, `image`(요청 지정 이미지 또는 이미지 템플릿, 없으면 null), `min_replicas`, `max_replicas`, `container_port`, `env_names`(정렬, 값 없음), `config_fingerprint`(8자리).
  - 대상을 확인하지 못하면(저장소 없음·권한 없음·설정 없음) `approval_target` null. 생성은 기존처럼 성공한다.
- 에러: 새 `ErrorCode.APPROVAL_TARGET_CHANGED` (409, `AI_006`, "승인 후 배포 설정이 바뀌었거나 승인 대상을 확인할 수 없습니다. 명령을 다시 보내 승인하세요").
- 저장: Flyway `V6__approved_config_fingerprint.sql`
  - `command_log.approved_config_fingerprint varchar(64)` nullable
  - `deployments.approved_config_fingerprint varchar(64)` nullable
  - 기존 행은 null. 이전 릴리스 JAR는 두 컬럼을 모르고 읽기·쓰기에 영향이 없다 ([ADR-0004](../../adr/0004-flyway-forward-compatible-migrations.md)).
- 상태 전이
  - confirm, 대상 intent, 지문 일치 → 기존 흐름 (EXECUTING → SUCCEEDED/FAILED).
  - confirm, 대상 intent, 지문 불일치 또는 저장 지문 없음 → `claim` 후 dispatch 없이 FAILED(`error_message`에 이유), 응답 409. 외부 호출 0회.
  - 배포 apply 직전, 승인 지문이 있고 적용할 설정 지문과 다름 → Kubernetes 적용 없이 FAILED(`APPLY_FAILED`, 이유 "승인 후 배포 설정이 바뀌어 적용하지 않음"). 새 FailureKind는 추가하지 않는다 (이전 릴리스 호환).
- CLI: `ask`에 승인 대상 줄 추가. `confirm`의 409는 기존 4xx 규칙대로 exit 1, 이유 출력. `--json`은 응답 그대로 (`approval_target` 포함).
- 콘솔: 자연어 명령 확인 영역에 승인 대상 요약 표시. 기존 디자인 사용.

## 권한·안전
- 호출 주체와 scope: 기존 NLP 명령·확인 API scope 그대로 (FULL).
- 소유권: 승인 대상 계산은 `ResourceAccessService.requireRepository`를 통과한 저장소만 설정을 읽는다. 통과하지 못하면 대상 없음으로 두고 다른 사용자 저장소의 존재를 드러내지 않는다.
- 위험도·승인: 기존 분류 유지. 지문 불일치는 승인을 소비하고(FAILED) 재사용하지 않는다.
- 비밀값: env 값은 응답·로그·저장 어디에도 평문으로 추가하지 않는다. 지문은 기존 HMAC 기반이라 값을 역산할 수 없다.

## 제약
- 참조: [architecture §3.1·§3.2·§5·§6](../../architecture.md), [ADR-0004](../../adr/0004-flyway-forward-compatible-migrations.md), [0095](../0095-deployment-request-detail/spec.md).
- 지문은 `jwt.secret`에서 파생한 HMAC 키에 의존한다. 키가 바뀌면 대기 중 승인은 모두 불일치로 거절된다 (안전한 방향).
- apply 직전 비교는 실제로 적용할 설정 객체로 계산한다. 다시 조회해 비교하지 않는다.

## 예외·경계 상황
| 상황 | 사용자·agent가 보는 것 | 시스템 동작 |
|---|---|---|
| 승인 대기 중 설정 변경 (Scale로 replica 변경 포함) | confirm 409, 이유 | 명령 FAILED, 배포 생성 없음 |
| 승인 후 빌드 중 설정 변경 | 배포 FAILED(APPLY_FAILED), 이유 | Kubernetes 적용 없음 |
| 설정을 바꿨다가 원래대로 되돌림 | 정상 실행 | 지문이 같으므로 승인 대상과 같은 설정 |
| 대상 저장소 없음·권한 없음·설정 없음 | 생성 응답 `approval_target` null, confirm 409 | 실행 없음 |
| 이전 릴리스가 만든 대기 명령(지문 null) | confirm 409 | 실행 없음. 승인 유효시간 10분 안의 명령만 해당 |
| 동시 confirm | 한쪽만 claim | 기존 조건부 update 유지 |
| 직접 요청 배포 (웹·CLI·CI) | 변화 없음 | 승인 지문 null → 비교 없음 |
| 백엔드 재시작 중 EXECUTING | #57 재시작 대조 그대로 | 변화 없음 |

## 미결 질문 (Open)
- 없음.

## 결정 기록 (Decisions)
- 2026-10-11 사용자 결정: 편의성(#106)보다 기능부터. 승인 복구(#56 → #101)를 먼저 진행한다.
- 2026-10-11 Q1 → 사용자 결정(권장안): confirm뿐 아니라 apply 직전에도 승인 지문과 비교해 다르면 적용하지 않고 FAILED.
- 2026-10-11 Q2 → 사용자 결정(권장안): confirm 시 불일치면 409, 명령은 FAILED. 같은 자연어 명령을 다시 보내 새 설정으로 승인한다. 재승인 API는 만들지 않는다.
- 2026-10-11 Q3 → 사용자 결정(권장안): 확인 응답에 승인 대상 요약(저장소·브랜치·commit·이미지·레플리카·포트·env 이름)과 설정 지문 8자리를 보인다. env 값은 보이지 않는다.
- 2026-10-11 기본값: 대상 intent는 설정을 적용하는 DEPLOY·ROLLBACK·ROLLBACK_EXECUTION. 지문 없는 대상 명령은 실행하지 않는다 (확인할 수 없는 승인을 통과시키지 않음).
- 2026-10-11 기본값: apply 직전 거절은 기존 `APPLY_FAILED`로 기록한다. 새 FailureKind는 이전 릴리스가 읽지 못한다.
- 2026-10-11 (구현 중) 사실 정정: CLI는 4xx를 exit 1(입력·요청 오류)로, 5xx·응답 실패를 exit 3으로 매핑한다. 409를 위해 exit code 규칙을 바꾸지 않고 기존대로 exit 1을 쓴다.

## 완료 증거
| 증명할 것 | 방법 | 환경 |
|---|---|---|
| 설정이 그대로면 기존처럼 실행, 바뀌면 409·FAILED·dispatch 0회, 지문 없음 거절 | NLP 서비스·확인 테스트 | 로컬 PostgreSQL |
| 승인 후 설정 변경 시 Kubernetes 적용 0회·FAILED, 같으면 적용 | 단계 서비스 테스트 | 로컬 |
| 승인 대상 계산 시 권한 밖 저장소 설정을 읽지 않음, env 값 미노출 | 단위 테스트 | 로컬 |
| V5 → V6 적용, 기존 명령·배포 행 유지, 이전 JAR 읽기·쓰기 | 패키징 JAR + 로컬 PostgreSQL, 운영 DB 사본(사용자 실행) | 로컬, 운영 DB 사본 |
| CLI 승인 대상 출력·409 exit 1, 웹 확인 영역 표시 | CLI 계약 테스트, mock API + 브라우저, tsc·build | 로컬 |
| 회귀 | PR 전 `./gradlew test` 전체 1회 | 로컬 |

### 실행 결과 (2026-10-11)
- 로컬 PostgreSQL: `ApprovalPinningTest` — 실제 소유권 검사·설정 지문으로 권한 밖 저장소 대상 없음, env 값 미노출, env 값·레플리카 변경 시 지문 변경과 되돌리면 동일, 승인 대기 중 설정 변경 시 409·명령 FAILED·dispatch 0회, 지문 없는 대상 명령 거절, 설정이 같으면 승인 지문과 함께 실행·SUCCEEDED.
- 로컬: `NlpCommandServiceTest`(생성 시 지문 저장·승인 대상 응답, 불일치·지문 없음·대상 확인 불가 409), `DeploymentPipelineStepServiceTest`(승인 지문과 다르면 Kubernetes 적용 0회, 같으면 적용), 동시 승인 테스트 유지.
- 로컬: 전체 `./gradlew test` 235개, 실패·오류·건너뜀 0개.
- 로컬 패키징 JAR + PostgreSQL 16: 이전 릴리스 코드(main `3f54e0f`) JAR로 V5 스키마와 종료 상태 배포 6행·명령 5행 생성 → 새 JAR로 V6 migrate·Hibernate validate·readiness UP, 기존 행 유지, 새 컬럼 모두 null → V6 DB에서 이전 JAR 조회·배포 생성/갱신 → 새 JAR로 그 행 조회. 미완료 행은 #57 재시작 대조가 정리하므로 비교 대상에서 뺐다.
- 로컬 CLI 계약 테스트: `ask` 승인 대상 출력, `confirm` 409 이유·exit 1 (기존 4개 포함 통과).
- 로컬 콘솔: `tsc --noEmit`, `npm run build` 통과. mock API + 브라우저로 승인 대상 표시와 409 이유 표시(서버 message 표시로 공통 오류 문구 개선) 확인. 375px 폭에서 승인 대상 블록 넘침 없음(DOM 측정). 기존 모바일 사이드바 미접힘은 #109.
- 운영 DB 사본 (2026-10-11, 사용자 실행): `verify-v6-on-db-copy.sh` 통과 보고 (V6 migrate·Hibernate validate·readiness UP, 행 수 유지, 새 컬럼 NULL; 스크립트 종료 시 사본·JAR 정리). 세부 출력 수치는 받지 않았다. 운영 원본에 V6를 적용한 결과가 아니다.
- 증거: `.local/evidence/0056-pin-approved-deployment-config/`.

## Tasks
### T1. 지문과 승인 대상
- 변경: `DeploymentConfigSnapshots.fingerprint`, 승인 대상 계산 컴포넌트, `ApprovalTarget` DTO, V6, `CommandLog`·`Deployment` 필드, `ErrorCode`.
- 검증: 지문 변화 조건, 권한 밖 저장소 미조회.
### T2. 명령 생성·확인과 apply 직전 비교
- 변경: `NlpCommandService`, `NlpCommandResponse`, `DeploymentOrigin`·`createDeployment`, `DeploymentPipelineStepService.applyK8sManifests`.
- 금지: 지문 불일치 시 dispatch·Kubernetes 호출.
- 검증: 일치·불일치·지문 없음·되돌림, 동시 confirm.
### T3. 클라이언트·문서
- 변경: CLI `ask`·`confirm`, 웹 자연어 명령 확인 영역, architecture §3.1·§5·§6·§9, CLI_REFERENCE, README들, product, CICD(V6).
- 검증: CLI 계약 테스트, 브라우저 확인, 이전 JAR 호환, 운영 DB 사본.

## 운영 반영
- 2026-10-11: PR #112 머지 `bb66ac0`, main 배포 run 38111893601 build·deploy success. receiver가 `validate` 기동·readiness·revision을 확인한 뒤 성공했으므로 V6이 운영 원본에 적용된 간접 증거다. 운영 API·DB와 실제 승인 흐름은 직접 확인하지 않았다.

## 회고
- 어긋난 점: (1) 스펙에 CLI 409를 exit 3으로 적었지만 기존 규칙은 4xx → exit 1이었다. 계약 테스트가 잡았고 기존 규칙을 유지했다. (2) 승인 대상 계산을 트랜잭션 안에서 하면 권한 거절 예외가 트랜잭션을 rollback-only로 만든다(#57과 같은 함정). 처음부터 트랜잭션 없이 읽게 했다. (3) 마이그레이션 호환 검증에서 새 JAR의 재시작 대조(#57)가 seed한 미완료 행을 정리해 값 비교가 깨졌다. 마이그레이션 검증 seed는 종료 상태만 쓰고, 운영 DB 사본 스크립트는 미완료 행이 있으면 값 비교 대신 행 수·새 컬럼만 확인한다.
- 원인 분류: 스펙 누락(기존 CLI exit 규칙 미확인), 외부 제약 미인지(기동 대조가 검증 데이터를 바꿈).
- 고친 위치: 스펙 Decisions(구현 중), CLI 계약 테스트, `ApprovalTargetResolver` 주석, 검증 스크립트.
