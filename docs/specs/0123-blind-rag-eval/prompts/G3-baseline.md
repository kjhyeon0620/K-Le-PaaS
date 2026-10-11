아래 서비스들을 단일 노드 k3s(CPU 10개, 메모리 8GB)에 각각 컨테이너로 배포하려고 해.

배포할 때 정할 수 있는 설정은 이것뿐이야:
- image: 이미지 이름·태그
- env: 환경 변수 (이름=값)
- container_port: 컨테이너 포트
- replicas: 레플리카 수
- readiness_probe: HTTP GET 경로와 포트, 또는 null (HTTP 외의 probe는 쓸 수 없으니 HTTP 헬스 엔드포인트가 없는 서비스는 null)
- cpu_request, memory_request: 예 "100m", "128Mi"

서비스마다 정상 기동해서 Ready가 되는 최소 설정을 정해줘. 공개 이미지는 공식 이미지의 실제 동작 기준으로.
아래 형식의 JSON 하나로만 답해:
{"서비스 키": {"image": "...", "env": {"이름": "값"}, "container_port": 숫자, "replicas": 1, "readiness_probe": {"path": "/...", "port": 숫자} 또는 null, "cpu_request": "...", "memory_request": "..."}}

서비스 (키: 설명):
{{SERVICES}}
