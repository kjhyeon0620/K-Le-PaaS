-- 이력 조회 성능 측정용 시드 데이터 (#79). 로컬 측정 DB에만 실행한다. 운영 DB에 실행하지 않는다.
-- 사용: psql -v users=1000 -v repos=3000 -v logs=1000000 -v deploys=300000 -f seed-history.sql
-- 사용자·저장소별 행 수는 random()^k로 치우치게 만든다 (작은 id에 행이 몰림). 같은 seed면 같은 데이터가 만들어진다.

\set ON_ERROR_STOP on
select setseed(0.42);

insert into users (created_at, updated_at, email, name, role)
select now() - interval '400 days', now(), 'user' || i || '@seed.test', 'user' || i, 'USER'
from generate_series(1, :users) i;

insert into repositories (created_at, updated_at, user_id, git_url, owner, repo_name, cloud_vendor)
select now() - interval '380 days', now(),
       1 + floor(:users * random() ^ 2)::bigint,
       'https://github.com/seed/repo' || i, 'seed', 'repo' || i, 'NCP'
from generate_series(1, :repos) i;

-- 명령 이력: 상위 사용자에 몰리게 (random()^3), 최근 1년에 분산
insert into command_log (confirmed, is_executed, requires_confirmation, created_at, updated_at, user_id,
                         ai_response, raw_command, interpreted_intent, risk_level, status)
select r < 0.3, r < 0.8, r < 0.3, t, t,
       1 + floor(:users * random() ^ 3)::bigint,
       'seeded ai response text for benchmark row ' || i,
       'deploy repo' || (i % 97) || ' to production',
       (array['STATUS','DEPLOY','SCALE','RESTART','LOGS'])[1 + (i % 5)],
       (array['LOW','MEDIUM','HIGH'])[1 + (i % 3)],
       (array['SUCCEEDED','FAILED','PENDING','EXPIRED'])[1 + (i % 4)]
from (select i, random() as r, now() - random() * interval '365 days' as t
      from generate_series(1, :logs) i) s;

-- 배포 이력: 상위 저장소에 몰리게 (random()^3), 최근 1년에 분산
insert into deployments (created_at, updated_at, started_at, finished_at, repository_id,
                         branch_name, commit_hash, image_uri, status)
select t, t, t, t + interval '3 minutes',
       1 + floor(:repos * random() ^ 3)::bigint,
       'main', md5(i::text) || substr(md5((i + 1)::text), 1, 8),
       'ghcr.io/seed/app:sha-' || md5(i::text),
       (array['SUCCESS','SUCCESS','SUCCESS','FAILED','CANCELED'])[1 + (i % 5)]
from (select i, now() - random() * interval '365 days' as t
      from generate_series(1, :deploys) i) s;

-- 대량 적재 직후에는 visibility map이 비어 있어 Index Only Scan을 쓸 수 없다. autovacuum이 돈 운영 상태에 맞춘다.
vacuum analyze;
