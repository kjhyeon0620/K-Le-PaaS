# Oracle k3s + GHCR Deployment MVP

This document records the runtime assumptions for deploying an externally built
GHCR image to an Oracle Free Tier k3s target through K-Le-PaaS.

## Scope

- K-Le-PaaS remains the deployment control plane.
- GitHub Actions builds and pushes the linux/arm64 image to GHCR.
- K-Le-PaaS resolves the image URI, applies Kubernetes resources, records status,
  waits for the Kubernetes Deployment to become Available, and sends existing
  deployment notifications.
- The existing NCP path keeps using Kaniko and NCR.

## Build Strategy Mapping

| Cloud vendor | Build strategy | Image source |
| --- | --- | --- |
| `NCP` | `KANIKO` | NCR image produced by the existing Kaniko Job |
| `ON_PREMISE` | `GITHUB_ACTIONS_GHCR` | GHCR image produced by GitHub Actions |

`AWS` remains planned and is not opened by this MVP.

## Oracle k3s Runtime Requirements

- k3s is installed on the Oracle server.
- K-Le-PaaS runs with a kubeconfig that can reach that k3s cluster.
- `K8S_NAMESPACE` points to the target namespace.
- A GHCR image pull secret exists in that namespace when the GHCR image is private.
  For a public image, turn the pull secret off (see "Image Pull Secret").
- For the Oracle MVP, configure the service as `NODE_PORT` when host Nginx is
  the public edge.
- Secrets such as kubeconfig, GHCR tokens, SSH keys, and production env vars must
  stay outside Git.

## Image URI Template

`DeploymentConfig.imageUriTemplate` supports these placeholders:

- `{owner}`
- `{repoName}`
- `{commitHash}`
- `{shortCommitHash}`

Example for the Smart Sousvide backend image:

```text
ghcr.io/kjhyeon0620/smart-sousvide-iot-platform/backend:sha-{commitHash}
```

If a deployment request provides `image_uri`, that explicit image URI is used
instead of the template.

For external image strategies, either `image_uri` or `image_uri_template` must be
configured. K-Le-PaaS does not infer a default GHCR package path because package
names can differ by repository, for example `repo:sha-...` versus
`repo/backend:sha-...`.

## Recommended Trigger

Use GitHub Actions to call K-Le-PaaS after the GHCR push succeeds. A raw push
webhook can arrive before the image exists, so it is not reliable enough for the
Oracle GHCR path by itself.

Raw GitHub push webhooks are ignored for repositories configured with
`GITHUB_ACTIONS_GHCR` or `PREBUILT_IMAGE`. They remain valid for the existing
`KANIKO` path.

## CI Deployment Authentication

The workflow calls `POST /api/v1/deployments` with `Authorization: Bearer <token>`
and `repository_id` in the body.

### GitHub Actions (OIDC, recommended)

GitHub Actions workflows do not need a stored K-Le-PaaS token. The workflow asks
GitHub for a short-lived OIDC token for each run and sends it as the bearer token.
K-Le-PaaS verifies the GitHub signature, issuer, audience, expiry, and branch, then
accepts the deployment only when:

- the registered repository for `repository_id` is the same `owner/repo` as the
  token's `repository` claim,
- `branch_name` is the branch of the token's `ref` (only `refs/heads/main` is
  allowed by default), and
- `commit_hash` matches the token's `sha` (a prefix is allowed).

An invalid, expired, or wrong-audience token, or a token from another branch, gets
HTTP 401. A token that does not match the request gets HTTP 403 (`CLI_005`). The
token can only create deployments; every other API returns 403.

```yaml
permissions:
  contents: read
  packages: write
  id-token: write   # lets the job request an OIDC token

# ... build and push the image, then:
      - name: Trigger K-Le-PaaS deployment
        env:
          KLEPAAS_DEPLOY_URL: ${{ vars.KLEPAAS_DEPLOY_URL }}       # https://<host>/api/v1/deployments
          KLEPAAS_REPOSITORY_ID: ${{ vars.KLEPAAS_REPOSITORY_ID }}
          IMAGE_URI: ${{ env.IMAGE_NAME }}:${{ env.IMAGE_TAG }}
        run: |
          OIDC_TOKEN=$(curl -sSf -H "Authorization: Bearer $ACTIONS_ID_TOKEN_REQUEST_TOKEN" \
            "$ACTIONS_ID_TOKEN_REQUEST_URL&audience=k-le-paas" | jq -r .value)
          echo "::add-mask::$OIDC_TOKEN"
          curl --fail-with-body -X POST "$KLEPAAS_DEPLOY_URL" \
            -H "Authorization: Bearer $OIDC_TOKEN" \
            -H "Content-Type: application/json" \
            -d "{\"repository_id\": ${KLEPAAS_REPOSITORY_ID}, \"branch_name\": \"${GITHUB_REF_NAME}\",
                 \"commit_hash\": \"${GITHUB_SHA}\", \"image_uri\": \"${IMAGE_URI}\"}"
```

The deploy URL and repository ID are not secrets; store them as repository
variables (or secrets). The audience must match the server setting
`github.actions.oidc.audience` (`GITHUB_ACTIONS_OIDC_AUDIENCE`, default `k-le-paas`).
Operators can allow more branches with `github.actions.oidc.allowed-refs`
(`GITHUB_ACTIONS_OIDC_ALLOWED_REFS`, comma-separated full refs).

To move a workflow from a stored token to OIDC: add `id-token: write`, switch the
deploy step to the OIDC token, run the workflow once to confirm the deployment is
accepted, then delete the `KLEPAAS_TOKEN` secret and revoke that token in
`Settings > CLI Tokens`.

### Other CI systems (deploy-only token)

For CI systems other than GitHub Actions, use a **deploy-only** CLI token:

1. In the web console, open `Settings > CLI Tokens`, choose `배포 전용`, select the
   target repository, and issue the token.
2. Store it as a CI secret (for example `KLEPAAS_TOKEN`) together with the matching
   `KLEPAAS_REPOSITORY_ID`.
3. A deploy-only token can create deployments only for that repository. Requests
   for another repository return HTTP 403 (`CLI_005`), and every other API,
   including reads, is rejected. Rotate the token before its expiry date.

### Concurrent and repeated requests

Only one deployment runs per repository. While one is in progress:

- A retry of the same request (same `branch_name`, `commit_hash`, and `image_uri`)
  returns the in-progress deployment with HTTP 200 instead of creating another one,
  so CI retries are safe.
- Any other request returns HTTP 409 (`DEPLOY_003`) with the in-progress deployment
  ID in the message. Re-run the workflow after that deployment finishes.

After a deployment finishes, the same request creates a new deployment (an
intentional redeploy).

## Image Pull Secret

By default every deployment references an image pull secret: the configured
`image_pull_secret_name`, or the server default `kubernetes.image-pull-secret`.
If that secret holds an expired or revoked credential, the registry rejects the
pull (GHCR answers 403) even for a public image, and the deployment fails as
soon as the new pod reports `ImagePullBackOff`.

For a public image, set `image_pull_secret_enabled: false` in the repository
deployment config (`PUT /api/v1/repositories/{id}/config`) or turn off "Use image
pull secret" in the console. Deployments then run without `imagePullSecrets`, and
no registry credential needs to be kept valid. The stored secret name is kept and
is used again if you turn the setting back on. Omitting the field keeps the current
value; existing repositories keep using their pull secret.

For a private image, keep it on and keep the referenced secret valid. A read-only
registry token (for GHCR, `read:packages` only) limits the impact if it leaks.

## Rollout Result

A deployment request is judged only by the Deployment generation it applied.
The applied request ID is kept in the Deployment annotation
`klepaas.io/deployment-id`.

| Result | When | `fail_reason` example |
|---|---|---|
| `SUCCESS` | That generation is observed and all desired replicas are updated and available | - |
| `FAILED` (immediately) | A new pod waits with `ImagePullBackOff`, `InvalidImageName`, `ErrImageNeverPull`, `CreateContainerConfigError`, or `CrashLoopBackOff`, or the Deployment reports `ProgressDeadlineExceeded` | `rollout 실패: ImagePullBackOff (pod=..., generation=5): Back-off pulling image ...` |
| `FAILED` (timeout) | No result within `kubernetes.rollout.timeout-ms` (120 seconds), e.g. a failing readiness probe or an unschedulable pod | `rollout 타임아웃(120초, generation=8): observedGeneration=8, updated=1, available=1, unavailable=1` |
| `CANCELED` | Another apply, restart, or scale changed the Deployment while waiting | `다른 변경으로 대체됨: generation 6 → 7` |

On failure the previous pods keep serving. A canceled request is not retried;
the newer change is judged on its own.

`fail_reason` carries the observed evidence: for `CrashLoopBackOff` the last
termination reason and exit code (for example `마지막 종료=OOMKilled, exit=137`),
and on timeout the new pod state (`스케줄 불가: <scheduler message>` or
`실행 중이나 Ready 아님`).

To see the pod state, recent logs, and Kubernetes events of the pods a
deployment request applied, use `GET /api/v1/deployments/{id}/logs`,
`klepaas deployments logs <id>`, or the Logs button in the console. When a later
request has already replaced the Deployment, the earlier request shows
`NOT_CURRENT` instead of another request's pods. Logs are read live and are not
stored; events expire after the cluster's event TTL (one hour by default).

## Example: second app (Node.js)

[kjhyeon0620/klepaas-sample-node](https://github.com/kjhyeon0620/klepaas-sample-node)
is a dependency-free Node.js app deployed the same way (#55). Its workflow builds a
linux/arm64 image on `ubuntu-24.04-arm`, pushes `ghcr.io/<owner>/<repo>:sha-<commit>`,
and calls the deployment API with an OIDC token.

- Keep the deployment URL as a repository **secret** so a public repository's
  Actions logs do not show the server host. The repository ID is not secret; keep it
  as a repository **variable** (a short numeric secret masks every matching digit in
  the logs).
- A manual run (`workflow_dispatch`) with an `image_tag` input deploys an existing
  image without building. Use it to return to a previous image. The deployment
  record then shows the commit the workflow ran on (OIDC requires it) and the
  previous image.
- Restoring a previous image does not undo a configuration change. If a deployment
  failed because of its configuration (for example a missing env var), fix the
  configuration and deploy again. Deployment records do not store the configuration.

## Service Exposure

The default Kubernetes Service type remains `CLUSTER_IP`, preserving the existing
NCP behavior. For Oracle single-node k3s behind host Nginx, set:

```text
service_type: NODE_PORT
node_port: 30080
container_port: 8080
```

Then route host Nginx to the selected node port, for example
`http://127.0.0.1:30080`.

`NODE_PORT` requires an explicit `node_port` so the host Nginx upstream remains
stable. Switching back to `CLUSTER_IP` clears the stored `node_port`.

## Health Probe and Resources

By default a deployed container has no probes and no CPU/memory requests or
limits. For an HTTP app, set `health_probe` in the repository deployment
config (`PUT /api/v1/repositories/{id}/config`) or in the console:

```json
{
  "health_probe": {
    "path": "/health",
    "port": null,
    "initial_delay_seconds": 5,
    "period_seconds": 10,
    "failure_threshold": 3,
    "startup_failure_threshold": 30
  },
  "resources": {
    "cpu_request": "100m",
    "cpu_limit": "500m",
    "memory_request": "128Mi",
    "memory_limit": "256Mi"
  }
}
```

- `health_probe` becomes an HTTP readiness probe. A deployment is recorded as
  successful only when the new pods pass it, so a wrong path makes the
  deployment fail at the rollout timeout instead of succeeding.
  See [Rollout Result](#rollout-result).
- `port` defaults to `container_port`. Omitted timing values use the Kubernetes
  defaults.
- `startup_failure_threshold` adds a startup probe on the same path for slow
  starting apps. The app gets `period_seconds × startup_failure_threshold`
  seconds to start (300 seconds with the default 10-second period and 30)
  before the pod restarts. The deployment still fails if the app is not ready
  within the rollout timeout.
- `resources` values are Kubernetes quantities. Each value must be greater than
  zero and a request cannot exceed its limit.
- Omitting `health_probe` or `resources` in an update keeps the stored values.
  Sending a group replaces all of its fields: an empty `path` removes the
  probes, and empty resource values remove the requests/limits.

## Default Deployment Domain

When a repository is registered without a custom `domain_url`, K-Le-PaaS creates
a default deployment domain from the repository name:

```text
{repo_name}.{DEPLOYMENT_DOMAIN_SUFFIX}
```

`repo_name` is normalized for DNS use before the domain is stored: lowercase
letters, numbers, and `-` are kept; other characters become `-`; repeated `-`
characters are collapsed; leading and trailing `-` characters are removed.

The default suffix is:

```text
DEPLOYMENT_DOMAIN_SUFFIX=klepaas.io
```

For the Oracle server, set:

```text
DEPLOYMENT_DOMAIN_SUFFIX=juhyeon.app
```

Example:

```text
smart-sousvide-iot-platform.juhyeon.app
```

If a repository create request includes `domain_url`, that custom value is used
instead. `domain_url` must be unique across repositories. When a default or
custom domain is already in use, K-Le-PaaS rejects the request instead of adding
suffixes such as `-2` or `-3`; set a different custom `domain_url` explicitly.
