# 관측 지표

작성: 2026-10-25 (#174). 백엔드는 LLM 호출과 진단 경로 지표를 Micrometer로 모아 Prometheus 형식으로 노출한다.

## 노출

- 관리 포트에서만 연다: `MANAGEMENT_PORT`(기본 8081), `MANAGEMENT_ADDRESS`(기본 `127.0.0.1`). 앱 포트와 공개 경로에는 `/actuator`가 없다.
- 열린 엔드포인트는 `/actuator/prometheus` 하나다(health·env 등은 닫혀 있다). 관리 포트가 서버 밖으로 열리지 않으므로 인증 없이 허용한다(`SecurityConfig`).
- 지표는 메모리에 있다. 재기동하면 0부터 다시 센다. 영구 기록은 진단 이력 테이블(`deployment_diagnoses`)이다.

운영 서버 안에서 확인:

```bash
curl -s 127.0.0.1:8081/actuator/prometheus | grep '^klepaas_'
```

## 지표

| 지표 | 태그 | 뜻 |
|---|---|---|
| `klepaas_llm_calls_total` | `feature`(diagnosis·nlp), `provider`, `model`, `outcome`(success·error) | LLM 호출 수. 응답을 받으면 success(응답 파싱 실패도 과금되므로 success), 공급사 호출 실패·키 없음은 error |
| `klepaas_llm_latency_seconds` | `feature`, `provider`, `model` | 성공 호출 지연(히스토그램). 실패 지연은 타임아웃이 섞여 기록하지 않는다 |
| `klepaas_llm_tokens_total` | `feature`, `provider`, `model`, `kind`(input·cached_input·output·reasoning) | 공급사가 응답에 보고한 토큰. 입력은 캐시 입력을, 출력은 추론 토큰을 포함한다. 보고하지 않는 항목은 세지 않는다 |
| `klepaas_diagnosis_requests_total` | `route`(reused·no_evidence·rule·model·model_error) | 진단 요청이 어느 경로로 끝났는지. reused·no_evidence·rule은 모델을 부르지 않은 경로(#117·#170) |

- 사용자 ID 같은 높은 카디널리티 값과 프롬프트·응답·비밀값은 태그에 넣지 않는다.
- 래퍼(`MeteredLlmClient`)는 `LlmConfig`가 만드는 모든 LLM 클라이언트를 감싼다. 새 기능이 LLM을 쓰면 `feature` 이름만 정하면 된다.

## 자주 쓰는 식

```promql
# 모델을 부르지 않은 진단 비율 (재기동 이후)
sum(klepaas_diagnosis_requests_total{route=~"rule|no_evidence|reused"}) / sum(klepaas_diagnosis_requests_total)

# 기능별 LLM 지연 p95 (재기동 이후, 히스토그램 구간 추정)
histogram_quantile(0.95, sum by (le, feature) (klepaas_llm_latency_seconds_bucket))

# 최근 1시간 기능별 토큰
sum by (feature, kind) (increase(klepaas_llm_tokens_total[1h]))
```

트래픽이 적은 서버에서는 `rate`·`increase`가 비어 보일 수 있다(수집 사이에 값이 변하지 않으면 계산되지 않는다). 대시보드는 재기동 이후 누적값을 기본으로 보인다.

## 비용

이번에는 토큰만 지표로 낸다(#174 결정). 비용은 토큰 × 가격으로 계산한다:

```
비용 = (input − cached_input) × 입력 가격 + cached_input × 캐시 입력 가격 + output × 출력 가격
```

가격은 평가 하네스의 가격표(`backend/src/test/resources/diagnosis-eval/pricing.json`, 확인 날짜 포함)를 쓴다. 제품의 비용 지표와 공급사 청구액 대조는 예산 상한 이슈에서 다룬다. 외부 가격 API·중개 가격표는 실제 청구가와 다를 수 있어 쓰지 않는다.

## 로컬에서 보기

```bash
docker compose --profile observability up -d prometheus grafana
```

- Prometheus <http://127.0.0.1:9090> 가 `host.docker.internal:8081`(백엔드 관리 포트)을 15초마다 긁는다 (`scripts/observability/prometheus.yml`).
- Grafana <http://127.0.0.1:3000> 의 "K-Le-PaaS / K-Le-PaaS LLM 사용량" 대시보드(익명 보기): LLM 호출, 지연 p50·p95·평균, 토큰, 진단 경로, 모델 미호출 비율, 실패 비율, 호출 추이.
- 운영 서버에는 이 구성을 두지 않는다. 운영 Prometheus·알림은 범위 밖이다.
