package klepaas.backend.ai.entity;

public enum Intent {
    // Platform-level deployment operations
    DEPLOY,
    SCALE,
    RESTART,

    // Legacy read intents
    STATUS,
    LOGS,
    LIST_DEPLOYMENTS,
    LIST_REPOSITORIES,

    // Kubernetes read operations (kubectl equivalent)
    LIST_PODS,
    POD_STATUS,
    SERVICE_STATUS,
    DEPLOYMENT_STATUS,
    LIST_SERVICES,
    LIST_INGRESSES,
    LIST_NAMESPACES,
    LIST_ENDPOINTS,
    GET_SERVICE,
    GET_DEPLOYMENT,
    POD_LOGS,

    // Rollback operations
    LIST_ROLLBACK,
    ROLLBACK,
    ROLLBACK_EXECUTION,

    // Overview, help, cost
    OVERVIEW,
    LIST_COMMANDS,
    COST_ANALYSIS,
    HELP,

    UNKNOWN
}
