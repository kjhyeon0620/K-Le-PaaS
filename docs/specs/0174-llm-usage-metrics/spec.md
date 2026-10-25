---
issue: 174
title: LLM 호출·토큰·진단 경로 지표 (Micrometer·Prometheus)
status: done
size: M
type: feat
branch: feat/#174-llm-usage-metrics
---

# LLM 호출·토큰·진단 경로 지표 (Micrometer·Prometheus)

## 목적 / 성공 조건
- 목적: 지금 LLM 사용량은 진단만 이력 테이블(`deployment_diagnoses`)에 토큰이 남고, 자연어 명령은 로그에만 있다. 호출 수·실패·지연·토큰을 기능·공급사·모델별로 한곳에서 볼 수 없고, 규칙 우선 진단(#170)이 운영에서 모델 호출을 얼마나 줄였는지도 수치로 볼 수 없다. LLM 호출 경로 한 곳에 지표를 붙이고 Prometheus 형식으로 노출한다. 비용 절감·예산 상한(다음 이슈)의 측정 기반이다.
- 기술적 완료:
  - `LlmConfig.create`가 만드는 모든 `LlmClient`를 계측 래퍼로 감싼다(진단·자연어 명령, 모든 공급사).
  - 지표(Micrometer):
    - `klepaas_llm_calls_total{feature, provider, model, outcome}` — feature는 `diagnosis`·`nlp`, outcome은 `success`·`error`
    - `klepaas_llm_latency_seconds{feature, provider, model}` — 성공 호출의 지연 분포(히스토그램, p50·p95 계산용)
    - `klepaas_llm_tokens_total{feature, provider, model, kind}` — kind는 `input`·`cached_input`·`output`·`reasoning`(공급사가 보고한 값만, 추정 없음)
    - `klepaas_diagnosis_requests_total{route}` — route는 `reused`·`no_evidence`·`rule`·`model`·`model_error`(#117·#170 경로별 진단 요청 수)
  - Actuator와 Prometheus 레지스트리를 넣고 `/actuator/prometheus`를 노출한다(Q1).
  - 사용자 ID 같은 높은 카디널리티 태그는 넣지 않는다(사용자별 사용량은 예산 상한 이슈에서 DB로).
- 운영: API 응답·동작은 바뀌지 않는다. 지표 엔드포인트가 추가된다(Q1의 노출 범위).

## 범위
- 포함: 의존성(`spring-boot-starter-actuator`, `micrometer-registry-prometheus`), 계측 래퍼, 진단 경로 카운터, 노출 설정·보안, 지표 문서(`docs/observability.md`: 지표 목록, 로컬에서 보는 법, 비용 계산식), 테스트, 로컬 확인.
- 제외: 사용자별·일별 예산 상한과 차단(다음 이슈), k6 부하 시험(다음 이슈), 운영 Prometheus·Grafana 설치, 알림, 비용(USD) 지표(Q2), 프롬프트·모델 동작 변경.

## 입출력·계약
- API: 기존 `/api/v1/**` 변경 없음. 지표 엔드포인트는 Q1.
- 설정: `management.*`(노출 엔드포인트, 포트·주소). 새 앱 설정 키는 Q1 결과에 따라 문서에 적는다.
- 저장: 없음(지표는 메모리, 재기동하면 0부터). 영구 기록은 기존 `deployment_diagnoses`.

## 권한·안전
- 지표에는 프롬프트·응답·비밀값·사용자 정보를 넣지 않는다. 태그는 고정 집합(feature, provider, model, outcome, kind, route)만.
- 노출 범위(Q1): 공개 인터넷에 지표를 열지 않는다.
- 계측 실패가 LLM 호출·진단을 막지 않는다(Micrometer 기록은 예외를 던지지 않는다. 래퍼는 원래 예외를 그대로 다시 던진다).

## 제약
- 참조: [architecture](../../architecture.md), [#170](../0170-rule-first-diagnosis/spec.md), [#115 비용 측정](../0115-diagnosis-cost-reduction/spec.md)
- 토큰은 `LlmClient.Usage`가 공급사 응답에서 받은 값만 센다. 보고하지 않는 항목(null)은 0으로 세지 않고 건너뛴다.
- 기존 로그(`Diagnosis: ... inputTokens=...`)는 그대로 둔다.

## 예외·경계 상황
| 상황 | 지표 |
|---|---|
| 공급사 호출 실패(AI_001)·키 없음 | `calls{outcome=error}` +1, 지연·토큰 기록 안 함 |
| 응답은 왔지만 파싱 실패(AI_002) | `calls{outcome=success}`·토큰 기록(과금됨), 진단 경로 `model_error` |
| 규칙 결과·근거 없음·재사용 | LLM 지표 변화 없음, 진단 경로만 +1 |
| 재기동 | 메모리 지표 0부터(Prometheus `rate`·`increase`로 본다) |

## 미결 질문 (Open)
- 없음

## 결정 기록 (Decisions)
- 2026-10-25 Q1 → 관리 포트를 따로 두고 `127.0.0.1`에만 연다(`management.server.port`·`address`). 앱 포트·공개 경로에는 지표가 없다. 사용자 결정.
- 2026-10-25 Q2 → 이번에는 토큰만 지표로 낸다. 가격(비용 지표)은 예산 상한 이슈에서 저장소 가격표(확인 날짜 포함) 방식으로 추가하고, 공급사 청구액과 대조한다. 외부 가격 API·중개 가격표는 실제 청구가와 다를 수 있고 운영에 외부 의존을 더해 쓰지 않는다. 사용자 결정.
- 2026-10-25 Q3 → 운영 확인은 머지 후 사용자가 운영 서버 안에서 `curl` 한 줄을 실행해 결과를 붙인다. 에이전트는 운영 서버에 접속하지 않는다. 사용자 결정.
- 2026-10-25 Q4 → 패키징 JAR + 로컬 Prometheus·Grafana로 진단·자연어 요청 뒤 지표가 쌓이는지 화면까지 확인한다. 사용자 결정.
- 2026-10-25 (구현 중) 관리 포트에도 앱의 Spring Security 필터가 적용돼 지표가 401이었다 → `SecurityConfig`에 `/actuator/prometheus` permitAll을 더한다. 관리 포트는 127.0.0.1에만 열리고, 앱 포트에는 이 경로가 매핑되지 않는다. 새 경로 추가에 따른 scope 규칙 검토(AGENTS §5): 인증 대상 아님(관리 포트·로컬 전용).
- 2026-10-25 (구현 중) 앱 포트의 없는 경로가 404가 아니라 500으로 나오는 기존 격차를 발견했다(`GlobalExceptionHandler`가 `NoResourceFoundException`도 500으로). 에러 응답 계약 변경이라 이번에 고치지 않고 architecture §9에 기록, 이슈 후보로 보고. 테스트는 "200이 아니고 지표가 나오지 않음"으로 확인.
- 2026-10-25 (구현 중) 테스트 컨텍스트는 `management.server.port=-1`(관리 HTTP 끔)로 둔다. 컨텍스트가 여러 개 떠도 포트가 겹치지 않게. 노출 범위 테스트만 0(임의 포트)으로 켠다.
- 2026-10-25 (구현 중) Grafana 대시보드는 재기동 이후 누적값을 기본으로 보인다. 처음에 `rate`·`increase` 패널로 만들었더니 호출이 드물어 비어 보였다(개인 서버 트래픽도 같다). 실패가 한 번도 없을 때 실패 비율이 비지 않게 0을 채운다.
- 2026-10-25 기본값: 계측은 `LlmConfig.create`의 반환값을 감싸는 한 곳에서만 한다(호출부마다 넣지 않는다).
- 2026-10-25 기본값: 지연은 성공 호출만 기록한다(실패 지연은 타임아웃이 섞여 분포를 왜곡한다). 실패는 횟수로 본다.

## 완료 증거
| 증명할 것 | 방법 | 환경 |
|---|---|---|
| 래퍼가 성공·실패·토큰을 기록하고 원래 결과·예외를 바꾸지 않음 | 단위 테스트(SimpleMeterRegistry, 가짜 LlmClient) | 로컬 |
| 진단 경로 카운터 | 서비스 테스트: 재사용·근거 없음·규칙·모델·모델 오류 | 로컬 |
| 노출 범위 | 앱 포트에서 `/actuator/**`가 열리지 않음, 관리 포트에서 지표가 나옴 | 로컬 |
| 실제 동작 | 패키징 JAR로 진단·자연어 요청 후 지표 확인(Q4) | 로컬 |
| 운영 | Q3 | 운영 |
| 전체 | `./gradlew test` | 로컬 |

## 실행 결과 (2026-10-25)
- 단위·HTTP 테스트(로컬): `MeteredLlmClientTest`(성공 시 호출·지연·보고된 토큰만, 실패 시 error 횟수만 세고 같은 예외), `DeploymentDiagnosisServiceTest`(경로 rule·no_evidence·reused·model·model_error), `LlmConfigTest`(빈이 래퍼로 감싸짐), `ManagementEndpointHttpTest`(관리 포트 `/actuator/prometheus` 200·`# HELP`, 앱 포트는 200 아님, 관리 포트의 health·env는 닫힘).
- 로컬 실제 실행(패키징 JAR + PostgreSQL 16 + 임시 k3s, 진단·자연어 모델 `gpt-6-luna` 실제 호출 3회, 비용 1센트 미만): 잘못된 probe 포트 배포 진단(규칙) → 같은 배포 재진단(재사용) → 느린 기동 배포 진단(모델) → 재진단(재사용), 자연어 명령 2건. 관리 포트 지표:
  - `klepaas_diagnosis_requests_total` reused 2·rule 1·model 1
  - `klepaas_llm_calls_total` diagnosis success 1·nlp success 2
  - `klepaas_llm_tokens_total` diagnosis input 2,074·output 691·reasoning 249·cached_input 0, nlp input 2,585·output 236·reasoning 117
  - `klepaas_llm_latency_seconds` diagnosis 1회 10.1초, nlp 2회 합 10.0초
- 로컬 Prometheus(v2.54.1)가 `host.docker.internal:8081`을 긁어 target `up`, Grafana(11.2.0) 대시보드에 호출·지연·토큰·경로·모델 미호출 75%·실패 0% 표시. 원자료 `.local/evidence/0174-llm-usage-metrics/`(지표 원문, 대시보드 화면).

## 운영 반영
- 머지 전 확인 필요: 운영 서버에서 관리 포트(기본 8081)를 다른 프로세스가 쓰지 않는지. 쓰고 있으면 백엔드가 뜨지 못하고 배포 수신기가 이전 릴리스로 되돌린다. 그 경우 `MANAGEMENT_PORT`를 비어 있는 포트로 설정한다.
- 2026-10-25 사용자가 운영 서버에서 8081이 비어 있음을 확인했다(머지 전). 기본값 그대로 배포한다.
- 머지 후 배포 workflow 결과, Q3 운영 확인(사용자가 서버 안에서 `curl`)을 다음 PR에서 기록한다.
- 2026-10-25 머지(#175) 후 `main` 배포 workflow 성공. 운영 확인(사용자 실행): 관리 포트가 `127.0.0.1:8081`에서만 LISTEN, `/actuator/prometheus` 응답(`jvm_` 57줄). 재기동 직후 `klepaas_` 지표 없음 → 자연어 명령 1건(`LIST_REPOSITORIES`) 뒤 `klepaas_llm_calls_total{feature=nlp,outcome=success}` 1, 지연 3.2초, 토큰 입력 1,290·출력 46. 원자료 `.local/evidence/0174-llm-usage-metrics/ops/`.

## 회고
- 어긋난 점: 관리 포트에도 앱 보안 필터가 걸린다는 것을 예상하지 못했다(테스트로 발견). 대시보드를 처음에 `rate` 기준으로 만들어 적은 트래픽에서 비어 보였다.
- 원인 분류: 외부 제약 미인지(Spring Security의 관리 컨텍스트 적용, 낮은 트래픽에서의 PromQL 동작).
- 고친 위치: `SecurityConfig`, 대시보드, 이 스펙 결정 기록.
- 남은 것: 운영 확인(Q3), 비용 지표·사용자별 예산 상한(다음 이슈), 없는 경로 500 격차(이슈 후보), 운영 Prometheus·알림(범위 밖).
