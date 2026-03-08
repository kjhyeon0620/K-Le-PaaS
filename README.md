# KLEPaaS (K-Le-PaaS)

> **AI-First Kubernetes PaaS** — 자연어 명령으로 Kubernetes 클러스터에 앱을 배포·모니터링·롤백하는 플랫폼

---

## 프로젝트 개요

KLEPaaS는 주니어 개발자가 복잡한 Kubernetes/CI-CD 지식 없이도 자연어 한 문장으로 애플리케이션을 배포할 수 있도록 하는 PaaS 플랫폼입니다.

**핵심 가치:**
- "nginx 재시작해줘" → Gemini AI → `kubectl rollout restart` 자동 실행
- GitHub 저장소만 연결하면 소스 빌드 → 컨테이너 이미지 → K8s 배포까지 자동화
- 위험한 명령(배포/롤백/스케일)은 사용자 확인 단계를 거쳐 안전하게 실행

---

## 아키텍처 개요

```
[사용자 자연어 입력]
        ↓
[Next.js 프론트엔드]  ←→  [Spring Boot 백엔드]
                              ↓
                    [Gemini 2.5 Flash API]
                    (Intent 분류 + 인자 추출)
                              ↓
                    [ActionDispatcher]
                    ├── 배포 파이프라인
                    │     GitHub ZIP → NCP Object Storage
                    │     → Kaniko K8s Job (이미지 빌드)
                    │     → K8s Deployment/Service 생성
                    └── KubectlService (Fabric8)
                          실시간 클러스터 조회
```

---

## 기술 스택

| 레이어 | 기술 |
|--------|------|
| **프론트엔드** | Next.js 15, React 19, TypeScript, Tailwind CSS, shadcn/ui |
| **백엔드** | Spring Boot, Java 17, Spring Data JPA, Spring Security |
| **AI/NLP** | Google Gemini 2.5 Flash (REST API 직접 호출) |
| **인프라** | NCP(Naver Cloud Platform) Object Storage, Container Registry(NCR) |
| **K8s** | Fabric8 Kubernetes Client, Kaniko (컨테이너 빌드) |
| **인증** | GitHub OAuth2, JWT (HS256, 액세스/리프레시) |
| **DB** | H2 (개발), PostgreSQL (운영) |

---

## 프로젝트 구조

```
klepaas_v2/
├── frontend/          # Next.js 15 웹 콘솔
│   ├── app/           # App Router 페이지
│   ├── components/    # 도메인 컴포넌트 (대시보드, NLP 콘솔, 배포 모니터 등)
│   ├── hooks/         # WebSocket, Toast 등 커스텀 훅
│   └── lib/           # API 클라이언트, 타입, 유틸
│
├── backend/           # Spring Boot 백엔드
│   └── src/main/java/klepaas/backend/
│       ├── ai/        # NLP/AI 처리 (Gemini, IntentParser, ActionDispatcher)
│       ├── auth/      # GitHub OAuth, JWT
│       ├── deployment/ # 배포 파이프라인 (소스 업로드 → 빌드 → K8s 배포)
│       ├── infra/     # 클라우드 인프라 (NCP, K8s ManifestGenerator)
│       ├── user/      # 사용자 관리
│       └── global/    # 공통 예외처리, 설정, 알림 인터페이스
│
└── docs/              # 프로젝트 문서
    ├── PROJECT_STATUS.md  # 현재 진행 상황 및 미구현 사항
    └── ROADMAP.md         # Phase 6 이후 로드맵
```

---

## 배포 파이프라인 흐름

```
POST /api/v1/deployments
  ↓ [DeploymentService] 트랜잭션 커밋 후 비동기 실행
  1. GitHub ZIP 다운로드 (GitHub App Installation Token)
     → 최상위 디렉토리 제거 후 재패키징
  2. NCP Object Storage 업로드  builds/{deploymentId}/source.zip
  3. Kaniko K8s Job 생성
     - initContainer(aws-cli): Object Storage → /workspace unzip
     - kaniko: --context=dir:///workspace → NCR push
  4. 빌드 상태 폴링 (Exponential Backoff: 10s→20s→60s, 최대 30분)
     - initContainer 실패 / ImagePullBackOff 조기 감지
  5. K8s Deployment + Service 생성/업데이트 (fabric8 serverSideApply)
```

---

## NLP 처리 흐름

```
POST /api/v1/nlp/command  { "command": "nginx 파드 목록 보여줘" }
  ↓ Gemini 2.5 Flash → { "intent": "LIST_PODS", "args": {}, ... }
  ↓ IntentParser → ParsedIntent
  ↓ ActionDispatcher.classifyRisk() → LOW/MEDIUM/HIGH
  ↓ LOW  → 즉시 실행 (KubectlService.listPods)
    MEDIUM/HIGH → requiresConfirmation=true 반환 → 사용자 확인 후 실행
```

지원 Intent (28개): `LIST_PODS`, `DEPLOY`, `ROLLBACK`, `SCALE`, `RESTART`, `LOGS`, `OVERVIEW`, `COST_ANALYSIS` 등

---

## 빠른 시작

### 사전 요구사항

- Java 17+, Gradle
- Node.js 18+
- NCP 계정 (Object Storage, Container Registry, NKS 클러스터)
- GitHub OAuth App + GitHub App 등록
- Gemini API 키

### 환경변수 설정

`backend/.env` 파일 생성:

```env
# GitHub
GITHUB_CLIENT_ID=...
GITHUB_CLIENT_SECRET=...
GITHUB_REDIRECT_URI=http://localhost:3000/console/auth/callback
GITHUB_APP_ID=...
GITHUB_APP_PRIVATE_KEY=...
GITHUB_APP_SLUG=...

# NCP
NCP_ACCESS_KEY=...
NCP_SECRET_KEY=...
NCP_STORAGE_BUCKET=...
NCR_ENDPOINT=...

# Kubernetes
K8S_NAMESPACE=default
K8S_IMAGE_PULL_SECRET=ncp-cr

# Gemini
GEMINI_API_KEY=...

# JWT
JWT_SECRET=...  # openssl rand -hex 32
```

`frontend/.env.local` 파일 생성:

```env
NEXT_PUBLIC_API_URL=http://localhost:8080
NEXT_PUBLIC_WS_URL=ws://localhost:8080
NEXT_PUBLIC_APP_BASE_PATH=/console
```

### 로컬 실행

```bash
# 백엔드
cd backend
./gradlew bootRun

# 프론트엔드
cd frontend
npm install
npm run dev
```

---

## 문서 목록

| 문서 | 내용 |
|------|------|
| [`docs/PROJECT_STATUS.md`](docs/PROJECT_STATUS.md) | 현재 구현 완성도, 미구현 사항 목록 |
| [`docs/ROADMAP.md`](docs/ROADMAP.md) | Phase 6+ 로드맵 및 개선 아이디어 |
| [`backend/claudedocs/project-direction.md`](backend/claudedocs/project-direction.md) | 아키텍처 결정 사항 상세 문서 |
| [`frontend/README.md`](frontend/README.md) | 프론트엔드 구조 및 개발 가이드 |

---

## Git Workflow

```
main (production)
  └── develop
        └── feat/#N-기능명
```

**커밋 컨벤션**: `feat:` / `fix:` / `refactor:` / `chore:` / `docs:`
