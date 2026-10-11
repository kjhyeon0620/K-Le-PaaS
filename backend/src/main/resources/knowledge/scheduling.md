# Pod가 노드에 배치되지 않을 때 (FailedScheduling)

## 증상
- Pod가 Pending이고 컨테이너가 생성되지 않는다.
- 이벤트에 `FailedScheduling`과 `Insufficient cpu` 또는 `Insufficient memory`가 있다.

## 원인 구분
- `Insufficient cpu`: CPU 요청(requests)이 노드의 남은 용량보다 크다 (insufficient_cpu). 직전 성공 배포 대비 CPU 요청이나 레플리카 수가 늘었는지 본다.
- `Insufficient memory`: 메모리 요청이 남은 용량보다 크다.
- `node(s) had untolerated taint`, `didn't match node selector`: 배치 규칙 문제다.

## 확인할 것
- 적용 설정의 resources(requests)와 레플리카 수
- 같은 노드의 다른 앱 사용량
