#!/usr/bin/env bash
# 이력 조회 인덱스 전후 측정 (#79). 로컬 Docker PostgreSQL에서만 실행한다.
# 1) 빈 컨테이너에 V1~V2 적용 2) 시드 3) 측정(before) 4) V3 적용 5) 측정(after) 6) 컨테이너 삭제
# 사용: scripts/db/history-index-benchmark.sh [출력 디렉터리]
#   환경변수: RUNS(기본 10), IMAGE(기본 postgres:16-alpine), KEEP=1이면 컨테이너를 남긴다.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
MIGRATIONS="$ROOT/backend/src/main/resources/db/migration"
OUT=${1:-"$ROOT/.local/evidence/0079-history-query-indexes/$(date +%Y-%m-%d)"}
RUNS=${RUNS:-10}
IMAGE=${IMAGE:-postgres:16-alpine}
NAME=klepaas-history-bench
mkdir -p "$OUT"

psql_() { docker exec -i "$NAME" psql -X -q -v ON_ERROR_STOP=1 -U bench -d bench "$@"; }
# 반복문 안에서 쓰는 -c 전용 호출. -i가 없어야 반복문의 입력(쿼리 목록)을 읽어 가지 않는다
psqlc() { docker exec "$NAME" psql -X -q -v ON_ERROR_STOP=1 -U bench -d bench "$@"; }

cleanup() { [ "${KEEP:-0}" = 1 ] || docker rm -f "$NAME" >/dev/null 2>&1 || true; }
trap cleanup EXIT

docker rm -f "$NAME" >/dev/null 2>&1 || true
docker run -d --name "$NAME" -e POSTGRES_USER=bench -e POSTGRES_PASSWORD=bench -e POSTGRES_DB=bench "$IMAGE" >/dev/null
until docker exec "$NAME" pg_isready -U bench -d bench -q 2>/dev/null; do sleep 1; done
sleep 2

for f in "$MIGRATIONS"/V1__*.sql "$MIGRATIONS"/V2__*.sql; do psql_ < "$f"; done
echo "seeding..."
psql_ -v users=1000 -v repos=3000 -v logs=1000000 -v deploys=300000 < "$ROOT/scripts/db/seed-history.sql"

# 측정 대상: 행이 가장 많은 사용자·저장소(heavy)와 행 수가 중앙값인 사용자·저장소(median)
read -r HEAVY_USER MEDIAN_USER HEAVY_REPO MEDIAN_REPO HEAVY_REPO_OWNER < <(psql_ -tA -F' ' <<'SQL'
with u as (select user_id, count(*) c from command_log group by user_id),
     r as (select repository_id, count(*) c from deployments group by repository_id)
select (select user_id from u order by c desc, user_id limit 1),
       (select user_id from u order by c, user_id offset (select count(*) / 2 from u) limit 1),
       (select repository_id from r order by c desc, repository_id limit 1),
       (select repository_id from r order by c, repository_id offset (select count(*) / 2 from r) limit 1),
       (select user_id from repositories where id = (select repository_id from r order by c desc, repository_id limit 1));
SQL
)

{
  echo "image: $IMAGE ($(docker exec "$NAME" postgres --version))"
  echo "host: $(uname -sm), docker cpus=$(docker info --format '{{.NCPU}}') mem=$(docker info --format '{{.MemTotal}}')"
  echo "runs per query: 1 warm-up + $RUNS measured"
  psql_ -tA <<SQL
select 'rows: users=' || (select count(*) from users) || ' repositories=' || (select count(*) from repositories)
    || ' command_log=' || (select count(*) from command_log) || ' deployments=' || (select count(*) from deployments);
select 'heavy user $HEAVY_USER: ' || count(*) || ' command_log rows' from command_log where user_id = $HEAVY_USER;
select 'median user $MEDIAN_USER: ' || count(*) || ' command_log rows' from command_log where user_id = $MEDIAN_USER;
select 'heavy repo $HEAVY_REPO: ' || count(*) || ' deployments rows' from deployments where repository_id = $HEAVY_REPO;
select 'median repo $MEDIAN_REPO: ' || count(*) || ' deployments rows' from deployments where repository_id = $MEDIAN_REPO;
select 'heavy repo owner $HEAVY_REPO_OWNER: ' || count(*) || ' deployments rows' from deployments d join repositories r on r.id = d.repository_id where r.user_id = $HEAVY_REPO_OWNER;
SQL
} > "$OUT/environment.txt"

# Hibernate가 생성한 SQL과 같은 모양 (첫 페이지 size=20, created_at DESC)
LOG_COLS="cl1_0.id,cl1_0.ai_response,cl1_0.confirmed,cl1_0.created_at,cl1_0.error_message,cl1_0.execution_result,cl1_0.intent_args,cl1_0.interpreted_intent,cl1_0.is_executed,cl1_0.raw_command,cl1_0.requires_confirmation,cl1_0.risk_level,cl1_0.session_id,cl1_0.status,cl1_0.updated_at,cl1_0.user_id"
DEP_COLS="d1_0.id,d1_0.branch_name,d1_0.commit_hash,d1_0.created_at,d1_0.external_build_id,d1_0.fail_reason,d1_0.finished_at,d1_0.github_delivery_id,d1_0.image_uri,sr1_0.id,sr1_0.cloud_vendor,sr1_0.created_at,sr1_0.external_build_project_id,sr1_0.git_url,sr1_0.owner,sr1_0.repo_name,sr1_0.updated_at,sr1_0.user_id,d1_0.started_at,d1_0.status,d1_0.storage_object_key,d1_0.updated_at"
query() { # $1=user $2=repo $3=owner, 출력: name<TAB>sql
  printf 'Q1_page_user%s\tselect %s from command_log cl1_0 where cl1_0.user_id=%s order by cl1_0.created_at desc offset 0 rows fetch first 20 rows only\n' "$1" "$LOG_COLS" "$1"
  printf 'Q1_count_user%s\tselect count(*) from command_log cl1_0 where cl1_0.user_id=%s\n' "$1" "$1"
  printf 'Q2_page_repo%s\tselect %s from deployments d1_0 left join repositories sr1_0 on sr1_0.id=d1_0.repository_id where d1_0.repository_id=%s order by d1_0.created_at desc offset 0 rows fetch first 20 rows only\n' "$2" "$DEP_COLS" "$2"
  printf 'Q2_count_repo%s\tselect count(*) from deployments d1_0 where d1_0.repository_id=%s\n' "$2" "$2"
  printf 'Q2_unsorted_repo%s\tselect %s from deployments d1_0 left join repositories sr1_0 on sr1_0.id=d1_0.repository_id where d1_0.repository_id=%s fetch first 10 rows only\n' "$2" "$DEP_COLS" "$2"
  [ -n "${3:-}" ] && printf 'Q3_page_owner%s\tselect d1_0.id,d1_0.created_at from deployments d1_0 left join repositories sr1_0 on sr1_0.id=d1_0.repository_id left join users u1_0 on u1_0.id=sr1_0.user_id left join repositories sr2_0 on sr2_0.id=d1_0.repository_id where u1_0.id=%s order by d1_0.created_at desc offset 0 rows fetch first 20 rows only\n' "$3" "$3"
  return 0
}

measure() { # $1=phase
  local phase=$1 summary="$OUT/$1-summary.tsv"
  printf 'query\tmedian_ms\tmin_ms\tmax_ms\tplan_top\n' > "$summary"
  { query "$HEAVY_USER" "$HEAVY_REPO" "$HEAVY_REPO_OWNER"; query "$MEDIAN_USER" "$MEDIAN_REPO"; } | while IFS=$'\t' read -r name sql; do
    psqlc -c "explain (analyze, buffers) $sql" >/dev/null   # 워밍업
    local times=()
    for i in $(seq 1 "$RUNS"); do
      plan=$(psqlc -tA -c "explain (analyze, buffers) $sql")
      [ "$i" = 1 ] && printf '%s\n' "$plan" > "$OUT/$phase-$name.plan.txt"
      times+=("$(printf '%s\n' "$plan" | sed -n 's/^Execution Time: \([0-9.]*\) ms$/\1/p')")
    done
    sorted=$(printf '%s\n' "${times[@]}" | sort -g)
    median=$(printf '%s\n' "$sorted" | awk -v n="$RUNS" 'NR==int((n+1)/2){a=$1} NR==int(n/2)+1{b=$1} END{printf "%.3f", (a+b)/2}')
    # 계획에 나온 주요 노드를 위에서부터 (중복 제거)
    top=$(grep -o -E '(Index Only Scan|Index Scan|Bitmap Index Scan|Bitmap Heap Scan|Parallel Seq Scan|Seq Scan|Incremental Sort|Sort|Nested Loop|Hash Join|Merge Join)' "$OUT/$phase-$name.plan.txt" | awk '!seen[$0]++' | paste -sd'>' - || true)
    printf '%s\t%s\t%s\t%s\t%s\n' "$name" "$median" "$(printf '%s\n' "$sorted" | head -1)" "$(printf '%s\n' "$sorted" | tail -1)" "$top" >> "$summary"
  done
  echo "== $phase"; column -t -s $'\t' "$summary"
}

measure before
psql_ < "$MIGRATIONS"/V3__*.sql
psql_ -c "analyze command_log; analyze deployments;"
measure after
echo "results: $OUT"
