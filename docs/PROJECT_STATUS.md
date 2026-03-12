# 프로젝트 진행 현황

> 마지막 업데이트: 2026-03-12
> 현재 브랜치: main (Phase 1~5 완료)

---

## 전체 완성도

```
인증/보안        ████████░░  80%
NLP/AI          █████████░  90%
배포 파이프라인  ████████░░  80%
K8s 조회        █████████░  90%
WebSocket       ████░░░░░░  40%
모니터링         ████░░░░░░  40%
알림(Slack)     ██░░░░░░░░  20%
GitOps/Webhook  ░░░░░░░░░░   0%
CLI 제어면      ████████░░  80%
```

---

## 구현 완료 (Phase 1~5)

### Phase 1 — Entity 기반 설계
- [x] `User`, `SourceRepository`, `Deployment`, `DeploymentConfig`, `CommandLog` 엔티티
- [x] `DeploymentStatus`, `CloudVendor`, `Role` enum
- [x] `BaseTimeEntity` JPA Auditing
- [x] Repository 레이어 (`@EntityGraph` 포함)
- [x] `CloudInfraProvider` 인터페이스 + `CloudInfraProviderFactory` (Strategy Pattern)

### Phase 2 — 서비스 레이어 + API
- [x] `GlobalExceptionHandler` + `ApiResponse<T>` / `ErrorResponse` 공통 응답
- [x] `NotificationService` 인터페이스 정의
- [x] `DeploymentService` (배포 CRUD, 스케일, 재시작)
- [x] `UserService`, `RepositoryService`
- [x] `DeploymentController`, `RepositoryController`, `UserController`, `SystemController`

### Phase 3 — 인증/인가
- [x] GitHub OAuth2 로그인 플로우 (코드 교환 → JWT 발급)
- [x] JWT 액세스/리프레시 토큰 (HS256)
- [x] Spring Security + Role(USER/ADMIN) 기반 접근 제어
- [x] `GitHubInstallationTokenService` — GitHub App 설치 토큰 자동 취득 + 캐싱

### Phase 4 — NCP 인프라 (배포 파이프라인)
- [x] GitHub ZIP 다운로드 (302 redirect 수동 처리) + 최상위 디렉토리 제거 후 재패키징
- [x] NCP Object Storage 업로드 (AWS SDK V2, S3 호환)
- [x] Kaniko K8s Job 생성 (fabric8 client)
  - initContainer(`aws-cli`): Object Storage → `/workspace` unzip
  - kaniko: `--context=dir:///workspace` → NCR push
- [x] 빌드 상태 폴링 (Exponential Backoff: 10s→20s→60s, 최대 30분)
- [x] Pod 조기 실패 감지 (initContainer exitCode, ImagePullBackOff, FailedMount, Unschedulable)
- [x] K8s Deployment + Service + Ingress 생성/업데이트 (fabric8 serverSideApply)
- [x] `@Async` + `@Transactional` 타이밍 버그 수정 (afterCommit 콜백)

### Phase 5 — AI 자연어 명령
- [x] `GeminiClient` — Gemini 2.5 Flash REST API 직접 호출
- [x] `IntentParser` — JSON 응답 파싱 (마크다운 코드블록 처리)
- [x] `ActionDispatcher` — 28개 Intent → 서비스 메서드 매핑 + LOW/MEDIUM/HIGH 리스크 분류
- [x] `NlpCommandService` — LOW 즉시 실행, MEDIUM/HIGH 확인 대기
- [x] `ConversationSession` — DB 기반 세션 관리 (Redis 제거)
- [x] `NlpController` — `/command`, `/confirm`, `/history`
- [x] 시스템 프롬프트 외부 파일 분리 (`prompts/system-prompt.txt`)
- [x] `KubectlService` — Fabric8로 pods/services/ingresses/namespaces/deployments/logs/overview 직접 조회

### 프론트엔드
- [x] GitHub OAuth 콜백 + JWT 저장/갱신 (`auth-context.tsx`)
- [x] 대시보드, 자연어 명령 콘솔, 배포 모니터링, GitHub 연동, 실시간 모니터링, 설정 페이지 UI
- [x] `lib/api.ts` — 백엔드 전체 REST API 연동 + `ApiResponse<T>` 자동 unwrap
- [x] `use-global-websocket.ts` — 실시간 배포 상태 스트리밍 훅 (백엔드 엔드포인트 미구현)
- [x] `nlp-response-renderers/` — Intent별 응답 렌더링 컴포넌트

### CLI / 인증 확장
- [x] `frontend/cli/` 기반 Node CLI 런타임 추가
- [x] `auth`, `ask`, `confirm`, `history`, `deployments`, `cost` 명령 구현
- [x] `doctor`, 환경변수 인증, `deployments wait`, `deployments export` 추가
- [x] `--json`, `--quiet`, 종료 코드 표준화
- [x] 비용 추정 API (`/api/v1/cost/*`) 추가
- [x] 웹 설정 페이지에서 `CLI Tokens` 발급/조회/폐기 UI 추가
- [x] CLI 토큰 해시 저장 + JWT/CLI 토큰 겸용 인증 필터 구현
- [x] 웹 승인형 `auth login --web` 플로우 추가 (`/api/v1/cli-auth/sessions/*`, `/console/cli/authorize`)

---

## 미구현 사항

### 백엔드

#### 높은 우선순위
| 항목 | 위치 | 내용 |
|------|------|------|
| **Slack 알림 구현체** | `global/service/NotificationService.java` | 인터페이스만 있고 `SlackNotificationService` 구현체 없음. 배포 시작/성공/실패 알림 미발송 |
| **WebSocket 배포 스트리밍** | 미존재 | 프론트 `use-global-websocket.ts`가 연결 시도하지만 백엔드 `/ws/**` 엔드포인트 없음 |
| **배포 로그 조회** | `DeploymentService.java:87` | `TODO` 주석 — Kaniko Pod 로그를 fabric8 `watchLog()`로 실제 스트리밍 필요 |
| **대시보드 통계 API** | 미존재 | 클러스터 수, 배포 수, CPU/메모리 사용률 집계 API 없음. 프론트에서 `0`으로 하드코딩 |

#### 중간 우선순위
| 항목 | 내용 |
|------|------|
| **스케일링 히스토리 API** | `api.ts:getScalingHistory()` stub — 레플리카 변경 이력 저장/조회 미구현 |
| **GitHub PR / Webhook API** | PR 목록 조회, Webhook 설정 모두 프론트에서 stub |
| **이미지 태그 관리** | 현재 `:latest` 고정 → commit SHA 태그로 변경해야 롤백이 정확히 동작 |
| **JWT Refresh Token 순환** | 현재 리프레시 토큰은 갱신 없이 재사용됨 |

### 프론트엔드 (백엔드 미구현에 기인)

| 항목 | 파일 | 상태 |
|------|------|------|
| 실시간 리소스 모니터링 | `api.ts:getNKSCpuUsage()` 등 | `return {}` stub |
| Slack 알림 설정 | `SlackNotificationsSection.tsx` | UI만 있고 API 연동 없음 |
| 롤백 직접 API | `api.ts:getRollbackList()` | stub (현재 NLP 통해서만 가능) |
| Pull Request 목록 | `api.ts:getPullRequests()` | stub |
| MCP 커넥터 | `MCPConnectorsSection.tsx` | stub |
| 배포 로그 다이얼로그 | `api.ts:getDeploymentLogs()` | stub |

---

## 신규 기획 메모

### CLI 제어면

- 상태: 1차 MVP 구현 완료
- 문서: [`docs/CLI_STRATEGY.md`](CLI_STRATEGY.md)
- 정의: 웹 콘솔을 대체하지 않고, 운영/자동화/비용 가드레일을 담당하는 보조 인터페이스
- 구현 범위: `auth`, `ask`, `confirm`, `history`, `deployments`, `cost`, `doctor`
- 인증 방식: 사람용 `auth login --web`, 머신용 `auth login --token`
- 자동화 편의: `KLEPAAS_BASE_URL`, `KLEPAAS_TOKEN`, `KLEPAAS_REFRESH_TOKEN`
- 비용 방향: billing 조회형보다 배포 전 추정형 FinOps 우선
