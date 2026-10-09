---
issue: 65
title: GitHub Actions OIDC 기반 CI 배포 인증
status: done
size: L
type: feat
branch: feat/#65-github-actions-oidc
---

# GitHub Actions OIDC 기반 CI 배포 인증

## 목적 / 성공 조건
- 목적: GitHub Actions 워크플로가 저장소별 K-Le-PaaS 토큰 Secret 없이 배포를 요청한다. 토큰 재발급과 만료로 자동 배포가 멈추는 일이 없어진다.
- 기술적 완료: GitHub이 서명한 OIDC 토큰을 `Authorization: Bearer`로 보낸 `POST /api/v1/deployments`가, 토큰의 저장소와 요청의 `repository_id`가 일치할 때만 그 저장소 소유자의 배포로 접수된다.
- 운영: 검증에 실패한 토큰은 401, 저장소·커밋이 맞지 않으면 403 `CLI_005`를 받고 배포는 생성되지 않는다. GitHub Actions 밖의 CI는 #50의 배포 전용 토큰을 계속 쓴다.

## 범위
- 포함: OIDC 토큰 검증(서명·`iss`·`aud`·`exp`·`ref`), 요청 저장소·커밋과 claim 대조, 기존 #52 배포 생성 경로 재사용, 문서와 워크플로 예시, (머지·배포 후) IoT 저장소 워크플로 전환 PR
- 제외: GitHub 외 CI의 OIDC, 저장소별 허용 브랜치, dev/prod 분리, 토큰 재사용(jti) 차단, 배포 조회·scale 등 배포 생성 외 API

## 입출력·계약
- API `POST /api/v1/deployments` (요청·응답 형식 변경 없음)
  - 인증: `Authorization: Bearer <GitHub Actions OIDC 토큰>`
  - 401: 서명 위조, 다른 `iss`, `aud` 불일치, 만료, 허용되지 않은 `ref`, JWKS 조회 실패
  - 403 `CLI_005`: 요청 `repository_id`의 등록 저장소 `owner/repo`가 토큰 `repository`와 다름, 없는 저장소, `branch_name`이 `ref`의 브랜치와 다름, `commit_hash`가 토큰 `sha`와 다름
  - 나머지(201/200/409)는 #52와 같다
- OIDC 인증 주체는 배포 생성만 할 수 있다. 다른 API는 403이다 (`/auth/me`, `/users/me`, `/auth/logout` 포함).
- 설정 (새 키)
  - `github.actions.oidc.audience`: 기본 `k-le-paas`
  - `github.actions.oidc.allowed-refs`: 기본 `refs/heads/main` (쉼표로 여러 개)
  - `github.actions.oidc.jwks-uri`: 기본 `https://token.actions.githubusercontent.com/.well-known/jwks` (테스트에서 로컬 JWKS로 바꾸는 용도)
  - issuer는 `https://token.actions.githubusercontent.com`로 고정
- 저장: 변경 없음 (마이그레이션 없음)
- 의존성: `spring-security-oauth2-jose` (Spring Boot 관리 버전). JWKS 조회·캐시·키 교체와 서명 검증에 쓴다.

## 권한·안전
- 호출 주체: GitHub Actions 워크플로 (`permissions: id-token: write`)
- 소유권: 토큰에는 K-Le-PaaS 사용자가 없다. 요청 `repository_id`의 등록 저장소를 찾아 `owner/repo`가 claim과 같으면(대소문자 무시) 그 저장소 소유자로 `createDeployment`를 호출한다. 같은 GitHub 저장소를 여러 사용자가 등록할 수 있으므로 이름만으로 저장소를 고르지 않는다.
- 저장소가 없거나 다르면 같은 403으로 응답해 다른 사용자 저장소의 존재를 드러내지 않는다.
- 위험도: 배포 생성과 같다. 승인 흐름 없음 (CI 경로).
- 비밀값: 워크플로에 K-Le-PaaS 비밀값이 필요 없다. OIDC 토큰은 로그에 남기지 않는다.

## 제약
- 참조: [architecture](../../architecture.md) §4, §7, [ADR-0003](../../adr/0003-cli-token-scopes.md), [#52 스펙](../0052-serialize-deployments/spec.md)
- 인증은 기존 `JwtAuthenticationFilter` 순서(Web JWT → CLI 토큰) 뒤에 OIDC를 시도한다. JWT 형식(점 2개)이 아닌 토큰은 OIDC 검증을 하지 않는다.

## 예외·경계 상황
| 상황 | 사용자·agent가 보는 것 | 시스템 동작 |
|---|---|---|
| 정상 토큰, 일치하는 저장소·브랜치·커밋 | 201 (또는 #52 규칙의 200/409) | 저장소 소유자 이름으로 배포 생성 |
| 위조 서명, 다른 iss, 잘못된 aud, 만료 | 401 | 배포 없음 |
| main 외 브랜치·PR(`refs/pull/...`) 토큰 | 401 | 배포 없음 |
| 다른 GitHub 저장소의 토큰으로 이 `repository_id` 요청 | 403 `CLI_005` | 배포 없음 |
| `repository_id`가 없는 저장소 | 403 `CLI_005` | 배포 없음 |
| `branch_name`·`commit_hash`가 토큰과 다름 | 403 `CLI_005` | 배포 없음 |
| OIDC 토큰으로 다른 API 호출 | 403 | - |
| GitHub JWKS 조회 실패·지연 | 401 | 다음 요청에서 다시 조회 |
| 같은 토큰 재전송(유효 기간 안) | #52 규칙(진행 중이면 같은 배포 200) | 재사용 차단은 하지 않음 |

## 미결 질문 (Open)
- 없음

## 결정 기록 (Decisions)
- 2026-10-03 허용 브랜치 → 전역 설정 `github.actions.oidc.allowed-refs`(기본 `refs/heads/main`). 저장소별 설정은 하지 않는다. (dev/prod 분리가 범위 밖이고 마이그레이션·UI가 필요 없음)
- 2026-10-03 audience → 기본값 `k-le-paas`를 둔다. (운영 환경 변수 변경 없이 머지 후 바로 동작. aud는 비밀값이 아니고 다른 서비스용 토큰의 재사용을 막는 용도)
- 2026-10-03 IoT 워크플로 전환 → 이 PR 머지·운영 배포 후 IoT 저장소에 PR을 만든다. 머지, Secret 삭제, 기존 전체 권한 토큰 폐기는 사용자가 한다.
- 기본값 가정: JWKS 검증은 `spring-security-oauth2-jose`(Nimbus)를 쓴다. 키 캐시·교체·서명 검증을 직접 구현하지 않기 위해. [승격: ADR-0007]
- 기본값 가정: 요청 `branch_name`은 `ref`의 브랜치와, `commit_hash`는 토큰 `sha`와 같아야 한다(커밋은 앞부분 일치 허용). 배포 기록이 실제 실행한 워크플로의 커밋과 같게 하기 위해.
- 2026-10-03 (구현 중) JWKS 조회에 연결·읽기 5초 제한을 둔다. → GitHub JWKS가 응답하지 않을 때 요청 스레드가 무기한 대기하지 않게.
- 2026-10-03 (구현 중) 허용 설정 키에 환경 변수(`GITHUB_ACTIONS_OIDC_AUDIENCE`, `GITHUB_ACTIONS_OIDC_ALLOWED_REFS`)를 연결했다. JWKS URI는 테스트용이라 환경 변수를 두지 않는다.
- 기본값 가정: OIDC 인증 주체는 `SCOPE_GITHUB_ACTIONS` 권한만 갖고 `POST /deployments`만 허용한다. `/auth/me` 등 기존 `authenticated()` 경로는 CLI scope 권한 보유자로 좁혀 OIDC 주체를 막는다 (기존 사용자·토큰에는 변화 없음).

## 완료 증거
| 증명할 것 | 방법 | 환경 | 결과 |
|---|---|---|---|
| 정상 토큰으로 배포 접수, 저장소 소유자로 생성 | `GitHubActionsOidcHttpTest` (로컬 JWKS 서버 + 로컬 서명 토큰) | 로컬 | 통과 |
| 위조·만료·잘못된 aud·다른 iss·다른 브랜치·PR ref → 401, 배포 없음 | 같은 테스트 | 로컬 | 통과 |
| 다른 저장소 claim, 없는 저장소, 브랜치·커밋 불일치 → 403, 배포 없음 | 같은 테스트 | 로컬 | 통과 |
| OIDC 주체로 다른 API 호출 → 403 | 같은 테스트 | 로컬 | 통과 |
| 기존 JWT·CLI 토큰 동작 유지 | `./gradlew test bootJar` (190개) | 로컬 | 통과 |
| 패키징 JAR가 실제 GitHub JWKS를 조회하고 위조 토큰을 거부 | 패키징 JAR + 위조 토큰 요청 | 로컬 (실제 GitHub JWKS) | 통과 (401, 약 1.4초) |
| IoT 워크플로 전환 후 Secret 없이 실제 배포 성공 | IoT 저장소 PR 머지 후 workflow 실행 | 운영 | 머지 후 (운영 반영에 기록) |
| 기존 CI용 전체 권한 토큰 폐기 | 사용자 확인 | 운영 | 머지 후 (운영 반영에 기록) |

## Tasks
### T1. 검증기와 인증 필터
- 변경: `build.gradle` 의존성, `GitHubActionsTokenVerifier`(새), `JwtAuthenticationFilter`, `CustomUserDetails`, `SecurityConfig`, `application.yaml` 설정 키
- 금지: 토큰 원문 로그
- 검증: 로컬 JWKS HTTP 테스트

### T2. 배포 요청 대조
- 변경: `DeploymentController`, 저장소·커밋 대조 로직
- 검증: 같은 HTTP 테스트

### T3. 문서
- `docs/ORACLE_K3S_GHCR_DEPLOYMENT.md`(OIDC 워크플로 예시, 배포 전용 토큰은 GitHub Actions 외 CI용), `docs/architecture.md`(§4 권한, §7 설정, §9에서 #65 제거), `docs/adr/0007-github-actions-oidc.md`, `docs/product.md`, `backend/README.md`

### T4. IoT 전환 (머지·운영 배포 후)
- IoT 저장소 `deploy-klepaas-oracle.yml`: `permissions: id-token: write`, OIDC 토큰 요청, `KLEPAAS_TOKEN` 제거. PR 생성까지. 머지·Secret 삭제·기존 토큰 폐기는 사용자

## 운영 반영
- 2026-10-03 PR #73 머지. main `Build and deploy`의 build·deploy 성공 (마이그레이션 없음).
- IoT 저장소 워크플로를 OIDC로 전환(smart-sousvide-iot-platform PR #69). 머지 후 실행에서 `KLEPAAS_TOKEN` 없이 OIDC 토큰으로 배포가 접수됐다(배포 #8, branch main, commit 84b385f).
- 배포 #8의 rollout은 실패했다. 클러스터 `ghcr-pull-secret`의 토큰이 만료돼 GHCR이 403을 반환했고 새 Pod가 `ImagePullBackOff`였다. 이미지는 공개이고 Pod 템플릿은 이미지 태그 외에 바뀌지 않았다(#51 전체 교체 정상). 기존 Pod는 계속 서비스했다. 공개 이미지에서 pull secret을 끄는 작업은 #74.
- 기존 CI용 전체 권한 토큰 폐기와 `KLEPAAS_TOKEN` Secret 삭제는 사용자가 진행한다.

## 회고
- 어긋난 점: 인증 필터에 검증기 의존성을 추가하자 필터를 직접 import하는 `@WebMvcTest` 슬라이스 5개가 컨텍스트를 만들지 못했다. 동작 문제는 아니고 테스트 구성 문제였다.
- 원인 분류: 외부 제약 미인지 (슬라이스 테스트가 보안 필터의 의존성을 직접 나열함)
- 고친 위치: 각 슬라이스의 `@Import`에 검증기를 추가. 반복되면 공통 테스트 보안 설정으로 묶는다.
