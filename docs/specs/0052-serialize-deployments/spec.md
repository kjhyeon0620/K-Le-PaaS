---
issue: 52
title: 동일 저장소 동시 배포와 webhook·callback 중복 접수 방지
status: done
size: L
type: fix
branch: fix/#52-serialize-deployments
---

# 동일 저장소 동시 배포와 webhook·callback 중복 접수 방지

## 목적 / 성공 조건
- 목적: 같은 저장소에 배포 파이프라인이 동시에 둘 이상 돌지 않는다. GitHub webhook 재전송과 CI 재시도가 배포를 중복으로 만들지 않는다.
- 기술적 완료: 배포 생성 경로(webhook, `POST /deployments`, 자연어 DEPLOY·ROLLBACK)가 모두 `DeploymentService.createDeployment`에서 같은 검사를 거친다.
- 운영: 거절된 요청은 409와 진행 중인 배포 ID를 받는다. 진행 중 배포가 끝나면 다시 요청할 수 있다. GitHub delivery 기록에서 거절된 push를 확인하고 Redeliver할 수 있다.

## 범위
- 포함: 저장소 단위 동시 배포 거절(409), 진행 중 배포와 같은 요청의 재시도 처리, webhook `X-GitHub-Delivery` 중복 제거, 배포 실행 대기열이 가득 찼을 때의 정리
- 제외: 대기열(나중 요청을 진행 중 배포 뒤에 예약), 멈춘 배포의 상태 대조(#57), 배포 중 scale·restart 차단, `Idempotency-Key` 헤더, CLI 변경(배포 생성 명령 없음)

## 입출력·계약
- API `POST /api/v1/deployments`
  - 새 배포: 201 (기존과 같음)
  - 진행 중 배포와 `branch_name`·`commit_hash`·`image_uri`가 모두 같음: 새로 만들지 않고 그 배포를 200으로 반환
  - 진행 중 배포가 있고 요청이 다름: 409 `DEPLOY_003` (`DEPLOYMENT_IN_PROGRESS`), 메시지에 진행 중 배포 ID
- Webhook `POST /api/v1/webhooks/github` (push)
  - 이미 배포를 만든 `X-GitHub-Delivery`: 새 배포 없이 200
  - 진행 중 배포와 충돌: 409 (delivery ID는 저장하지 않아 Redeliver 가능)
  - 그 밖의 응답은 기존과 같음
- 자연어 DEPLOY·ROLLBACK: 같은 검사. 충돌하면 명령 실행이 `DEPLOY_003` 메시지로 실패한다.
- 저장: Flyway `V5__deployment_github_delivery_id.sql`
  - `deployments.github_delivery_id varchar(64)` nullable, unique. 기존 행은 null.
- 상태 전이: 변경 없음. 대기열 거절 시 `PENDING → FAILED`

## 진행 중 판정
- 진행 중 = 상태가 `PENDING`, `UPLOADING_SOURCE`, `BUILDING`, `DEPLOYING` 중 하나이고, 마지막 갱신(`updated_at`) 후 경과 시간이 `빌드 타임아웃 + rollout 타임아웃 + 10분` 이내인 배포
- 이보다 오래된 미완료 배포는 막지 않는다. 상태값은 바꾸지 않는다(대조는 #57).
- 새 설정 키는 만들지 않는다. 기존 `deployment.pipeline.build-timeout`, `kubernetes.rollout.timeout-ms`로 계산한다.

## 권한·안전
- 호출 주체와 scope는 기존과 같다 (`POST /deployments`: FULL 또는 지정 저장소 DEPLOY, webhook: 서명 검증).
- 소유권 검사(`ResourceAccessService.requireRepository`)를 먼저 하고, 그다음 저장소 행을 잠근다. 권한 없는 요청은 잠그지 않는다.
- 비밀값 없음. delivery ID는 비밀값이 아니다.

## 제약
- 참조: [architecture](../../architecture.md) §3.2, §5, §6, [ADR-0004](../../adr/0004-flyway-forward-compatible-migrations.md)
- 동시 요청 중 하나만 통과해야 한다. `createDeployment` 트랜잭션에서 저장소 행에 `PESSIMISTIC_WRITE` 잠금을 잡은 뒤 진행 중 배포와 delivery ID를 확인한다. delivery ID는 unique 제약으로도 막는다.
- 잠금을 잡은 트랜잭션 안에서는 외부 호출(Kubernetes, GitHub)을 하지 않는다.

## 예외·경계 상황
| 상황 | 사용자·agent가 보는 것 | 시스템 동작 |
|---|---|---|
| 같은 저장소에 다른 배포 요청 2건이 동시에 도착 | 하나는 201, 하나는 409 `DEPLOY_003` | 배포 1건만 생성·실행 |
| CI가 같은 요청을 재시도 (진행 중) | 200, 같은 배포 ID | 새 배포 없음 |
| CI가 같은 요청을 재시도 (이전 배포 완료 후) | 201, 새 배포 | 의도적 재배포로 본다 |
| 같은 delivery ID webhook 2회 (동시 포함) | 200 | 배포 1건 |
| 진행 중에 다른 커밋 push webhook | GitHub에 409 | 배포 없음, delivery ID 저장 안 함 |
| 다른 저장소의 배포 | 서로 막지 않음 | - |
| 오래된 미완료 배포(재시작으로 멈춤) | 새 배포 201 | 기존 행 상태는 그대로 |
| 배포 실행 대기열이 가득 참 | 응답은 201(생성은 커밋됨), 배포는 곧 FAILED | `PENDING → FAILED` "배포 실행 대기열이 가득 찼습니다". 저장소를 막지 않는다 |
| 권한 없음 / 다른 사용자 저장소 | 기존과 같음 (404/403) | 잠금·검사 전에 거절 |
| `X-GitHub-Delivery` 없음·형식 이상 | 기존처럼 처리 | 중복 제거만 하지 않음 |

## 미결 질문 (Open)
- 없음

## 결정 기록 (Decisions)
- 2026-10-03 멈춘 미완료 배포 처리 → 마지막 갱신 후 파이프라인 최대 시간이 지나면 진행 중으로 보지 않는다. 상태는 바꾸지 않는다. (재시작 후 저장소가 영구히 잠기지 않게. 실제 결과를 모르는 배포를 FAILED로 기록하지 않기 위해. 대조는 #57)
- 2026-10-03 CI 재시도 → 진행 중 배포와 branch·commit·image_uri가 같으면 기존 배포를 200으로 반환. 완료 후 같은 요청은 새 배포. 별도 Idempotency-Key 없음. (CI workflow를 바꾸지 않고, 의도적 재배포를 막지 않기 위해)
- 2026-10-03 진행 중 충돌 webhook → GitHub에 409. 거절된 delivery ID는 저장하지 않는다. (실패가 GitHub delivery 기록에 남고 Redeliver로 이어갈 수 있게)
- 기본값 가정: 충돌 요청은 거절(409)하고 대기열에 넣지 않는다 (이슈 본문 기준).
- 기본값 가정: 대기열 거절 시 배포를 FAILED로 기록한다. 실행되지 않은 것이 확실하므로 사실과 같다.
- 2026-10-03 (구현 중) image_uri 없는 요청(webhook, 콘솔, 자연어)은 branch·commit만 같으면 같은 요청으로 본다. → 진행 중 배포의 image_uri는 빌드·이미지 확인 뒤에 채워지므로 비교하면 같은 요청도 409가 된다.
- 2026-10-03 (구현 중) 기존 권한 HTTP 테스트 2개가 PENDING 배포 fixture를 둔 채 같은 저장소에 새 배포를 요청했다. 새 규칙에서는 409가 맞으므로 fixture를 완료 상태로 바꿨다. (테스트 목적은 권한 경계)

## 완료 증거
| 증명할 것 | 방법 | 환경 | 결과 |
|---|---|---|---|
| 같은 저장소 동시 요청 2건 중 1건만 생성 | `DeploymentSerializationHttpTest` (실제 HTTP·H2, 스레드 동시 호출) | 로컬 | 통과 |
| 다른 저장소는 서로 막지 않음 | 같은 테스트 | 로컬 | 통과 |
| 진행 중 같은 요청 재시도는 기존 배포 200, 완료 후에는 201 | 같은 테스트 | 로컬 | 통과 |
| 같은 delivery ID 2회(동시 포함) → 배포 1건, 충돌 push → 409, 완료 후 Redeliver 가능 | 같은 테스트 | 로컬 | 통과 |
| 오래된 미완료 배포는 막지 않고 상태 유지 | 같은 테스트 | 로컬 | 통과 |
| 대기열 거절 시 FAILED | 같은 테스트 | 로컬 | 통과 |
| 백엔드 전체 | `./gradlew test bootJar` (186개) | 로컬 | 통과 |
| V4 DB에 V5 적용, validate 기동, 기존 행 유지 | 패키징 JAR | 로컬 H2 | 통과 |
| 운영 DB 사본(V4)에 V5 적용 후 validate 기동 | 패키징 JAR | 운영 DB 사본 | 통과 (2026-10-03, readiness `UP`, V5 1건 적용, 사본 삭제) |

## Tasks
### T1. 마이그레이션과 엔티티
- 변경: `V5__deployment_github_delivery_id.sql`, `Deployment.githubDeliveryId`, `DeploymentRepository` 조회 메서드, `SourceRepositoryRepository` 잠금 조회
- 검증: JPA 왕복, 패키징 JAR 마이그레이션

### T2. 배포 생성 직렬화
- 변경: `DeploymentService.createDeployment` (잠금, delivery 중복, 진행 중 판정, 같은 요청 반환), `ErrorCode.DEPLOYMENT_IN_PROGRESS`, 대기열 거절 처리, `DeploymentController` 201/200
- 금지: 잠금 안에서 외부 호출
- 검증: 동시성 테스트, 서비스 테스트

### T3. Webhook
- 변경: `GitHubWebhookController`(`X-GitHub-Delivery` 전달, 409), `GitHubWebhookService`
- 검증: webhook HTTP 테스트

### T4. 문서
- `docs/architecture.md` (§3.2 흐름, §6 에러 코드, §9에서 #52 제거), `docs/ORACLE_K3S_GHCR_DEPLOYMENT.md` (CI 재시도와 409), `backend/README.md`, `docs/product.md`

## 운영 반영
- 2026-10-03 PR #72 머지. main `Build and deploy`의 build·deploy 성공. 운영 백엔드는 `ddl-auto=validate`로 기동하므로 receiver readiness 통과로 운영 DB V5 적용을 확인했다.

## 회고
- 어긋난 점: 스펙 범위 안에서 구현했다. 기존 권한 HTTP 테스트 2개가 진행 중(PENDING) 배포 fixture를 둔 채 같은 저장소에 배포를 요청하고 있어, 새 규칙에서 409가 됐다. 스펙 작성 중 실행 대기열 거절 시 배포가 PENDING으로 남는 기존 결함을 찾아 범위에 넣었다.
- 원인 분류: 외부 제약 미인지 (테스트 fixture가 "진행 중 배포가 있어도 새 배포 가능"을 전제)
- 고친 위치: 해당 fixture를 완료 상태로 변경. 규칙 자체는 `DeploymentSerializationHttpTest`가 검사하므로 별도 장치는 만들지 않았다.
