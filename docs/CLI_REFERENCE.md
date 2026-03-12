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

환경변수 우선순위:

- `KLEPAAS_BASE_URL`
- `KLEPAAS_TOKEN`
- `KLEPAAS_REFRESH_TOKEN`

적용 우선순위는 `CLI 인자 > 환경변수 > 설정 파일`이다.

---

## 인증

```bash
klepaas auth login --token <access-token> [--refresh-token <refresh-token>]
klepaas auth login --web
klepaas auth whoami
klepaas auth logout
```

- `--web`는 브라우저에서 KLEPaaS 웹 승인 페이지를 열고, 승인 후 CLI 전용 토큰을 자동 저장한다.
- 로그인되지 않은 브라우저는 GitHub 로그인 후 같은 승인 페이지로 복귀한다.
- 토큰은 XDG config 경로 또는 `~/.config/klepaas/config.json`에 저장된다.
- 사람 사용자는 `--web`, Jenkins/AI/스크립트는 `Settings > CLI Tokens`에서 발급한 전용 토큰으로 `--token` 로그인을 사용하는 것을 권장한다.

예시:

```bash
# 사람 사용자
npm run cli -- auth login --web

# 머신 사용자
npm run cli -- auth login --token "kpa_cli_..."
```

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
klepaas deployments wait 42 --timeout 600 --interval 5
klepaas deployments export 42 --format yaml --output klepaas-export.yaml
```

`deployments wait`는 배포 상태가 `SUCCESS`가 될 때까지 폴링한다.

- 성공 종료 상태: `SUCCESS`
- 실패 종료 상태: `FAILED`, `CANCELED`
- timeout 시 종료 코드 `5`

`deployments export`는 현재 배포 상태와 저장소/런타임 설정을 파일로 내보낸다.

- 기본 포맷: `json`
- 지원 포맷: `json`, `yaml`
- 1차 범위는 배포 메타데이터, 저장소 정보, 런타임 설정(`min/max replicas`, `container port`, `domain`, `env vars`)이다.

---

## 진단 명령

```bash
klepaas doctor
klepaas doctor --json
```

`doctor`는 아래 항목을 점검한다.

- 설정 파일 존재 여부
- 활성 프로필
- 유효한 base URL 존재 여부
- access token 존재 여부
- `/api/v1/system/health` 호출 가능 여부
- `/api/v1/system/version` 호출 가능 여부
- 토큰이 있으면 `/api/v1/auth/me` 인증 가능 여부

머신/스크립트 환경 예시:

```bash
KLEPAAS_BASE_URL=http://localhost:8080 \
KLEPAAS_TOKEN=kpa_cli_xxx \
npm run cli -- doctor
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
이 비용 모델은 실제 billing 조회가 아니라 배포 spec 기반 추정 모델이다.

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

---

## 인증 저장 위치

- 기본 저장 파일: `~/.config/klepaas/config.json`
- 저장 항목:
  - 활성 프로필 이름
  - 프로필별 `baseUrl`
  - `accessToken`
  - `refreshToken`
  - 최근 사용자 정보
