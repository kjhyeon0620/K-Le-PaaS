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
- `--json`: 구조화된 JSON 출력 (시각은 API 값 그대로 UTC, 예: `2026-10-09T09:09:30.682Z`)
- `--quiet`: 성공 메시지 최소화

표 출력의 시각은 한국 시간으로 표시한다 (예: `2026-10-09 18:09:30 KST`).

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
klepaas auth login --web [--scope read-only|propose-only|full]
klepaas auth whoami
klepaas auth logout
```

- `--web`는 브라우저에서 KLEPaaS 웹 승인 페이지를 열고, 승인 후 CLI 전용 토큰을 자동 저장한다.
- 로그인되지 않은 브라우저는 GitHub 로그인 후 같은 승인 페이지로 복귀한다.
- 토큰은 XDG config 경로 또는 `~/.config/klepaas/config.json`에 저장된다.
- 사람 사용자는 `--web`, Jenkins/AI/스크립트는 `Settings > CLI Tokens`에서 발급한 전용 토큰으로 `--token` 로그인을 사용하는 것을 권장한다.
- CLI 토큰은 권한 범위(scope)를 가진다. 서버가 모든 요청에서 검사하며 CLI에서 명령을 숨기는 방식이 아니다.
  - `read-only`(조회 전용): 조회(GET)와 비용 계산(`cost plan/diff/explain/check`)만 허용한다. 배포·스케일·재시작·설정 변경·자연어 명령·토큰 발급/폐기는 HTTP 403으로 거부된다.
  - `propose-only`(제안 전용): `read-only`에 자연어 명령 생성(`ask`)이 더해진다. MEDIUM/HIGH 명령은 확인 대기로 기록될 뿐이고, `confirm`은 403이다. 사람이 웹 또는 전체 권한 토큰으로 승인해야 실행된다.
  - 배포 전용(`DEPLOY`): 웹 `Settings > CLI Tokens`에서 저장소 1개를 골라 발급한다. 그 저장소의 `POST /api/v1/deployments`만 허용하며 조회를 포함한 다른 요청은 403이다. CI 배포 콜백용이며 `--web` 로그인으로는 받을 수 없다.
  - `full`(전체 권한): 발급한 사용자와 같은 권한. 기존 토큰과 `--scope`를 지정하지 않은 `--web` 로그인은 `full`이다.
  - CLI 로그인 승인과 토큰 발급은 전체 권한으로만 할 수 있어, 조회 전용 토큰으로 더 높은 권한의 토큰을 얻을 수 없다.
- 모니터링 스크립트에는 `read-only`, AI agent에는 `propose-only`, CI에는 배포 전용 토큰을 권장한다. 승인 화면에 요청 권한이 표시된다.

예시:

```bash
# 사람 사용자
npm run cli -- auth login --web

# 조회 전용 로그인 (모니터링용)
npm run cli -- auth login --web --scope read-only

# 제안 전용 로그인 (AI agent용: 변경은 사람이 confirm)
npm run cli -- auth login --web --scope propose-only

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
klepaas deployments logs 42 --lines 100
klepaas deployments restart 42
klepaas deployments scale 42 --replicas 3
klepaas deployments wait 42 --timeout 600 --interval 5
klepaas deployments export 42 --format yaml --output klepaas-export.yaml
```

`deployments wait`는 배포 상태가 `SUCCESS`가 될 때까지 폴링한다.

- 성공 종료 상태: `SUCCESS`
- 비성공 종료 상태: `FAILED`, `CANCELED`, `UNKNOWN` (판정 불가). exit 3을 반환한다.
- `--json`은 비성공 종료에서도 `deployment_id`, `final_status`, `fail_reason`, `timeline`을 JSON 하나로 출력한다.
- timeout 시 종료 코드 `5`

`deployments get`은 배포 기록과 함께 실패 종류(`Failure Kind`), 요청 경로(`Trigger`), 자연어 승인 명령(`Command`), 관측한 이미지 digest(`Image Digest`)를 보여 준다. #95 이전 배포는 `-`(기록 없음)이다. `--json`에는 `failure_kind`, `trigger_source`, `requested_by_user_id`, `command_log_id`, `image_digest`가 추가된다.

`deployments logs`는 배포 상태·실패 원인과, 그 배포 요청이 적용한 Pod의 조회 시점 상태·최근 로그·Kubernetes 이벤트를 보여 준다.

- `--lines`: Pod별 최근 로그 줄 수, 1~200 (기본 100). 범위 밖이면 종료 코드 `1`
- 재시작한 컨테이너는 직전 컨테이너 로그도 보여 준다.
- `Observation`이 `NOT_CURRENT`(다른 요청이 적용했거나 적용 기록 없음)나 `UNAVAILABLE`(Kubernetes 조회 실패)이면 Pod 로그 없이 이유를 보여 준다. 조회 자체가 성공하면 관측 상태와 무관하게 종료 코드 `0`이다.
- `--json`은 API 응답(`deployment_id`, `status`, `fail_reason`, `observation`, `observation_message`, `applied_deployment_id`, `pods`, `events`)을 그대로 출력한다.
- 로그는 저장되지 않으며, Kubernetes 이벤트는 보존 기간(기본 1시간)이 지나면 사라진다.

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
