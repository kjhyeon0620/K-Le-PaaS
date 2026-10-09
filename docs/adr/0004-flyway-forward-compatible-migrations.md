# ADR-0004: Flyway와 이전 릴리스 호환 마이그레이션

## Status
Accepted (#48 PR #62, 운영 DB V1 baseline 등록). 기준선과 H2 baseline 등록은 [ADR-0008](0008-postgresql-production-database.md)로 대체. 나머지 규칙은 유지

## Context
- 운영은 `ddl-auto=validate`로 기동한다. 스키마 변경을 수동 SQL로 적용해 왔고, 컬럼 추가가 반복될 예정이었다 (#49 이후).
- 앱 롤백(ADR-0005)은 DB를 되돌리지 않는다.

## Decision
- Flyway를 도입하고 기존 운영 DB는 baseline(V1)으로 등록한다.
- 마이그레이션은 PR당 최대 1개, 순차 번호, 머지된 파일은 수정하지 않는다.
- 새 컬럼은 nullable이거나 기본값을 둔다. 이전 릴리스 JAR가 롤백된 상태에서도 테이블을 읽고 쓸 수 있어야 한다.
- 머지 전에 운영 DB 사본에서 패키징된 JAR로 기동을 검증한다.
- PostgreSQL 전환과 묶지 않는다.

## Alternatives
- **Hibernate `ddl-auto=update`**: 변경 이력과 검증이 없다.
- **PostgreSQL 전환과 동시 도입**: 변경 범위가 커져 분리했다.

## Consequences
- 장점: 스키마 변경 이력과 적용 순서가 보장된다. 기동 시 자동 적용 후 validate로 불일치를 잡는다.
- 감수하는 점: 마이그레이션은 되돌리지 않는다. 파괴적 변경(컬럼 삭제, 타입 축소)은 여러 릴리스에 나눠야 한다. DB 백업은 배포 job 밖의 운영 절차다.

## Revisit When
- PostgreSQL로 전환할 때
- 다중 인스턴스로 확장할 때

## References
- [CICD.md "Writing schema migrations"](../CICD.md)
