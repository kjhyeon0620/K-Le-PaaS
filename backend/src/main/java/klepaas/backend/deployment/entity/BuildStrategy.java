package klepaas.backend.deployment.entity;

public enum BuildStrategy {
    KANIKO,
    GITHUB_ACTIONS_GHCR,
    PREBUILT_IMAGE
}
