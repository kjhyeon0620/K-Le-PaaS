# K-Le-PaaS Frontend

K-Le-PaaS 프론트엔드는 agent-safe Kubernetes control plane을 사람이 사용할 수 있는 Web console과 자동화용 CLI로 연결하는 Next.js 애플리케이션입니다.

프론트엔드는 백엔드 제어면을 직접 대체하지 않습니다. 인증, 위험도 분류, confirmation, 배포 실행, 비용 계산은 백엔드 API를 기준으로 동작하고, 프론트엔드는 이를 사용자와 agent가 다루기 쉬운 화면과 CLI로 제공합니다.

## 역할

- GitHub OAuth 로그인과 인증 상태 관리
- 저장소 등록과 배포 실행 UI
- 자연어 명령 입력과 confirmation UI
- 배포 목록, 상세, scale, restart, wait/export 보조 UI
- `UNKNOWN` 배포는 “판정 불가”로 표시하며 CLI wait는 exit 3으로 종료 (#57; 백엔드 재시작 대조가 기록한 결과와 이유를 표시)
- WebSocket 기반 배포 이벤트 수신
- CLI token 발급/조회/폐기 UI
- Node 기반 `klepaas` CLI 제공
- 비용 plan, diff, explain, check CLI 제공
- 모니터링, Slack 설정, MCP 설정 등 일부 화면 stub 제공

## 기술 스택

| 항목 | 내용 |
|---|---|
| Framework | Next.js 15, React 19 |
| Language | TypeScript 5 |
| Styling | Tailwind CSS 4, shadcn/ui, Radix UI |
| Icons | Lucide React |
| Charts | Recharts |
| Forms | React Hook Form, Zod |
| API client | `frontend/lib/api.ts` |
| WebSocket | `frontend/hooks/use-global-websocket.ts` |
| CLI | `frontend/cli/*.mjs` |

## 현재 구현 상태

| 영역 | 상태 | 메모 |
|---|---|---|
| 인증 UI | 구현 MVP | OAuth callback, auth context, token 저장/검증 흐름 존재 |
| 저장소 / 배포 UI | 구현 MVP | backend repository/deployment API와 일부 연동 |
| 자연어 명령 UI | 구현 MVP | `/api/v1/nlp/command`, `/api/v1/nlp/confirm`, history 연동 |
| CLI token UI | 구현 MVP | `/api/v1/cli-tokens` 연동 |
| WebSocket | 구현 MVP | `/api/v1/ws/deployments?token={jwt}` 연결 |
| CLI | 구현 MVP | `auth`, `ask`, `confirm`, `history`, `deployments`(`logs` 포함), `cost`, `doctor` |
| Scaling history | 구현 MVP | backend scaling-history API adapter 존재 |
| Deployments 화면 | 구현 | `/api/v1/repositories`, 저장소별 `/api/v1/deployments?repositoryId=&size=1`, `/repositories/{id}/config`. 배포 기록 상태만 표시하고 Pod 실제 상태·자원 사용량은 표시하지 않음. Scale 대화상자는 `/repositories/{id}/replicas` 관측값을 현재 레플리카로 표시(관측 불가 시 "확인 불가"). History: 저장소 배포 목록 → 배포 요청 상세(`?view=deployments&repository=<id>&deployment=<id>`, `/deployments/{id}/detail`) |
| 배포 설정 편집 | 구현 | 빌드 방식, 이미지 템플릿, pull secret, 서비스 타입·NodePort, 포트, 도메인, envFrom, probe·자원. GitHub 화면 Configure에서 이동. 조회 실패 시 저장하지 않음 |
| Dashboard data | 일부 구현 | 저장소별 최신 배포는 실제 API. 상단 통계 카드(`getDashboardData`)는 고정값 stub |
| Deployment logs UI | 구현 | Deployments 화면 Logs: 최신 배포의 실패 원인, 이 배포 요청이 적용한 Pod 로그(직전 컨테이너 포함)·이벤트, 관측 불가 사유 (`/api/v1/deployments/{id}/logs`) |
| Monitoring / alerts | 일부 구현 | UI와 client stub 중심, backend metrics API 없음 |
| Slack 설정 UI | 일부 구현 | 화면은 있으나 실제 설정 저장 API 없음 |
| Rollback direct API | 예정/Stub | 일부 자연어 flow UI는 있으나 direct backend API는 없음 |
| Pull request 목록 | 예정/Stub | frontend client stub |
| MCP connector | 예정/Stub | 설정 UI와 client stub, backend MCP server 없음 |

## 프로젝트 구조

```text
frontend/
├── app/                     # Next.js App Router
│   ├── page.tsx             # 메인 console 화면
│   ├── auth/callback/       # 인증 callback
│   ├── cli/authorize/       # CLI web approval 화면
│   └── oauth2-callback/     # OAuth callback 처리
├── components/              # dashboard, command, deployment, GitHub, monitoring, settings UI
├── contexts/                # auth context
├── hooks/                   # WebSocket, toast, mobile hooks
├── lib/                     # API client, config, utils, response types
├── cli/                     # Node CLI runtime
├── screenshots/             # README/포트폴리오용 화면 이미지
└── package.json
```

## 로컬 실행

```bash
cd frontend
npm install
npm run dev
```

기본 주소:

```text
http://localhost:3000
```

프로덕션 빌드:

```bash
cd frontend
npm run build
npm start
```

## 환경변수

`frontend/lib/config.ts` 기준 환경변수입니다.

```env
NEXT_PUBLIC_API_URL=http://localhost:8080
NEXT_PUBLIC_WS_URL=ws://localhost:8080
NEXT_PUBLIC_BASE_PATH=/console
```

`NEXT_PUBLIC_API_URL`과 `NEXT_PUBLIC_WS_URL`은 빌드 시점에 반영됩니다. 값을 바꾼 뒤에는 dev server 또는 build를 다시 실행하세요.

## 백엔드 연동

프론트엔드는 Spring Boot backend API를 사용합니다.

| 기능 | 백엔드 경로 |
|---|---|
| 인증 | `/api/v1/auth/**` |
| CLI token | `/api/v1/cli-tokens/**` |
| CLI web login | `/api/v1/cli-auth/sessions/**` |
| 저장소 | `/api/v1/repositories/**` |
| 배포 | `/api/v1/deployments/**` |
| 자연어 명령 | `/api/v1/nlp/**` |
| 비용 | `/api/v1/cost/**` |
| WebSocket | `/api/v1/ws/deployments?token={jwt}` |

`frontend/lib/api.ts`에는 backend API adapter와 아직 backend가 없는 UI용 stub이 함께 있습니다. 새 화면을 추가할 때는 stub을 실제 API처럼 보이게 문서화하지 않습니다.

## CLI 사용

CLI는 `frontend/cli/index.mjs`에서 제공됩니다.

```bash
cd frontend
npm run cli -- --help
npm run cli -- doctor
npm run cli -- auth login --web
npm run cli -- auth login --token <token>
npm run cli -- ask "default namespace pods 보여줘"
npm run cli -- confirm <commandLogId> --yes
npm run cli -- history --page 0 --size 20
npm run cli -- deployments list --repository-id 1 --json
npm run cli -- deployments get <deploymentId>
npm run cli -- deployments scale <deploymentId> --replicas 3
npm run cli -- deployments restart <deploymentId>
npm run cli -- deployments wait <deploymentId> --timeout 600
npm run cli -- deployments export <deploymentId> --format yaml
npm run cli -- cost check --file docs/examples/cli-cost-spec.json --max-monthly 120000
```

CLI 환경변수:

```text
KLEPAAS_BASE_URL
KLEPAAS_TOKEN
KLEPAAS_REFRESH_TOKEN
```

자세한 명령은 [CLI 레퍼런스](../docs/CLI_REFERENCE.md)를 참고하세요.

## 주요 화면 이미지

`screenshots/` 아래에 현재 화면 예시가 포함되어 있습니다.

- `screenshots/dashboard-overview.png`
- `screenshots/deployments-list.png`
- `screenshots/github-repositories.png`
- `screenshots/github-deployment-monitor.png`
- `screenshots/monitoring-cluster.png`
- `screenshots/commands-console-empty.png`
- `screenshots/commands-console-success.png`
- `screenshots/auth-login.png`

## 검증

```bash
cd frontend
npm ci
npm run build
npm run cli -- doctor
```

백엔드가 떠 있지 않으면 `doctor`의 API health/auth check는 실패할 수 있습니다. 이 경우 실패 사유를 그대로 기록합니다.

## 문서 정리 원칙

- 이 README는 frontend와 CLI를 실행하고 이해하는 데 필요한 내용만 담습니다.
- 장기 계획, MCP/IaC 상세 설계, 내부 판단은 루트의 `.local/docs/`에서 관리합니다.
- 실제 backend API가 없는 기능은 `구현`이 아니라 `일부 구현`, `예정`, `Stub`으로 표시합니다.
- 존재하지 않는 문서를 링크하지 않습니다.

## 관련 문서

- [프로젝트 README](../README.md)
- [Backend README](../backend/README.md)
- [CLI 전략](../docs/CLI_STRATEGY.md)
- [CLI 레퍼런스](../docs/CLI_REFERENCE.md)
