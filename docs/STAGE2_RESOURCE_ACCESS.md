# Stage 2 resource access

Application users can read Kubernetes resources only in the configured `kubernetes.namespace` and only when those resources carry their repository's `klepaas.io/repository-id` label. A requested different namespace is rejected before a Kubernetes request. Deployment, Service, Ingress, and Pod reads and Pod logs use repository-label selection; an arbitrary resource or Pod name cannot grant access. Existing unlabeled Kubernetes objects need an operator-reviewed label migration before the platform can update or scale them.

The operator provisions ConfigMaps and Secrets outside the application API and explicitly approves their names per repository ID in server configuration. For example, after checking the repository ID, namespace, and actual references for the existing IoT deployment, the operator may add:

```yaml
kubernetes:
  namespace: klepaas
  runtime-resources:
    repositories:
      "<verified-IoT-repository-ID>":
        config-maps: [smart-sousvide-runtime-config]
        secrets: [smart-sousvide-app-env]
        image-pull-secrets: [ghcr-pull-secret]
```

The image pull secret is a separate approval. If the deployment config omits its name, approve the actual `kubernetes.image-pull-secret` fallback for that repository before deployment. Names in this document are a transition example, not global defaults or a grant to another repository. Never put Secret values in this policy. Without an operator entry, empty reference settings can be saved, but deployments that use a fallback pull secret require the explicit per-repository grant. Removing a name revokes it for the next deployment even if the repository saved it earlier.

Update the operator-owned configuration and restart the backend through the normal release process after reviewing each repository ID and reference. Ordinary repository configuration APIs can select only approved names; they cannot modify this server policy or provision a Secret.
