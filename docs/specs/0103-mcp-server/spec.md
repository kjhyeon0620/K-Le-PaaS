---
issue: 103
title: K-Le-PaaS MCP 서버와 에이전트 조회·제안 연결
status: done
size: L
type: feat
branch: feat/#103-mcp-server
---

# K-Le-PaaS MCP 서버와 에이전트 조회·제안 연결

## 목적 / 성공 조건
- 목적: 코딩 에이전트(Claude Code·Codex 등)가 도구 목록과 입력 스키마를 보고 K-Le-PaaS를 직접 쓰게 한다. #102에서 검증한 조회·제안 흐름을 MCP 도구로 제공하고, 배포·권한 로직은 복제하지 않는다.
- 기술적 완료: 백엔드 `/api/v1/mcp`(Streamable HTTP)가 조회 도구와 복구 제안 도구를 제공한다. 인증은 기존 CLI 토큰이고, 도구는 기존 서비스(소유권 검사 포함)를 호출한다.
- 운영: 클라이언트 설정과 호출 예제를 문서화한다. 실제 클라이언트 등록·확인은 사용자가 한다.

## 현재 동작 (#102)
- 에이전트는 PROPOSE_ONLY 토큰으로 CLI/REST를 쓴다: 앱 목록, 배포 목록·요청 상세·로그, 진단, 복구 계획, 복구 제안. 실행은 사람이 `nlp/confirm`(FULL)으로 승인한다.
- scope는 `SecurityConfig`가 URL별로 강제한다.

## 범위
- 포함: MCP 서버(공식 Java SDK), 도구 7개, 도구별 scope 검사, 인증 연결, 자동 테스트(SDK 클라이언트로 HTTP 호출), 문서.
- 제외: 에이전트의 승인·실행 도구(confirm·recover·restart·scale·배포), 별도 권한 모델, OAuth 인가 서버, MCP resources·prompts, stdio 서버, 콘솔 변경.

## 설계
- 라이브러리: `io.modelcontextprotocol.sdk:mcp:2.0.1`(core + Jackson 3). 서블릿 기반 Streamable HTTP transport를 Spring Boot에 서블릿으로 등록한다. Spring 전용 MCP 모듈은 쓰지 않는다(Spring AI 2.x로 옮겨졌고 Boot 4 호환을 확인하지 못함).
- 경로: `/api/v1/mcp`. 운영에서 백엔드는 `/api` 경로로 노출되므로 인프라 변경이 없다.
- 상태: 도구 호출만 있으므로 세션 없는 서버(`HttpServletStatelessServerTransport`, `McpStatelessSyncServer`)를 쓴다. 도구는 서블릿 요청 스레드에서 실행한다(`immediateExecution`). 입력 스키마(필수 값·범위·길이)는 SDK가 검사한다.
- 인증: `Authorization: Bearer <CLI 토큰>`. 기존 인증 필터를 그대로 거친다. 익명은 401.
- 권한:
  - `/api/v1/mcp`는 READ_ONLY·PROPOSE_ONLY·FULL만 통과한다. DEPLOY·CI 토큰은 403.
  - 조회 도구는 READ_ONLY 이상, `propose_recovery`는 PROPOSE_ONLY·FULL이다. REST 규칙과 같다.
  - 도구별 scope는 요청 시점의 인증 정보로 검사한다. 범위 밖이면 도구 오류(`CLI_005`)를 돌려준다.
- 도구 실행: 기존 서비스 메서드를 인증된 사용자 ID로 호출한다. 소유권 검사·입력 검증·에러 코드는 REST와 같다. 응답은 REST `data`와 같은 JSON(snake_case)을 텍스트로 담는다. `BusinessException`은 도구 오류(`isError=true`, 코드·메시지)로 돌려준다.

## 도구
| 이름 | 입력 | 대응 REST | scope |
|---|---|---|---|
| `list_repositories` | 없음 | `GET /repositories` | READ_ONLY 이상 |
| `list_deployments` | `repository_id`, `page`(기본 0), `size`(기본 20, 최대 100) | `GET /deployments?repositoryId=` | READ_ONLY 이상 |
| `get_deployment_detail` | `deployment_id` | `GET /deployments/{id}/detail` | READ_ONLY 이상 |
| `get_deployment_logs` | `deployment_id`, `lines`(기본 100, 1~200) | `GET /deployments/{id}/logs` | READ_ONLY 이상 |
| `diagnose_deployment` | `deployment_id`, `refresh`(기본 false) | `POST /deployments/{id}/diagnosis` | READ_ONLY 이상 |
| `get_recovery_plan` | `target_deployment_id` | `GET /deployments/{id}/recovery-plan` | READ_ONLY 이상 |
| `propose_recovery` | `target_deployment_id`, `reason`(선택, 200자 이하) | `POST /deployments/{id}/recovery-proposal` | PROPOSE_ONLY·FULL |

- 도구 설명에 "읽기 전용" 또는 "승인 대기 명령만 만든다, 실행은 사람이 klepaas confirm으로 승인"을 적는다. annotations에 readOnlyHint 등을 표시한다.

## 입출력·계약
- 새 endpoint: `/api/v1/mcp` (MCP Streamable HTTP). REST 응답·에러 코드·마이그레이션·설정 키 변화 없음.
- 문서: `docs/CLI_REFERENCE.md`의 AI agent 절차에 MCP 연결(Claude Code `claude mcp add --transport http ... --header "Authorization: Bearer ..."`, Codex 설정 예), `architecture.md`, `backend/README.md`.

## 결정 기록 (Decisions)
- 2026-10-11 Q1 → 사용자 결정: 백엔드 원격 HTTP MCP. 로컬 stdio·별도 Node 서버를 제안했으나 원격으로 정했다.
- 2026-10-11 Q2 → 사용자 결정(권장안): 공식 SDK. 백엔드가 Java라 공식 MCP Java SDK를 쓴다(TypeScript SDK와 원격 HTTP를 함께 고를 수 없어 재질문 후 확정).
- 2026-10-11 Q3 → 사용자 결정: 실제 클라이언트 등록·확인은 사용자가 한다. 나는 SDK 클라이언트로 실제 HTTP 호출 자동 테스트까지 한다.
- 2026-10-11 기본값: 경로 `/api/v1/mcp`(인프라 변경 없음), 인증은 기존 CLI 토큰 Bearer(OAuth 인가 서버는 범위 밖), 도구 7개는 #102 흐름과 같다.

## 완료 증거
| 증명할 것 | 방법 | 환경 |
|---|---|---|
| 도구 목록·스키마, 조회 도구 결과가 REST와 같음 | MCP SDK 클라이언트 → 실제 HTTP → Spring Security → 서비스 → PostgreSQL | 로컬 (Testcontainers) |
| 익명 401, DEPLOY 403, READ_ONLY의 propose 거절, PROPOSE_ONLY 제안은 PENDING만 생성 | 같은 테스트 | 로컬 |
| 다른 사용자 자원은 도구 오류(404 코드), 실행 없음 | 같은 테스트 | 로컬 |
| 회귀 | PR 전 `./gradlew test`, CLI 테스트 1회 | 로컬 |
| 실제 클라이언트(Claude Code 등) 조회·제안 | 사용자 확인 | 사용자 환경 |

### 실행 결과 (2026-10-11)
- 로컬: `McpServerHttpTest` — 공식 SDK 클라이언트(`HttpClientStreamableHttpTransport`, Bearer CLI 토큰) → 실제 HTTP(RANDOM_PORT) → Spring Security → `/api/v1/mcp` → 서비스 → PostgreSQL(Testcontainers).
  - 익명 401, DEPLOY 403.
  - PROPOSE_ONLY: initialize, 도구 7개 목록, 앱 목록·배포 목록·상세(failure_kind)·로그(NOT_CURRENT)·진단(근거 없음 → `rule`, 모델 미호출)·복구 계획(실행 가능)·복구 제안(PENDING 명령 1건).
  - 입력 스키마 위반(사유 201자, lines 201)은 도구 오류.
  - READ_ONLY: 조회 성공, 제안은 `CLI_005`.
  - 다른 사용자의 PROPOSE_ONLY: 앱 목록 빈 배열, 상세·제안은 `DEPLOY_001`.
  - 제안 외 실행·배포 없음(파이프라인·manifest 생성 호출 없음).
- 로컬: `./gradlew test` 265개 통과(건너뜀 1: 외부 모델 비교).
- 하지 않은 검증: 실제 MCP 클라이언트(Claude Code·Codex) 등록·호출 — 사용자 결정으로 사용자가 확인한다. 운영 배포 후 확인.
- 의존성: `io.modelcontextprotocol.sdk:mcp:2.0.1`(reactor-core, json-schema-validator 포함). SDK는 Jackson 3.1.4로 빌드됐고 앱은 Spring Boot가 관리하는 3.0.4로 실행된다. 테스트한 경로(프로토콜·도구 호출·스키마 검사)는 동작했다.

## 운영 반영
- 마이그레이션·설정 변경 없음. 배포 후 사용자가 PROPOSE_ONLY 토큰으로 클라이언트를 등록해 확인한다(`docs/CLI_REFERENCE.md` MCP로 연결).

## 회고
- 어긋난 점: 처음 질문에서 원격 HTTP와 TypeScript SDK를 함께 제시해 백엔드 언어와 맞지 않는 조합이 나왔다. 재질문으로 Java SDK로 정했다. 새 Spring 테스트 컨텍스트가 공유 테스트 DB의 연결 수를 넘겨 다른 테스트가 실패했고, 기존 HTTP 테스트와 같은 설정을 써서 컨텍스트를 공유했다.
- 원인 분류: 질문 설계(선택지 간 제약을 드러내지 않음), 테스트 인프라 제약(컨텍스트마다 연결 풀) 미기록.
- 고친 위치: 이 스펙 결정 기록, `McpServerHttpTest` 주석(컨텍스트 공유 이유).
