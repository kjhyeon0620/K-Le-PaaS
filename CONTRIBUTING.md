# Contributing Guide

## Workflow

이 저장소는 이슈 단위의 Spec-Driven Development로 진행한다. 절차 전체는 [`docs/specs/README.md`](docs/specs/README.md)에 있다.

1. 이슈 등급(S/M/L)을 정한다. M 이상은 `docs/specs/`에 스펙을 쓰고 `status: ready`로 만든다.
2. 이슈를 만든다 (feature / bug 템플릿: 배경, 작업, 완료 조건, 스펙). 이미 열린 이슈가 있으면 그 이슈를 쓴다.
3. 최신 `main`에서 `<type>/#<issue>-<kebab-summary>` 브랜치를 만든다.
   - `feat`, `fix`, `docs`, `ci`, `test`, `refactor`
   - 예: `feat/#51-app-probes-resources`, `fix/#47-remove-fake-status-values`
4. 코드, 테스트, 문서를 반영한다. 구현 중 스펙이 바뀌면 [구현 중 스펙 변경](docs/specs/README.md#구현-중-스펙-변경) 기준을 따른다. 범위 변경은 승인 후 진행한다.
5. AI 에이전트가 작업했다면, 커밋하기 전에 작업 요약을 보고하고 승인을 받는다.
6. 이슈 범위의 파일만 stage했는지 확인하고 커밋, 푸시한 뒤, PR 템플릿을 모두 채워 `main`으로 PR을 만든다.
7. 스펙의 회고를 채우고 status를 `done`으로 바꾼 상태로 PR에 포함한다. 사용자가 리뷰하고 머지한다. 머지 후 `main` 배포 workflow 결과는 다음 이슈 PR에서 스펙의 `운영 반영`에 기록한다.

## Branch / Commit
- `main`은 배포 브랜치다. 직접 푸시하지 않는다. `main` push는 플랫폼 운영 배포를 실행한다 ([CICD.md](docs/CICD.md)).
- Conventional Commits `type: summary`를 쓴다. 예: `feat: enforce CLI token scopes on the server`
- PR 제목은 이슈 제목과 같게 한다. 본문에는 `Closes #N`을 넣는다.

## Documentation
- 어떤 변경에 어떤 문서를 함께 고쳐야 하는지는 [문서 동기화 표](docs/specs/README.md#6-문서-동기화-완료-조건)를 따른다.
- 문서의 역할
  - 제품 목표와 실행 순서: `docs/product.md`
  - 구조와 공통 계약: `docs/architecture.md`
  - 결정 근거: `docs/adr/`
  - 이슈 스펙: `docs/specs/`
  - 공개 사용법: `README.md`, `backend/README.md`, `frontend/README.md`, `docs/CLI_REFERENCE.md`
- 같은 내용을 여러 문서에 복사하지 않는다. 한 곳에 쓰고 나머지는 링크한다.
- 공개 저장소다. 비밀값, 운영 호스트, 개인 인벤토리, 운영 검증 원자료는 `.local/`에 두고 커밋하지 않는다.
- 구현 상태는 `구현`, `구현 MVP`, `일부 구현`, `예정` 중 하나로 쓴다. 확인되지 않은 것은 과장하지 않는다.

## GitHub Ownership
- AI 에이전트: 이슈 생성, 브랜치 생성, 구현과 검증, 커밋 전 작업 요약 보고, 승인 후 커밋·푸시·PR 생성
- 사용자: 보고 승인, PR 리뷰와 **머지**, 운영 환경 변경 승인
- 세부 규칙은 [`AGENTS.md` §3](AGENTS.md)에 있다.

## Coding / Testing
- Backend: Java 17, Spring Boot. 핵심 비즈니스 로직과 권한 경계는 반드시 테스트한다. 권한이 없는 경로에서 외부 호출이 0회인지 확인한다.
- Frontend: Next.js, TypeScript. UI 규칙은 [`frontend/DESIGN.md`](frontend/DESIGN.md)를 따른다.
- 스키마 변경은 Flyway 규칙을 따른다 ([CICD.md "Writing schema migrations"](docs/CICD.md)).
- 검증 결과에는 환경(로컬 / 운영 DB 사본 / 실제 클러스터 / 운영)을 구분해 적는다.
