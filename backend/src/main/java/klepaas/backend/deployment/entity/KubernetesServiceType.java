package klepaas.backend.deployment.entity;

public enum KubernetesServiceType {
    CLUSTER_IP("ClusterIP"),
    NODE_PORT("NodePort");

    private final String kubernetesValue;

    KubernetesServiceType(String kubernetesValue) {
        this.kubernetesValue = kubernetesValue;
    }

    public String getKubernetesValue() {
        return kubernetesValue;
    }
}
