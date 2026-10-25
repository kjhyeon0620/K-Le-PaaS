# LLM 경로 부하 시험 (#182)

자연어 명령(`POST /api/v1/nlp/command`)을 동시에 보낼 때 지연, 처리량, DB 연결, 예산 상한을 본다. 실제 모델 대신 가짜 OpenAI 호환 서버를 쓴다(비용 0). **로컬 전용이다. 운영에는 걸지 않는다.** 결과와 해석은 [스펙 0182](../../docs/specs/0182-llm-load-test/spec.md)에 있다.

## 준비

- k6: `brew install k6`
- PostgreSQL 16 (예: `docker run --rm -d --name klepaas-load-pg -p 127.0.0.1:15482:5432 -e POSTGRES_USER=klepaas -e POSTGRES_PASSWORD=local-only postgres:16-alpine`)
- 패키징 JAR: `cd backend && ./gradlew bootJar`

## 1. 가짜 LLM

```bash
python3 scripts/load/fake_llm.py --port 18090 --delay 3
```

`--mode hang`(응답하지 않음), `--mode error`(500)도 있다. `GET /stats`는 받은 요청 수와 동시 처리 최대치를 돌려준다.

## 2. 백엔드

자연어 명령과 진단의 공급사를 가짜 서버로 바꾸고, Tomcat 스레드 지표를 켠다. 그 밖의 필수 환경 변수(DB, JWT, GitHub 등)는 로컬 값으로 채운다.

```bash
NLP_LLM_PROVIDER=openai-compatible NLP_LLM_MODEL=gpt-6-luna NLP_LLM_BASE_URL=http://127.0.0.1:18090/v1 \
DIAGNOSIS_LLM_PROVIDER=openai-compatible DIAGNOSIS_LLM_BASE_URL=http://127.0.0.1:18090/v1 \
LLM_BUDGET_DAILY_USD_PER_USER=0 \
java -jar backend/build/libs/backend-0.0.1-SNAPSHOT.jar --server.port=18068 --server.tomcat.mbeanregistry.enabled=true
```

- 모델 이름 `gpt-6-luna`는 가격표에 있어서 비용이 계산된다(S4).
- S1~S3는 예산 상한을 0(없음)으로 둔다. S4는 작은 값(예: `0.001`)으로 다시 띄운다.

## 3. 테스트 사용자와 토큰

로컬 DB에 사용자, 저장소 1개, FULL scope CLI 토큰을 넣는다. 토큰은 직접 만든 임의 값을 쓰고, 파일이나 출력에 남기지 않는다. 저장되는 것은 SHA-256뿐이다.

```sql
insert into users(created_at,updated_at,email,name,role) values(now(),now(),'load@load.test','load','USER') returning id;
insert into cli_access_tokens(created_at,updated_at,expires_at,user_id,token_prefix,token_hash,name,scope)
  values(now(),now(),now()+interval '1 day',<user_id>,'<토큰 앞 12자>','<토큰 SHA-256 hex>','load','FULL');
insert into repositories(created_at,updated_at,user_id,git_url,owner,repo_name,cloud_vendor)
  values(now(),now(),<user_id>,'https://example.test/load/r','load','r','ON_PREMISE');
```

## 4. 시나리오

```bash
TOKEN=<토큰> SCENARIO=s1 VUS=20 DURATION=30s k6 run scripts/load/nlp.js       # 동시 자연어 명령
TOKEN=<토큰> SCENARIO=s2 VUS=20 PROBE_RPS=2 DURATION=30s k6 run scripts/load/nlp.js   # 부하 중 DB 조회 지연 (VUS=0이면 기준)
TOKEN=<토큰> SCENARIO=s3 VUS=12 TIMEOUT=300s k6 run scripts/load/nlp.js       # 가짜 LLM --mode hang
TOKEN=<토큰> SCENARIO=s4 VUS=50 k6 run scripts/load/nlp.js                    # 동시 50개 한 번씩(예산 초과량)
```

- k6 요약의 `http_req_duration{scenario:nlp}`, `{scenario:probe}`가 시나리오별 지연이다.
- 동시에 관리 포트 지표를 본다: `curl -s 127.0.0.1:8081/actuator/prometheus | grep -E '^hikaricp_connections_(active|pending|max)|^tomcat_threads_busy'`
- S4의 실제 사용액: `select count(*), sum(cost_usd) from llm_usages where user_id=<user_id>;`

## 해석할 때

- 부하 생성기, 서버, DB, 가짜 LLM을 한 대에서 돌린다. 숫자는 같은 환경 안의 상대 비교로만 쓴다(운영 용량이 아니다).
- 가짜 LLM 지연(기본 3초)은 #174 운영 측정(3.2초)을 따른 것이다. 실제 공급사의 지연 분포와 동시 처리 제한은 반영하지 않는다.
