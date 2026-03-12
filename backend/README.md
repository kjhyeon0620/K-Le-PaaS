# KLEPaaS Backend

Spring Boot 기반 백엔드. GitHub OAuth 인증, Gemini AI 자연어 처리, NCP 인프라 연동, Kubernetes 배포 파이프라인을 담당한다.

---

## 기술 스택

| 항목 | 내용 |
|------|------|
| **언어/프레임워크** | Java 17, Spring Boot 4.0.2 |
| **ORM** | Spring Data JPA (Hibernate 6) |
| **DB** | H2 (개발) / PostgreSQL (운영) |
| **HTTP Client** | Spring RestClient (GitHub API, Gemini API) |
| **K8s Client** | Fabric8 Kubernetes Client |
| **Cloud SDK** | AWS SDK V2 (NCP Object Storage S3 호환) |
| **인증** | Spring Security, JWT (HS256), GitHub OAuth2 |
| **빌드** | Gradle |
| **JSON** | Jackson (`SNAKE_CASE` — 프론트엔드 호환 필수) |

---

## 패키지 구조

```
klepaas.backend/
├── ai/                         # NLP/AI 처리
│   ├── client/                 # GeminiClient (RestClient 패턴)
│   ├── config/                 # GeminiConfig
│   ├── controller/             # NlpController
│   ├── dto/                    # NlpCommandRequest/Response, ParsedIntent 등
│   ├── entity/                 # CommandLog, ConversationSession, Intent, RiskLevel
│   ├── exception/              # AiProcessingException
│   ├── repository/             # CommandLogRepository, ConversationSessionRepository
│   └── service/
│       ├── NlpCommandService   # 오케스트레이터 (프롬프트 → Gemini → 파싱 → 실행)
│       ├── IntentParser        # Gemini JSON 응답 → ParsedIntent 변환
│       ├── ActionDispatcher    # Intent → 서비스 메서드 매핑 + 리스크 분류
│       └── KubectlService      # Fabric8로 K8s 직접 조회 (pods/services/logs 등)
│
├── auth/                       # 인증/인가
│   ├── config/                 # SecurityConfig, CustomUserDetails
│   ├── controller/             # AuthController
│   ├── dto/                    # TokenResponse, GitHubUserResponse 등
│   ├── jwt/                    # JwtTokenProvider, JwtAuthenticationFilter
│   ├── oauth/                  # GitHubOAuthClient, GitHubAppJwtProvider, GitHubAppClient
│   └── service/
│       ├── AuthService         # GitHub OAuth 로그인 플로우, JWT 발급
│       └── GitHubInstallationTokenService  # GitHub App 설치 토큰 취득/캐싱
│
├── deployment/                 # 배포 도메인
│   ├── controller/             # DeploymentController, RepositoryController
│   ├── dto/                    # CreateDeploymentRequest, DeploymentResponse 등
│   ├── entity/                 # Deployment, SourceRepository, DeploymentConfig, DeploymentStatus
│   ├── repository/             # DeploymentRepository, SourceRepositoryRepository 등
│   └── service/
│       ├── DeploymentService   # 배포 CRUD, 스케일, 재시작
│       ├── DeploymentPipelineService  # 비동기 배포 파이프라인 (5단계)
│       └── RepositoryService   # 저장소 등록/조회
│
├── infra/                      # 클라우드 인프라 추상화
│   ├── CloudInfraProvider      # 인터페이스 (uploadSource, triggerBuild, getBuildStatus)
│   ├── CloudInfraProviderFactory  # CloudVendor → Bean 선택 (Strategy Pattern)
│   ├── config/                 # S3Config, KubernetesConfig, RestClientConfig
│   ├── dto/                    # BuildResult, BuildStatusResult
│   ├── kubernetes/
│   │   └── KubernetesManifestGenerator  # Deployment + Service + Ingress 생성/업데이트
│   └── service/
│       └── NcpInfraService     # NCP 구현체 (GitHub ZIP → Object Storage → Kaniko Job)
│
├── user/                       # 사용자 관리
│   ├── controller/             # UserController
│   ├── entity/                 # User, Role
│   ├── repository/             # UserRepository
│   └── service/                # UserService
│
└── global/                     # 공통 인프라
    ├── config/                 # AsyncConfig, JpaConfig, SwaggerConfig
    ├── controller/             # SystemController (헬스체크)
    ├── dto/                    # ApiResponse<T>, ErrorResponse
    ├── entity/                 # BaseTimeEntity (JPA Auditing)
    ├── exception/              # GlobalExceptionHandler, ErrorCode, 각종 예외 클래스
    └── service/
        └── NotificationService  # 알림 인터페이스 (Slack 구현체 미작성)
```

---

## API 엔드포인트

### 인증
```
GET  /api/v1/auth/oauth2/url/{provider}   OAuth 인증 URL 생성 (provider: github)
POST /api/v1/auth/oauth2/login            GitHub OAuth 코드 교환 → JWT 발급
GET  /api/v1/auth/me                      현재 사용자 정보 (JWT 필요)
POST /api/v1/auth/refresh                 액세스 토큰 갱신
```

### 저장소
```
POST   /api/v1/repositories              저장소 등록 { owner, repo_name, git_url, cloud_vendor }
GET    /api/v1/repositories              내 저장소 목록
GET    /api/v1/repositories/{id}         저장소 상세
DELETE /api/v1/repositories/{id}         저장소 삭제
GET    /api/v1/repositories/{id}/config  배포 설정 조회
PUT    /api/v1/repositories/{id}/config  배포 설정 변경
```

### 배포
```
POST /api/v1/deployments                 배포 요청 { repository_id, branch_name, commit_hash }
GET  /api/v1/deployments                 배포 목록 (페이징, ?repositoryId=N)
GET  /api/v1/deployments/{id}            배포 상세
GET  /api/v1/deployments/{id}/status     배포 상태
GET  /api/v1/deployments/{id}/logs       배포 로그 (TODO: Kaniko Pod 로그 스트리밍)
POST /api/v1/deployments/{id}/scale      스케일링 { replicas }
POST /api/v1/deployments/{id}/restart    재시작
```

### NLP 자연어 명령
```
POST /api/v1/nlp/command   명령 처리 { command, session_id? }
POST /api/v1/nlp/confirm   위험 명령 확인/취소 { command_log_id, confirmed }
GET  /api/v1/nlp/history   명령 이력 (페이징)
```

### 시스템
```
GET /api/v1/system/health   헬스체크
GET /api/v1/system/version  버전 정보
```

---

## 주요 설계 결정

### 배포 파이프라인 — Kaniko K8s Job

초기 설계(NCP SourceBuild)에서 변경. NCP SourceBuild는 Object Storage를 source로 지원하지 않아 Kaniko K8s Job 방식으로 전환.

```
initContainer(amazon/aws-cli)
  → aws s3 cp s3://{bucket}/builds/{id}/source.zip --endpoint-url {NCP_ENDPOINT}
  → python3 unzip → /workspace (emptyDir)

kaniko
  → --context=dir:///workspace
  → --destination={NCR_ENDPOINT}/{owner}-{repo}:{shortSha}
```

### AI 모듈 — Java 내장 Gemini 직접 호출

Python 워커 없이 Spring Boot 단일 프로세스에서 Gemini REST API를 직접 호출. `RestClient` + `@Value` 기반 설정.

### K8s 매니페스트 — fabric8 직접 apply

Helm CLI 의존성 없이 fabric8 `serverSideApply()`로 Deployment + Service + Ingress를 직접 생성/업데이트.

### 비동기 파이프라인 타이밍 버그 해결

`@Async` + `@Transactional` 동시 사용 시 트랜잭션 커밋 전에 비동기 스레드가 DB를 조회하는 문제를 `TransactionSynchronizationManager.registerSynchronization(afterCommit → executePipeline)`으로 해결.

---

## 환경변수

`backend/.env` 파일 (`.env[.properties]` 형식으로 자동 로드):

```env
# GitHub OAuth App
GITHUB_CLIENT_ID=
GITHUB_CLIENT_SECRET=
GITHUB_REDIRECT_URI=http://localhost:3000/console/auth/callback

# GitHub App (저장소 접근용)
GITHUB_APP_ID=
GITHUB_APP_PRIVATE_KEY=      # PEM 전체 내용 (줄바꿈 \n 처리)
GITHUB_APP_SLUG=

# NCP Object Storage
NCP_ACCESS_KEY=
NCP_SECRET_KEY=
NCP_STORAGE_BUCKET=

# NCP Container Registry
NCR_ENDPOINT=                 # 예: klepaas.kr.ncr.ntruss.com

# Kubernetes
K8S_NAMESPACE=default
K8S_IMAGE_PULL_SECRET=ncp-cr  # docker-registry Secret 이름

# Kaniko
KANIKO_IMAGE=gcr.io/kaniko-project/executor:latest

# Gemini
GEMINI_API_KEY=
GEMINI_MODEL=gemini-2.5-flash  # 선택사항 (기본값)

# JWT
JWT_SECRET=                   # openssl rand -hex 32
```

---

## 로컬 개발

```bash
# 환경변수 설정 후
./gradlew bootRun

# 빌드만
./gradlew build

# 테스트 (현재 테스트 없음 - Phase 6 예정)
./gradlew test
```

H2 콘솔: `http://localhost:8080/h2-console`
- JDBC URL: `jdbc:h2:file:./data/klepaas`
- Username: `sa`

---

## 상세 문서

- [아키텍처 결정 사항](claudedocs/project-direction.md) — 설계 변경 이력, Strategy Pattern, Source Staging 플로우 상세
- [프로젝트 진행 현황](../docs/PROJECT_STATUS.md)
- [로드맵](../docs/ROADMAP.md)
