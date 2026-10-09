# ADR-0008: 운영 DB를 PostgreSQL로 전환하고 마이그레이션 기준선을 다시 잡는다

## Status
Accepted

## Context
- 운영 DB는 백엔드 작업 디렉터리의 H2 파일 DB였고, 테스트도 H2 in-memory였다. 마이그레이션(V1~V5)은 H2 문법(`enum (...)`)이라 다른 엔진에서 실행되지 않았다.
- 잠금(#52 저장소 행 `PESSIMISTIC_WRITE`), unique, 실행 계획 같은 DB 동작을 운영 엔진이 아닌 H2에서만 검증하고 있었다.
- 실제 서비스 운영 전이라 운영 데이터를 보존할 필요가 없다 (사용자 결정, 2026-10-09).
- ADR-0004는 PostgreSQL 전환을 다시 검토할 조건으로 두었다.

## Decision
- 운영 DB는 PostgreSQL 16을 백엔드와 같은 호스트에 OS 패키지(systemd)로 설치하고 localhost에서만 접속한다. 플랫폼 DB를 사용자 앱 클러스터(k3s)에 두지 않는다.
- 기존 H2 마이그레이션 V1~V5를 삭제하고 PostgreSQL 문법의 `V1__baseline_schema.sql` 하나로 대체한다. 결과 스키마(테이블, 컬럼, nullable, 기본값, unique, FK)는 같다. 운영 DB는 빈 상태에서 이 V1부터 적용한다. `baseline-on-migrate`는 쓰지 않는다.
- H2 데이터는 이전하지 않고 전환 시 삭제한다.
- 테스트는 Testcontainers PostgreSQL을 쓴다. 컨테이너 하나를 테스트 JVM이 공유하고 Spring 컨텍스트마다 빈 데이터베이스를 만든다.
- 접속 정보는 `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` 환경변수로 받는다. 비밀번호는 기본값이 없고 운영 `EnvironmentFile`에만 둔다.
- 이 기준선 다음부터는 ADR-0004 규칙(순차 번호, PR당 1개, 머지된 파일 불변, 이전 릴리스 호환)을 다시 따른다. 머지된 마이그레이션을 바꾼 것은 이번 한 번이다.

## Alternatives
- **H2 유지**: 변경이 없지만 운영 엔진과 다른 동작을 계속 검증하게 되고, 실행 계획 기반 성능 분석을 운영 엔진에서 할 수 없다.
- **기존 V1~V5를 유지하고 PostgreSQL용 위치를 따로 둠**: 이력은 남지만 두 엔진용 마이그레이션을 함께 관리해야 한다. 보존할 데이터가 없어 이점이 없다.
- **같은 호스트의 Docker 컨테이너**: 운영 호스트에 컨테이너 런타임이 없고, 기존 플랫폼 운영 방식(systemd)과 다르다.
- **k3s StatefulSet**: 플랫폼 DB와 사용자 앱이 같은 장애 영역에 들어간다 (ADR-0005와 같은 이유).
- **H2 PostgreSQL 호환 모드로 테스트**: 빠르지만 엔진 차이가 남는다.

## Consequences
- 장점: 운영과 테스트가 같은 엔진이다. 잠금·제약·실행 계획을 운영 엔진에서 검증할 수 있다.
- 감수하는 점: 기존 운영 데이터(사용자, 저장소, CLI 토큰, 배포 이력)가 사라져 재설정이 필요하다. 전환 이전 릴리스로 앱을 롤백하면 빈 H2로 기동된다. 백엔드 테스트에 Docker가 필요하고 느려진다. PostgreSQL은 FK에 인덱스를 자동으로 만들지 않으므로 조회 경로별 인덱스를 마이그레이션으로 관리해야 한다. DB 백업은 배포 job 밖의 운영 절차다.

## Revisit When
- 다중 인스턴스나 관리형 DB가 필요할 때
- 운영 호스트를 옮기거나 DB를 백엔드와 분리해야 할 때

## References
- [spec](../specs/0075-postgresql-migration/spec.md)
- [ADR-0004](0004-flyway-forward-compatible-migrations.md), [ADR-0005](0005-platform-release-systemd-receiver.md)
- [CICD.md](../CICD.md)
