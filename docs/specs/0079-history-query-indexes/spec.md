---
issue: 79
title: 명령·배포 이력 조회 인덱스 추가와 실행 계획 전후 측정
status: done
size: M
type: feat
branch: feat/#79-history-query-indexes
---

# 명령·배포 이력 조회 인덱스 추가와 실행 계획 전후 측정

## 목적 / 성공 조건
- 목적: 사용자별 명령 이력과 저장소별 배포 이력 조회가 행 수에 비례한 전체 스캔·정렬 없이 인덱스로 처리된다.
- 기술적 완료: Flyway `V3`가 PostgreSQL 기준선 위에 복합 인덱스를 추가하고 `ddl-auto=validate`가 통과한다. 같은 데이터·같은 쿼리로 측정한 전후 실행 계획과 실행 시간이 문서에 남는다.
- 운영: 운영 데이터가 적어 인덱스 생성은 즉시 끝난다. 측정은 로컬이며 운영 성능 개선 수치로 주장하지 않는다.

## 대상 쿼리 (Hibernate가 생성한 실제 SQL, 2026-10-09 확인)
| 쿼리 | 호출 경로 | SQL 요지 |
|---|---|---|
| Q1 `CommandLogRepository.findByUserId` | `GET /api/v1/nlp/history` (기본 `size=20`, `created_at DESC`) | `where user_id=? order by created_at desc offset ? fetch first ?` + `select count(*) ... where user_id=?` |
| Q2 `DeploymentRepository.findBySourceRepositoryId` | `GET /api/v1/deployments?repository_id=` (기본 `size=20`, `created_at DESC`), 자연어 배포 조회(정렬 없음, 10건) | `deployments left join repositories ... where d.repository_id=? order by d.created_at desc ...` + `count(*)` |
| Q3 `DeploymentRepository.findBySourceRepositoryUserId` | **호출하는 코드 없음** | `repositories`를 두 번 조인하고 `users.id=?`로 거른 뒤 `created_at desc` 정렬 |

## 범위
- 포함: 시드 스크립트·측정 스크립트(`scripts/db/`), `V3` 인덱스 마이그레이션, 측정 결과 문서(`docs/performance/history-query-indexes.md`), #74·#75 스펙의 운영 반영 기록, architecture.md §9 갱신
- 제외: 쿼리·엔티티 변경, Q3 삭제(호출이 없어 별도 이슈 후보), 운영 DB 측정, 커넥션 풀·설정 튜닝, count를 `Slice`로 바꾸는 API 계약 변경

## 입출력·계약
- API·CLI: 변경 없음
- 저장: `V3__history_query_indexes.sql`
  - `command_log (user_id, created_at desc)`
  - `deployments (repository_id, created_at desc)`
  - 기존 행·이전 릴리스에 영향 없음 (인덱스만 추가)

## 권한·안전
- 변경 없음. 시드 데이터는 가짜 값이고 로컬 컨테이너에만 넣는다. 운영 DB에 시드 스크립트를 실행하지 않는다.

## 제약
- 참조: [ADR-0004](../../adr/0004-flyway-forward-compatible-migrations.md), [ADR-0008](../../adr/0008-postgresql-production-database.md)
- 측정은 같은 컨테이너·같은 데이터에서 인덱스만 바꿔 비교한다. 첫 실행은 버리고 반복 측정의 중앙값과 범위를 적는다.
- 수치는 실제 측정값만 쓴다. 측정 환경과 한계를 함께 적는다.

## 예외·경계 상황
| 상황 | 결과 | 비고 |
|---|---|---|
| 운영 적용 | 인덱스 생성 | 운영 행 수가 적어 잠금 시간 무시 가능. 큰 테이블이면 `CREATE INDEX CONCURRENTLY`가 필요하지만 Flyway 트랜잭션과 맞지 않아 이번에는 쓰지 않는다 |
| 정렬 없는 Q2(자연어 경로) | 같은 인덱스로 `repository_id` 범위 스캔 | - |
| Q3 | 이번 인덱스로 일부만 개선될 수 있음 | 호출이 없어 전용 인덱스를 만들지 않는다 |

## 미결 질문 (Open)
- 없음

## 결정 기록 (Decisions)
- 2026-10-09 시드 규모 → `users` 1,000, `repositories` 3,000, `command_log` 1,000,000, `deployments` 300,000. 사용자·저장소별 행 수는 치우친 분포(소수 사용자·저장소에 많은 행)로 만든다. `created_at`은 최근 1년에 분산한다. (사용자 요청: command_log 100만, deployments 수십만, 사용자·저장소 여러 개)
- 2026-10-09 정렬 컬럼 → 실제 SQL과 컨트롤러 기본값이 모두 `created_at DESC`라 `(fk, created_at desc)`로 만든다.
- 2026-10-09 Q3 → 호출이 없어 전용 인덱스를 만들지 않고 측정만 한다. 삭제는 범위 밖, 별도 이슈 후보.
- 2026-10-09 (구현 중) 시드 직후 `ANALYZE`만 한 첫 측정에서 무거운 사용자의 count가 인덱스 후 18.829ms → 51.774ms로 늘었다(Bitmap Heap Scan). visibility map이 비어 Index Only Scan을 못 쓴 것으로 보고, 시드 끝에 `VACUUM ANALYZE`를 넣어 다시 측정했다(Index Only Scan, Heap Fetches 0, 6.241ms). autovacuum이 도는 운영 상태에 맞춘 조건이다. 첫 측정은 문서에 함께 기록한다. (완료 증거 보강)
- 2026-10-09 (구현 중) 정렬 없는 Q2(자연어 경로)는 무거운 저장소에서 인덱스 전에도 Seq Scan으로 10건을 찾고 바로 끝나 변화가 없었다(0.062ms). 문서에 그대로 적었다. (사실 기록)
- 기본값 가정: 측정 환경은 로컬 Docker `postgres:16-alpine`(운영과 같은 메이저 버전), 쿼리당 1회 워밍업 후 10회 측정.

## 완료 증거
| 증명할 것 | 방법 | 환경 | 결과 |
|---|---|---|---|
| 인덱스 전후 실행 계획·시간 | `scripts/db/history-index-benchmark.sh` (워밍업 1회 + 10회) | 로컬 Docker PostgreSQL 16.15 | [결과 문서](../../performance/history-query-indexes.md). 예: 무거운 사용자 명령 이력 첫 페이지 Parallel Seq Scan+Sort 21.239ms → Index Scan 0.072ms, 버퍼 21,810 → 26 |
| V3가 기준선 위에 적용, validate 통과 | `FlywayMigrationTest`, `CommandLogStatusSchemaValidationTest` | 로컬 (Testcontainers PostgreSQL 16) | 4개 통과 (V1~V3 적용) |
| 운영 V3 적용 | 머지 후 배포 결과 | 운영 | 머지 후 `운영 반영`에 기록 |

## 운영 반영
- (머지 후 기록)

## 회고
- 어긋난 점: 대량 적재 직후 측정에서 count가 인덱스 후 더 느려졌다. 측정 조건(VACUUM 여부)이 결과를 뒤집을 수 있었다. 대상 쿼리 중 하나(Q3)는 호출하는 코드가 없었다.
- 원인 분류: 완료 기준 느슨 (측정 데이터의 상태 조건을 정하지 않음)
- 고친 위치: 시드 스크립트에 `VACUUM ANALYZE`를 넣고, 두 조건의 결과를 문서에 함께 남겼다. Q3 삭제는 범위 밖 이슈 후보로 남긴다.
