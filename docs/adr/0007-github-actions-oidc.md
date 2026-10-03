# ADR-0007: GitHub Actions 배포는 OIDC 토큰으로 인증

## Status
Accepted (#65)

## Context
- #50의 배포 전용 토큰은 권한을 줄였지만, 저장소마다 장기 토큰을 GitHub Secret에 넣고 만료 전에 재발급해야 한다. 만료를 놓치면 자동 배포가 401로 멈춘다.
- GitHub Actions는 실행마다 GitHub이 서명한 단기 OIDC 토큰(`repository`, `ref`, `sha` 등 claim)을 발급한다.
- 같은 GitHub 저장소를 여러 K-Le-PaaS 사용자가 등록할 수 있다.

## Decision
- `POST /api/v1/deployments`는 GitHub Actions OIDC 토큰을 받는다. 서명(GitHub JWKS), `iss`, `aud`(설정, 기본 `k-le-paas`), `exp`, 허용 `ref`(설정, 기본 `refs/heads/main`)를 검증한다.
- 대상 저장소는 요청의 `repository_id`로 정하고, 등록 저장소의 `owner/repo`가 토큰 `repository`와 같을 때만 그 소유자의 배포로 만든다. `branch_name`과 `commit_hash`도 토큰의 `ref`, `sha`와 같아야 한다.
- OIDC 인증 주체는 배포 생성만 할 수 있다. 사용자 계정이 없으므로 다른 API는 403이다.
- JWKS 조회·캐시·서명 검증은 `spring-security-oauth2-jose`(Nimbus)를 쓴다.
- 배포 전용 토큰(ADR-0003 `DEPLOY`)은 GitHub Actions 밖의 CI용으로 유지한다.

## Alternatives
- **배포 전용 토큰 유지**: 저장소마다 Secret과 재발급이 남는다.
- **토큰 claim의 저장소 이름만으로 대상 선택**: 같은 저장소를 여러 사용자가 등록하면 대상이 모호해진다.
- **jjwt와 직접 만든 JWKS 캐시**: 키 교체·캐시·시간 제한을 직접 구현해야 해서 채택하지 않았다.

## Consequences
- 장점: 워크플로에 K-Le-PaaS 비밀값이 필요 없고, 토큰은 실행마다 새로 발급돼 몇 분 안에 만료된다. 배포 기록의 커밋이 실제 실행한 워크플로 커밋과 같다.
- 감수하는 점: 허용 브랜치는 전역 설정이라 저장소별로 다르게 둘 수 없다. 유효 기간 안의 토큰 재사용은 막지 않는다(#52의 중복 규칙만 적용). GitHub JWKS를 조회할 수 없으면 OIDC 배포가 401이 된다.

## Revisit When
- 저장소별·환경별(dev/prod) 허용 브랜치가 필요할 때
- GitHub 외 CI의 OIDC를 받을 때

## References
- [spec 0065](../specs/0065-github-actions-oidc/spec.md)
- [ORACLE_K3S_GHCR_DEPLOYMENT.md "CI Deployment Authentication"](../ORACLE_K3S_GHCR_DEPLOYMENT.md)
