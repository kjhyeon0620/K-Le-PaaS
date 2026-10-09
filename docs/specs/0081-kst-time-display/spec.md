---
issue: 81
title: API 시각에 시간대를 포함하고 콘솔·CLI 표시를 한국 시간으로 통일
status: done
size: M
type: feat
branch: feat/#81-kst-time-display
---

# API 시각에 시간대를 포함하고 콘솔·CLI 표시를 한국 시간으로 통일

## 목적 / 성공 조건
- 목적: 같은 시각이 화면·CLI마다 다르게 보이거나 9시간 어긋나 보이는 일이 없어진다. 사용자는 모든 시각을 한국 시간(KST)으로 본다.
- 기술적 완료: API 응답의 시각은 모두 `Z`가 붙은 UTC ISO 8601이다. 콘솔과 CLI 사람용 출력은 Asia/Seoul로 표시한다.
- 운영: DB 값과 저장 방식은 바뀌지 않는다. 배포 후 기존 행도 같은 규칙으로 표시된다.

## 배경 (현재 코드)
- 엔티티·DTO 시각은 `LocalDateTime`이고 Jackson이 시간대 없이 직렬화한다 (`2026-10-09T09:09:30.682189`).
- 운영 서버(`Etc/UTC`)와 PostgreSQL(`Etc/UTC`) 시간대가 UTC라 값의 의미는 UTC다. 다만 코드 어디에도 그 가정이 명시돼 있지 않다.
- 콘솔 약 45곳이 `new Date(문자열)`, `toLocaleString()`으로 표시한다. 시간대 없는 문자열은 브라우저 시간대로 해석되므로 KST 브라우저에서 9시간 어긋난다. `formatTimeAgo`는 다른 방식으로 보정한다. #78의 Deployments 화면만 UTC로 해석한다.
- CLI는 응답 문자열을 그대로 출력한다.

## 범위
- 포함: 백엔드 시각 직렬화(전역 Jackson 설정), 애플리케이션 시간대 UTC 고정, 콘솔 공통 시각 함수(`frontend/lib/time.ts`)와 모든 표시 지점 교체, CLI 표 출력의 KST 표시, architecture.md §6 공통 계약, CLI_REFERENCE.md
- 제외: DB·서버 시간대 변경, 사용자별 시간대 설정, 요청 본문의 시각 입력 형식 변경(현재 시각을 입력받는 API 없음)

## 입출력·계약
- API: 응답의 모든 `LocalDateTime` 필드 → `2026-10-09T09:09:30.682Z` (UTC, `Z`). 필드 이름·개수는 그대로다.
- CLI: 표 출력의 시각은 `2026-10-09 18:09:30 KST`. `--json`은 API 값 그대로 (`Z`). exit code 변경 없음.
- 저장: 변경 없음 (마이그레이션 없음)

## 권한·안전
- 변경 없음. 비밀값 없음.

## 제약
- 참조: [architecture](../../architecture.md) §6, [CLI_REFERENCE](../../CLI_REFERENCE.md)
- 직렬화 형식 변경은 API 응답 계약 변경이다. 콘솔·CLI를 같은 PR에서 함께 바꾼다.
- #78(콘솔 Deployments 화면)이 먼저 머지된 뒤 그 위에서 구현한다 (같은 파일을 바꾼다).

## 예외·경계 상황
| 상황 | 결과 |
|---|---|
| 시각이 null | 화면 `-`, CLI `-` (지금과 같음) |
| 이전 형식(시간대 없음) 문자열이 들어옴 | 공통 함수는 UTC로 해석한다 (배포 중 구버전 응답, 캐시) |
| 브라우저 시간대가 KST가 아님 | 그래도 KST로 표시한다 |

## 미결 질문 (Open)
- 없음

## 결정 기록 (Decisions)
- 2026-10-09 방식 → DB는 UTC로 두고, API가 시간대 포함 ISO 8601로 내보내며, 콘솔·CLI가 KST로 표시한다. (사용자 결정. 서버·DB를 KST로 바꾸면 기존 행과 타임아웃·만료 계산에 영향이 커서 제외)
- 기본값 가정: API 오프셋은 `+09:00`이 아니라 `Z`(UTC)로 보낸다. 저장 값과 같고, 표시 시간대는 클라이언트가 정한다.
- ~~기본값 가정: 애플리케이션 시작 시 기본 시간대를 UTC로 고정하고 Hibernate `jdbc.time_zone`도 UTC로 둔다.~~ → 2026-10-09 (구현 중) `BackendApplication.main`에서 기본 시간대만 UTC로 고정한다. JVM 기본 시간대가 UTC면 JDBC 변환도 UTC라 `jdbc.time_zone`은 따로 두지 않았다. 테스트 JVM도 `user.timezone=UTC`로 실행한다(`build.gradle`). 운영 서버가 이미 UTC라 저장 값은 바뀌지 않는다.
- 2026-10-09 (구현 중) Spring MVC 응답은 Jackson 3(`tools.jackson`)으로 직렬화되므로 `JsonMapperBuilderCustomizer`로 `LocalDateTime` 직렬화기를 등록했다. Jackson 2 `ObjectMapper` 빈(Swagger, WebSocket)은 시각을 직렬화하지 않는다(WebSocket 시각은 이미 `Instant`). (사실 확인)
- 2026-10-09 (구현 중) CLI `deployments export` 파일은 기계용 데이터라 API 값(UTC)을 그대로 둔다. 표 출력(`history`, `deployments list/get`)만 KST로 표시한다. (구현 재량)
- 기본값 가정: 화면 표시 형식은 절대 시각 `yyyy-MM-dd HH:mm KST`, 목록의 상대 시각("30m ago")은 유지한다.

## 완료 증거
| 증명할 것 | 방법 | 환경 | 결과 |
|---|---|---|---|
| 응답 시각이 `Z`가 붙은 UTC | `JacksonTimeConfigTest`, `DeploymentSerializationHttpTest`(배포 생성 응답 `created_at`이 `Z`로 끝나고 현재 시각과 1분 이내) | 로컬 (Testcontainers PostgreSQL 16) | 통과. 백엔드 전체 191개 통과 |
| 서버 시간대와 무관하게 UTC로 저장·응답 | 패키징 JAR를 `TZ=America/New_York`로 기동해 배포 생성, 응답 `created_at`과 실제 UTC 비교 | 로컬 | `2026-10-09T14:57:14.899267Z`, 실제 UTC 14:57:14와 일치 |
| 콘솔 표시 KST | 같은 백엔드에 붙인 브라우저, 배포 상세 | 로컬 | `2026-10-09 23:57 KST` (14:57Z + 9h) |
| 공통 함수가 PC 시간대와 무관 | `lib/time.ts`를 `TZ=UTC`·`America/New_York`·`Asia/Seoul`에서 실행(자정 넘김, 시간대 없는 예전 형식, `+09:00` 포함) | 로컬 | 세 시간대 모두 같은 결과 |
| CLI 표 출력 KST | `formatKst` 실행, CLI 로드 | 로컬 | `2026-10-09 18:09:30 KST` |
| 타입·빌드 | `npx tsc --noEmit -p .`, `npm run build` | 로컬 | 통과 |
| 운영 배포 후 표시 | 머지 후 확인 | 운영 | 머지 후 `운영 반영`에 기록 |

## 운영 반영
- (머지 후 기록)

## 회고
- 어긋난 점: 백엔드는 시간대 없는 시각을 보내고, 콘솔은 화면마다 다른 방식(브라우저 시간대, 9시간 보정, UTC 가정)으로 해석했다. 서버가 UTC라는 가정이 코드에 없었다.
- 원인 분류: 공통 구조 없음
- 고친 위치: 응답 형식을 API 계약(architecture §6.1)으로 정하고, 콘솔 시각 처리를 `lib/time.ts` 하나로 모았다.
