# ADR-0005: 플랫폼 자체 배포는 systemd 릴리스와 SSH receiver

## Status
Accepted (PR #44, #45)

## Context
- K-Le-PaaS 자체(backend JAR, Next.js frontend)는 Oracle ARM64 서버에서 기존 systemd 서비스로 운영 중이었다. H2 데이터가 backend 작업 디렉터리에 있다.
- 사용자 앱 배포(Kubernetes)와 플랫폼 배포를 섞지 않고, 수동 배포를 자동화해야 했다.

## Decision
- `main` push에서 ARM64로 빌드한 릴리스 archive(`backend.jar`, standalone frontend, `REVISION`, `SHA256SUMS`)를 SSH로 보낸다.
- 서버에서는 root 소유 receiver가 forced command로 `deploy <sha>`만 받는다. archive를 검증하고, 릴리스를 만들고, `current`를 원자적으로 바꾸고, readiness·revision·frontend marker를 확인한다.
- 실패하면 이전 릴리스로 복구하고, 복구에 성공해도 실패로 보고한다.
- DB 파일은 복사하거나 복원하지 않는다. 무중단은 약속하지 않는다.

## Alternatives
- **플랫폼도 k3s에 배포**: 기존 H2 데이터와 서비스 전환 위험이 크고, 사용자 앱과 장애 영역이 합쳐진다.
- **CI에서 직접 파일 교체·재시작**: 배포 계정 권한이 너무 넓어진다.

## Consequences
- 장점: 배포 계정 권한이 receiver 하나로 제한된다. 릴리스 단위로 추적하고 복구할 수 있다.
- 감수하는 점: 재시작 동안 다운타임이 있다. DB 백업, 비활성 릴리스 정리, 스키마 복구는 운영자 작업이다.

## Revisit When
- 무중단 배포가 필요해질 때
- DB를 외부 PostgreSQL로 옮길 때

## References
- [CICD.md](../CICD.md), [workflows/README.md](../../.github/workflows/README.md)
