# Oracle ARM64 systemd releases

This deploy path ships one Git revision as a backend JAR and complete Next.js standalone frontend. CI builds on ARM64, sends a compressed release over a pinned SSH connection, and asks the root-owned receiver to validate and activate it. The receiver never receives application secrets. Its working directories and environment files come from the existing systemd units. The backend connects to a PostgreSQL database on the same host through the `DB_*` variables in its `EnvironmentFile` ([ADR-0008](adr/0008-postgresql-production-database.md)); no deployment step creates, copies, or restores the database.

## Normal release flow

After the initial installation is complete, follow this workflow; do not repeat account provisioning or the initial database migration for every merge.

1. Open a PR to `main`. The `build` job runs backend tests and packaging, builds the ARM64 frontend, and verifies the release archive. PRs to `develop` are also checked but are not deployed.
2. Merge after the required checks pass. The resulting `main` push builds that revision and, on success, runs the `production` deploy job. Manual workflow dispatch is supported only on `main`.
3. Read the **deploy job and its log**, not just the build result. A superseded commit may be skipped; the latest main release is the intended target. The receiver checks backend readiness/revision and the frontend release marker before reporting success.
4. On release failure, inspect the failed run. The receiver attempts to restore the previous application release and reports failure even if restoration succeeds. Follow the recovery procedure below if restoration also fails.

This workflow updates the K-Le-PaaS platform itself. User applications use their own image build and callback flow described in [Oracle k3s + GHCR deployment](ORACLE_K3S_GHCR_DEPLOYMENT.md).

Releases restart services; they do not promise zero downtime. Database backups/restores, application environment changes, and inactive release cleanup are separate operator tasks. Schema changes ship as Flyway migrations in `backend/src/main/resources/db/migration` and are applied when the backend starts, before Hibernate validates the mappings (`ddl-auto=validate`). An empty database starts from the PostgreSQL baseline `V1`; later releases apply only newer versions. Before merging a release that adds a migration, test it against a copy of the production database and take a `pg_dump` backup. Migrations are not reversed by an application rollback, so keep each migration compatible with the previous release. Never treat application rollback as database rollback.

### Writing schema migrations

- Add at most one migration per PR, named `V<n>__<what_and_why>.sql`, where `<n>` is the latest merged version plus one. Versions are sequential integers.
- If another PR takes the same number first, renumber yours before merging. Never edit or rename a migration that has been merged or applied: Flyway checksum validation then fails at startup.
- Add columns as nullable or with a default so the previous release's JAR still reads and writes the table after an application rollback.
- Test-only migrations live in `backend/src/test/resources/db/testmigration` with a `V9999__` prefix so they never collide with real versions.
- Before merging, start the packaged JAR against a PostgreSQL database migrated to the previous release's schema (locally with `compose.yaml`) and against a copy of the production database (`pg_dump`/`pg_restore`) with `--spring.jpa.hibernate.ddl-auto=validate`, and confirm the new version is applied, readiness returns `UP`, and existing rows remain.

The rationale is recorded in [ADR-0004](adr/0004-flyway-forward-compatible-migrations.md). The H2 migrations were replaced once by the PostgreSQL baseline ([ADR-0008](adr/0008-postgresql-production-database.md)); that exception does not apply to later migrations.

## Initial installation

Do this during a maintenance window before enabling the production deploy job. Record `systemctl cat <backend-unit>` and `systemctl cat <frontend-unit>` and test that both existing services still start from their original unit definitions. Keep those unit definitions: on the first failed release, the receiver removes only its own `90-klepaas-release.conf` drop-ins and restarts the original legacy JAR and `npm start` frontend. The original frontend does not need a standalone build for this fallback. The backend needs PostgreSQL before its first release; see [PostgreSQL](#postgresql).

Confirm installed Java 17, Node.js 22, Python 3.12, `systemd`, `sudo` and disk space for two complete releases. **Before any release**, both original application units must run under a dedicated runtime identity with no sudo authorization and no SSH login or writable SSH authentication material. A sudo-capable account is unsafe even with `NoNewPrivileges=true`: released code could create a fresh login outside its unit. Provision the runtime identity and grant it only the existing backend working directory write access and required application file reads; validate the legacy units still start and keep the original backend data location. Root owns unit definitions, the deployment tree, and the deploy SSH account's authorized key. The example release base below is root-owned and world-readable so the dedicated app account can run the artifacts. Ensure `/opt/klepaas`, `/opt/klepaas/releases` and `/etc/klepaas` cannot be written by either the deploy SSH account or application account. The receiver also sets `NoNewPrivileges=true` on both release units as defense in depth. Verify the backend unit retains its original `WorkingDirectory=` and absolute `EnvironmentFile=`. Verify the frontend unit retains its absolute `EnvironmentFile=` for runtime secrets; deployment sets its `WorkingDirectory=` to the standalone release.

The application identity must have both read and traverse permission on the backend working directory, not just traverse permission. Verify the running Java process's actual working directory after changing identities or permissions.

## PostgreSQL

The platform database is PostgreSQL 16 from the OS package on the backend host, managed by systemd and reachable only from localhost. It is not deployed to k3s.

1. Install the `postgresql` package. Keep `listen_addresses = 'localhost'` and password (`scram-sha-256`) authentication for host connections; do not open the port externally.
2. Create a login role (default name `klepaas`, not a superuser) with a generated password and a database `klepaas` owned by that role. Flyway creates the schema on the first backend start.
3. Add `DB_URL=jdbc:postgresql://localhost:5432/klepaas`, `DB_USERNAME` and `DB_PASSWORD` to the backend unit's `EnvironmentFile` that already holds its secrets (readable only by its owner and the application group). Never put the password in the repository, CI, or release archive. Without `DB_PASSWORD` the backend fails to start (password authentication fails) and the receiver rolls the release back.
4. Back up with `pg_dump -Fc` as a separate operator task. The deploy job does not back up or restore the database.

### Switching from H2 (one-time)

Existing H2 data is not migrated ([ADR-0008](adr/0008-postgresql-production-database.md)).

1. Complete the steps above before merging the first PostgreSQL release.
2. Merge and confirm the deploy job: readiness `UP` and the expected revision.
3. Delete the backend working directory's H2 `data/` directory. Rolling the application back to a release from before the switch then starts on an empty H2 database.
4. Set up the platform again: sign in with GitHub, register repositories and deployment settings, reissue CLI tokens, and update repository IDs used by GitHub Actions deployments.

Install from the reviewed repository revision:

```sh
sudo install -d -o root -g root -m 0755 /opt/klepaas /etc/klepaas
sudo install -d -o root -g root -m 0755 /opt/klepaas/releases
sudo install -o root -g root -m 0755 scripts/deploy/receiver.py /usr/local/sbin/klepaas-deploy
```

Create `/etc/klepaas/deploy.json` owned by root, mode 0644 or more restrictive. Replace unit names and loopback ports with the actual *already running* production services; keep each URL an HTTP origin with no path:

```json
{
  "release_base": "/opt/klepaas",
  "backend_unit": "BACKEND_UNIT.service",
  "frontend_unit": "FRONTEND_UNIT.service",
  "backend_url": "http://127.0.0.1:8080",
  "frontend_url": "http://127.0.0.1:3000"
}
```

Use a dedicated SSH account with a public key restricted to the receiver. The deploy account needs no write permission in the release base. Install a narrowly scoped sudoers rule with `visudo -f /etc/sudoers.d/klepaas-deploy` (root ownership, mode 0440):

```text
klepaas-deploy ALL=(root) NOPASSWD: /usr/local/sbin/klepaas-deploy *
```

Install exactly this forced-command prefix for the public key in the deploy account's `authorized_keys` (replace the key placeholder):

```text
restrict,command="sudo -n /usr/local/sbin/klepaas-deploy \"$SSH_ORIGINAL_COMMAND\"" ssh-ed25519 PUBLIC_KEY_MATERIAL ci-release
```

The forced command passes one quoted argument to the receiver. The receiver accepts only `deploy <40 lowercase hexadecimal Git SHA>`, rejects all other arguments, checks the root-owned config, and never interprets the argument as a shell command. Make the deploy account's home and `.ssh/authorized_keys` root-controlled so the deploy key cannot replace its own forced command. In CI pin the production server's verified SSH host key in `known_hosts` and use `StrictHostKeyChecking=yes`; do not disable host checking or obtain the key from the same unverified connection. Do not place secret values, key material, public hostnames, or production paths from the operator's private inventory in this file.

In repository settings, define build-time variables `NEXT_PUBLIC_API_URL` (HTTPS) and `NEXT_PUBLIC_WS_URL` (WSS). In the `production` GitHub environment, configure protected-branch access for `main` and secrets `DEPLOY_HOST`, `DEPLOY_USER` (`klepaas-deploy`), `DEPLOY_PORT`, `DEPLOY_SSH_KEY`, and `DEPLOY_KNOWN_HOSTS` (verified host-key line). Require the `build` job as the branch protection check before merging; the `deploy` job runs only on `main` push or manual `main` dispatch and serializes production deployments. CI keeps the release tarball artifact for 14 days. On-host releases remain available for rollback until an operator manually prunes only inactive, non-fallback releases; there is no automatic pruning or database backup in the deployment job.

## Archive contract and release gates

The CI release tarball contains `backend.jar`, a complete `frontend/` standalone output (`server.js`, bundled `node_modules`, `.next` runtime/static, and `public`), `frontend/public/release.txt`, `REVISION`, and `SHA256SUMS`. Both text revision markers contain the same 40-character commit SHA and a newline. `SHA256SUMS` has one `<sha256><two spaces><relative path>` line for **every** regular file except the manifest itself, including `REVISION`. No links, special files, duplicate paths, dot components, absolute paths, nested parent escapes, or unexpected root entries are allowed. CI must not include `.env` files or secrets in the artifact. The receiver bounds compressed bytes, individual files, expanded bytes, and entry count, then stages and verifies everything before switching.

The receiver creates `/opt/klepaas/releases/<sha>` and atomically updates `/opt/klepaas/current`. Root-owned systemd drop-ins change the backend and frontend launch commands, prohibit privilege escalation, and set the standalone frontend working directory. The backend command forces `--spring.jpa.hibernate.ddl-auto=validate`; a schema mismatch fails the release. Backend readiness must return HTTP 200 at `/api/v1/system/ready` (including a database probe), `/api/v1/system/version` must report `data.revision == <sha>`, the frontend must serve the exact SHA at `/console/release.txt`, and `/console` must return HTML. Replaying the active SHA rechecks these endpoints without restarting services. If start, readiness, revision, or frontend probes fail, the receiver restores the previous symlink and the original drop-in contents, restarts both services, checks the prior revision (or the legacy health endpoint on the first release), and exits nonzero even when rollback succeeds. Uploads, lock waits, service control, and HTTP probes have time limits. A hard kill or server crash during the switch still requires operator inspection of `current` and the units. No deployment step changes or restores the database.

Run the local receiver checks with `python3 -m unittest discover -s scripts/deploy -p 'test_*.py' -v`. They exercise malicious tar members, revision validation, first-release legacy fallback, a successful revision switch, an idempotent replay, and a failed-health rollback against temporary filesystem state and simulated systemd/HTTP boundaries. They do not touch real units, accounts, or production.

## Incident and manual recovery

Check the failed workflow's exit status and inspect `systemctl status` and `journalctl -u <unit>` on the server using the operator's normal access; do not print application environment files into CI logs. If a release failed and automatic rollback also failed, restore the prior release by atomically pointing `current` to the previous *validated* `/opt/klepaas/releases/<sha>` under root, reload units when changing drop-ins, restart both units, then verify the same revision/readiness/frontend probes. Before the first successful release, remove only `90-klepaas-release.conf` for the two configured units, remove the receiver-created `current` symlink, reload and restart the original units, then check the legacy backend `/api/v1/system/health` and frontend HTML. Do not delete any release while it is the active or fallback target. A code rollback does not reverse schema migrations: make that decision separately from deployment, with a stopped database and a verified backup if restoration is required.

## #57 두 릴리스 배포 순서

1. 준비 릴리스는 V5로 nullable `applied_resource_uid`·`applied_generation`과 status CHECK의 `UNKNOWN` 허용을 추가한다. UNKNOWN 읽기·표시를 지원하지만 생성하거나 기동 대조를 실행하지 않는다. 기존 행은 새 컬럼이 null이며, 이전 JAR가 읽을 수 있는 상태만 계속 쓴다.
2. 준비 릴리스의 운영 배포·V5 적용을 확인한 후 다음 릴리스에서 기동 대조와 UNKNOWN 기록을 활성화한다. 이때 롤백 대상은 준비 릴리스다. UNKNOWN 행이 생긴 뒤에는 준비 릴리스보다 오래된 JAR로 되돌릴 수 없다.

준비 릴리스는 PR #97로 운영 배포됐다 (2026-10-10). 활성화 릴리스는 PR #98이며, 배포 후 롤백 대상은 준비 릴리스 이상이어야 한다. 활성화 릴리스는 새 마이그레이션과 새 `failure_kind` 값을 추가하지 않는다.

각 릴리스는 별도 PR과 사용자 승인을 거친다. V4→V5는 로컬 패키징 JAR 및 운영 DB 사본에서 기존 배포 행·값 유지, 새 컬럼 null, Flyway 적용, Hibernate validate, readiness를 확인한다. 로컬에서는 이전 JAR의 조회·배포 행 생성/갱신도 검증한다. 운영 원본에 검증용 UNKNOWN을 넣지 않는다. 상세 범위와 증거는 [스펙 0057](specs/0057-reconcile-interrupted-work/spec.md)을 따른다.
