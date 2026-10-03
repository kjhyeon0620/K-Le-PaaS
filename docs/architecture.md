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
                                              H2 파일 DB (운영) · Flyway
                                                     │
                                              Oracle k3s (사용자 앱) / NCP NKS
```

| 영역 | 기술 |
|---|---|
| Backend | Java 17, Spring Boot 4.0.2, Spring Security, Spring Data JPA, Flyway, Fabric8, AWS SDK v2(S3 호환), Springdoc, Jackson `SNAKE_CASE` |
| Frontend | Next.js 15, React 19, TypeScript 5, Tailwind 4, shadcn/ui ([DESIGN.md](../frontend/DESIGN.md)) |
| CLI | `frontend/cli/*.mjs` (Node) |
| AI | Gemini 2.5 Flash (`gemini.api.model`), 시스템 프롬프트 `backend/src/main/resources/prompts/system-prompt.txt` |
| DB | H2 파일 DB (운영), `ddl-auto=validate`, Flyway `db/migration` |
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
  → DEPLOYING: Deployment/Service/Ingress 적용 → Deployment Available 대기
       (기존 Deployment는 관측한 resourceVersion으로 전체 교체, Service·Ingress는 강제 server-side apply.
        설정한 readiness probe를 통과하지 못한 새 Pod는 Available이 되지 않아 타임아웃 시 FAILED)
  → SUCCESS / FAILED, Slack·WebSocket 알림
```

- 빌드 경로 기본값: `NCP → KANIKO`, `ON_PREMISE → GITHUB_ACTIONS_GHCR` ([ADR-0001](adr/0001-external-image-build.md)).
- 외부 이미지 경로의 저장소는 raw push webhook을 무시한다. 이미지 push가 끝난 뒤 CI가 배포 API를 호출한다.
- "진행 중"은 `PENDING`~`DEPLOYING` 상태이면서 마지막 갱신 후 `빌드 타임아웃 + rollout 타임아웃 + 10분` 이내인 배포다. 재시작 등으로 그보다 오래 멈춘 배포는 새 배포를 막지 않고, 상태도 바꾸지 않는다(대조는 #57).
- 생성하는 리소스에는 `klepaas.io/repository-id` 라벨을 붙이고, 조회와 변경도 이 라벨과 설정된 namespace 안으로 제한한다 ([ADR-0006](adr/0006-resource-access-scope.md)).

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

### 4.2 리소스 접근 ([ADR-0006](adr/0006-resource-access-scope.md), [STAGE2_RESOURCE_ACCESS.md](STAGE2_RESOURCE_ACCESS.md))

- 사용자는 본인 저장소와, 그 저장소 라벨이 붙은 설정 namespace 안의 Kubernetes 리소스만 조회하고 변경할 수 있다.
- ConfigMap, Secret, image pull secret 참조는 운영자가 서버 설정(`kubernetes.runtime-resources.repositories`)에서 저장소별로 허용한 이름만 쓸 수 있다. Secret 값은 다루지 않는다.

## 5. 상태 모델

| 모델 | 값 | 비고 |
|---|---|---|
| `DeploymentStatus` | `PENDING → UPLOADING_SOURCE → BUILDING → DEPLOYING → SUCCESS`, `FAILED`, `CANCELED` | 외부 이미지 경로는 업로드·빌드 단계를 건너뛴다 |
| `CommandStatus` | `PENDING → EXECUTING → SUCCEEDED / FAILED`, `PENDING → EXPIRED / CANCELLED`, `UNKNOWN` | `UNKNOWN`은 결과를 판단할 수 없는 상태. 자동으로 재실행하지 않는다 |
| `RiskLevel` | `LOW`, `MEDIUM`, `HIGH` | §3.1 매핑 |
| `CliTokenScope` | `READ_ONLY`, `PROPOSE_ONLY`, `DEPLOY`, `FULL` | §4.1 |
| `BuildStrategy` | `KANIKO`, `GITHUB_ACTIONS_GHCR`, `PREBUILT_IMAGE` | §3.2 |
| `CloudVendor` | `NCP`, `AWS`, `ON_PREMISE` | 구현된 빌드 provider는 NCP뿐이다 (§9) |

## 6. 공통 계약

### 6.1 API

- 경로 prefix는 `/api/v1`, JSON 필드는 `snake_case`다.
- 에러는 `ErrorCode` enum(`backend/.../global/exception/ErrorCode.java`)이 기준이다. 코드 체계는 `<영역>_<번호>`다.

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
| `app.base-url`, `cors.allowed-origins` | localhost | Web console 주소 |

비밀값(`JWT_SECRET`, GitHub OAuth/App, NCP 키, `GEMINI_API_KEY`, `SLACK_WEBHOOK_URL`, `GITHUB_WEBHOOK_SECRET`)은 환경변수로만 주입한다. 목록은 [README 환경변수](../README.md#환경변수)에 있다.

## 8. 테스트와 검증 층위

| 층위 | 방법 |
|---|---|
| 단위·통합 | `cd backend && ./gradlew test` (컨트롤러는 실제 HTTP 테스트 포함) |
| 패키징 | `./gradlew bootJar` 후 JAR를 H2 파일 DB로 기동, `ddl-auto=validate`, readiness `UP` |
| 운영 DB 사본 | 스키마 변경 시 머지 전에 필수 |
| 실제 클러스터 | k3s 임시 컨테이너 등 격리 환경. 운영 앱에 실패를 주입하지 않는다 |
| 프론트 | `cd frontend && npm run build`, `npx tsc --noEmit` |
| 릴리스 스크립트 | `python3 -m unittest discover -s scripts/deploy -p 'test_*.py'`, `scripts/ci/test-package-release.sh` |

각 검증이 어느 층위인지 구분해서 기록한다. 로컬 통과를 운영 검증으로 표현하지 않는다.

## 9. 알려진 격차

스펙을 쓸 때 반드시 확인한다. 해소하면 이 목록에서 지운다.

| 격차 | 영향 | 이슈 |
|---|---|---|
| rollout 판정이 요청 단위가 아니라 앱 이름 기준이다 | 다른 배포의 완료를 성공으로 기록할 수 있다 | #53 |
| `GET /deployments/{id}/logs`가 placeholder 응답이다 | 실패 원인을 API에서 볼 수 없다 | #54 |
| 승인한 뒤 실행 시점에 설정을 다시 조회한다 | 승인 대기 중에 바뀐 설정으로 실행될 수 있다 | #56 |
| 백엔드 재시작 후 진행 중이던 배포·명령 상태를 대조하지 않는다 | 영원히 진행 중으로 남는다 | #57 |
| 콘솔이 설정 조회에 실패하면 기본값으로 저장할 수 있다 | 기존 설정을 덮어쓴다 | #66 |
| 모니터링, alerts, PR 목록, Slack 설정, MCP 화면이 stub이다 | 동작하지 않는 기능이 정상처럼 보인다 | #59 |
| KANIKO 빌드 provider는 NCP(`ncpInfraService`)만 있다. `AWS`(`awsInfraService`), `ON_PREMISE`(`k8sInfraService`) bean은 없다 | AWS나 ON_PREMISE 저장소가 KANIKO 경로를 타면 provider 조회에서 실패한다. 외부 이미지 경로는 provider를 쓰지 않아 영향이 없다 | 후순위 |
| CI 배포 토큰이 저장소별 장기 Secret이다 | 수동 재발급, 유출 위험 | #65 |
| 비용은 spec 기반 추정이다 | 실제 청구액과 다를 수 있다 | 범위 밖 |
| 배포 job에 DB 백업·스키마 롤백이 없다 | 앱 롤백이 DB 롤백이 아니다 | 운영 절차 ([CICD.md](CICD.md)) |
