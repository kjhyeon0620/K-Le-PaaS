# 실행 중이지만 Ready가 되지 않을 때 (readiness probe 실패)

## 증상
- Pod는 Running이지만 Ready가 아니고, rollout이 타임아웃으로 실패한다.
- 이벤트에 `Readiness probe failed`가 반복된다.

## 원인 구분
- `connection refused`: probe가 가리키는 포트에서 앱이 듣지 않는다. 앱 로그의 listen 포트와 probe 포트를 비교한다 (probe_port_closed).
- HTTP probe인데 `connection reset`, `malformed HTTP response`, 응답이 HTTP가 아니면 TCP 전용 서비스에 HTTP probe를 건 것이다 (probe_protocol_mismatch).
- HTTP 404·500 응답: 경로가 틀렸거나 앱의 헬스 엔드포인트가 실패한다.
- 기동이 느린 앱이면 startup probe 허용 시간이 부족할 수 있다.

## 확인할 것
- 적용 설정의 health_probe(경로·포트)와 container_port
- 앱이 실제로 여는 포트와 프로토콜 (앱 문서나 기동 로그)
