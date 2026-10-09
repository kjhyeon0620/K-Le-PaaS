---
issue: 75
title: 운영 DB를 H2 파일 DB에서 PostgreSQL로 전환
status: done
size: L
type: feat
branch: feat/#75-postgresql-migration
---

# 운영 DB를 H2 파일 DB에서 PostgreSQL로 전환

## 목적 / 성공 조건
- 목적: 운영과 테스트가 같은 DB 엔진(PostgreSQL)에서 동작한다. H2 전용 문법과 H2에서만 통과하는 동작(잠금, unique, 인덱스 계획)에 기대지 않는다. 이후 인덱스 최적화(다음 이슈)를 운영 엔진에서 `EXPLAIN (ANALYZE, BUFFERS)`로 측정할 수 있다.
- 기술적 완료: 빈 PostgreSQL에 백엔드를 기동하면 Flyway가 PostgreSQL 기준선(V1)을 적용하고 `ddl-auto=validate`가 통과하며 readiness가 `UP`이다. 백엔드 테스트가 PostgreSQL에서 통과한다.
- 운영: 접속 정보가 없거나 DB에 연결할 수 없으면 백엔드가 기동에 실패하고, receiver가 readiness 실패로 이전 릴리스로 되돌린다. 운영자는 `journalctl`에서 연결 오류를 본다.

## 범위
- 포함
  - PostgreSQL 기준선 마이그레이션 1개 (기존 V1~V6을 합친 최종 스키마)
  - datasource 설정을 환경변수로 받기, H2 의존성·H2 console 제거
  - 테스트를 PostgreSQL에서 실행 (방식은 Q2)
  - `FlywayMigrationTest`를 새 기준선에 맞게 수정 (H2 baseline-on-migrate 시나리오 제거)
  - 로컬 개발용 PostgreSQL 실행 방법
  - 문서: architecture.md(§1, §7, §8), CICD.md(H2 `data/` 전제, 마이그레이션 검증 절차), README 환경변수, ADR 신규(ADR-0004 일부 대체)
  - 운영 서버에 PostgreSQL 준비와 전환 절차 문서화. 실제 운영 반영은 사용자 승인 후 진행
- 제외
  - 기존 H2 운영 데이터 이전 (사용자 결정: 데이터 보존 불필요)
  - 인덱스 추가와 성능 측정 (다음 이슈, 새 기준선 다음 버전으로)
  - 자동 DB 백업 job, 복제·고가용성, 커넥션 풀 튜닝
  - 애플리케이션 쿼리·엔티티 동작 변경 (타입 매핑을 맞추는 수준의 수정만 허용)

## 입출력·계약
- API, CLI, exit code: 변경 없음
- 저장
  - `db/migration`의 `V1__baseline_schema.sql` ~ `V5__*.sql`을 삭제하고 PostgreSQL 문법의 `V1__baseline_schema.sql` 하나로 대체한다. 결과 스키마는 기존 V5 적용 후 스키마와 같은 테이블·컬럼·nullable·기본값·unique·FK를 가진다.
  - H2 `enum (...)` 컬럼은 `varchar` + `check` 제약으로 옮긴다 (Hibernate의 PostgreSQL `@Enumerated(STRING)` 매핑과 `validate`가 맞는 형태).
  - `command_log.status`는 기존처럼 `varchar(20) default 'UNKNOWN' not null`을 유지한다.
  - `spring.flyway.baseline-on-migrate`, `baseline-version` 제거 (빈 DB에서 시작)
- 설정 (새 키)

  | 환경변수 | 기본값 | 의미 |
  |---|---|---|
  | `DB_URL` | `jdbc:postgresql://localhost:5432/klepaas` | JDBC URL |
  | `DB_USERNAME` | `klepaas` | DB 계정 |
  | `DB_PASSWORD` | 없음 (필수) | DB 비밀번호. 없으면 기동 실패 |

- 상태 전이: 변경 없음

## 권한·안전
- 호출 주체, 토큰 scope, 소유권 검사: 변경 없음
- 비밀값: `DB_PASSWORD`는 운영 `EnvironmentFile`에만 둔다. 코드, 문서, 스펙, CI 로그, PR에 남기지 않는다. receiver는 비밀값을 받지 않는다는 기존 원칙(ADR-0005)을 유지한다.
- 운영 DB는 localhost(또는 사설망)에서만 접속을 허용한다. 외부 포트를 열지 않는다.
- 애플리케이션 DB 계정은 소유 DB 하나에만 권한을 갖고 superuser가 아니다.

## 제약
- 참조: [architecture](../../architecture.md) §1, §7, §8, [ADR-0004](../../adr/0004-flyway-forward-compatible-migrations.md), [ADR-0005](../../adr/0005-platform-release-systemd-receiver.md), [CICD.md](../../CICD.md)
- ADR-0004의 "머지된 마이그레이션은 수정하지 않는다"를 이번 한 번 깬다. 근거는 운영 DB를 빈 상태로 다시 만드는 것이고, 새 ADR에 기록한다. 이후 마이그레이션은 다시 ADR-0004 규칙(순차 번호, 이전 릴리스 호환)을 따른다.
- 진행 중인 #74는 이 이슈 다음에 머지한다. #74는 이 브랜치가 머지된 뒤 rebase하고, 마이그레이션을 `V2__deployment_image_pull_secret_enabled.sql`로 바꾼다 (SQL은 PostgreSQL에서 그대로 실행된다).
- 기존 동시성 보장(#52의 저장소 행 `PESSIMISTIC_WRITE` 잠금, delivery ID unique)은 PostgreSQL에서 다시 검증한다. H2에서 통과했다는 것을 근거로 삼지 않는다.
- Gradle은 같은 작업 트리에서 동시에 실행하지 않는다. 테스트가 띄운 DB 컨테이너는 검증 후 정리한다.

## 예외·경계 상황
| 상황 | 사용자·agent가 보는 것 | 시스템 동작 |
|---|---|---|
| `DB_PASSWORD` 없음 | 백엔드 기동 실패 (`password authentication failed`) | Spring이 해석하지 못한 `${DB_PASSWORD}`를 문자열 그대로 비밀번호로 보내 인증에 실패한다. receiver가 readiness 실패로 이전 릴리스로 롤백. 운영 DB는 `scram-sha-256` 인증을 쓴다(CICD.md) |
| PostgreSQL 중지·연결 불가 | readiness 503, 기동 중이면 기동 실패 | 상태를 추정하지 않고 오류로 드러낸다 |
| 전환 이전 릴리스로 앱 롤백 | 이전 릴리스가 빈 H2 파일 DB로 기동 | H2 파일은 전환 시 삭제했으므로 데이터가 없다(Q4). 운영 절차에 명시 |
| 엔티티 타입과 기준선 불일치 | 기동 시 `validate` 실패 | 머지 전 PostgreSQL 기동 검증으로 막는다 |
| 같은 저장소 동시 배포 요청 | 기존과 같음 (201 / 409) | PostgreSQL 행 잠금으로 직렬화되는지 테스트로 확인 |

## 미결 질문 (Open)
- 없음

## 결정 기록 (Decisions)
- 2026-10-09 운영 데이터 → 이전하지 않는다. (사용자 결정: 실제 서비스 운영 전이라 데이터 보존이 필요 없음)
- 2026-10-09 마이그레이션 이력 → V1~V6을 PostgreSQL 기준선 V1 하나로 합친다. (빈 DB에서 시작하므로 baseline-on-migrate와 H2 문법 파일을 유지할 이유가 없다. ADR로 기록) [승격: ADR 신규]
- 2026-10-09 인덱스 최적화 → 이 이슈에 넣지 않고 다음 이슈에서 기준선 다음 버전으로 추가한다. (전후 측정을 분리하기 위해)
- ~~2026-10-09 #74와의 순서 → #74를 먼저 머지하고 그 V6을 기준선에 포함한다.~~ → 2026-10-09 변경: #74는 아직 PR이 없다. 이 이슈를 먼저 머지하고 #74의 마이그레이션을 V2로 바꿔 rebase한다. (기다리지 않고 진행하기 위해. #74의 V6는 머지·적용된 적이 없어 번호를 바꿔도 된다)
- 2026-10-09 Q1 운영 PostgreSQL → 백엔드와 같은 호스트에 OS 패키지(PostgreSQL 16, systemd)로 설치하고 localhost에서만 접속한다. (운영 호스트 확인 결과 ARM64 Ubuntu 24.04, Docker 없음, 메모리·디스크 여유 충분, apt 후보 16. 기존 systemd 운영 방식과 같고 플랫폼 DB를 사용자 앱 클러스터(k3s)와 분리한다)
- 2026-10-09 Q2 테스트 DB → Testcontainers PostgreSQL, 테스트 전체가 컨테이너 하나를 공유한다. (운영과 같은 엔진으로 검증. CI `ubuntu-24.04-arm` 러너에 Docker 있음)
- 2026-10-09 Q3 로컬 개발 DB → 저장소 루트에 `compose.yaml`(PostgreSQL 서비스 하나)을 추가한다.
- 2026-10-09 Q4 기존 H2 데이터 파일 → 전환 시 삭제한다. 전환 이전 릴리스로 앱을 롤백하면 빈 H2로 기동된다. (사용자 결정: 데이터 보존 불필요)
- 기본값 가정: 전환 후 재설정(GitHub 로그인, 저장소 등록, CLI 토큰 재발급, GitHub Actions 설정의 저장소 ID 갱신)은 운영자가 직접 하고, 절차만 CICD.md에 문서화한다.
- 2026-10-09 (구현 중) 비밀번호 기본값이 없으면 Spring은 해석하지 못한 `${DB_PASSWORD}`를 문자열 그대로 쓴다. 기동 실패는 placeholder 오류가 아니라 DB 인증 실패로 일어난다. 운영 DB가 비밀번호 인증(`scram-sha-256`)이면 기동이 실패하므로 그대로 두고 CICD.md에 적었다. (사실 정정)
- 2026-10-09 (구현 중) H2 전용 수동 마이그레이션 `db/manual/`과 이를 실행하던 테스트 2개(`CommandConfirmationConcurrencyTest.migrationBackfillsLegacyCombinationsAndIsIdempotent`, `CommandLogStatusSchemaValidationTest.migratedStatusColumnValidatesAtStartup`)를 삭제하고, 후자는 PostgreSQL에서 매핑 검증만 하는 `flywaySchemaValidatesAgainstMappings`로 바꿨다. `FlywayMigrationTest`의 baseline-on-migrate 시나리오도 삭제했다. (검증 대상 스크립트와 기능이 없어짐. `command_log.status` 기본값 `UNKNOWN`은 `FlywayMigrationTest`가 계속 검증한다)
- 2026-10-09 (구현 중) 스키마 동일성 증거 → H2를 classpath에서 제거해 두 엔진의 `information_schema`를 비교하지 않고, H2 V1~V5와 PostgreSQL V1의 DDL을 파싱해 비교(변이 주입으로 비교 스크립트 확인)하고 PostgreSQL에서 Hibernate `validate`를 통과시키는 것으로 바꿨다. (완료 증거 정정)
- 2026-10-09 (구현 중) `SecurityConfig`에서 H2 console 전용 설정(`/h2-console/**` permitAll, `X-Frame-Options: SAMEORIGIN`)을 제거했다. `X-Frame-Options`는 Spring Security 기본값 `DENY`가 된다. (구현 재량, 더 엄격한 방향)
- 2026-10-09 (구현 중) 테스트 DB 격리 → `PostgresTestDatabase`(`ApplicationContextInitializer`, `META-INF/spring.factories` 등록)가 컨텍스트마다 새 데이터베이스를 만들고 `spring.test.database.replace=none`으로 `@DataJpaTest`의 내장 DB 교체를 끈다. 기존 HTTP 테스트 4개의 H2 URL 지정을 제거했다. (구현 재량)
- 기본값 가정: 설정 키 이름은 `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`. URL과 계정은 로컬 기본값을 두고 비밀번호는 기본값 없이 필수로 둔다.
- 기본값 가정: PostgreSQL 16 (Ubuntu 24.04 기본 패키지 버전)
- 기본값 가정: Flyway PostgreSQL 지원 모듈(`flyway-database-postgresql`)은 Spring Boot 의존성 관리 버전을 쓴다.

## 완료 증거
| 증명할 것 | 방법 | 환경 | 결과 |
|---|---|---|---|
| 빈 PostgreSQL에 기준선 적용, `validate` 통과 | `FlywayMigrationTest`, `CommandLogStatusSchemaValidationTest`, Spring 컨텍스트 테스트 | 로컬 (Testcontainers `postgres:16-alpine`) | 통과 |
| 기준선 스키마가 기존 스키마(V1~V5)와 같음 | H2 V1~V5와 PostgreSQL V1의 DDL 파싱 비교 (테이블·컬럼·타입·nullable·unique·기본값·enum 값·제약). 값 3개를 바꾼 변이본에서 차이 3건을 잡는지 확인 | 로컬 | 컬럼 115/115, 제약 차이 0. 변이 3건 검출 |
| 기존 백엔드 테스트 전체 통과 (#52 동시성 테스트 포함) | `./gradlew test` 1회 | 로컬 (Docker Desktop, PostgreSQL 16 컨테이너) | 188개 통과, 실패·건너뜀 0. CI는 PR에서 확인 |
| 패키징된 JAR가 PostgreSQL로 기동, readiness `UP` | `bootJar` 후 `compose.yaml` PostgreSQL에 `DB_*`로 기동 (`ddl-auto=validate`) | 로컬 | Flyway V1 적용, `/api/v1/system/ready` 200 `UP`, version revision 일치 |
| `DB_PASSWORD` 없으면 기동 실패 | 같은 JAR를 변수 없이 기동 | 로컬 | exit 1, `password authentication failed` |
| 운영 DB 준비 (localhost 전용, 비밀번호 인증, 접속 정보) | 설정·역할·DB 소유자 조회, `select 1` | 운영 | 확인 |
| 운영 전환 후 readiness·revision 확인, 로그인·저장소 등록 동작 | 배포 workflow 결과와 운영 확인 | 운영 | 머지 후 `운영 반영`에 기록 |

## Tasks (L 등급만)
### T1. PostgreSQL 기준선
- 먼저 읽을 것: 기존 `V1`~`V6`, 엔티티의 `@Enumerated`·`columnDefinition`, `db/manual/command-log-status.sql`
- 변경: `V1__baseline_schema.sql`(PostgreSQL) 작성, 기존 V1~V6 삭제, `application.yaml` Flyway baseline 설정 제거
- 금지: 컬럼·제약의 의미 변경, 인덱스 추가(다음 이슈)
- 검증: 스키마 비교 결과, `validate` 통과

### T2. 설정과 의존성
- 변경: `application.yaml` datasource를 `DB_*` 환경변수로, `application-dev.yaml`의 H2 console 제거, `build.gradle`에서 `h2`·`spring-boot-h2console` 제거와 `flyway-database-postgresql` 추가, 로컬 DB 실행 방법(Q3)
- 금지: 비밀번호 기본값
- 검증: 로컬 기동, 변수 누락 시 기동 실패

### T3. 테스트
- 변경: `application-test.yaml`과 테스트 DB 구성(Q2), `FlywayMigrationTest` 수정(빈 DB 적용 + V9999 후속 버전 적용만 남김)
- 금지: 실패하는 테스트를 지우거나 단언을 약하게 바꾸기. PostgreSQL에서 다르게 동작하는 테스트는 원인을 스펙에 기록하고 고친다
- 검증: `./gradlew test` 전체 1회

### T4. 문서와 ADR
- 변경: ADR 신규(PostgreSQL 전환과 기준선 재설정, ADR-0004 일부 대체), ADR-0004 Status에 링크, architecture.md §1·§7·§8, CICD.md(H2 `data/` 전제, 마이그레이션 검증 절차, PostgreSQL 준비·전환·백업 절차), README 환경변수
- 금지: 운영 호스트 정보, 비밀번호
- 검증: 문서 동기화 표 확인

### T5. 운영 전환 (사용자 승인 후)
- 절차: 운영 PostgreSQL 16 설치(apt, localhost 전용) → DB·계정 생성 → backend `EnvironmentFile`에 `DB_*` 추가 → 머지·배포 → 기존 H2 파일 삭제 → readiness·revision 확인 → 재설정(결정 기록의 기본값 가정)
- 금지: 사용자 명시 요청 없이 운영 서버·Secret 변경
- 검증: 배포 workflow 결과, 운영 확인 결과를 `운영 반영`에 기록 (원자료는 `.local/evidence/`)

## 운영 반영
- 2026-10-09 머지 전 준비 (사용자 수행, 에이전트가 읽기 전용으로 확인): 운영 호스트에 PostgreSQL 16 OS 패키지 설치. `listen_addresses` 기본값(localhost)이고 5432는 127.0.0.1에서만 열려 있음. host 접속은 `scram-sha-256`. `klepaas` 역할(superuser 아님)과 그 역할이 소유한 `klepaas` DB 생성. 백엔드 `EnvironmentFile`에 `DB_URL`·`DB_USERNAME`·`DB_PASSWORD` 3개 추가. 비밀번호로 `select 1` 접속 확인.
- 2026-10-09 PR #76 머지(`88991d5`). Build and deploy workflow의 build(Testcontainers 테스트 포함)·deploy 성공. 운영 readiness `UP`, revision 일치, 운영 PostgreSQL의 Flyway 이력 `1 baseline schema` 성공.
- 2026-10-09 H2 `data/` 삭제(사용자 수행). GitHub 로그인과 IoT 저장소 재등록(새 저장소 ID 1로 기존과 같아 GitHub Actions 설정 변경 없음), 배포 설정은 운영 중인 k3s Deployment 값으로 API를 통해 다시 입력.
- 2026-10-09 재설정 후 IoT 배포 workflow 재실행: OIDC 인증·배포 접수·기록은 정상, rollout은 `FAILED`. 원인은 이 전환과 무관한 만료된 `ghcr-pull-secret`(공개 이미지에도 인증 실패, 10월 3일 배포부터 같은 상태)로 #74에서 다룬다.
- 범위 밖 발견: 콘솔 저장소 카드의 Configure 버튼이 "곧 구현될 예정" 알림만 띄운다 (architecture.md §9에 기록).

## 회고
- 어긋난 점: 이 이슈의 출발점이었던 인덱스 최적화 요청이 운영 DB를 PostgreSQL로 전제했지만 실제 운영은 H2였다. 비밀번호 누락 시 실패 방식(placeholder 오류가 아닌 인증 실패)도 스펙 전제와 달랐다.
- 원인 분류: 외부 제약 미인지 (운영 엔진이 문서에는 있었지만 작업 요청에 전달되지 않음)
- 고친 위치: 운영과 테스트를 같은 엔진으로 맞추고(ADR-0008), 실패 방식은 CICD.md와 예외 표에 기록했다.
