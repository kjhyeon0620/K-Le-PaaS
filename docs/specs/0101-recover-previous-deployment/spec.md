---
issue: 101
title: 이전 배포 이미지·설정으로 복구 계획 생성·승인·실행
status: done
size: L
type: feat
branch: feat/#101-recover-previous-deployment
---

# 이전 배포 이미지·설정으로 복구 계획 생성·승인·실행

## 목적 / 성공 조건
- 목적: 실패한 배포를 같은 저장소의 이전 성공 배포 이미지와 배포 설정으로 되돌린다. 무엇이 바뀌는지 계획으로 확인하고 승인한 계획만 실행한다. 콘솔 Rollback의 거짓 성공(stub)을 없앤다.
- 기술적 완료: 복구 계획은 배포할 이미지(관측 digest가 있으면 digest 고정), 되돌릴 설정 항목, env 차이, 실행 가능 여부와 계획 지문을 돌려준다. 실행은 계획 지문이 같을 때만 설정을 복원하고 새 배포 요청을 만든다. 이후 결과는 기존 요청 단위 rollout 판정으로 확인한다.
- 운영: 실행할 수 없으면 이유(설정 기록 없음, env 차이, 지원하지 않는 빌드 경로)를 보고, 계획이 바뀌었으면 409로 다시 계획을 보게 한다. 복구 배포는 상세에서 복구 대상 배포를 보여 준다.

## 현재 동작 (2026-10-11 코드 확인)
- 콘솔 `RollbackDialog`는 `getRollbackList`(빈 목록)·`rollbackToCommit`(`{}` 반환) stub을 쓰고 성공 토스트를 띄운다.
- 자연어 LIST_ROLLBACK은 상태와 무관한 최근 배포 10개를 `can_rollback: true`로 돌려준다. ROLLBACK은 `main` 브랜치와 commit으로 새 배포를 만들어 이미지를 다시 결정한다(이전 이미지·설정 복원 아님).
- 배포 기록(#95)에는 이미지·관측 digest·설정 스냅샷(env는 HMAC 지문만)이 있다. env 값은 남지 않는다.
- #56으로 승인 배포는 apply 직전 승인 설정 지문과 비교한다.
- KANIKO 빌드 경로 파이프라인은 요청 이미지와 관계없이 소스에서 다시 빌드한다.

## 범위
- 포함
  - 복구 후보 조회, 복구 계획 조회, 계획 지문 기반 실행 API.
  - 실행 시 env 외 배포 설정을 대상 스냅샷 값으로 복원(기존 설정 검증 경로 재사용)하고, 대상 이미지로 새 배포 요청 생성. 승인 설정 지문(#56)으로 apply 직전까지 고정.
  - 복구 배포에 복구 대상 배포 ID 기록·표시.
  - 콘솔 Rollback 대화상자 실제 연결, CLI 명령, 자연어 LIST_ROLLBACK·ROLLBACK·ROLLBACK_EXECUTION 교체.
- 제외
  - env 값 저장·복원(값이 없으므로 다르면 차단).
  - KANIKO 경로 복구(차단, 이유 표시), DB·데이터 복구, 자동 롤백, 상태 있는 앱.
  - 복구 계획의 DB 저장(계획 지문으로 승인).

## 입출력·계약
- `GET /api/v1/repositories/{repositoryId}/recovery-candidates` (READ_ONLY 이상): 같은 저장소의 SUCCESS이면서 이미지 기록이 있는 배포, 최신순 최대 20개. 필드: `deployment_id`, `commit_hash`, `image_uri`, `image_digest`, `finished_at`, `config_recorded`.
- `GET /api/v1/deployments/{targetId}/recovery-plan` (READ_ONLY 이상): `target_deployment_id`, `target_commit`, `image`(배포할 이미지), `image_digest`, `digest_pinned`, `config_changes`(현재 → 대상, env 제외, #95 `Change` 형식), `env_differences`(#95 `Change` 형식, `env:<이름>`), `executable`, `blocked_reasons`, `plan_fingerprint`(실행 가능할 때만).
- `POST /api/v1/deployments/{targetId}/recover` (FULL) body `{plan_fingerprint}` → 201 `DeploymentResponse`.
- 계획 지문: SHA-256(대상 배포 ID | 배포할 이미지 | 현재 설정 지문(#56)). 대상 기록은 바뀌지 않으므로 현재 설정이 바뀌면 지문이 바뀐다.
- 배포할 이미지: 대상에 관측 digest가 있으면 `<이미지 이름>@<digest>`(태그 제거), 없으면 기록된 `image_uri`(`digest_pinned=false`).
- 실행 가능 조건: 대상이 같은 저장소의 SUCCESS + 이미지 기록 + 설정 스냅샷 있음 + 대상 빌드 경로가 KANIKO가 아님 + 현재 env 이름·지문이 대상과 같음(지문 키가 다르면 확인 불가로 차단).
- 에러: `DEPLOY_004` 409 "복구 계획이 바뀌었습니다. 계획을 다시 확인하세요", `DEPLOY_005` 409 "복구할 수 없습니다"(이유는 message), 대상 없음·권한 없음 404 `DEPLOY_001`, 진행 중 배포 409 `DEPLOY_003`(기존).
- 실행 순서(한 트랜잭션): 계획 재계산 → 지문 비교 → 설정 복원(`RepositoryService.updateDeploymentConfig`, env는 현재 값 유지) → 복원된 설정 지문을 승인 지문으로 새 배포 생성(`image_uri` = 배포할 이미지, `recovered_from_deployment_id` = 대상). 커밋 후 기존 파이프라인 실행. 실패 시 설정 변경도 롤백된다.
- 저장: `V7__deployment_recovered_from.sql` — `deployments.recovered_from_deployment_id bigint` nullable. `DeploymentResponse`에 `recovered_from_deployment_id` 추가.
- 자연어
  - LIST_ROLLBACK: 복구 후보를 기존 `list_rollback` 형식으로 돌려준다. `can_rollback`은 설정 기록 여부.
  - ROLLBACK·ROLLBACK_EXECUTION(`owner`, `repo`, `commit_hash` 선택): commit 접두가 맞는 최신 후보, commit이 없으면 두 번째 최신 후보(직전 성공)를 대상으로 한다. 생성 시 계획을 계산해 `approval_target`에 대상·이미지·차단 이유를 보이고 계획 지문을 저장한다(#56 경로). confirm 시 계획 지문이 다르거나 실행 불가면 409 `AI_006`. 실행은 위 복구 실행과 같다.
  - `ApprovalTarget`에 `recovered_from_deployment_id`, `blocked_reasons` 추가(배포 명령은 null).
- CLI: `deployments recovery-plan <targetId>`, `deployments recover <targetId> [--yes]`(계획 출력 → 확인 → 실행). `--json` 지원. 실행 불가·409는 기존 4xx 규칙(exit 1).
- 콘솔: Rollback 대화상자가 후보 목록 → 계획(이미지·digest 고정 여부·되돌릴 설정·env 차이·차단 이유) → 확인 → 실행. 실행 불가면 실행 버튼 비활성. stub 함수 제거.

## 권한·안전
- 모든 조회·실행은 `ResourceAccessService`로 저장소·배포 소유권을 먼저 확인한다. 다른 사용자 배포는 404.
- 실행은 FULL scope(기본 규칙). 자연어 확인은 기존 confirm 규칙.
- env 값은 응답·로그에 나오지 않는다. 차이는 이름과 종류만 보인다.
- 복원 설정의 envFrom·pull secret 참조는 기존 허용 참조 검사를 그대로 통과해야 한다.

## 제약
- 참조: [architecture §3.2·§6](../../architecture.md), [0056](../0056-pin-approved-deployment-config/spec.md), [0095](../0095-deployment-request-detail/spec.md), [ADR-0004](../../adr/0004-flyway-forward-compatible-migrations.md).
- 새 `TriggerSource`·`FailureKind` 값을 추가하지 않는다(이전 릴리스가 읽지 못함). 요청 경로는 기존 WEB·CLI·NLP.

## 예외·경계 상황
| 상황 | 사용자가 보는 것 | 시스템 동작 |
|---|---|---|
| 대상에 설정 기록 없음(#95 이전) | 차단 이유 | 실행 불가 |
| 현재 env가 대상과 다름 | env 차이와 차단 이유 | 실행 불가. 사용자가 env를 맞춘 뒤 다시 계획 |
| 대상 빌드 경로 KANIKO | 차단 이유 | 실행 불가 |
| 계획 확인 후 설정 변경 | 409 DEPLOY_004 | 실행·설정 변경 없음 |
| 실행 후 빌드·apply 전 설정 변경 | 배포 FAILED(APPLY_FAILED) | #56 apply 직전 비교 |
| 진행 중 배포 있음 | 409 DEPLOY_003 | 설정 변경도 롤백 |
| 복원 설정이 허용 참조·도메인 검증 실패 | 400/409 기존 에러 | 실행 없음 |
| 대상 digest 없음 | `digest_pinned=false` 표시 | 기록된 이미지 URI로 배포 |

## 미결 질문 (Open)
- 없음.

## 결정 기록 (Decisions)
- 2026-10-11 Q1 → 사용자 결정(권장안): 이미지와 env 외 설정을 대상 스냅샷으로 복원한다. env가 다르면 실행을 막고 차이를 보인다. env 값을 추정하지 않는다.
- 2026-10-11 Q2 → 사용자 결정(권장안): 관측 digest가 있으면 digest로 고정 배포한다.
- 2026-10-11 Q3 → 사용자 결정(권장안): 계획을 저장하지 않고 계획 지문으로 승인한다. apply 직전은 #56 승인 설정 지문을 재사용한다.
- 2026-10-11 Q4 → 사용자 결정: API·콘솔·CLI에 더해 자연어 ROLLBACK도 같은 복구 계획으로 바꾼다.
- 2026-10-11 기본값: KANIKO 대상은 차단한다(파이프라인이 이미지를 다시 빌드함). 후보는 최신 20개.
- 2026-10-11 기본값: 복구 배포를 구분하려고 nullable `recovered_from_deployment_id`를 추가한다. 새 TriggerSource는 이전 릴리스가 읽지 못해 쓰지 않는다.
- 2026-10-11 (구현 중) 자연어 LIST_ROLLBACK의 `can_rollback`은 설정 기록이 있고 최신 성공 배포가 아닐 때 true다. 실제 실행 가능 여부는 계획에서 정한다.
- 2026-10-11 (구현 중) 사실 확인: 레지스트리 없이 import한 이미지는 관측 digest(`imageID`)로 다시 받아올 곳이 없어 digest 고정 배포가 ImagePullBackOff가 된다. 레지스트리에서 받은 이미지(GHCR 등)는 digest로 pull할 수 있다. 로컬 실제 클러스터 검증은 태그 경로로 했고, digest 고정 pull은 운영 레지스트리 이미지에서 확인한다.

## 완료 증거
| 증명할 것 | 방법 | 환경 |
|---|---|---|
| 계획: digest 고정, 설정 차이, env 차이·설정 기록 없음·KANIKO 차단, 권한 밖 404 | 서비스 테스트 | 로컬 PostgreSQL |
| 실행: 지문 일치 시 설정 복원·이미지 배포·승인 지문·대상 기록, 불일치 409·변경 없음, 진행 중 배포 시 설정 롤백 | 서비스 테스트 | 로컬 PostgreSQL |
| 자연어 LIST_ROLLBACK·ROLLBACK 승인 대상과 confirm 409 | NLP 테스트 | 로컬 |
| 실제 복구: 실패 배포 후 이전 이미지·설정으로 SUCCESS, digest 일치 | 패키징 JAR + 임시 k3s | 로컬 실제 클러스터 |
| V6 → V7, 이전 JAR 호환 | 패키징 JAR + PostgreSQL, 운영 DB 사본(사용자) | 로컬, 운영 DB 사본 |
| 콘솔 대화상자·CLI 계약 | mock API + 브라우저, CLI 테스트, tsc·build | 로컬 |
| 회귀 | PR 전 `./gradlew test` 1회 | 로컬 |

### 실행 결과 (2026-10-11)
- 로컬 PostgreSQL: `RecoveryServiceTest` — 후보(성공+이미지, 최신순), 대상 선택(직전 성공·commit 접두), 권한 밖 404, 계획(digest 고정 이미지·설정 차이·digest 없음), 계획 후 설정 변경 시 409·변경 없음, env 값 차이 차단·지문 없음, 진행 중 배포 시 409와 설정 복원 롤백, 실행 시 env 외 설정 복원·env 유지·대상 이미지·대상 기록·승인 지문·요청 경로·커밋 후 파이프라인 1회, 설정 기록 없음·KANIKO 차단, 자연어 ROLLBACK 승인 대상(계획 지문·대상·차단 이유 없음)과 권한 밖 대상 없음.
- 로컬: `ActionDispatcherTest` ROLLBACK이 commit 재빌드 없이 승인 계획 지문으로 복구하고 명령 ID를 남긴다. 전체 `./gradlew test` 238개, 실패·오류·건너뜀 0개.
- 로컬 실제 클러스터 (패키징 JAR + PostgreSQL 16 + Docker 임시 k3s v1.30.6): v1(redis) SUCCESS → replica 2로 설정 변경 + 없는 이미지 FAILED → env 값을 바꾸면 계획 실행 불가(env:GREETING) → env 복원 후 후보·계획(min/max_replicas 2→1) → 틀린 지문 409 → 복구 실행 SUCCESS. Deployment replica 1/1, 요청 annotation = 복구 배포 ID, 설정 1/1·env 유지, 관측 digest가 v1과 같음, `recovered_from_deployment_id` = v1. 위 사실 확인대로 digest 고정 경로는 import 이미지라 태그 경로로 검증했다.
- 로컬 패키징 JAR + PostgreSQL 16: 이전 릴리스(main `bb66ac0`) JAR로 V6 스키마와 종료 상태 행 생성 → 새 JAR V7 migrate·validate·readiness UP, 기존 행 유지·새 컬럼 null → V7 DB에서 이전 JAR 조회·배포 생성/갱신 → 새 JAR로 그 행 조회.
- 로컬 CLI 계약 테스트 5개 통과 (`recovery-plan` 출력·POST 없음, `recover --yes --json` 계획 지문 전송, 실행 불가 exit 1·POST 없음).
- 로컬 콘솔: `tsc --noEmit`, `npm run build` 통과. mock API + 브라우저로 후보 목록, 실행 가능 계획(digest 고정·되돌릴 설정), 차단 계획(env 차이·이유·버튼 비활성), 실행 후 닫힘(201) 확인. 375px에서 대화상자·페이지 가로 넘침 없음(DOM 측정).
- 운영 DB 사본 (2026-10-11, 사용자 실행): V6 사본 배포 14행(미완료 0)·명령 0행에 V7 migrate·Hibernate validate·readiness UP, 기존 행 값 유지, 새 컬럼 모두 NULL. 원본 변경 없음, 종료 시 사본·확인용 JAR 정리. 운영 원본에 V7을 적용한 결과가 아니다.
- 증거: `.local/evidence/0101-recover-previous-deployment/`.

## Tasks
### T1. 복구 계획·실행 서비스와 API
- 변경: `RecoveryService`, DTO, `DeploymentController`, `ErrorCode`, V7, `Deployment`·`DeploymentResponse`·`DeploymentOrigin`.
### T2. 자연어 교체
- 변경: `ActionDispatcher` LIST_ROLLBACK·ROLLBACK, `ApprovalTargetResolver`, `ApprovalTarget`.
### T3. 콘솔·CLI·문서
- 변경: `rollback-dialog.tsx`, `lib/api.ts`, CLI, architecture·CLI_REFERENCE·README·product·스펙.

## 운영 반영
- 미착수.

## 회고
- 어긋난 점: (1) 응답 DTO를 복구 대상 기록 전에 만들어 `recovered_from_deployment_id`가 빠졌다. 통합 테스트가 잡았고 기록 후 다시 만든다. (2) 실제 클러스터에서 digest 고정 배포가 ImagePullBackOff였다. 원인은 레지스트리 없는 import 환경이었고, 로컬 검증은 태그 경로로 하고 한계를 기록했다. (3) 두 테스트 메서드가 한 DB를 공유해 전체 행 수 비교가 깨졌다.
- 원인 분류: 테스트 부재(응답 필드), 외부 제약 미인지(로컬 검증 환경의 이미지 출처).
- 고친 위치: `RecoveryService.recover`, 스펙 Decisions, `RecoveryServiceTest`(상대 비교).
