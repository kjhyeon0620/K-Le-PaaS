# CLI Reference

> 구현 상태 기준 문서

---

## 실행 방법

```bash
cd frontend
npm run cli -- --help
```

설치형으로 쓰려면:

```bash
cd frontend
npm link
klepaas --help
```

---

## 공통 옵션

- `--profile <name>`: 저장할 인증 프로필 선택
- `--base-url <url>`: API base URL override
- `--json`: 구조화된 JSON 출력
- `--quiet`: 성공 메시지 최소화

기본 API URL은 `http://localhost:8080`이다.

---

## 인증

```bash
klepaas auth login --token <access-token> [--refresh-token <refresh-token>]
klepaas auth login --web
klepaas auth login --code <oauth-code> [--redirect-uri <uri>]
klepaas auth whoami
klepaas auth logout
```

- `--web`는 브라우저 기반 OAuth 흐름을 시도한다.
- `--code`는 브라우저에서 받은 OAuth code를 직접 교환할 때 사용한다.
- 토큰은 XDG config 경로 또는 `~/.config/klepaas/config.json`에 저장된다.

---

## 운영 명령

```bash
klepaas ask "staging nginx 상태 보여줘"
klepaas confirm 123 --yes
klepaas history --page 0 --size 20
```

```bash
klepaas deployments list --repository-id 1
klepaas deployments get 42
klepaas deployments restart 42
klepaas deployments scale 42 --replicas 3
```

---

## 비용 명령

```bash
klepaas cost plan --file docs/examples/cli-cost-spec.json
klepaas cost diff --file docs/examples/cli-cost-spec.json
klepaas cost explain --file docs/examples/cli-cost-spec.json
klepaas cost check --file docs/examples/cli-cost-spec.json --max-monthly 120000
```

입력 파일은 JSON만 지원한다.

예시:

```json
{
  "planned": {
    "cloudVendor": "NCP",
    "environment": "dev",
    "replicas": 2,
    "cpuMillicores": 1000,
    "memoryMb": 2048,
    "storageGb": 20,
    "loadBalancer": true,
    "outboundTrafficGb": 120
  },
  "current": {
    "cloudVendor": "NCP",
    "environment": "dev",
    "replicas": 1,
    "cpuMillicores": 500,
    "memoryMb": 1024,
    "storageGb": 10,
    "loadBalancer": false,
    "outboundTrafficGb": 40
  },
  "monthlyBudgetLimit": 120000
}
```

---

## 종료 코드

- `0`: 성공
- `1`: 입력 오류 또는 정책 위반
- `2`: 인증 실패 또는 토큰 갱신 실패
- `3`: API 요청 실패
- `4`: 비용 한도 초과
- `5`: OAuth/대기 명령 타임아웃
