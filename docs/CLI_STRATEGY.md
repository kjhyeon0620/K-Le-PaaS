# CLI 전략 및 문서 정리

> 마지막 업데이트: 2026-03-12
> 상태: 1차 MVP 구현 완료

---

## 목적

KLEPaaS의 CLI는 웹 콘솔을 대체하는 주 인터페이스가 아니라, 기존 API와 NLP 엔진을 재사용하는 보조 제어면(control plane)으로 정의한다.

핵심 목표는 아래 3가지다.

- 운영자와 개발자가 브라우저 없이 빠르게 상태를 조회하고 제어할 수 있게 한다.
- AI agent, MCP tool calling, shell script 같은 비대화형 도구가 안정적으로 사용할 수 있는 인터페이스를 제공한다.
- 비용 추정과 정책 검사를 배포 시점에 연결하여 비용 인지형 배포 경험을 만든다.

---

## 도입 판단

### 지금 CLI를 도입하는 이유

- 백엔드가 이미 NLP 명령 API와 배포 제어 API를 제공하고 있어 얇은 클라이언트로 시작할 수 있다.
- 웹 콘솔은 사람 중심 UX에 강하고, CLI는 자동화/스크립팅/구조화 출력에 강하다.
- 포트폴리오 관점에서도 `자연어 PaaS + 자동화 CLI + 비용 가드레일` 조합은 설명력이 높다.

### CLI를 주 인터페이스로 보지 않는 이유

- 현재 제품의 핵심 UX는 자연어 웹 콘솔과 위험 명령 확인 플로우다.
- 대시보드, 시각화, 실시간 모니터링은 웹이 더 적합하다.
- 배포/롤백/스케일 같은 고위험 명령은 웹과 동일한 정책과 확인 절차를 공유해야 한다.

---

## CLI 역할 정의

CLI는 아래 3개 역할에 집중한다.

### 1. 운영용 인터페이스

- 배포 목록/상세 조회
- 재시작, 스케일, 로그 확인
- 배포 완료까지 대기
- 현재 배포 설정 export
- 명령 이력 조회
- 로컬 환경 진단

### 2. 자동화용 인터페이스

- shell script, cron, internal agent, MCP bridge에서 호출
- `--json`, `--quiet`, exit code 기반 제어
- 비대화형 명령 실행과 결과 파싱

### 3. 비용 가드레일 인터페이스

- 배포 전 예상 비용 확인
- 변경 전후 비용 차이 분석
- 정책 한도 초과 시 실패 처리

---

## 우선순위

### 구현 완료 (1차 MVP)

- `klepaas auth login --token`
- `klepaas auth login --web`
- `klepaas auth whoami`
- `klepaas auth logout`
- `klepaas ask "<natural-language-command>"`
- `klepaas confirm <command-id>`
- `klepaas history`
- `klepaas deployments list`
- `klepaas deployments get <id>`
- `klepaas deployments restart <id>`
- `klepaas deployments scale <id> --replicas <n>`
- `klepaas deployments wait <id> --timeout <sec>`
- `klepaas deployments export <id> --format json|yaml`
- `klepaas doctor`
- 전역 옵션: `--json`, `--quiet`
- 종료 코드 표준화
- 환경변수 기반 설정: `KLEPAAS_BASE_URL`, `KLEPAAS_TOKEN`, `KLEPAAS_REFRESH_TOKEN`

### 구현 완료 (비용 MVP)

- `klepaas cost plan`
- `klepaas cost diff`
- `klepaas cost explain`
- `klepaas cost check --max-monthly <amount>`

### 다음 우선순위

- `klepaas status`
- `klepaas logs`
- `klepaas completion`
- repository 자동 추론
- 파일 기반 spec export/apply 고도화

---

## 지금 당장 하지 않을 것

아래 항목은 아이디어는 좋지만 현재 단계에서 범위 대비 리스크가 크다.

- 실제 Billing API 기반 이번 달 청구 금액 집계
- 유휴 리소스 자동 판정 (`doctor --waste`)
- AI 기반 인스턴스/스펙 자동 추천
- CLI 내부에 별도 intent parsing 로직 구현
- 웹과 다른 confirmation/risk 정책 도입

이유는 인증/정확도/설명 책임이 커지고, 포트폴리오에서 방어하기 어려운 지점이 늘어나기 때문이다.

---

## 비용 기능 방향

비용 기능은 `청구서 조회형 FinOps`보다 `배포 전 추정형 FinOps`로 시작한다.

### 채택한 방향

- 리소스 spec 기반 예상 월 비용 산출
- 현재 배포 대비 변경분 증감 계산
- 항목별 비용 근거 출력
- 정책 한도 초과 시 CLI 실패 반환

### 채택하지 않은 방향

- 클라우드 billing API에 직접 의존하는 실시간 과금 조회
- 사용률만 보고 유휴 리소스를 자동 판단하는 기능

### 비용 기능의 메시지

KLEPaaS는 배포 후 비용을 보여주는 플랫폼이 아니라, 배포 시점에 비용을 인지하게 만드는 플랫폼으로 포지셔닝한다.

---

## 자동화 도구와의 관계

현재 KLEPaaS는 플랫폼 내부 빌드/배포 파이프라인을 가진다. 따라서 Jenkins 같은 외부 도구는 내부 구현 필수 요소가 아니라, KLEPaaS를 호출하는 외부 소비자다.

즉 CLI의 가치는 아래와 같이 설명한다.

- KLEPaaS 내부 파이프라인을 대체하기 위함이 아니다.
- 기존 CI/CD, shell script, AI agent가 KLEPaaS API를 일관된 방식으로 호출할 수 있게 하기 위함이다.
- 현재 시점에서 더 중요한 1차 명분은 Jenkins 연동보다 운영용/자동화용/비용 정책용 인터페이스 제공이다.

---

## 설계 원칙

- CLI는 기존 백엔드 API의 thin client로 시작한다.
- 위험 명령의 정책과 확인 흐름은 웹과 동일한 백엔드 정책을 따른다.
- 머신 사용을 위해 모든 핵심 명령은 JSON 출력과 명확한 종료 코드를 지원한다.
- CLI 인자 외에도 환경변수 기반 인증과 base URL override를 지원한다.
- 자연어 명령은 부가 기능으로 제공하고, 자동화 핵심은 명시형 서브커맨드로 설계한다.
- 비용 계산 로직은 클라우드별 어댑터로 분리해 멀티 클라우드 확장 가능성을 유지한다.

### 인증 원칙

- 사람 사용자 인증은 `auth login --web`으로 제공한다.
- 머신/에이전트/Jenkins 인증은 웹에서 발급한 `CLI Access Token`으로 제공한다.
- GitHub OAuth callback을 CLI가 직접 받지 않고, 웹 승인 페이지가 중간 브로커가 되어 CLI 전용 토큰을 전달한다.
- CLI 토큰은 평문 저장하지 않고 해시만 저장하며, 발급 시 1회만 원문을 노출한다.

---

## 제안 명령 구조

```text
klepaas auth login --web
klepaas auth login --token <token>
klepaas auth whoami
klepaas auth logout

klepaas ask "staging nginx 상태 보여줘"
klepaas confirm 123
klepaas history --page 0 --size 20

klepaas deployments list
klepaas deployments get 42
klepaas deployments restart 42
klepaas deployments scale 42 --replicas 3
klepaas deployments wait 42 --timeout 600
klepaas deployments export 42 --format yaml

klepaas cost plan --file deploy.yaml
klepaas cost diff --file deploy.yaml
klepaas cost explain --file deploy.yaml --json
klepaas cost check --file deploy.yaml --max-monthly 100000

klepaas doctor
klepaas logs 42
```

---

## 문서 작업 체크리스트

### 지금 바로 작성/정리할 문서

- [x] CLI 도입 목적과 범위를 정리한 전략 문서
- [x] CLI 명령어 스펙 문서
- [x] CLI 인증/토큰 저장 방식 문서
- [x] CLI 종료 코드 및 JSON 출력 계약 문서
- [x] 비용 추정 모델 문서
- [x] 비용 계산식/가격 소스/한계 문서

### 구현이 시작되면 추가해야 할 문서

- [ ] 설치 가이드 (`npm`, standalone binary, 또는 wrapper 방식 중 선택 후 작성)
- [ ] 로컬 개발 가이드
- [ ] 배포/릴리스 가이드
- [ ] CLI 테스트 전략 문서
- [ ] 운영 예시 문서
- [ ] 외부 자동화 도구 연동 예시 문서

---

## 구현 후 메모

### 실제 반영된 인증 방식

- `auth login --web`
  - CLI가 웹 승인 세션을 생성하고 브라우저에서 `/console/cli/authorize`를 연다.
  - 로그인되지 않은 브라우저는 GitHub 로그인 후 승인 페이지로 복귀한다.
  - 승인 완료 시 CLI가 세션을 exchange하여 전용 CLI 토큰을 저장한다.
- `auth login --token`
  - 웹 설정 페이지 `CLI Tokens`에서 발급한 토큰으로 로그인한다.
  - 폐기된 토큰은 즉시 401로 거부된다.
- 환경변수 인증
  - `KLEPAAS_BASE_URL`, `KLEPAAS_TOKEN`, `KLEPAAS_REFRESH_TOKEN`을 지원한다.
  - 우선순위는 `CLI 인자 > 환경변수 > 설정 파일`이다.

### 비용 모델

- 대상: `NCP`, `AWS`, `ON_PREMISE`
- 입력: `cloudVendor`, `environment`, `replicas`, `cpuMillicores`, `memoryMb`, `storageGb`, `loadBalancer`, `outboundTrafficGb`
- 출력: `estimatedMonthlyCost`, `deltaMonthlyCost`, `limitExceeded`, `costBreakdown`, `assumptions`
- 성격: 실제 billing 조회가 아니라 배포 전 추정형 모델

## 다음에 결정할 항목

### 배포/패키징

- CLI를 npm 배포 형태로 유지할지
- standalone binary를 추가로 만들지
- 릴리스/버전 정책을 어떻게 가져갈지

### 자동화 편의

- repository 자동 추론 범위를 어디까지 허용할지
- `export` 결과를 장기적으로 `apply` spec과 얼마나 맞출지
- headless 환경에서 브라우저 자동 오픈 UX를 어떻게 다듬을지

### 비용 모델

- 2차 대상 클라우드를 어디까지 확장할지
- 가격표를 정적 설정으로 유지할지 외부 소스로 관리할지
- 현재 export/runtime 정보와 비용 spec을 어떻게 연결할지

---

## 다음 구현 순서

1. `logs`, `completion`
2. CLI 배포/릴리스 문서와 패키징 방식 정리
3. repository 자동 추론 및 export/apply spec 정리
4. 비용 모델 고도화 또는 billing 연동 여부 재평가

---

## 포트폴리오 메시지

아래 메시지가 가장 설득력 있다.

- 웹 기반 자연어 PaaS에 CLI 제어면을 추가했다.
- 사람뿐 아니라 AI agent와 자동화 도구가 같은 제어면을 사용할 수 있게 했다.
- 배포 전에 예상 비용과 비용 증감을 계산해 비용 인지형 배포 경험을 만들었다.
- 정책 기반 비용 한도 초과 시 자동으로 배포를 차단할 수 있게 했다.
