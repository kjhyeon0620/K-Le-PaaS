Kubernetes 배포 실패를 진단하는 도구를 시험하려고 해. 실제로 실패하는 배포 시나리오를 설계해줘.

환경: 단일 노드 k3s (CPU 10개, 메모리 8GB). 아래 서비스를 컨테이너로 배포한다. 서비스마다 정상 동작하는 기준 설정이 있다.

배포할 때 바꿀 수 있는 설정은 이것뿐이야:
- image: 이미지 이름·태그
- env: 환경 변수 (이름=값)
- container_port: 컨테이너 포트
- replicas: 레플리카 수
- readiness_probe: HTTP GET 경로와 포트, 또는 null (HTTP 외 probe는 없음)
- cpu_request, memory_request

각 실패의 원인은 반드시 아래 목록 중 하나여야 해:
- IMAGE / image_not_pullable: 레지스트리·이미지가 없거나 닿지 않아 받지 못함
- IMAGE / invalid_image_reference: 이미지 참조 형식이 잘못됨
- ENV_CONFIG / required_env_missing: 앱이 요구하는 env가 없음
- READINESS / probe_protocol_mismatch: probe 프로토콜이 앱과 맞지 않음 (예: HTTP가 아닌 서비스에 HTTP probe)
- READINESS / probe_port_closed: probe가 앱이 열지 않은 포트로 감
- SCHEDULING / insufficient_cpu: 노드 CPU가 요청을 감당하지 못함

요청:
1. 기준 설정에서 설정을 바꿔 실패하게 만드는 시나리오를 24개 써줘. 서비스마다 2~4개, 원인 6종을 고르게.
2. 같은 원인이라도 겉으로 드러나는 모습이 다양하도록 섞어줘. 로그만 봐서는 헷갈리는 경우도 일부 넣어줘.
3. 아래 형식의 JSON 배열 하나로만 답해. changes에 없는 설정은 기준 그대로다.
[{"id": "짧은 id", "service": "서비스 키", "changes": {"image": "...", "env_set": {"이름": "값"}, "env_remove": ["이름"], "container_port": 숫자, "replicas": 숫자, "readiness_probe": {"path": "/...", "port": 숫자} 또는 null, "cpu_request": "...", "memory_request": "..."}, "category": "범주", "root_cause": "구체 원인", "frequency": "상 또는 중 또는 하", "fix": "고치는 방법 한두 문장"}]
changes에는 바꾸는 키만 넣어.

서비스와 기준 설정:
{{BASELINES}}

사내 서비스 명세:
{{INTERNAL_SPECS}}
