# Oracle ARM64 systemd releases

This deploy path ships one Git revision as a backend JAR and complete Next.js standalone frontend. CI builds on ARM64, sends a compressed release over a pinned SSH connection, and asks the root-owned receiver to validate and activate it. The receiver never receives application secrets. Its working directories and environment files come from the existing systemd units; the backend unit's original working directory must continue to contain the existing H2 `data/` directory. No database file is copied during a deployment.

## One-time operator setup

Do this during a maintenance window before enabling the production deploy job. Record `systemctl cat <backend-unit>` and `systemctl cat <frontend-unit>` and test that both existing services still start from their original unit definitions. Keep those unit definitions: on the first failed release, the receiver removes only its own `90-klepaas-release.conf` drop-ins and restarts the original legacy JAR and `npm start` frontend. The original frontend does not need a standalone build for this fallback. The first automated release **must not run before** the explicit H2 schema/data migration described in [the migration procedure](../backend/src/main/resources/db/manual/README.md). Take the H2 backup only with the backend stopped and use it only for a separately planned restore, never as an automatic deploy rollback. The migration must permit old-version inserts during code rollback; check its exact SQL and run its validation queries first.

Confirm installed Java 17, Node.js 22, Python 3.12, `systemd`, `sudo` and disk space for two complete releases. **Before any release**, both original application units must run under a dedicated runtime identity with no sudo authorization and no SSH login or writable SSH authentication material. A sudo-capable account is unsafe even with `NoNewPrivileges=true`: released code could create a fresh login outside its unit. Provision the runtime identity and grant it only the existing backend working directory/H2 `data/` write access and required application file reads; validate the legacy units still start and keep the original backend data location. Root owns unit definitions, the deployment tree, and the deploy SSH account's authorized key. The example release base below is root-owned and world-readable so the dedicated app account can run the artifacts. Ensure `/opt/klepaas`, `/opt/klepaas/releases` and `/etc/klepaas` cannot be written by either the deploy SSH account or application account. The receiver also sets `NoNewPrivileges=true` on both release units as defense in depth. Verify the backend unit retains its original `WorkingDirectory=` and absolute `EnvironmentFile=`. Verify the frontend unit retains its absolute `EnvironmentFile=` for runtime secrets; deployment sets its `WorkingDirectory=` to the standalone release.

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

The receiver creates `/opt/klepaas/releases/<sha>` and atomically updates `/opt/klepaas/current`. Root-owned systemd drop-ins change the backend and frontend launch commands, prohibit privilege escalation, and set the standalone frontend working directory. The backend command forces `--spring.jpa.hibernate.ddl-auto=validate`; a schema mismatch fails the release. Backend readiness must return HTTP 200 at `/api/v1/system/ready` (including a database probe), `/api/v1/system/version` must report `data.revision == <sha>`, the frontend must serve the exact SHA at `/console/release.txt`, and `/console` must return HTML. Replaying the active SHA rechecks these endpoints without restarting services. If start, readiness, revision, or frontend probes fail, the receiver restores the previous symlink and the original drop-in contents, restarts both services, checks the prior revision (or the legacy health endpoint on the first release), and exits nonzero even when rollback succeeds. Uploads, lock waits, service control, and HTTP probes have time limits. A hard kill or server crash during the switch still requires operator inspection of `current` and the units. No deployment step changes or restores the H2 database.

Run the local receiver checks with `python3 -m unittest discover -s scripts/deploy -p 'test_*.py' -v`. They exercise malicious tar members, revision validation, first-release legacy fallback, a successful revision switch, an idempotent replay, and a failed-health rollback against temporary filesystem state and simulated systemd/HTTP boundaries. They do not touch real units, accounts, or production.

## Incident and manual recovery

Check the failed workflow's exit status and inspect `systemctl status` and `journalctl -u <unit>` on the server using the operator's normal access; do not print application environment files into CI logs. If a release failed and automatic rollback also failed, restore the prior release by atomically pointing `current` to the previous *validated* `/opt/klepaas/releases/<sha>` under root, reload units when changing drop-ins, restart both units, then verify the same revision/readiness/frontend probes. Before the first successful release, remove only `90-klepaas-release.conf` for the two configured units, remove the receiver-created `current` symlink, reload and restart the original units, then check the legacy backend `/api/v1/system/health` and frontend HTML. Do not delete any release while it is the active or fallback target. A code rollback does not reverse schema migrations: make that decision separately from deployment, with a stopped database and a verified backup if restoration is required.
