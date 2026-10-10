# Architecture

이 문서는 모듈 책임, 주요 흐름, 공통 계약(권한, 위험도, 상태 모델, 에러 코드), 설정, 알려진 격차를 정리한 기준 문서다.
결정 근거는 [`adr/`](adr/README.md), 운영 절차는 [`CICD.md`](CICD.md)와 [`ORACLE_K3S_GHCR_DEPLOYMENT.md`](ORACLE_K3S_GHCR_DEPLOYMENT.md)에 있다.
코드와 이 문서가 다르면 코드가 사실이다. 이 문서를 고치거나 [§9 알려진 격차](#9-알려진-격차)에 추가한다.

## 1. 구성

```text
사용자 / 운영자 / Agent / CI
   ├─ Web console (Next.js, /console) ─┐
   ├─ CLI (frontend/cli, Node)  ───────┼─▶ Spring Boot API (/api/v1)
   ├─ 자연어 명령 ─────────────────────┤      ├─ 인증: JWT(Web) · CLI 토큰(scope)
   └─ CI (GitHub Actions) ─────────────┘      ├─ ai: Gemini → Intent → 위험도 → 승인 → 실행
                                              ├─ deployment: 저장소·설정·배포·스케일·파이프라인
                                              ├─ infra: NCP(Object Storage·Kaniko·NCR), Kubernetes(Fabric8)
                                              ├─ webhook: GitHub push
                                              └─ global: 에러, WebSocket, Slack, system(health/ready/version)
                                                     │
                                              PostgreSQL 16 (운영) · Flyway
                                                     │
                                              Oracle k3s (사용자 앱) / NCP NKS
```

| 영역 | 기술 |
|---|---|
| Backend | Java 17, Spring Boot 4.0.2, Spring Security, Spring Data JPA, Flyway, Fabric8, AWS SDK v2(S3 호환), Springdoc, Jackson `SNAKE_CASE` |
| Frontend | Next.js 15, React 19, TypeScript 5, Tailwind 4, shadcn/ui ([DESIGN.md](../frontend/DESIGN.md)) |
| CLI | `frontend/cli/*.mjs` (Node) |
| AI | Gemini 2.5 Flash (`gemini.api.model`), 시스템 프롬프트 `backend/src/main/resources/prompts/system-prompt.txt` |
| DB | PostgreSQL 16 (운영, 백엔드와 같은 호스트), `ddl-auto=validate`, Flyway `db/migration` ([ADR-0008](adr/0008-postgresql-production-database.md)) |
| 플랫폼 배포 | GitHub Actions ARM64 빌드 → SSH receiver → systemd 릴리스 ([ADR-0005](adr/0005-platform-release-systemd-receiver.md)) |

## 2. 모듈 책임 (`klepaas.backend`)

| 패키지 | 책임 |
|---|---|
| `auth` | GitHub OAuth, JWT, CLI 토큰(`auth/token`, scope), CLI 웹 로그인 세션(`auth/weblogin`), `SecurityConfig` |
| `ai` | `GeminiClient`, `IntentParser`, `ActionDispatcher`(intent → 위험도 → 실행), `NlpCommandService`, `CommandConfirmationService`, `KubectlService`(Fabric8 조회, 셸 실행 없음) |
| `deployment` | `RepositoryService`(저장소·배포 설정), `DeploymentService`(배포·스케일·재시작), `DeploymentPipelineService`/`StepService`(비동기 파이프라인), `ExternalImageResolver`, `ResourceAccessService`·`RuntimeResourcePolicy`(소유권·허용 참조) |
| `infra` | `CloudInfraProvider`(NCP 구현: `NcpInfraService`), `KubernetesManifestGenerator`(Deployment/Service/Ingress 적용, rollout 대기), `ImageTagGenerator` |
| `cost` | spec 기반 비용 plan/diff/explain/check (실제 billing 아님) |
| `webhook` | GitHub push webhook 서명 검증, KANIKO 경로 자동 배포 |
| `global` | `ErrorCode`/`GlobalExceptionHandler`, WebSocket 배포 이벤트, Slack 알림, system API |
| `user` | 사용자 조회 |

## 3. 주요 흐름

### 3.1 자연어 명령

```text
POST /api/v1/nlp/command
  → Gemini로 intent 해석 (IntentParser)
  → ActionDispatcher가 위험도 결정
       DEPLOY, ROLLBACK, ROLLBACK_EXECUTION → HIGH
       SCALE, RESTART                        → MEDIUM
       그 외(조회)                            → LOW
  → LOW: 즉시 실행 (소유권 범위 안의 조회)
  → MEDIUM/HIGH: CommandLog(PENDING) 저장 후 승인 대기
POST /api/v1/nlp/confirm
  → 본인 명령이고, 생성 10분 이내이고, PENDING일 때만 EXECUTING으로 원자적 전이 (한 번만 소비)
  → 10분이 지났으면 EXPIRED
  → 실행 결과로 SUCCEEDED / FAILED
```

- 자연어를 셸 명령으로 실행하지 않는다. 모든 실행은 백엔드 서비스와 Fabric8 호출을 거친다 ([ADR-0002](adr/0002-intent-risk-confirmation.md)).
- `PROPOSE_ONLY` 토큰은 `nlp/command`로 명령을 만들 수 있지만 `confirm`은 403이다.

### 3.2 배포

```text
POST /api/v1/deployments (FULL 또는 DEPLOY scope. DEPLOY는 지정 저장소만 허용)
  → 저장소 행 잠금 후 진행 중 배포 확인 (webhook·자연어 DEPLOY/ROLLBACK도 같은 경로)
       진행 중 배포와 같은 요청(branch·commit·image_uri) → 그 배포를 200으로 반환
       다른 요청 → 409 DEPLOY_003 / 이미 배포를 만든 webhook delivery → 새 배포 없음
  → Deployment(PENDING) 저장
  → 트랜잭션 커밋 후 비동기 파이프라인 시작 (실행 대기열이 가득 차면 FAILED)
  ├─ KANIKO (NCP)
  │    UPLOADING_SOURCE: GitHub ZIP 다운로드 → 재패키징 → NCP Object Storage
  │    BUILDING: Kaniko Job (initContainer가 소스 준비) → NCR {owner}-{repo}:{shortSha}
  └─ GITHUB_ACTIONS_GHCR / PREBUILT_IMAGE (외부 이미지)
       요청의 image_uri 또는 image_uri_template으로 이미지 결정 (빌드 단계 없음)
  → DEPLOYING: 상태와 image_uri를 먼저 커밋 → Deployment/Service/Ingress 적용
       (기존 Deployment는 관측한 resourceVersion으로 전체 교체, Service·Ingress는 강제 server-side apply.
        Deployment metadata에 annotation `klepaas.io/deployment-id`로 적용한 요청 ID를 남긴다)
  → 전체 apply 반환 후 응답의 Deployment UID·generation을 요청 행에 저장 (#57)
  → 트랜잭션 밖에서 적용한 generation의 rollout만 판정 (#53)
       성공: 그 generation을 관측했고 Available, updated = available = desired, unavailable = 0
       FAILED: Progressing=False, 새 ReplicaSet Pod의 ImagePullBackOff·InvalidImageName·ErrImageNeverPull·
               CreateContainerConfigError·CrashLoopBackOff (타임아웃 전 즉시), Deployment 삭제,
               `kubernetes.rollout.timeout-ms`(120초) 타임아웃 (readiness 실패, 스케줄 불가 등)
       CANCELED: 대기 중 다른 apply·restart·scale로 generation이 바뀜 (대체)
  → SUCCESS / FAILED / CANCELED (사유는 fail_reason), Slack·WebSocket 알림
  배포 요청 기록 (#95, 이전 배포는 null이고 "기록 없음"으로 표시, 추정하지 않음)
       생성 시: 요청 경로(WEB·CLI·CI_TOKEN·CI_OIDC·WEBHOOK·NLP), 요청자(사람일 때), 자연어 승인 명령 ID
       apply 직전 커밋: 적용한 배포 설정 스냅샷 (env 값은 저장하지 않고 이름별 HMAC 지문만. 키는 jwt.secret에서 파생)
       판정 시: 성공이면 새 Pod imageID의 digest, 실패·대체면 실패 종류(FailureKind)
GET /api/v1/deployments/{id}/detail
  → 요청 기록, 적용 설정(env 이름만), 같은 저장소의 직전 SUCCESS 배포 대비 변경(이미지·digest·설정, env는 추가·삭제·값 변경·판정 불가),
    실패 종류별 고정 설명. 한쪽 설정 기록이 없으면 이미지만 비교(NOT_RECORDED)
       fail_reason에는 관측 근거를 적는다: 반복 종료는 마지막 종료 사유·exit code(OOMKilled 등),
       타임아웃은 새 Pod 상태(스케줄 불가 메시지, 실행 중이나 Ready 아님) (#54)
GET /api/v1/deployments/{id}/logs?lines=N (기본 100, 1~200)
  → 소유권 검사 후, 클러스터 Deployment의 klepaas.io/deployment-id가 이 배포일 때만
    현재 revision ReplicaSet의 Pod(최대 5) 상태·최근 로그(재시작했으면 직전 컨테이너 로그)와 그 Pod·ReplicaSet 이벤트(최대 20)
  → observation: AVAILABLE / NOT_CURRENT(다른 요청이 적용, 적용 기록 없음, Deployment 없음) / UNAVAILABLE(Kubernetes 조회 실패)
  → 로그는 저장하지 않는다. 사용자 메시지에는 Kubernetes API 주소를 넣지 않는다
```

- 빌드 경로 기본값: `NCP → KANIKO`, `ON_PREMISE → GITHUB_ACTIONS_GHCR` ([ADR-0001](adr/0001-external-image-build.md)).
- 외부 이미지 경로의 저장소는 raw push webhook을 무시한다. 이미지 push가 끝난 뒤 CI가 배포 API를 호출한다.
- "진행 중"은 `PENDING`~`DEPLOYING` 상태이면서 마지막 갱신 후 `빌드 타임아웃 + rollout 타임아웃 + 10분` 이내인 배포다. 재시작 등으로 그보다 오래 멈춘 배포는 새 배포를 막지 않는다. 재시작 직후 대조 전에는 이전 프로세스의 배포도 이 규칙으로 새 요청을 막을 수 있다.
- 생성하는 리소스에는 `klepaas.io/repository-id` 라벨을 붙이고, 조회와 변경도 이 라벨과 설정된 namespace 안으로 제한한다 ([ADR-0006](adr/0006-resource-access-scope.md)).

#### 재시작 대조 (#57, `InterruptedWorkReconciler`)

```
웹 서버가 요청을 받기 전: 미완료 배포(PENDING~DEPLOYING)·EXECUTING 명령 id만 확정 (시각 비교 없음)
ApplicationReadyEvent에서 한 번, 행마다: 짧은 읽기 → 소유권 확인 → (DEPLOYING만) Kubernetes 한 번 조회 → 이전 상태 조건 update
  PENDING / UPLOADING_SOURCE / BUILDING         → FAILED(OTHER). 외부 빌드 완료·취소는 주장하지 않음
  DEPLOYING, 적용 UID·generation 없음           → UNKNOWN (apply 전후 중단·이전 릴리스)
  소유권 미확인 / 다른 저장소 라벨 / 조회 실패  → UNKNOWN (소유권 미확인이면 Kubernetes 호출 없음)
  적용 근거 있음, Deployment 없음               → FAILED(DEPLOYMENT_MISSING)
  UID·요청 annotation·generation 변경           → CANCELED(SUPERSEDED). 같은 이미지 restart·scale 포함
  일치 + #53 성공 조건 / 명확한 실패            → SUCCESS(digest는 새 revision Pod에서 관측될 때만) / FAILED(기존 FailureKind)
  이미지 불일치·진행 중                         → UNKNOWN. rollout을 기다리지 않음
  EXECUTING 명령                                → UNKNOWN. 재실행·승인 재소비 없음
```

- 이유는 `fail_reason`(명령은 `error_message`)에 `재시작 대조:` 접두로 남긴다. 알림·WebSocket은 보내지 않는다.
- 외부 변경(재배포·재빌드·restart·scale·rollback)을 자동 실행하지 않는다. 주기적·수동 재대조는 없다.
- 한 행의 조회·저장 실패는 로그만 남기고 그 행을 그대로 둔다. 기동·readiness는 막지 않으며 다음 재기동에 다시 대상이 된다.
- 단일 백엔드 프로세스 전제다. 여러 인스턴스가 동시에 대조하는 경우는 다루지 않는다.

### 3.3 CLI 웹 로그인

`POST /cli-auth/sessions` → 사용자가 브라우저에서 승인(`approve`는 FULL 필요) → CLI가 `exchange`로 토큰을 받는다. 토큰은 해시만 저장하고, 원문은 발급할 때 한 번만 노출한다. `DEPLOY` scope는 웹 설정에서만 발급한다.

### 3.4 플랫폼 자체 배포

`main` push → `release.yml`(ARM64 빌드, 테스트, 릴리스 archive) → `production` 환경의 deploy job → SSH forced command receiver가 검증, 활성화, readiness·revision 확인, 실패 시 이전 릴리스 복구. DB는 건드리지 않는다. 자세한 절차는 [CICD.md](CICD.md).

## 4. 권한

### 4.1 토큰 scope ([ADR-0003](adr/0003-cli-token-scopes.md))

| scope | 허용 | 용도 |
|---|---|---|
| `READ_ONLY` | 모든 GET, `POST /cost/**` | 모니터링 |
| `PROPOSE_ONLY` | READ_ONLY + `POST /nlp/command` (승인은 불가) | AI agent |
| `DEPLOY` | 지정한 저장소 1개의 `POST /deployments`만 | CI |
| `FULL` | 전체 | 사람 (Web JWT는 항상 FULL) |

- 규칙은 `SecurityConfig`에서 강제한다. 요청 본문의 저장소 일치 여부만 `DeploymentController`에서 확인한다.
- 범위를 벗어나면 403 `CLI_005`

### 4.2 GitHub Actions OIDC ([ADR-0007](adr/0007-github-actions-oidc.md))

- GitHub Actions 워크플로는 실행마다 발급받은 OIDC 토큰으로 `POST /deployments`를 호출한다. 저장 토큰이 필요 없다.
- `JwtAuthenticationFilter`가 Web JWT, CLI 토큰 다음으로 OIDC 토큰을 검증한다: GitHub JWKS 서명, `iss`, `aud`, `exp`, 허용 `ref`. 실패하면 401.
- 인증 주체에는 사용자가 없고 `SCOPE_GITHUB_ACTIONS` 권한만 있다. `POST /deployments` 외 API는 403.
- `ResourceAccessService.requireGitHubActionsOwner`가 요청 `repository_id`의 등록 저장소 `owner/repo`·`branch_name`·`commit_hash`를 토큰 `repository`·`ref`·`sha`와 대조하고, 맞으면 그 저장소 소유자로 배포를 만든다. 다르거나 저장소가 없으면 403 `CLI_005`.

### 4.2 리소스 접근 ([ADR-0006](adr/0006-resource-access-scope.md), [STAGE2_RESOURCE_ACCESS.md](STAGE2_RESOURCE_ACCESS.md))

- 사용자는 본인 저장소와, 그 저장소 라벨이 붙은 설정 namespace 안의 Kubernetes 리소스만 조회하고 변경할 수 있다.
- ConfigMap, Secret, image pull secret 참조는 운영자가 서버 설정(`kubernetes.runtime-resources.repositories`)에서 저장소별로 허용한 이름만 쓸 수 있다. Secret 값은 다루지 않는다.
- 배포 설정 `image_pull_secret_enabled`가 false면 Pod에 `imagePullSecrets`를 넣지 않고 pull secret 허용 검사도 하지 않는다(공개 이미지). 기본은 true(설정 이름 또는 `kubernetes.image-pull-secret`).

## 5. 상태 모델

| 모델 | 값 | 비고 |
|---|---|---|
| `DeploymentStatus` | `PENDING → UPLOADING_SOURCE → BUILDING → DEPLOYING → SUCCESS`, `FAILED`, `CANCELED`, `UNKNOWN` | 외부 이미지 경로는 업로드·빌드 단계를 건너뛴다. `CANCELED`는 rollout 대기 중 다른 변경으로 대체된 배포 (§3.2). `UNKNOWN`은 판정 불가이며 CLI wait는 exit 3. 재시작 대조(§3.2)만 기록한다 |
| `TriggerSource` | `WEB`, `CLI`, `CI_TOKEN`, `CI_OIDC`, `WEBHOOK`, `NLP` | 배포 요청 경로 (#95). 웹 JWT와 CLI 전체 권한 토큰은 인증 주체의 CLI 토큰 여부로 구분 |
| `FailureKind` | `IMAGE_PULL`, `CRASH_LOOP`, `CONFIG_ERROR`, `READINESS_TIMEOUT`, `UNSCHEDULABLE`, `ROLLOUT_TIMEOUT`, `PROGRESS_DEADLINE`, `DEPLOYMENT_MISSING`, `SUPERSEDED`, `BUILD_FAILED`, `APPLY_FAILED`, `OTHER` | 판정 시점에 정하는 실패 종류 (#95). 예외는 단계(이미지 준비·apply)로 분류 |
| `CommandStatus` | `PENDING → EXECUTING → SUCCEEDED / FAILED`, `PENDING → EXPIRED / CANCELLED`, `EXECUTING → UNKNOWN` | `UNKNOWN`은 결과를 판단할 수 없는 상태 (재시작 시 실행 중이던 명령). 자동으로 재실행하지 않는다 |
| `RiskLevel` | `LOW`, `MEDIUM`, `HIGH` | §3.1 매핑 |
| `CliTokenScope` | `READ_ONLY`, `PROPOSE_ONLY`, `DEPLOY`, `FULL` | §4.1 |
| `BuildStrategy` | `KANIKO`, `GITHUB_ACTIONS_GHCR`, `PREBUILT_IMAGE` | §3.2 |
| `CloudVendor` | `NCP`, `AWS`, `ON_PREMISE` | 구현된 빌드 provider는 NCP뿐이다 (§9) |

## 6. 공통 계약

### 6.1 API

- 경로 prefix는 `/api/v1`, JSON 필드는 `snake_case`다.
- 시각은 UTC로 저장하고, 응답에는 `Z`가 붙은 ISO 8601(`2026-10-09T09:09:30.682Z`)로 보낸다 (`JacksonTimeConfig`, 애플리케이션 시간대는 UTC 고정). 콘솔·CLI 사람용 출력은 한국 시간(KST)으로 표시하고, 콘솔은 `frontend/lib/time.ts`만 쓴다 (#81).
- 에러는 `ErrorCode` enum(`backend/.../global/exception/ErrorCode.java`)이 기준이다. 코드 체계는 `<영역>_<번호>`다.
- 예외 메시지를 사용자에게 보이는 곳(`fail_reason`, 명령 기록, 자연어 응답, API 메시지)에 넣을 때는 `KubernetesErrorMessages.userMessage(e)`를 쓴다. Fabric8 예외 메시지에는 Kubernetes API 서버 주소가 들어 있어 Kubernetes status 메시지만 남긴다. 전체 예외는 서버 로그에만 남긴다 (#92).

| 영역 | 코드 예 |
|---|---|
| 공통 | `COMMON_001` 404, `COMMON_002` 409, `COMMON_003` 400, `COMMON_004` 500 |
| 저장소·배포 | `REPO_001/002`, `DEPLOY_001`~`DEPLOY_003` (`DEPLOY_003` 409: 같은 저장소 배포 진행 중) |
| 인프라 | `INFRA_001`~`INFRA_006` (업로드, 빌드, 배포, NCP 실패) |
| AI·명령 | `AI_001`~`AI_005` (`AI_005` 409: 명령을 승인할 수 없음) |
| CLI | `CLI_001`~`CLI_006` (`CLI_005` 403: scope 밖 요청) |
| GitHub | `GH_001`~`GH_003` |

새 에러 코드는 `ErrorCode`에 추가하고, 클라이언트(웹·CLI)의 처리도 확인한다.

### 6.2 CLI

- 모든 핵심 명령은 `--json` 출력과 명확한 exit code를 제공한다. 계약은 [CLI_REFERENCE.md](CLI_REFERENCE.md)에 있다.
- CLI는 thin client다. 위험도와 승인 정책은 백엔드를 따른다.

### 6.3 스키마

- `backend/src/main/resources/db/migration/V<n>__<what>.sql`, PR당 최대 1개, 머지된 마이그레이션은 수정하지 않는다.
- 새 컬럼은 nullable이거나 기본값이 있어야 한다. 앱을 롤백했을 때 이전 JAR가 읽고 쓸 수 있어야 한다.
- 머지 전에 운영 DB 사본에서 기동을 검증한다 ([CICD.md "Writing schema migrations"](CICD.md), [ADR-0004](adr/0004-flyway-forward-compatible-migrations.md)).

## 7. 설정

| 키 | 기본값 | 의미 |
|---|---|---|
| `kubernetes.namespace` | `${K8S_NAMESPACE:default}` | 앱 리소스 namespace. 사용자 접근 범위 |
| `kubernetes.image-pull-secret` | `${K8S_IMAGE_PULL_SECRET:ncp-cr}` | 기본 pull secret (저장소별 허용 필요) |
| `kubernetes.runtime-resources.repositories` | - | 저장소별 허용 ConfigMap/Secret/pull secret 이름 (운영자 소유) |
| `kaniko.image` | `gcr.io/kaniko-project/executor:latest` | Kaniko 이미지 |
| `deployment.domain.suffix` | `${DEPLOYMENT_DOMAIN_SUFFIX:klepaas.io}` | 기본 배포 도메인 |
| `deployment.pipeline.poll-initial-interval` / `poll-max-interval` / `build-timeout` | 10s / 60s / 30m | 빌드 상태 polling |
| `gemini.api.model` | `${GEMINI_MODEL:gemini-2.5-flash}` | 자연어 해석 모델 |
| `jwt.access-token-expiry` / `refresh-token-expiry` | 1h / 7d | Web 토큰 |
| `github.actions.oidc.audience` | `${GITHUB_ACTIONS_OIDC_AUDIENCE:k-le-paas}` | GitHub Actions OIDC 토큰의 `aud` |
| `github.actions.oidc.allowed-refs` | `${GITHUB_ACTIONS_OIDC_ALLOWED_REFS:refs/heads/main}` | OIDC 배포를 허용할 ref (쉼표 구분) |
| `app.base-url`, `cors.allowed-origins` | localhost | Web console 주소 |
| `spring.datasource.url` / `username` / `password` | `${DB_URL:jdbc:postgresql://localhost:5432/klepaas}` / `${DB_USERNAME:klepaas}` / `${DB_PASSWORD}` | DB 접속. 비밀번호는 기본값 없음 |

비밀값(`DB_PASSWORD`, `JWT_SECRET`, GitHub OAuth/App, NCP 키, `GEMINI_API_KEY`, `SLACK_WEBHOOK_URL`, `GITHUB_WEBHOOK_SECRET`)은 환경변수로만 주입한다. 목록은 [README 환경변수](../README.md#환경변수)에 있다.

## 8. 테스트와 검증 층위

| 층위 | 방법 |
|---|---|
| 단위·통합 | `cd backend && ./gradlew test` (컨트롤러는 실제 HTTP 테스트 포함). DB는 Testcontainers PostgreSQL이라 Docker가 필요하다 |
| 패키징 | `./gradlew bootJar` 후 JAR를 PostgreSQL(`compose.yaml`)로 기동, `ddl-auto=validate`, readiness `UP` |
| 운영 DB 사본 | 스키마 변경 시 머지 전에 필수 |
| 실제 클러스터 | k3s 임시 컨테이너 등 격리 환경. 운영 앱에 실패를 주입하지 않는다 |
| 프론트 | `cd frontend && npm run build`, `npx tsc --noEmit` |
| 릴리스 스크립트 | `python3 -m unittest discover -s scripts/deploy -p 'test_*.py'`, `scripts/ci/test-package-release.sh` |

각 검증이 어느 층위인지 구분해서 기록한다. 로컬 통과를 운영 검증으로 표현하지 않는다.

## 9. 알려진 격차

스펙을 쓸 때 반드시 확인한다. 해소하면 이 목록에서 지운다.

| 격차 | 영향 | 이슈 |
|---|---|---|
| rollout 타임아웃(`kubernetes.rollout.timeout-ms`, 120초)이 앱의 startup probe 허용 시간(`period_seconds × startup_failure_threshold`)과 무관한 고정값이다 | 기동이 120초보다 오래 걸리는 앱은 정상이어도 타임아웃 FAILED로 기록된다 | 이슈 후보 (#53에서 확인) |
| 승인한 뒤 실행 시점에 설정을 다시 조회한다 | 승인 대기 중에 바뀐 설정으로 실행될 수 있다 | #56 |
| 모니터링, alerts, PR 목록, Slack 설정, MCP 화면과 대시보드 상단 통계 카드(`getDashboardData` 고정값)가 stub이다 | 동작하지 않는 기능이 정상처럼 보인다 (예: 저장소가 있어도 "No repositories connected") | #59 |
| 콘솔 Deployments 화면의 Rollback 버튼이 stub이다. `getRollbackList()`는 항상 빈 목록, `rollbackToCommit()`은 아무 동작 없이 `{}`를 반환한다 (Config·Scale·Restart·Logs는 실제 API) | 동작하지 않는 기능이 정상처럼 보인다. 롤백은 현재 자연어 명령(ROLLBACK)으로만 가능하다 | 이슈 후보 (이전 성공 배포 목록·롤백 API) |
| Fabric8 7.2.0은 Jackson 2.18 기준인데 Spring Boot 의존성 관리로 Jackson 2.20.2가 실행된다. 서버에서 읽은 객체(`managedFields` 포함)를 `replace()`하면 복제 단계에서 직렬화가 실패한다 | Deployment 교체는 `managedFields`를 빼고 보내 우회했다(#86). 다른 경로에서 서버 객체를 그대로 다시 보내면 같은 오류가 날 수 있다 | 이슈 후보 (Fabric8·Jackson 버전 정합) |
| #95 이전 배포에는 요청 경로·설정·digest·실패 종류가 없다 | 과거 배포 상세는 "기록 없음", 비교는 이미지만 | 해소하지 않음 (backfill하지 않기로 결정) |
| 콘솔 사이드바가 모바일 폭에서 접히지 않는다 | 375px 폭에서 본문이 좁아진다 (#95 화면 확인 중 발견, 앱 공통 레이아웃) | 이슈 후보 |
| NodePort만 쓰는 앱도 `domain_url`이 필수다 (비우면 400) | 쓰지 않는 기본 도메인 Ingress가 만들어진다 (#55 샘플 앱) | 이슈 후보 |
| 콘솔 배포 설정 입력이 어렵다 (#55에서 사용자가 입력하지 못해 API로 대신 입력) | "설정만으로 새 앱 등록"의 실제 장벽 | 이슈 후보 |
| CLI 요청 함수(`frontend/cli/api.mjs` `toSnakeCase`)가 map 값의 키(예: env 이름 `SAMPLE_GREETING`)까지 snake_case로 바꾼다 | env를 보내는 CLI 명령을 만들면 키가 깨진다. 현재 그런 명령은 없다 | 이슈 후보 (#55에서 확인) |
| `DeploymentRepository.findBySourceRepositoryUserId`를 호출하는 코드가 없다 | 쓰이지 않는 쿼리가 남아 있다 (#79에서 확인) | 이슈 후보 |
| KANIKO 빌드 provider는 NCP(`ncpInfraService`)만 있다. `AWS`(`awsInfraService`), `ON_PREMISE`(`k8sInfraService`) bean은 없다 | AWS나 ON_PREMISE 저장소가 KANIKO 경로를 타면 provider 조회에서 실패한다. 외부 이미지 경로는 provider를 쓰지 않아 영향이 없다 | 후순위 |
| 비용은 spec 기반 추정이다 | 실제 청구액과 다를 수 있다 | 범위 밖 |
| 배포 job에 DB 백업·스키마 롤백이 없다 | 앱 롤백이 DB 롤백이 아니다 | 운영 절차 ([CICD.md](CICD.md)) |
