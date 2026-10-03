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

## CI Deployment Token

The workflow calls `POST /api/v1/deployments` with `Authorization: Bearer <token>`
and `repository_id` in the body. Use a **deploy-only** CLI token for this secret:

1. In the web console, open `Settings > CLI Tokens`, choose `배포 전용`, select the
   target repository, and issue the token.
2. Store it as the repository secret (for example `KLEPAAS_TOKEN`) together with
   the matching `KLEPAAS_REPOSITORY_ID`.
3. A deploy-only token can create deployments only for that repository. Requests
   for another repository return HTTP 403 (`CLI_005`), and every other API,
   including reads, is rejected.

Only one deployment runs per repository. While one is in progress:

- A retry of the same request (same `branch_name`, `commit_hash`, and `image_uri`)
  returns the in-progress deployment with HTTP 200 instead of creating another one,
  so CI retries are safe.
- Any other request returns HTTP 409 (`DEPLOY_003`) with the in-progress deployment
  ID in the message. Re-run the workflow after that deployment finishes.

After a deployment finishes, the same request creates a new deployment (an
intentional redeploy).

To replace an existing full-access CI token: issue the deploy-only token, update
the repository secret, re-run the workflow once to confirm the deployment is
accepted, then revoke the old token in the same settings screen. Rotate the token
before its expiry date.

GitHub Actions OIDC authentication is planned in
[#65](https://github.com/kjhyeon0620/K-Le-PaaS/issues/65). It will accept the
short-lived identity token GitHub issues to each workflow run, so repositories will
no longer need a stored K-Le-PaaS token or manual rotation. Deploy-only tokens will
remain for CI systems other than GitHub Actions.

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
