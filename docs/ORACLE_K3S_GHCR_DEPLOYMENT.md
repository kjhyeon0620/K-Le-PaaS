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
