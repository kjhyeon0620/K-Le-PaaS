# 로컬 콘솔 검증 절차

스펙의 `완료 증거`에서 콘솔 동작을 확인할 때 쓰는 절차다. 에이전트와 사람이 같은 순서로 띄우고, 같은 방식으로 로그인하고, 같은 위치에 증거를 남긴다.
화면별로 무엇을 로컬에서 확인할 수 있는지는 [`features.md`](features.md)를 본다.

- 환경: 로컬 PostgreSQL 16 + 로컬 백엔드 + 로컬 콘솔. 결과에는 `로컬`로 적는다. 클러스터·운영 검증을 대신하지 않는다.
- 이 문서의 토큰·비밀번호·키는 모두 로컬 전용 더미 값이다. 실제 값을 여기에 쓰지 않는다.

## 1. 띄우기

### 1.1 DB

다른 프로젝트가 5432를 쓰고 있을 수 있으므로 검증용 compose 프로젝트와 포트를 따로 쓴다. 매번 빈 DB에서 시작한다.

```bash
DB_PASSWORD=local-only DB_PORT=15432 docker compose -p klepaas-verify up -d postgres
docker compose -p klepaas-verify exec postgres pg_isready -U klepaas
```

### 1.2 백엔드

`--spring.config.import=`로 `backend/.env`를 읽지 않게 한다. 그 파일에는 실제 비밀값이 있을 수 있다.
외부 연동 값은 기동만 통과하는 더미이고, Kubernetes는 닿지 않는 주소로 막는다.

```bash
cd backend
env DB_URL=jdbc:postgresql://127.0.0.1:15432/klepaas DB_USERNAME=klepaas DB_PASSWORD=local-only \
  JWT_SECRET=local-verify-only-secret-at-least-32-bytes \
  GITHUB_CLIENT_ID=local-unused GITHUB_CLIENT_SECRET=local-unused \
  GITHUB_APP_ID=1 GITHUB_APP_PRIVATE_KEY=local-unused GITHUB_APP_SLUG=local-unused \
  NCP_ACCESS_KEY=local-unused NCP_SECRET_KEY=local-unused NCP_STORAGE_BUCKET=local-unused NCR_ENDPOINT=local-unused \
  GEMINI_API_KEY=local-unused OPENAI_API_KEY= SLACK_WEBHOOK_URL= \
  KUBERNETES_DISABLE_AUTOCONFIG=true KUBERNETES_MASTER=http://127.0.0.1:1 \
  ./gradlew bootRun --args='--spring.config.import= --server.address=127.0.0.1'
```

- 오래 실행되는 명령이므로 백그라운드로 띄운다. 같은 작업 트리에서 다른 Gradle 명령과 동시에 돌리지 않는다.
- 준비 확인: `curl -s http://127.0.0.1:8080/api/v1/system/ready`가 `"status":"UP"`을 돌려줄 때까지 기다린다. Flyway 마이그레이션이 이때 끝난다.

### 1.3 콘솔

백엔드 CORS 기본값이 `http://localhost:3000`이므로 콘솔도 `localhost:3000`으로 띄운다.

```bash
cd frontend
NEXT_PUBLIC_API_URL=http://localhost:8080 npm run dev
```

- 주소: `http://localhost:3000/console`

## 2. 로그인

GitHub OAuth는 로컬에서 쓸 수 없다. 테스트 사용자와 CLI 토큰을 DB에 넣고, 같은 토큰을 브라우저에 넣는다.
서버는 토큰의 SHA-256 해시만 저장하고, 콘솔은 `/api/v1/auth/me`로 토큰을 확인한다.

```bash
TOKEN=kpa_cli_local_verify_only
HASH=$(printf %s "$TOKEN" | shasum -a 256 | cut -d' ' -f1)
docker compose -p klepaas-verify exec -T postgres psql -U klepaas -d klepaas -v ON_ERROR_STOP=1 <<SQL
insert into users(created_at, updated_at, email, name, role)
  values (now(), now(), 'local@example.test', 'local', 'USER');
insert into cli_access_tokens(created_at, updated_at, expires_at, user_id, token_prefix, token_hash, name, scope)
  values (now(), now(), now() + interval '1 day', 1, 'kpa_cli_loca', '$HASH', 'local', 'FULL');
SQL
curl -s -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8080/api/v1/auth/me
```

브라우저에서 `http://localhost:3000/console`을 연 뒤 개발자 도구 콘솔(또는 에이전트의 JavaScript 실행 도구)에서 넣고 새로 고친다.
`user` 값은 위 `auth/me` 응답의 `data`를 그대로 쓴다.

```js
localStorage.setItem('auth_token', 'kpa_cli_local_verify_only')
localStorage.setItem('user', JSON.stringify(/* auth/me 응답의 data */))
location.reload()
```

## 3. 검증할 데이터 준비

저장소 연결(Connect New Repository)은 GitHub App이 필요하므로 로컬에서는 DB에 직접 넣는다.
화면에 필요한 데이터만 넣고, 넣은 SQL은 증거 폴더에 함께 남긴다.

```bash
docker compose -p klepaas-verify exec -T postgres psql -U klepaas -d klepaas -v ON_ERROR_STOP=1 <<'SQL'
insert into repositories(created_at, updated_at, user_id, git_url, owner, repo_name, cloud_vendor)
  values (now(), now(), 1, 'https://example.test/local/sample', 'local', 'sample', 'ON_PREMISE');
insert into deployment_configs(created_at, updated_at, repository_id, container_port, max_replicas, min_replicas, domain_url)
  values (now(), now(), 1, 8080, 1, 1, 'sample.local.test');
SQL
```

## 4. 조작과 증거

- 화면 이동, 버튼 이름, 기대 결과는 [`features.md`](features.md)를 따른다.
- 증거는 `.local/evidence/<스펙 번호>-<slug>/`에 둔다 (git 제외). 스크린샷, 실행한 SQL, API 응답, 백엔드 로그.
- 화면 결과와 API 또는 DB 조회 결과를 함께 남긴다. 화면만으로는 저장 여부를 증명하지 못한다.
- 저장 후에는 새로 고침해서 값이 유지되는지 본다.
- 스펙에는 증거 요약만 옮긴다: 확인한 것, 방법, 환경(`로컬`), 하지 않은 검증.
- 기대와 다르게 동작하면 고치기 전에 같은 절차로 재현한 증거부터 남긴다.

## 5. 정리

```bash
# 콘솔과 백엔드 프로세스를 멈춘 뒤
DB_PASSWORD=local-only docker compose -p klepaas-verify down -v
```

브라우저 localStorage의 `auth_token`, `user`도 지운다.
