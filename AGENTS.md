# Agent Guide

이 저장소에서 작업하는 모든 AI 에이전트(Claude, Codex, Gemini 등)의 진입점이다. `CLAUDE.md`는 이 파일을 불러온다.
사람을 위한 규칙은 [`CONTRIBUTING.md`](CONTRIBUTING.md)에 있다.

## 1. 먼저 읽을 것

| 목적 | 문서 |
|---|---|
| 작업 절차 (스펙 → 구현 → 회고) | [`docs/specs/README.md`](docs/specs/README.md) |
| 제품 목표, 실행 순서, 이슈 상태 | [`docs/product.md`](docs/product.md) |
| 구조, 권한, 상태 모델, 에러 코드, 설정, **알려진 격차** | [`docs/architecture.md`](docs/architecture.md) |
| 결정 근거 | [`docs/adr/`](docs/adr/README.md) |
| 플랫폼 배포, 스키마 마이그레이션 | [`docs/CICD.md`](docs/CICD.md) |
| 사용자 앱 배포 | [`docs/ORACLE_K3S_GHCR_DEPLOYMENT.md`](docs/ORACLE_K3S_GHCR_DEPLOYMENT.md), [`docs/STAGE2_RESOURCE_ACCESS.md`](docs/STAGE2_RESOURCE_ACCESS.md) |
| CLI 계약 | [`docs/CLI_REFERENCE.md`](docs/CLI_REFERENCE.md) |
| 프론트 UI 규칙 | [`frontend/DESIGN.md`](frontend/DESIGN.md) |

작업 대상 이슈의 스펙(`docs/specs/NNNN-*/spec.md`)이 있으면 그 스펙이 이번 작업의 기준이다.
GitHub 이슈 본문(배경, 작업, 완료 조건)도 함께 읽는다.

## 2. Spec-Driven 작업 규칙

- 이슈 등급(S/M/L)을 먼저 제안한다. M 이상이면 **`status: ready`인 스펙 없이 구현을 시작하지 않는다.**
- 스펙을 쓸 때는 구현자가 임의로 정해야 하는 부분만 질문한다 (한 번에 최대 5개). 답은 스펙의 Decisions에 기록한다.
- 여러 이슈에 영향을 주는 결정은 ADR이나 `architecture.md`로 옮기고, 스펙에는 링크만 남긴다.
- 구현 중 스펙 변경은 [기준](docs/specs/README.md#구현-중-스펙-변경)을 따른다. 사실 정정과 구현 재량 안의 결정은 스펙을 바로 고치고 계속 진행한다. 범위 변경(새 endpoint·CLI 옵션, 응답·에러·exit code, 마이그레이션, 설정 키, 위험도·scope·소유권 규칙)은 스펙 수정안을 쓰고 사용자에게 묻고 기다린다.
- 코드와 문서가 다른 것을 발견하면 `architecture.md` §9 알려진 격차에 기록하고 사용자에게 알린다. 관련 결함이면 이슈 후보로 보고한다.
- 완료 조건: 스펙의 완료 증거 실행, [문서 동기화 표](docs/specs/README.md#6-문서-동기화-완료-조건)에 따른 문서 반영, 회고 작성, `status: done` (모두 구현 PR 안에서)

## 3. Git·GitHub 워크플로 (필수)

1. **이슈 (에이전트)**: `gh issue list`로 중복을 확인한다. 열린 이슈가 있으면 그 이슈를 쓰고, 없으면 템플릿으로 생성한다 (§3.1).
2. **브랜치 (에이전트)**: 최신 `origin/main`에서 `<type>/#<issue>-<kebab-summary>`로 만든다.
   - 메인 작업 트리에 다른 이슈의 커밋하지 않은 변경이 있으면 건드리지 말고 `git worktree add .local/worktrees/<name> -b <branch> origin/main`으로 분리한다.
3. 스펙에서 승인된 범위의 파일만 수정하고 검증한다.
4. **커밋 전 보고 (에이전트)**: 커밋하지 않은 상태로 작업 요약만 보고한다 (변경 파일, 구현 방식, 이유, 리스크, 테스트 결과와 검증 환경). 커밋 메시지나 PR 초안은 보고하지 않는다.
5. **승인 (사용자)**
6. **커밋 → 푸시 → PR 생성 (에이전트)**: 이슈 범위의 파일만 stage하고 staged 목록을 확인한 뒤 진행한다.
7. **머지 (사용자)**. 머지 후 `main` 배포 workflow 결과 확인, 브랜치·worktree 정리는 사용자가 요청할 때 에이전트가 수행한다.

### Hard Rules
- `main`에서 직접 구현하지 않고, `main`에 직접 푸시하지 않는다. `main` push는 운영 배포를 실행한다.
- **머지하지 않는다.** `gh pr merge`, auto-merge 설정을 하지 않는다.
- 사용자 승인 전에는 커밋, 푸시, PR 생성을 하지 않는다. 승인 후 다시 고쳤으면 다시 보고한다.
- 운영 서버, 운영 DB, DNS, GitHub 환경·Secret 변경은 사용자가 명시적으로 요청한 경우에만 한다.
- 비밀값(토큰, 키, kubeconfig, 운영 호스트 정보)을 코드, 문서, 스펙, 로그, PR에 남기지 않는다.

### 3.1 템플릿과 이력 일관성 (필수)

제목, 본문, 브랜치, 커밋 메시지를 쓰기 전에 템플릿과 최근 이력(`git log --no-merges -20`, `gh issue list`, `gh pr list`)을 확인하고 형식을 맞춘다.
템플릿과 최근 이력이 충돌하면 템플릿의 섹션 구조를 유지하고, 문체는 최근 이력을 따른다. 충돌이 있었다면 보고에 적는다.

| 항목 | 규칙 | 예 |
|---|---|---|
| 이슈 | `.github/ISSUE_TEMPLATE/{feature_request,bug_report}.md`의 섹션을 모두 채운다. 제목은 `type: 한국어 요약` | `feat: 앱별 readiness/startup probe와 CPU·메모리 자원 설정` |
| 이슈 라벨 | feat·test → `enhancement`, fix → `bug`, docs → `documentation` | - |
| 브랜치 | `<feat\|fix\|docs\|ci\|test\|refactor>/#<issue>-<kebab-summary>` (영문) | `feat/#51-app-probes-resources` |
| 커밋 | Conventional Commits `type: summary` (scope 없음, 주로 영어 명령형) | `feat: enforce CLI token scopes on the server` |
| PR 제목 | 이슈 제목과 같게 | `feat: CLI 토큰 scope 도입과 변경 API 서버 측 권한 검사` |
| PR 본문 | `.github/pull_request_template.md`의 모든 섹션을 채우고 `Closes #N`, M 이상은 스펙 링크 | - |
| base | `main` | - |

## 4. 명령

| 목적 | 명령 |
|---|---|
| 백엔드 실행 | `cd backend && ./gradlew bootRun` (H2 console: `--args='--spring.profiles.active=dev'`) |
| 백엔드 테스트 | `cd backend && ./gradlew test` (단일: `--tests '<FQCN>'`) |
| 백엔드 패키징 | `cd backend && ./gradlew bootJar` |
| 프론트 | `cd frontend && npm ci && npm run build`, 타입만: `npx tsc --noEmit -p .` |
| CLI | `cd frontend && npm run cli -- doctor` |
| 릴리스 receiver 테스트 | `python3 -m unittest discover -s scripts/deploy -p 'test_*.py' -v` |
| 릴리스 패키징 테스트 | `bash scripts/ci/test-package-release.sh` |

- Gradle은 같은 작업 트리에서 동시에 두 개 이상 실행하지 않는다.
- 검증은 변경 규모에 맞게 한다. 문서만 바꿨으면 빌드나 테스트를 돌리지 않는다. 이미 통과한 검증은 입력이 바뀌었을 때만 다시 돌린다.
- 검증 결과에는 환경(로컬 / 운영 DB 사본 / 실제 클러스터 / 운영)을 함께 적는다. 로컬 통과를 운영 검증으로 표현하지 않는다.

## 5. 코드 규칙

- API는 `/api/v1`, JSON은 `snake_case`, 에러는 `ErrorCode`와 `BusinessException`을 쓴다.
- 새 endpoint를 추가하면 `SecurityConfig`의 scope 규칙을 함께 검토한다 (기본은 FULL).
- Kubernetes 리소스 접근은 `ResourceAccessService`를 거친다. 권한이 없는 경로에서 Kubernetes 호출이 일어나지 않아야 한다.
- 상태를 추정하거나 고정값으로 채우지 않는다. 관측할 수 없으면 `UNKNOWN`이나 오류로 드러낸다.
- 스키마 변경은 Flyway `V<n>__*.sql` 하나로, 이전 릴리스와 호환되게 쓴다 ([ADR-0004](docs/adr/0004-flyway-forward-compatible-migrations.md)).
- 프론트엔드에서 백엔드 API가 없는 기능을 실제 기능처럼 보이게 하지 않는다 (`frontend/lib/api.ts`의 stub 구분).
- `.local/`은 git에서 제외된 개인 작업 공간이다. 운영 검증 원자료는 `.local/evidence/`에 두고, 스펙에는 요약만 옮긴다.
