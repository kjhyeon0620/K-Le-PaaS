# ADR-0001: 빌드는 프로젝트 CI, 배포는 K-Le-PaaS (외부 이미지 경로)

## Status
Accepted (PR #34 Oracle k3s GHCR 배포 MVP, #42 envFrom)

## Context
- 기존 경로는 GitHub ZIP → NCP Object Storage → Kaniko Job → NCR 빌드였다. 이 경로는 NCP 인프라에 묶여 있고, 언어별 빌드 요구(ARM64, 멀티 스테이지, 테스트)를 플랫폼이 모두 떠안는다.
- Oracle Free Tier(ARM64) k3s에 IoT 앱을 배포해야 했다.
- raw push webhook은 이미지가 만들어지기 전에 도착할 수 있다.

## Decision
- 빌드 전략(`BuildStrategy`)과 배포 대상을 분리한다. `KANIKO`(NCP), `GITHUB_ACTIONS_GHCR`, `PREBUILT_IMAGE`
- 외부 이미지 경로에서는 프로젝트 CI가 이미지를 빌드해 push하고, push가 성공한 뒤 `POST /api/v1/deployments`로 배포를 요청한다. K-Le-PaaS는 이미지 URI 결정, Kubernetes 적용, 상태 기록, 알림을 맡는다.
- 이미지 URI는 요청의 `image_uri`나 설정의 `image_uri_template`(`{owner}`, `{repoName}`, `{commitHash}`, `{shortCommitHash}`)로 정한다. 기본 GHCR 경로를 추측하지 않는다.
- 외부 이미지 저장소에 온 raw push webhook은 무시한다.
- NCP Kaniko 경로는 유지한다.

## Alternatives
- **플랫폼이 모든 빌드를 수행 (Kaniko 확장)**: 언어·아키텍처별 요구를 플랫폼이 떠안고, Oracle ARM64 빌드 인프라가 추가로 필요하다.
- **Docker Compose 대상**: 상태, 스케일, rollout을 Kubernetes 수준으로 다룰 수 없다.

## Consequences
- 장점: 앱별 빌드 요구가 각 저장소 CI로 분리된다. 플랫폼은 배포 정합성과 권한에 집중한다.
- 감수하는 점:
  - CI가 배포 API를 호출할 인증이 필요하다 (저장소별 토큰 → #65 OIDC).
  - 빌드 로그는 K-Le-PaaS가 아니라 각 저장소의 Actions run에 있다.
  - 공통 workflow가 아직 없어 저장소마다 workflow를 작성해야 한다.

## Revisit When
- 여러 앱에서 workflow 중복이 부담이 될 때 (재사용 workflow)
- 빌드 요구가 없는 prebuilt 배포만 남을 때

## References
- [ORACLE_K3S_GHCR_DEPLOYMENT.md](../ORACLE_K3S_GHCR_DEPLOYMENT.md)
- [architecture §3.2](../architecture.md#32-배포)
