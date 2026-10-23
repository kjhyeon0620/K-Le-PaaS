# Product: K-Le-PaaS

이 문서는 제품 목표, 사용자, 범위, 성공 기준, 실행 순서를 담는다. 구조는 [`architecture.md`](architecture.md), 결정 근거는 [`adr/`](adr/README.md)에 있다.
기능별 구현 상태의 공개 요약은 루트 [`README.md`](../README.md)의 현재 상태 표에 있다.

## 1. 목표

- **제품 목표**: 프로젝트마다 K-Le-PaaS 코드를 수정하거나 서버에 수동 패치하지 않고, 새 HTTP 앱을 등록하고 배포하고 관측하고 복구할 수 있게 한다.
- **입증 기준**: 기존 IoT 앱과 다른 기술 스택(Node.js HTTP 앱)의 두 번째 앱으로 첫 배포 → 변경 → 실패 확인 → 이전 버전 복구까지를 설정만으로 수행한다 (#55).
- **차별점**: PaaS 기능의 개수가 아니라, **LLM·CLI·agent의 요청을 권한, 승인, 실제 상태로 통제하는 실행 경계**에 둔다.

## 2. 사용자

| 사용자 | 목적 | 인터페이스 |
|---|---|---|
| 앱 소유자 | 저장소를 등록하고 설정을 바꾸고 배포 상태를 확인한다 | Web console (`/console`) |
| 운영자 | 상태를 조회하고, 재시작·스케일·배포를 승인하고, 허용 참조를 관리한다 | Web, CLI, 서버 설정 |
| AI agent | 조회하고, 변경을 **제안**한다. 실행은 사람이 승인한다 | CLI (`propose-only` 토큰), 자연어 API |
| CI | 지정된 저장소의 이미지 배포를 요청한다 | `POST /api/v1/deployments` (GitHub Actions는 OIDC 토큰, 그 밖의 CI는 저장소별 배포 전용 토큰) |

## 3. 성공 기준

| 종류 | 기준 |
|---|---|
| 안전 | 승인되지 않은 MEDIUM/HIGH 변경이 실행되지 않는다. 권한 밖 요청은 Kubernetes 호출 전에 거부된다 |
| 사실성 | 상태, 로그, 배포 목록은 실제 관측값이거나 저장된 이력이다. 판단할 수 없으면 `UNKNOWN`으로 표시하고 고정값을 쓰지 않는다 |
| 배포 | 두 번째 앱 실증에서 앱별 K-Le-PaaS 코드 변경과 서버 수동 패치가 0회다. 모든 배포를 commit, 이미지, 설정, 결과로 추적할 수 있다 |
| 모델 평가 | 고정된 입력 30개를 재실행할 수 있고, 미확인 변경 실행 0건을 목표로 한다 (#58) |

## 4. 범위

### 포함
- GitHub OAuth, GitHub App 기반 저장소 접근
- 빌드 경로 두 가지: NCP Kaniko/NCR, 외부 이미지(GitHub Actions/GHCR, prebuilt)
- Kubernetes Deployment/Service/Ingress 적용, rollout 판정, scale, restart
- 자연어 명령 → intent → 위험도 분류 → 승인 → 실행
- CLI와 토큰 scope, 비용 추정(spec 기반), Slack/WebSocket 알림, GitHub webhook
- 플랫폼 자체 CI/CD (Oracle ARM64 systemd 릴리스)
- 근거 기반 읽기 전용 진단과 승인한 복구 계획 실행 (#99~#101, #105)
- 에이전트 조회·변경 제안과 MCP 서버, 운영문서·장애 이력 RAG 진단 (#102~#104). 에이전트는 제안만 하고 실행은 사람이 승인한다

### 제외 (현재 단계)
- 다중 클러스터, AWS provider
- IaC/Terraform (설계 후보로만 존재)
- 실제 billing API 기반 비용 조회
- DB 자동 생성, 백업·복원 자동화
- 한 저장소의 여러 서비스, dev/prod 환경 분리
- 도메인·HTTPS 자동화 (두 번째 앱 실증과 모델 평가 결과를 본 뒤 판단)

## 5. 실행 순서

2026-09-29 방향 재검토에서 정한 원칙은 다음 순서다: **사실과 다른 동작 제거 → 토큰 권한 → 스키마 관리 → 정상 동작 기준 → 배포 정합성 → 두 번째 앱 실증 → 평가·공개**.
2026-10-11 장기 로드맵을 반영해 그 뒤를 이었다: **사용 장벽 제거 → 모델 평가 → 근거 기반 진단 → 승인 복구 → 대표 시나리오 → 에이전트·MCP·RAG → 공개 정리**.
이 순서를 기준으로 각 이슈의 상태와 스펙을 관리한다. 순서 `-`인 이슈는 다른 작업 사이에 처리한다.

| 순서 | 이슈 | 제목 | 상태 | 스펙 |
|---|---|---|---|---|
| 1 | #46 | 재시작 replica 보존, 스케일 입력 검증 | 완료 (PR #60) | - |
| 2 | #47 | 상태 고정값, 대시보드 mock 제거 | 완료 (PR #61) | - |
| 3 | #48 | Flyway 도입, H2 baseline | 완료 (PR #62), 운영 V1 | - |
| 4 | #49 | CLI 토큰 scope 서버 강제 | 완료 (PR #63), 운영 V2 | - |
| 5 | #50 | propose-only, 저장소별 CI 배포 토큰 | 완료 (PR #64), 운영 V3 | - |
| 6 | #51 | 앱별 readiness/startup probe, requests/limits | 완료 (PR #71), 운영 V4 | [0051](specs/0051-app-probes-resources/spec.md) |
| 7 | #52 | 동일 앱 배포 직렬화, 중복 접수 방지 | 완료 (PR #72), 운영 V5 | [0052](specs/0052-serialize-deployments/spec.md) |
| 7-1 | #65 | GitHub Actions OIDC 기반 CI 배포 인증 | 완료 (PR #73), IoT 전환 | [0065](specs/0065-github-actions-oidc/spec.md) |
| 7-2 | #75 | 운영 DB H2 → PostgreSQL 전환 | 완료 (PR #76), 운영 PostgreSQL V1 | [0075](specs/0075-postgresql-migration/spec.md) |
| 7-3 | #74 | 공개 이미지 배포에서 pull secret 끄기 | 완료 (PR #77), 운영 V2, IoT 재배포 성공 | [0074](specs/0074-optional-image-pull-secret/spec.md) |
| 7-4 | #79 | 명령·배포 이력 조회 인덱스와 전후 측정 | 완료 (PR #80) | [0079](specs/0079-history-query-indexes/spec.md) |
| 7-5 | #78 | 콘솔 배포 화면과 백엔드 응답 필드 불일치 (#66 포함) | 완료 (PR #82) | [0078](specs/0078-console-deployments-api-contract/spec.md) |
| 7-5-1 | #66 | 설정 조회 실패 시 콘솔 저장이 기본값으로 덮어쓰는 문제 | #78에서 함께 해결 | - |
| 7-6 | #81 | API 시각 시간대 포함, 콘솔·CLI 한국 시간 표시 | 완료 (PR #83) | [0081](specs/0081-kst-time-display/spec.md) |
| 8 | #53 | 요청 단위 rollout 판정 | 완료 (PR #84) | [0053](specs/0053-request-scoped-rollout/spec.md) |
| 9 | #54 | 배포 실패 원인, 배포 로그 API | 완료 (PR #85) | [0054](specs/0054-deployment-failure-logs/spec.md) |
| 9-1 | #86 | restart·scale이 managedFields 직렬화 오류로 500 실패 | 완료 (PR #87) | - |
| 9-2 | #88 | 배포 로그 대화상자 폭·스크롤 | 완료 (PR #89) | - |
| 9-3 | #90 | 스케일 대화상자가 설정값을 현재 레플리카로 표시 | 완료 (PR #91) | [0090](specs/0090-observed-replicas/spec.md) |
| 9-4 | #92 | Kubernetes 호출 실패 메시지의 API 서버 주소 노출 | 완료 (PR #93) | - |
| 10 | #55 | 두 번째 앱 배포·실패·복구 실증 | 완료 (PR #94), 운영 실증 | [0055](specs/0055-second-app-demo/spec.md) |
| 10-1 | #95 | 배포 요청 상세 화면과 요청별 설정·근거 기록 | 완료 (PR #96) | [0095](specs/0095-deployment-request-detail/spec.md) |
| 11 | #57 | 재시작 후 미완료 배포·명령 상태 대조 | 완료 (1단계 PR #97, 2단계 PR #98) (L) | [0057](specs/0057-reconcile-interrupted-work/spec.md) |
| 12 | #106 | 콘솔에서 새 앱 배포 설정 입력·검증 | 완료 (M), env·레플리카 입력, 한 번 저장·변경 확인, 화면 미리 검사 | [0106](specs/0106-console-deployment-config/spec.md) |
| 12-1 | #107 | NodePort 전용 앱의 domain_url 필수 제거 | 완료 (S), V10 | - |
| 13 | #58 | 자연어 명령 해석 실제 모델 평가 기준선 | 완료 (M), `gemini-3.8-flash`·`gpt-6-luna` 비교. 운영 `gemini-2.5-flash`는 신규 사용자 차단으로 측정 불가 | [0058](specs/0058-nlp-model-eval-baseline/spec.md) |
| 13-1 | #135 | 앱 이름만 준 스케일·재시작 명령이 승인 후 실행에서 실패 | 완료 (M), 대상이 빠진 변경 명령은 승인 전에 되물음 | [0135](specs/0135-scale-restart-target/spec.md) |
| 13-1-1 | #144 | 자연어 재시작·스케일·롤백을 앱 이름으로 지정 | 완료 (M), 운영 확인 (이름 재시작 승인 대상 표시) | [0144](specs/0144-nl-target-by-name/spec.md) |
| 13-1-2 | #146 | CLI ask가 승인 대기 명령의 승인 방법을 안내 | 완료 (S), 터미널에서는 그 자리에서 [y/N] 승인 | - |
| 13-2 | #136 | 자연어 명령 해석 모델을 gpt-6-luna로 교체 | 완료 (L), 운영 전환 확인 | [0136](specs/0136-nlp-llm-luna/spec.md) |
| 13-3 | #140 | 자연어 "저장소 목록" 명령이 실제 목록 대신 고정 안내 문구를 돌려줌 | 완료 (S), 운영 확인 (실제 목록 2개) | - |
| 13-4 | #150 | OpenAI 호환 로컬 모델 서버를 진단·자연어 명령 공급사로 선택 | 완료 (M), 연결까지. 로컬 모델 측정은 후속 | [0150](specs/0150-local-llm-provider/spec.md) |
| 13-5 | #152 | 로컬 모델 3개의 자연어 명령·진단 평가 (생각 끔) | 완료 (M), `gemma4:26b-a4b`만 두 평가 통과(자연어 intent 150/150, 진단 구체 원인 100/100). 기본값 유지 | [0152](specs/0152-local-llm-eval/spec.md) |
| 13-6 | #154 | 독립 작성 평가 세트로 로컬 gemma4 진단 검증 | 완료 (M), #123 평가용 39사례 × 3회 구체 원인 117/117(`gpt-6-luna` 116/117). 기본값 유지 | [0154](specs/0154-gemma-blind-eval/spec.md) |
| 13-7 | #156 | 평가 하네스에 반복 일관성(pass^k)과 판단 보류 지표 추가 | 완료 (M), 진단·자연어 표에 pass^1·pass^n·판단 보류 정밀도/재현율·잘못된 단정, 진단 저장 결과 다시 계산 모드 | [0156](specs/0156-eval-consistency-metrics/spec.md) |
| 13-8 | #159 | 진단 정답 감사와 원시 응답 재채점 | 완료 (M), 서버 보정 전 원시 지표(로컬 모델 근거 위치 오류 27~38% 확인), 51사례 1차 감사(정정 제안 1) | [0159](specs/0159-raw-rescore-and-audit/spec.md) |
| 13-9 | #161 | 시그니처 규칙과 LLM의 추가 진단 가치 비교 | 완료 (M), 규칙 v1.1 개발 세트 48/49·잘못된 MATCH 0, 규칙 우선이 모든 모델에서 확신 오답·LLM 호출 감소. 제품 반영은 동결 평가 세트 확인 후 | [0161](specs/0161-signature-rule-baseline/spec.md) |
| 13-10 | #158 | 기계 변형 입력의 회귀·보류 검사 | 완료 (M), 23사례(+감사용 1), `gpt-6-luna` 저장 응답 전부 통과, 시그니처 규칙 v1.2(이전 Pod 이벤트 제외) | [0158](specs/0158-hard-diagnosis-cases/spec.md) |
| - | #138 | CRLF로 커밋된 파일의 줄바꿈을 LF로 정규화 | 완료 (S), 225개 | - |
| 14 | #99 | 배포 실패 진단 평가 사례와 판정 기준 | 완료 (PR #114) (M) | [0099](specs/0099-diagnosis-eval-cases/spec.md) |
| 15 | #100 | 배포 요청 상세의 근거 기반 읽기 전용 진단 | 완료 (PR #116) (L), 공급사 교체·비용 비교 포함 | [0100](specs/0100-evidence-based-diagnosis/spec.md) |
| 15-1 | #115 | AI 진단 호출 비용 절감과 전후 측정 | 완료 (M), 품질 기준을 지킨 절감은 출력 상한뿐 | [0115](specs/0115-diagnosis-cost-reduction/spec.md) |
| 15-2 | #117 | AI 진단 결과 재사용과 근거 없는 입력의 모델 호출 생략 | 완료 (M) | [0117](specs/0117-diagnosis-result-reuse/spec.md) |
| 16 | #56 | 승인한 배포 설정 버전 고정, 변경 시 재승인 | 완료 (PR #112) (L) | [0056](specs/0056-pin-approved-deployment-config/spec.md) |
| 17 | #101 | 이전 배포 이미지·설정으로 복구 계획 생성·승인·실행 | 완료 (PR #113) (L) | [0101](specs/0101-recover-previous-deployment/spec.md) |
| 18 | #105 | Node 앱 배포·실패·진단·승인·복구 대표 시나리오 실증 | 완료 (M), 운영 실증 | [0105](specs/0105-end-to-end-demo/spec.md) |
| 19 | #102 | CLI·API 기반 에이전트 상태 조회와 변경 제안 | 완료 (M) | [0102](specs/0102-agent-query-propose/spec.md) |
| 20 | #103 | K-Le-PaaS MCP 서버와 에이전트 조회·제안 연결 | 완료 (L), 실제 클라이언트 확인은 사용자 | [0103](specs/0103-mcp-server/spec.md) |
| 20-1 | #104 | 운영문서·장애 이력 RAG 기반 배포 진단 | 완료 (L) | [0104](specs/0104-rag-diagnosis/spec.md) |
| 20-2 | #123 | 독립 작성 평가 세트로 RAG 진단 효과 측정 | 완료 (M), 독립 세트에서 RAG 개선 없음(기존 99% → 97%) | [0123](specs/0123-blind-rag-eval/spec.md) |
| 20-3 | #125 | 진단에서 검색된 지식 목록과 유사도를 기록 | 완료 (S) | - |
| 20-4 | #126 | 만료된 승인 대기 명령이 이력에 PENDING으로 남음 | 완료 (S) | - |
| 20-5 | #129 | 진단 RAG를 설정으로 끄고 기본값을 끔 | 완료 (S), #123 근거 | - |
| 21 | #59 | README, 콘솔 미연결 화면, 데모 결과 정리 | 완료 | - |
| - | #108 | rollout 타임아웃을 앱의 startup probe 허용 시간에 맞춤 | 완료 (M) | [0108](specs/0108-rollout-timeout-from-probe/spec.md) |
| - | #109 | CLI env 키 변환·Fabric8 버전·미사용 쿼리·모바일 사이드바 정리 | 완료 (M), Fabric8 7.2.0 → 7.6.1(Jackson 2.20 기준) | [0109](specs/0109-small-gaps/spec.md) |

- 새 이슈를 착수할 때 M/L 등급이면 스펙을 먼저 만들고 이 표의 스펙 열을 채운다.
- #55의 샘플 앱과 실패 주입 방법은 초기에 정해서 #51~#54의 검증 대상으로 쓴다.
- 플랫폼 자체 배포(systemd)와 사용자 앱 배포(Kubernetes)는 별도 흐름이다. 플랫폼 CI/CD가 완료되었다고 #52~#55를 완료로 보지 않는다.

## 6. 후순위 후보

요구가 확인되면 좁은 범위의 이슈로 추가한다. MCP·RAG는 #103·#104로 옮겼다.

- 도메인·HTTPS 자동화, 서비스·환경 분리(dev/prod, preview), DB 자동 생성
- 배포 알림 연결, 메트릭·과거 로그 연결, 협업·역할 관리
- 모니터링 metrics API와 UI stub 연결, Slack 설정 저장
- 조직 저장소 권한 모델
- ~~인자 기반 위험도 규칙 (#58 결과로 판단)~~ #58 결과 지금은 필요 없음. 과대 분류는 모두 대상 없는 SCALE이라 승인·실행에서 막힌다. 대상 없는 변경 명령을 되묻는 처리는 #135에서 다룬다
- IaC/Terraform
