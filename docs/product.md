# Product: K-Le-PaaS

이 문서는 제품 목표, 사용자, 범위, 성공 기준, 실행 순서를 담는다. 구조는 [`architecture.md`](architecture.md), 결정 근거는 [`adr/`](adr/README.md)에 있다.
기능별 구현 상태의 공개 요약은 루트 [`README.md`](../README.md)의 현재 상태 표에 있다.

## 1. 목표

- **제품 목표**: 프로젝트마다 K-Le-PaaS 코드를 수정하거나 서버에 수동 패치하지 않고, 새 HTTP 앱을 등록하고 배포하고 관측하고 복구할 수 있게 한다.
- **입증 기준**: 기존 IoT 앱과 다른 기술 스택(Node.js HTTP 앱)의 두 번째 앱으로 첫 배포 → 변경 → 실패 확인 → 이전 버전 복구까지를 설정만으로 수행한다 (#55).
- **차별점**: PaaS 기능의 개수가 아니라, **LLM·CLI·agent의 요청을 권한, 승인, 실제 상태로 통제하는 실행 경계**에 둔다.

## 2. 사용자

| 사용자 | 목적 | 인터페이스 |
|---|---|---|
| 앱 소유자 | 저장소를 등록하고 설정을 바꾸고 배포 상태를 확인한다 | Web console (`/console`) |
| 운영자 | 상태를 조회하고, 재시작·스케일·배포를 승인하고, 허용 참조를 관리한다 | Web, CLI, 서버 설정 |
| AI agent | 조회하고, 변경을 **제안**한다. 실행은 사람이 승인한다 | CLI (`propose-only` 토큰), 자연어 API |
| CI | 지정된 저장소의 이미지 배포를 요청한다 | `POST /api/v1/deployments` (GitHub Actions는 OIDC 토큰, 그 밖의 CI는 저장소별 배포 전용 토큰) |

## 3. 성공 기준

| 종류 | 기준 |
|---|---|
| 안전 | 승인되지 않은 MEDIUM/HIGH 변경이 실행되지 않는다. 권한 밖 요청은 Kubernetes 호출 전에 거부된다 |
| 사실성 | 상태, 로그, 배포 목록은 실제 관측값이거나 저장된 이력이다. 판단할 수 없으면 `UNKNOWN`으로 표시하고 고정값을 쓰지 않는다 |
| 배포 | 두 번째 앱 실증에서 앱별 K-Le-PaaS 코드 변경과 서버 수동 패치가 0회다. 모든 배포를 commit, 이미지, 설정, 결과로 추적할 수 있다 |
| 모델 평가 | 고정된 입력 30개를 재실행할 수 있고, 미확인 변경 실행 0건을 목표로 한다 (#58) |

## 4. 범위

### 포함
- GitHub OAuth, GitHub App 기반 저장소 접근
- 빌드 경로 두 가지: NCP Kaniko/NCR, 외부 이미지(GitHub Actions/GHCR, prebuilt)
- Kubernetes Deployment/Service/Ingress 적용, rollout 판정, scale, restart
- 자연어 명령 → intent → 위험도 분류 → 승인 → 실행
- CLI와 토큰 scope, 비용 추정(spec 기반), Slack/WebSocket 알림, GitHub webhook
- 플랫폼 자체 CI/CD (Oracle ARM64 systemd 릴리스)

### 제외 (현재 단계)
- 다중 클러스터, AWS provider
- MCP 서버, IaC/Terraform (설계 후보로만 존재)
- 실제 billing API 기반 비용 조회
- DB 자동 생성, 백업·복원 자동화
- 한 저장소의 여러 서비스, dev/prod 환경 분리
- 도메인·HTTPS 자동화 (두 번째 앱 실증과 모델 평가 결과를 본 뒤 판단)

## 5. 실행 순서

2026-09-29 방향 재검토에서 정한 원칙은 다음 순서다: **사실과 다른 동작 제거 → 토큰 권한 → 스키마 관리 → 정상 동작 기준 → 배포 정합성 → 두 번째 앱 실증 → 평가·공개**.
이 순서를 기준으로 각 이슈의 상태와 스펙을 관리한다.

| 순서 | 이슈 | 제목 | 상태 | 스펙 |
|---|---|---|---|---|
| 1 | #46 | 재시작 replica 보존, 스케일 입력 검증 | 완료 (PR #60) | - |
| 2 | #47 | 상태 고정값, 대시보드 mock 제거 | 완료 (PR #61) | - |
| 3 | #48 | Flyway 도입, H2 baseline | 완료 (PR #62), 운영 V1 | - |
| 4 | #49 | CLI 토큰 scope 서버 강제 | 완료 (PR #63), 운영 V2 | - |
| 5 | #50 | propose-only, 저장소별 CI 배포 토큰 | 완료 (PR #64), 운영 V3 | - |
| 6 | #51 | 앱별 readiness/startup probe, requests/limits | 완료 (PR #71), 운영 V4 | [0051](specs/0051-app-probes-resources/spec.md) |
| 7 | #52 | 동일 앱 배포 직렬화, 중복 접수 방지 | 완료 (PR #72), 운영 V5 | [0052](specs/0052-serialize-deployments/spec.md) |
| 7-1 | #65 | GitHub Actions OIDC 기반 CI 배포 인증 | 완료 (PR #73), IoT 전환 | [0065](specs/0065-github-actions-oidc/spec.md) |
| 7-2 | #75 | 운영 DB H2 → PostgreSQL 전환 | 완료 (PR #76), 운영 PostgreSQL V1 | [0075](specs/0075-postgresql-migration/spec.md) |
| 7-3 | #74 | 공개 이미지 배포에서 pull secret 끄기 | 진행 중 (#65 운영 확인 중 발견) | [0074](specs/0074-optional-image-pull-secret/spec.md) |
| 8 | #53 | 요청 단위 rollout 판정 | 미착수 | - |
| 9 | #54 | 배포 실패 원인, 배포 로그 API | 미착수 | - |
| 10 | #55 | 두 번째 앱 배포·실패·복구 실증 | 미착수 | - |
| 11 | #56 | 승인한 배포 설정 버전 고정, 변경 시 재승인 | 데모 이후 | - |
| 12 | #57 | 재시작 후 미완료 배포·명령 상태 대조 | 데모 이후 | - |
| 13 | #58 | 자연어 명령 해석 실제 모델 평가 기준선 | 미착수 | - |
| 13-1 | #66 | 설정 조회 실패 시 콘솔 저장이 기본값으로 덮어쓰는 문제 | 미착수 (#51 검증 중 발견) | - |
| 14 | #59 | README, 콘솔 미연결 화면, 데모 결과 정리 | 미착수 | - |

- 새 이슈를 착수할 때 M/L 등급이면 스펙을 먼저 만들고 이 표의 스펙 열을 채운다.
- #55의 샘플 앱과 실패 주입 방법은 초기에 정해서 #51~#54의 검증 대상으로 쓴다.
- 플랫폼 자체 배포(systemd)와 사용자 앱 배포(Kubernetes)는 별도 흐름이다. 플랫폼 CI/CD가 완료되었다고 #52~#55를 완료로 보지 않는다.

## 6. 후순위 후보

요구가 확인되면 좁은 범위의 이슈로 추가한다.

- 도메인·HTTPS 자동화, 서비스·환경 분리, DB 자동 생성
- 모니터링 metrics API와 UI stub 연결, Slack 설정 저장
- 조직 저장소 권한 모델
- 인자 기반 위험도 규칙 (#58 결과로 판단)
- MCP 어댑터 (#49, #50의 scope 위에서만)
- IaC/Terraform
