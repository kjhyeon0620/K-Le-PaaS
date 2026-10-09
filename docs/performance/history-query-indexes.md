# 명령·배포 이력 조회 인덱스 전후 측정 (#79)

PostgreSQL 기준선([ADR-0008](../adr/0008-postgresql-production-database.md))에는 보조 인덱스가 없다. PostgreSQL은 외래키에 인덱스를 자동으로 만들지 않는다. 이력 조회 쿼리 세 개의 실제 SQL을 확인하고, 같은 데이터에서 `V3__history_query_indexes.sql` 적용 전후를 측정했다.

## 대상 쿼리

Hibernate가 생성한 SQL을 테스트에서 출력해 확인했다 (2026-10-09). 두 API 모두 기본값이 `size=20`, `created_at DESC`이다.

| 쿼리 | 호출 경로 | SQL 요지 |
|---|---|---|
| Q1 `CommandLogRepository.findByUserId` | `GET /api/v1/nlp/history` | `where user_id=? order by created_at desc offset ? fetch first 20` + `count(*) where user_id=?` |
| Q2 `DeploymentRepository.findBySourceRepositoryId` | `GET /api/v1/deployments?repository_id=`, 자연어 배포 조회(정렬 없이 10건) | `deployments left join repositories where repository_id=? order by created_at desc ...` + `count(*)` |
| Q3 `DeploymentRepository.findBySourceRepositoryUserId` | 호출하는 코드 없음 | `repositories`를 두 번 조인해 `users.id=?`로 거른 뒤 `created_at desc` 정렬 |

## 추가한 인덱스

```sql
create index idx_command_log_user_id_created_at on command_log (user_id, created_at desc);
create index idx_deployments_repository_id_created_at on deployments (repository_id, created_at desc);
```

필터 컬럼 다음에 정렬 컬럼을 두어 한 인덱스로 필터, 정렬, 첫 페이지 조기 종료, count를 처리한다. Q3은 호출이 없어 전용 인덱스를 만들지 않았다.

## 측정 조건

| 항목 | 값 |
|---|---|
| 환경 | 로컬 Docker `postgres:16-alpine` (PostgreSQL 16.15, 운영과 같은 메이저 버전), Apple Silicon(arm64) Mac, Docker CPU 10·메모리 약 7.7 GiB, PostgreSQL 기본 설정(`shared_buffers` 128MB) |
| 스키마 | 저장소의 V1~V2를 적용한 뒤 시드, 측정 후 V3 적용 |
| 데이터 | `users` 1,000 · `repositories` 3,000 · `command_log` 1,000,000 · `deployments` 300,000. 행이 작은 id에 몰리게 생성 (`random()^k`), `created_at`은 최근 1년에 분산, `setseed(0.42)`로 재현 가능 |
| 측정 대상 | 행이 가장 많은 사용자(user 1, 명령 100,040행)와 중앙값 사용자(user 493, 530행), 행이 가장 많은 저장소(repo 1, 배포 20,893행)와 중앙값 저장소(repo 1304, 54행) |
| 반복 | 쿼리마다 워밍업 1회 후 `EXPLAIN (ANALYZE, BUFFERS)` 10회. `Execution Time`의 중앙값과 범위 |
| 재현 | `scripts/db/history-index-benchmark.sh` (시드: `scripts/db/seed-history.sql`) |

## 결과 (시드 후 `VACUUM ANALYZE`)

| 쿼리 | 전: 실행 계획 | 전: 중앙값 (범위) | 후: 실행 계획 | 후: 중앙값 (범위) | 버퍼 (전 → 후) |
|---|---|---|---|---|---|
| Q1 첫 페이지, 무거운 사용자 | Parallel Seq Scan → Sort | 21.239ms (20.544–22.581) | Index Scan | 0.072ms (0.064–0.076) | 21,810 → 26 |
| Q1 count, 무거운 사용자 | Parallel Seq Scan | 17.235ms (17.031–20.028) | Index Only Scan (Heap Fetches 0) | 6.241ms (6.136–6.402) | 21,738 → 390 |
| Q1 첫 페이지, 중앙값 사용자 | Parallel Seq Scan → Sort | 16.418ms (15.360–17.697) | Index Scan | 0.062ms (0.055–0.064) | 21,810 → 26 |
| Q1 count, 중앙값 사용자 | Parallel Seq Scan | 16.223ms (15.433–18.715) | Index Only Scan | 0.090ms (0.084–0.098) | - |
| Q2 첫 페이지, 무거운 저장소 | Seq Scan → Sort | 14.671ms (14.539–15.701) | Index Scan | 0.105ms (0.100–0.116) | 7,327 → 29 |
| Q2 count, 무거운 저장소 | Parallel Seq Scan | 6.633ms (6.439–8.621) | Index Only Scan | 1.413ms (1.326–3.470) | 7,318 → 87 |
| Q2 첫 페이지, 중앙값 저장소 | Parallel Seq Scan → Sort | 6.455ms (6.121–6.944) | Index Scan | 0.116ms (0.107–0.123) | 7,396 → 30 |
| Q2 count, 중앙값 저장소 | Parallel Seq Scan | 6.146ms (6.004–6.438) | Index Only Scan | 0.056ms (0.051–0.067) | - |
| Q2 정렬 없음 10건, 무거운 저장소 | Seq Scan (10건 찾고 종료) | 0.062ms | 변화 없음 (Seq Scan) | 0.062ms | - |
| Q2 정렬 없음 10건, 중앙값 저장소 | Seq Scan | 1.337ms (0.792–3.154) | Index Scan | 0.101ms (0.094–0.139) | - |
| Q3 첫 페이지 (호출 없음) | Hash Join → Sort | 12.043ms (11.803–12.299) | Bitmap Index Scan → Sort | 7.482ms (7.368–7.852) | 7,622 → 7,255 |

버퍼는 첫 측정 실행의 `shared hit + read` 블록 수(8KB)다. 전 측정의 `command_log` 스캔은 테이블이 `shared_buffers`보다 커서 매번 일부를 OS 캐시에서 읽었다(`read`).

## VACUUM 전 측정에서 count가 느려진 경우

처음에는 시드 직후 `ANALYZE`만 하고 측정했다. 첫 페이지 조회는 위와 같이 줄었지만, **무거운 사용자의 count는 18.829ms → 51.774ms로 오히려 늘었다.** 실행 계획이 `Bitmap Heap Scan`이었고 버퍼가 21,935블록이었다. 대량 적재 직후 VACUUM이 한 번도 돌지 않아 visibility map이 비어 있으면 인덱스만으로 행의 가시성을 판단할 수 없어 힙을 다시 읽는다. `VACUUM ANALYZE` 후 같은 쿼리는 `Index Only Scan`(Heap Fetches 0), 390블록, 6.241ms였다. 운영은 autovacuum이 visibility map을 유지하므로 위 표를 기준으로 삼고, 대량 적재 직후에는 count가 느려질 수 있다는 점을 기록해 둔다. 원자료: 로컬 `.local/evidence/0079-history-query-indexes/2026-10-09-no-vacuum/`.

## 해석과 한계

- 로컬 단일 컨테이너, 동시 요청 없음, 캐시가 데워진 상태의 **쿼리 단독 실행 시간**이다. API 응답 시간이나 운영 처리량 개선으로 확대하지 않는다.
- 운영 DB의 실제 행 수는 아직 적다(2026-10-09 기준 배포 수 건). 이 측정은 이력이 쌓였을 때의 쿼리 계획을 미리 확인한 것이다.
- 분포는 합성 데이터다. 실제 사용자별 분포가 다르면 플래너 선택과 수치가 달라질 수 있다.
- count는 행 수에 비례한다. 행이 10만 개인 사용자의 count는 인덱스 후에도 6ms대이고, 페이지 수를 정확히 세는 대신 다음 페이지 존재만 확인하는 방식(`Slice`)은 API 응답 계약을 바꾸므로 이번 범위에서 제외했다.
- Q3은 호출이 없어 전용 인덱스를 만들지 않았다. 일부 개선은 저장소 인덱스 덕분이고, 정렬은 남는다.
- 인덱스 생성은 `CREATE INDEX`(쓰기 잠금)다. 운영 행 수가 적어 문제없지만, 큰 테이블이면 Flyway 트랜잭션 밖의 `CREATE INDEX CONCURRENTLY`를 검토해야 한다.
- 쓰기 비용(인덱스 유지)은 측정하지 않았다.
