---
issue: 95
title: 배포 요청 상세 화면과 요청별 설정·근거 기록
status: done
size: L
type: feat
branch: feat/#95-deployment-request-detail
---

# 배포 요청 상세 화면과 요청별 설정·근거 기록

## 목적 / 성공 조건
- 목적: 앱 소유자가 배포 목록에서 요청 한 건을 열어 **무엇이 바뀌었고 → 어떻게 진행됐고 → 결과가 무엇이며 → 근거가 무엇인지**를 한 화면에서 본다. 이후 AI 진단·MCP·복구 실행이 같은 기록을 근거로 쓴다 ([로드맵 단계 1](../../product.md)).
- 기술적 완료: 새 배포는 요청 경로·요청자·승인 명령·적용 설정·관측 digest·실패 종류가 기록된다. 상세 API가 기록, 직전 성공 배포와의 차이, 실패 종류별 규칙 기반 설명을 준다. 콘솔에 배포 목록과 상세 화면이 있다.
- 운영: 기록되지 않은 과거 값은 "기록 없음"으로 보이고 현재 값으로 추정하지 않는다.

## 현재 동작 (2026-10-10 코드 확인)
- `deployments`에는 저장소, branch, commit, image_uri, 상태, fail_reason, 시작·종료 시각, webhook delivery ID만 있다. 요청 경로·요청자·설정·digest가 없다.
- 배포 생성 경로: `DeploymentController`(웹 JWT, CLI 토큰, 배포 전용 토큰, GitHub Actions OIDC), `GitHubWebhookService`(push), `ActionDispatcher` DEPLOY·ROLLBACK(자연어, 승인 후 실행). 인증 주체(`CustomUserDetails`)에 scope와 OIDC 여부가 있다.
- 자연어 실행(`ActionDispatcher.dispatch`)은 승인 명령(CommandLog) ID를 받지 않아 배포와 연결되지 않는다.
- 파이프라인은 `applyK8sManifests`에서 배포 설정을 읽어 적용한다(#53). rollout 판정은 `RolloutResult`(SUCCEEDED/FAILED/SUPERSEDED)와 `fail_reason` 문장으로 남는다.
- 콘솔 Deployments 화면은 저장소별 최신 배포 1건을 카드와 대화상자로 보여 준다. 배포 목록 화면은 없다. `GET /deployments?repositoryId=`(페이지)는 있다.
- #55: 배포 #10(실패)과 #11(성공)은 commit·이미지가 같고 env만 달랐다.

## 범위
- 포함
  - Flyway `V4__deployment_request_record.sql`: 요청 경로, 요청자, 승인 명령 ID, 적용 설정 스냅샷, 관측 이미지 digest, 실패 종류 (모두 nullable, 기존 행 null)
  - 생성 경로별 요청 경로·요청자 기록, 자연어 실행에 승인 명령 ID 전달
  - apply 시점에 실제 적용한 설정 스냅샷 기록(비밀값 처리는 Q1), 성공 판정 시 Pod `imageID`에서 digest 기록, 판정 결과에서 실패 종류 기록
  - `GET /api/v1/deployments/{id}/detail`: 기록 + 직전 성공 배포와의 차이 + 실패 종류 설명
  - 콘솔: 저장소 배포 목록, 배포 요청 상세 화면 (Q2), #54 로그·이벤트 연결
  - CLI: `deployments get`에 요청 경로·digest·실패 종류 표시 (출력 필드 추가만)
- 제외
  - AI 진단, 롤백·복구 실행, 승인 흐름 변경(#56), 재시작 대조(#57)
  - 과거 배포의 설정·경로 추정 복원(backfill)
  - 빌드(Kaniko) 로그, 로그 보관

## 입출력·계약 (초안)
- 저장 (`deployments`, 모두 nullable)
  - `trigger_source` varchar: `WEB`, `CLI`, `CI_TOKEN`, `CI_OIDC`, `WEBHOOK`, `NLP`
  - `requested_by_user_id` bigint: 사람 요청이면 사용자, CI·webhook이면 null(저장소 소유자는 기존 연관으로 안다)
  - `command_log_id` bigint: 자연어 승인 명령
  - `config_snapshot` text(JSON): 적용한 배포 설정 (Q1)
  - `image_digest` varchar: 성공 판정 시 새 Pod의 `imageID`에서 관측한 `sha256:...`
  - `failure_kind` varchar: `IMAGE_PULL`, `CRASH_LOOP`, `CONFIG_ERROR`, `READINESS_TIMEOUT`, `UNSCHEDULABLE`, `ROLLOUT_TIMEOUT`, `PROGRESS_DEADLINE`, `DEPLOYMENT_MISSING`, `SUPERSEDED`, `BUILD_FAILED`, `APPLY_FAILED`, `OTHER`
- API `GET /api/v1/deployments/{id}/detail` (초안)
  - `deployment`: 기존 `DeploymentResponse` 필드 + `trigger_source`, `requested_by`, `command_log_id`, `image_digest`, `failure_kind`
  - `config`: 스냅샷 또는 null(`recorded: false`)
  - `previous_success`: 같은 저장소의 직전 `SUCCESS` 배포 ID·commit·이미지·digest 또는 null
  - `changes`: 직전 성공 대비 이미지·digest·설정 항목별 차이 (어느 한쪽 기록이 없으면 `unknown`)
  - `explanation`: 실패 종류별 고정 설명과 확인할 것 (Q3)
- 상태 전이: 변경 없음

## 권한·안전
- GET이므로 `READ_ONLY`·`PROPOSE_ONLY`·`FULL`. `requireDeployment` 뒤에만 조회한다.
- 비밀값: Secret·ConfigMap은 이름만 기록한다. env 값 처리는 Q1.
- 마이그레이션: 컬럼 추가만, 이전 JAR와 호환 ([ADR-0004](../../adr/0004-flyway-forward-compatible-migrations.md)). 머지 전 운영 DB 사본 검증.

## 미결 질문 (Open)
- 없음

## 결정 기록 (Decisions)
- 2026-10-10 등급 L. 스키마 변경, backend·frontend·CLI 출력.
- 2026-10-10 기본값: 스냅샷은 apply 시점에 실제로 적용한 설정을 기록한다(요청 생성 시점이 아님). 실패·중단에도 남는다.
- 2026-10-10 기본값: digest는 레지스트리를 호출하지 않고 성공 판정 때 새 ReplicaSet Pod의 `imageID`에서 관측한다. 관측 못 하면 null.
- 2026-10-10 기본값: 과거 배포는 backfill하지 않고 "기록 없음"으로 표시한다.
- 2026-10-10 Q1 env 값 → 저장하지 않는다. env 이름과 값의 HMAC 지문만 저장하고 화면에는 추가·삭제·값 변경됨만 표시한다. (#55처럼 값만 바뀐 경우도 구분, 비밀값이 DB에 남지 않음)
  - 지문 키는 새 설정 키 없이 `jwt.secret`에서 용도 라벨로 파생한다. 스냅샷에 키 식별자(키로 만든 짧은 지문)를 함께 저장하고, 두 스냅샷의 키 식별자가 다르면 값 변경 여부를 "판정 불가"로 둔다 (비밀키 교체 시 잘못된 "변경됨" 방지).
- 2026-10-10 Q2 화면 → Deployments 안의 전용 화면. 저장소 → 배포 목록 → 상세로 이동하고 URL(`?view=deployments&deployment=<id>`)로 열 수 있다.
- 2026-10-10 Q3 실패 종류별 설명 → 이번에 넣는다. 실패 종류를 판정 시점에 구조화해 저장하고, 종류별 고정 설명과 확인할 것을 보여 준다 (AI 진단의 규칙 기준선).
- 2026-10-10 Q4 요청 경로·요청자 기록과 자연어 승인 명령 연결 → 둘 다 이번에 넣는다. 자연어 실행에 승인 명령 ID를 전달한다.
- 2026-10-10 (구현 중) 웹 JWT와 CLI 전체 권한 토큰은 scope가 모두 `FULL`이라 구분되지 않는다 → 인증 주체(`CustomUserDetails`)에 CLI 토큰 인증 여부를 추가했다. 5-인자 생성자는 CLI 토큰 인증에서만 쓰인다.
- 2026-10-10 (구현 중) 스냅샷은 `startK8sDeploy`(apply 전에 커밋하는 트랜잭션)에서 설정을 읽어 기록한다. apply가 실패해도 남는다. apply는 같은 파이프라인에서 설정을 다시 읽으므로, 그 사이 설정이 바뀌면 스냅샷과 적용값이 다를 수 있다(밀리초 단위 창, 범위 밖).
- 2026-10-10 (구현 중) 파이프라인 예외의 실패 종류는 단계로 정한다: 이미지 준비(빌드·이미지 결정) → `BUILD_FAILED`, 스냅샷·apply → `APPLY_FAILED`, 그 밖 → `OTHER`. rollout 판정 실패는 `RolloutResult.failureKind`를 쓴다.
- 2026-10-10 (구현 중) 콘솔 API 메서드 이름 `getDeploymentHistory`가 이미 다른 화면에서 쓰여 `getRepositoryDeployments`로 추가했다.
- 2026-10-10 (구현 중) 화면은 Lazyweb 참고 사례(Vercel 배포 상세·목록)를 보고, 실패 요약을 위에 두고 요청·변경·설정을 카드로 나누는 구성으로 정했다. 로그·이벤트는 #54 대화상자를 연다.

## 완료 증거
| 증명할 것 | 방법 | 환경 | 결과 |
|---|---|---|---|
| 요청 경로 기록(웹·CI·webhook·자연어+승인 명령) | `DeploymentServiceTest`, `DeploymentControllerTest`, `GitHubWebhookServiceTest`, `NlpCommandServiceTest` | 로컬 | 통과 |
| 스냅샷: env 값 미저장·지문 비교·키 교체 시 판정 불가, apply 전 기록 | `DeploymentConfigSnapshotsTest`, `DeploymentPipelineStepServiceTest` | 로컬 | 통과 |
| 실패 종류(대기 사유·타임아웃·대체·삭제·진행 기한·단계별 예외), digest 파싱 | `KubernetesManifestGeneratorTest`, `DeploymentPipelineServiceTest` | 로컬 | 통과 |
| 상세: #55 사례(같은 이미지, env 삭제) 구분, 기록 없는 과거 배포 NOT_RECORDED·NO_PREVIOUS | `DeploymentDetailServiceTest` | 로컬 | 통과 |
| 상세 API 소유자 조회, 다른 사용자(JWT·CLI) 404, 기록 없는 배포는 설정 null | `Stage2HttpAccessTest` | 로컬 (Testcontainers PostgreSQL, V1~V4 적용·validate) | 통과 |
| V3 상태 DB(기존 행 포함)에 V4 적용, `validate` 기동, readiness `UP`, 기존 행 유지(새 컬럼 null) | 패키징 JAR (`spring.flyway.target=3`로 V3 생성 후 행 삽입, 재기동) | 로컬 PostgreSQL 16 | 통과 |
| 운영 DB 사본(V3, 배포 11행)에 V4 적용, `validate` 기동, readiness `UP`, 기존 행 유지(새 컬럼 값 0행) | 패키징 JAR (`verify-v4-on-db-copy.sh`, 사본은 확인 후 삭제) | 운영 DB 사본 | 통과 (2026-10-10, 사용자 서버 실행) |
| 콘솔: 배포 목록, #10(실패·env 삭제), #9(자연어·승인 명령·NOT_RECORDED), #3(기록 없음), 로그 대화상자, URL 이동·뒤로 가기, 모바일 폭 | mock API + Next dev, 브라우저 | 로컬 | 통과 (모바일은 앱 공통 사이드바가 접히지 않음, 이슈 후보) |
| 회귀 | `./gradlew test` (221개), `npx tsc --noEmit -p .`, `npm run build` | 로컬 | 통과 |
| digest·스냅샷 실제 기록, #55 사례 운영 구분 | 머지 후 운영 샘플 앱 배포 | 운영 | 머지 후 |

- 원자료: `.local/evidence/0095-deployment-request-detail/2026-10-10/`
- 참고 화면: Lazyweb 검색 (Vercel 배포 상세·목록)

## Tasks
### T1. 스키마와 엔티티
- 변경: `V4__deployment_request_record.sql`, `Deployment` 필드, `TriggerSource`·`FailureKind` enum
- 검증: JPA 왕복 테스트, 이력 없는 V3 DB에 V4 적용 후 `validate` 기동
### T2. 요청 경로·요청자·승인 명령
- 변경: `DeploymentController`(주체 → 경로), `GitHubWebhookService`, `ActionDispatcher`·`NlpCommandService`(명령 ID 전달), `DeploymentService.createDeployment`
- 금지: 기존 중복·동시성 판정(#52) 변경
### T3. 설정 스냅샷·digest·실패 종류
- 변경: `applyK8sManifests`(스냅샷), 판정 결과(digest, 실패 종류), 빌드·apply 실패 경로
- 검증: 단위 테스트, 임시 k3s에서 digest 관측
### T4. 상세 API
- 변경: `GET /deployments/{id}/detail`, 직전 성공 비교, 실패 종류 설명
- 검증: HTTP 테스트(소유자·다른 사용자·기록 없는 과거 배포)
### T5. CLI·콘솔
- 변경: CLI `deployments get` 필드, 콘솔 배포 목록·상세 화면(#54 로그·이벤트 연결)
- 먼저 읽을 것: `frontend/DESIGN.md`, 기존 Deployments 화면
- 검증: tsc·build, mock API 브라우저 확인(데스크톱·모바일)
### T6. 문서와 운영 검증
- 변경: architecture(§3.2, §5, §9 설정 스냅샷 격차 정리), backend/frontend README, CLI_REFERENCE, ORACLE 가이드, product
- 검증: 운영 DB 사본 V4 (사용자 서버 실행), 머지 후 운영 샘플 앱으로 #55 사례 구분 확인

## 운영 반영
- (머지 후 기록)

## 회고
- 어긋난 점: (1) 웹 JWT와 CLI 토큰이 같은 scope라 요청 경로를 구분할 수 없다는 점을 스펙 초안에서 확인하지 않았다. (2) 콘솔 API에 같은 이름의 메서드가 이미 있었다. (3) 모바일 폭에서 앱 공통 사이드바가 접히지 않는다.
- 원인 분류: 스펙 누락 (인증 주체 정보 조사), 외부 제약 미인지 (기존 프론트 코드)
- 고친 위치: 인증 주체에 CLI 토큰 여부를 추가했다. 이름 충돌은 tsc가 잡아 별도 장치는 두지 않는다. 사이드바는 architecture §9 이슈 후보로 남겼다.
