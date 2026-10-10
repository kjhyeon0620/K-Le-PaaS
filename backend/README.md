# K-Le-PaaS Backend

K-Le-PaaS 백엔드는 자연어/CLI/Web 요청을 감사 가능하고 위험도 분류된 Kubernetes 운영 작업으로 변환하는 Spring Boot 기반 control plane입니다.

백엔드는 인증, 저장소 등록, 배포 파이프라인, Kubernetes apply, 자연어 명령 처리, 위험 명령 confirmation, 비용 추정, Slack/WebSocket 알림, GitHub webhook 처리를 담당합니다.

## 역할

- GitHub OAuth 로그인과 JWT 발급
- GitHub App installation token 기반 저장소 접근
- CLI token 발급, 조회, 폐기
- Web 승인형 CLI login session 처리
- 저장소 등록과 배포 설정 관리
- GitHub ZIP source download와 repackaging
- NCP Object Storage upload
- Kaniko Kubernetes Job 기반 이미지 빌드
- commit SHA 기반 image URI 생성
- Fabric8 Kubernetes Client 기반 Deployment, Service, Ingress apply
- Gemini 2.5 Flash 기반 자연어 intent parsing
- LOW / MEDIUM / HIGH risk classification
- MEDIUM / HIGH 명령 confirmation flow
- command log와 command history 저장
- spec 기반 cost plan, diff, explain, check
- Slack Incoming Webhook 알림 MVP
- 인증된 WebSocket 배포 이벤트 MVP
- GitHub push webhook 기반 자동 배포 MVP

## 기술 스택

| 항목 | 내용 |
|---|---|
| Language | Java 17 |
| Framework | Spring Boot 4.0.2 |
| Persistence | Spring Data JPA, Hibernate, PostgreSQL 16, Flyway |
| HTTP client | Spring RestClient |
| Kubernetes | Fabric8 Kubernetes Client |
| Cloud SDK | AWS SDK v2, NCP Object Storage S3 호환 API |
| Auth | Spring Security, JWT, GitHub OAuth |
| API docs | Springdoc OpenAPI |
| Build | Gradle |
| JSON contract | Jackson `SNAKE_CASE` |

## 패키지 구조

```text
klepaas.backend/
├── ai/          # Gemini client, intent parser, dispatcher, NLP command API
├── auth/        # GitHub OAuth, JWT, CLI token, CLI web login
├── cost/        # 비용 추정, diff, explain, budget check
├── deployment/  # repository, deployment, scaling, pipeline orchestration
├── global/      # 공통 응답, 예외, WebSocket, Slack notification, system API
├── infra/       # CloudInfraProvider, NCP infra, Kubernetes manifest apply
├── user/        # 사용자 조회
└── webhook/     # GitHub webhook 수신과 push event 처리
```

## 주요 API

### 인증

```text
GET  /api/v1/auth/oauth2/url/{provider}
POST /api/v1/auth/oauth2/login
GET  /api/v1/auth/me
POST /api/v1/auth/refresh
POST /api/v1/auth/logout
```

### CLI 인증

```text
POST   /api/v1/cli-tokens
GET    /api/v1/cli-tokens
DELETE /api/v1/cli-tokens/{id}

POST /api/v1/cli-auth/sessions
GET  /api/v1/cli-auth/sessions/{id}
POST /api/v1/cli-auth/sessions/{id}/approve
POST /api/v1/cli-auth/sessions/{id}/reject
POST /api/v1/cli-auth/sessions/{id}/exchange
```

### 저장소와 배포

```text
POST   /api/v1/repositories
GET    /api/v1/repositories
GET    /api/v1/repositories/{id}
DELETE /api/v1/repositories/{id}
GET    /api/v1/repositories/{id}/config
PUT    /api/v1/repositories/{id}/config
GET    /api/v1/repositories/{repositoryId}/scaling-history
GET    /api/v1/repositories/{repositoryId}/replicas

POST /api/v1/deployments
GET  /api/v1/deployments?repositoryId={repositoryId}
GET  /api/v1/deployments/{id}
GET  /api/v1/deployments/{id}/status
GET  /api/v1/deployments/{id}/logs
GET  /api/v1/deployments/{id}/detail
POST /api/v1/deployments/{id}/scale
POST /api/v1/deployments/{id}/restart
```

`POST /api/v1/deployments`는 같은 저장소에 진행 중인 배포가 있으면 409(`DEPLOY_003`)를 반환합니다. 진행 중 배포와 같은 요청(branch·commit·image_uri)을 다시 보내면 새로 만들지 않고 그 배포를 200으로 반환합니다.

`GET /api/v1/deployments/{id}/logs?lines=100`은 배포 상태·실패 원인과, 그 배포 요청이 적용한 Pod의 조회 시점 상태·최근 로그·Kubernetes 이벤트를 반환합니다. 다른 요청이 적용한 Deployment면 Pod를 읽지 않고 `observation: NOT_CURRENT`, Kubernetes 조회가 실패하면 `UNAVAILABLE`입니다. 로그는 저장하지 않으며 스트리밍과 빌드(Kaniko) 로그는 지원하지 않습니다.

`GET /api/v1/deployments/{id}/detail`은 배포 요청 상세입니다: 요청 경로·요청자·자연어 승인 명령, 적용한 배포 설정(env는 이름만), 관측한 이미지 digest, 실패 종류와 설명, 같은 저장소의 직전 성공 배포 대비 변경. #95 이전 배포는 기록이 없어 해당 값이 null입니다.

`GET /api/v1/repositories/{repositoryId}/replicas`는 저장소 앱의 현재 Deployment replica 관측값(`desired`, `ready`, `available`, `updated`)을 반환합니다. Deployment가 없거나 다른 저장소 소유면 `observation: NOT_FOUND`, Kubernetes 조회가 실패하면 `UNAVAILABLE`이고 숫자는 null입니다. 배포 설정의 `min_replicas`·`max_replicas`와는 다른 값입니다.

### 자연어 명령

```text
POST /api/v1/nlp/command
POST /api/v1/nlp/confirm
GET  /api/v1/nlp/history
```

LOW risk 명령은 즉시 실행됩니다. MEDIUM / HIGH risk 명령은 command log에 저장된 뒤 `/api/v1/nlp/confirm`으로 확인되어야 실행됩니다.

### 비용

```text
POST /api/v1/cost/plan
POST /api/v1/cost/diff
POST /api/v1/cost/explain
POST /api/v1/cost/check
```

현재 비용 모델은 실제 billing API가 아니라 배포 spec 기반 추정 모델입니다.

### WebSocket, Webhook, System

```text
WS   /api/v1/ws/deployments?token={jwt}
POST /api/v1/webhooks/github
GET  /api/v1/system/health
GET  /api/v1/system/version
```

WebSocket은 query string의 JWT를 검증하고 사용자별 배포 이벤트를 전송합니다.

## 배포 파이프라인

```text
Deployment 생성
  -> transaction commit 이후 비동기 pipeline 시작
  -> GitHub ZIP source download
  -> top-level directory 제거 후 repackaging
  -> NCP Object Storage upload
  -> Kaniko Kubernetes Job 생성
  -> initContainer가 source.zip을 emptyDir로 복사/해제
  -> Kaniko가 dir:///workspace context로 image build
  -> NCR에 {owner}-{repo}:{shortSha} push
  -> Fabric8로 Deployment 생성·전체 교체(resourceVersion 고정), Service/Ingress server-side apply
  -> 적용한 generation의 rollout만 판정 (성공 / 이미지·설정·반복 종료 즉시 실패 / 타임아웃 실패 / 다른 변경으로 대체 시 CANCELED)
  -> Slack/WebSocket 알림과 deployment status update
```

## 현재 구현 상태

| 영역 | 상태 | 메모 |
|---|---|---|
| GitHub OAuth / JWT | 구현 MVP | OAuth login, refresh, logout API 존재 |
| GitHub App source access | 구현 MVP | installation token과 ZIP download 경로 존재 |
| NCP Object Storage / Kaniko / NCR | 구현 MVP | NCP 중심 구현, AWS/ON_PREMISE provider는 예정 |
| Kubernetes apply | 구현 MVP | Deployment, Service, Ingress 반영, 앱별 health probe·requests/limits (`PUT /repositories/{id}/config`의 `health_probe`, `resources`), 요청 단위 rollout 판정 |
| 자연어 명령 / risk confirmation | 구현 MVP | `ActionDispatcher`와 `NlpCommandService`에서 처리 |
| CLI token / web login | 구현 MVP | CLI token과 browser approval flow 존재 |
| Cost guardrails | 구현 MVP | spec 기반 추정과 budget check 존재 |
| Slack / WebSocket | 구현 MVP | 운영 환경 설정과 frontend 정합성 확인 필요 |
| GitHub webhook | 구현 MVP | global secret 기반 push auto deploy, `X-GitHub-Delivery` 중복 제거, 진행 중 배포와 충돌 시 409 |
| Scaling history | 구현 MVP | scale 이력 저장/조회 존재 |
| Deployment logs | 구현 MVP | 배포 요청이 적용한 Pod의 상태·최근 로그·이벤트 조회, 실패 원인 근거 (저장·스트리밍 없음) |
| Monitoring metrics | 예정 | backend metrics API 없음 |
| MCP / IaC | 예정 | backend 구현 없음 |

## 환경변수

`backend/.env` 파일은 `application.yaml`에서 optional import됩니다.

```env
APP_BASE_URL=http://localhost:3000/console
CORS_ALLOWED_ORIGINS=http://localhost:3000

JWT_SECRET=

GITHUB_CLIENT_ID=
GITHUB_CLIENT_SECRET=
GITHUB_REDIRECT_URI=http://localhost:3000/console/auth/callback
GITHUB_APP_ID=
GITHUB_APP_PRIVATE_KEY=
GITHUB_APP_SLUG=
GITHUB_WEBHOOK_SECRET=
GITHUB_ACTIONS_OIDC_AUDIENCE=k-le-paas
GITHUB_ACTIONS_OIDC_ALLOWED_REFS=refs/heads/main

NCP_ACCESS_KEY=
NCP_SECRET_KEY=
NCP_STORAGE_BUCKET=
NCR_ENDPOINT=

K8S_NAMESPACE=default
K8S_IMAGE_PULL_SECRET=ncp-cr
KANIKO_IMAGE=gcr.io/kaniko-project/executor:latest

SLACK_WEBHOOK_URL=

GEMINI_API_KEY=
GEMINI_MODEL=gemini-2.5-flash
```

비밀값은 커밋하지 않습니다.

## 로컬 실행

로컬 PostgreSQL은 저장소 루트의 `compose.yaml`로 띄웁니다. 같은 `DB_PASSWORD`를 백엔드에도 설정합니다 (`backend/.env`).

```bash
DB_PASSWORD=<로컬 비밀번호> docker compose up -d
cd backend
./gradlew bootRun
```

SQL 로그를 보려면 `dev` profile을 사용합니다.

```bash
cd backend
./gradlew bootRun --args='--spring.profiles.active=dev'
```

기본 API 주소:

```text
http://localhost:8080
```

Swagger UI는 Springdoc 기본 경로를 사용합니다.

```text
http://localhost:8080/swagger-ui/index.html
```

## 검증

```bash
cd backend
./gradlew test
./gradlew build
```

Kubernetes 배포까지 확인할 때:

```bash
kubectl get jobs,pods,deploy,svc,ingress -n <namespace>
kubectl describe pod <pod-name> -n <namespace>
kubectl logs <pod-name> -n <namespace>
kubectl rollout status deployment/<deployment-name> -n <namespace>
```

## 관련 문서

- [프로젝트 README](../README.md)
- [CLI 전략](../docs/CLI_STRATEGY.md)
- [CLI 레퍼런스](../docs/CLI_REFERENCE.md)
