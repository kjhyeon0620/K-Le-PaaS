# ADR-0006: 리소스 접근은 namespace·저장소 라벨·운영자 허용 참조로 제한

## Status
Accepted (여러 프로젝트 배포 2단계, PR #44에서 운영 전환)

## Context
- 여러 사용자의 앱이 같은 클러스터와 namespace에서 실행된다. 이름만으로 Pod, 로그, Secret 참조에 접근할 수 있으면 다른 사용자의 앱을 조회하거나 Secret을 끌어다 쓸 수 있다.

## Decision
- 사용자는 설정된 `kubernetes.namespace` 안에서, 본인 저장소의 `klepaas.io/repository-id` 라벨이 붙은 리소스만 조회하고 변경한다. 다른 namespace 요청은 Kubernetes 호출 전에 거부한다.
- ConfigMap, Secret, image pull secret은 운영자가 서버 설정에서 저장소별로 허용한 이름만 참조할 수 있다. namespace에 존재한다는 것만으로는 허용하지 않는다. Secret 값은 API로 다루지 않는다.
- API, CLI, 자연어가 같은 소유권 검사를 쓴다.

## Alternatives
- **사용자별 namespace**: 격리는 강하지만 기존 IoT 배포 전환과 RBAC 구성이 필요하다. 후속 단계로 둔다.

## Consequences
- 장점: 다른 사용자의 리소스 접근과 Secret 참조가 차단된다.
- 감수하는 점:
  - 라벨이 없는 기존 리소스는 운영자가 검토해 라벨을 붙인 뒤에만 관리할 수 있다.
  - 허용 참조를 바꾸려면 서버 설정 변경과 재시작이 필요하다.

## Revisit When
- 사용자·환경별 namespace 분리가 필요해질 때

## References
- [STAGE2_RESOURCE_ACCESS.md](../STAGE2_RESOURCE_ACCESS.md)
