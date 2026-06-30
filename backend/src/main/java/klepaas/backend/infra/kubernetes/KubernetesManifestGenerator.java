package klepaas.backend.infra.kubernetes;

import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.api.model.apps.DeploymentCondition;
import io.fabric8.kubernetes.api.model.networking.v1.Ingress;
import io.fabric8.kubernetes.api.model.networking.v1.IngressBuilder;

import io.fabric8.kubernetes.client.KubernetesClient;
import klepaas.backend.deployment.entity.DeploymentConfig;
import klepaas.backend.deployment.entity.KubernetesServiceType;
import klepaas.backend.global.exception.BusinessException;
import klepaas.backend.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class KubernetesManifestGenerator {

    private final KubernetesClient kubernetesClient;

    @Value("${kubernetes.namespace:default}")
    private String namespace;

    @Value("${kubernetes.image-pull-secret:ncp-cr}")
    private String imagePullSecretName;

    @Value("${kubernetes.rollout.timeout-ms:120000}")
    private long rolloutTimeoutMs;

    @Value("${kubernetes.rollout.poll-interval-ms:2000}")
    private long rolloutPollIntervalMs;

    /**
     * K8s Deployment + Service + Ingress 생성/업데이트
     */
    public void deploy(String appName, String imageUri, DeploymentConfig config, Long repoId) {
        Map<String, String> labels = Map.of(
                "app.kubernetes.io/name", appName,
                "app.kubernetes.io/managed-by", "klepaas",
                "klepaas.io/repository-id", String.valueOf(repoId)
        );

        try {
            createOrUpdateDeployment(appName, imageUri, config, labels);
            createOrUpdateService(appName, config, labels);

            if (config.getDomainUrl() != null && !config.getDomainUrl().isBlank()) {
                createOrUpdateIngress(appName, config.getDomainUrl(), config.getContainerPort(), labels);
            }

            log.info("K8s resources deployed: app={}, namespace={}", appName, namespace);
        } catch (Exception e) {
            log.error("K8s deployment failed: app={}, error={}", appName, e.getMessage(), e);
            throw new BusinessException(ErrorCode.DEPLOY_FAILED, "K8s 배포 실패: " + e.getMessage());
        }
    }

    /**
     * K8s 리소스 스케일링
     */
    public void scale(String appName, int replicas) {
        kubernetesClient.apps().deployments()
                .inNamespace(namespace)
                .withName(appName)
                .scale(replicas);
        log.info("Scaled: app={}, replicas={}", appName, replicas);
    }

    public void waitForDeploymentAvailable(String appName) {
        long deadline = System.currentTimeMillis() + rolloutTimeoutMs;
        while (System.currentTimeMillis() <= deadline) {
            Deployment deployment = kubernetesClient.apps().deployments()
                    .inNamespace(namespace)
                    .withName(appName)
                    .get();
            if (isDeploymentRolloutComplete(deployment)) {
                log.info("Deployment rollout available: app={}, namespace={}", appName, namespace);
                return;
            }
            sleepBeforeNextRolloutCheck(appName);
        }
        throw new BusinessException(
                ErrorCode.DEPLOY_FAILED,
                "K8s rollout 타임아웃: " + appName + " Deployment가 Available 상태가 아닙니다"
        );
    }

    boolean isDeploymentRolloutComplete(Deployment deployment) {
        if (deployment == null
                || deployment.getMetadata() == null
                || deployment.getSpec() == null
                || deployment.getStatus() == null
                || deployment.getStatus().getConditions() == null) {
            return false;
        }
        int desiredReplicas = valueOrZero(deployment.getSpec().getReplicas());
        int updatedReplicas = valueOrZero(deployment.getStatus().getUpdatedReplicas());
        int availableReplicas = valueOrZero(deployment.getStatus().getAvailableReplicas());
        int unavailableReplicas = valueOrZero(deployment.getStatus().getUnavailableReplicas());
        long generation = valueOrZero(deployment.getMetadata().getGeneration());
        long observedGeneration = valueOrZero(deployment.getStatus().getObservedGeneration());

        return deployment.getStatus().getConditions().stream()
                .anyMatch(this::isAvailableCondition)
                && observedGeneration >= generation
                && updatedReplicas == desiredReplicas
                && availableReplicas == desiredReplicas
                && unavailableReplicas == 0;
    }

    private boolean isAvailableCondition(DeploymentCondition condition) {
        return "Available".equals(condition.getType()) && "True".equals(condition.getStatus());
    }

    private int valueOrZero(Integer value) {
        return value != null ? value : 0;
    }

    private long valueOrZero(Long value) {
        return value != null ? value : 0L;
    }

    private void sleepBeforeNextRolloutCheck(String appName) {
        try {
            Thread.sleep(rolloutPollIntervalMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.DEPLOY_FAILED, "K8s rollout 대기 중단됨: " + appName);
        }
    }

    private void createOrUpdateDeployment(String appName, String imageUri,
                                           DeploymentConfig config, Map<String, String> labels) {
        List<EnvVar> envVars = config.getEnvVars().entrySet().stream()
                .map(e -> new EnvVarBuilder().withName(e.getKey()).withValue(e.getValue()).build())
                .collect(Collectors.toList());

        Deployment deployment = new DeploymentBuilder()
                .withNewMetadata()
                    .withName(appName)
                    .withNamespace(namespace)
                    .withLabels(labels)
                .endMetadata()
                .withNewSpec()
                    .withReplicas(config.getMinReplicas())
                    .withNewSelector()
                        .withMatchLabels(Map.of("app.kubernetes.io/name", appName))
                    .endSelector()
                    .withNewTemplate()
                        .withNewMetadata()
                            .withLabels(labels)
                        .endMetadata()
                        .withNewSpec()
                            .withImagePullSecrets(new LocalObjectReferenceBuilder()
                                    .withName(resolveImagePullSecretName(config))
                                    .build())
                            .withContainers(new ContainerBuilder()
                                    .withName(appName)
                                    .withImage(imageUri)
                                    .withPorts(new ContainerPortBuilder()
                                            .withContainerPort(config.getContainerPort())
                                            .build())
                                    .withEnv(envVars)
                                    .build())
                        .endSpec()
                    .endTemplate()
                .endSpec()
                .build();

        kubernetesClient.apps().deployments()
                .inNamespace(namespace)
                .resource(deployment)
                .serverSideApply();
    }

    private String resolveImagePullSecretName(DeploymentConfig config) {
        String configured = config.getImagePullSecretName();
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        return imagePullSecretName;
    }

    private void createOrUpdateService(String appName, DeploymentConfig config, Map<String, String> labels) {
        ServicePort servicePort = buildServicePort(config);
        Service service = new ServiceBuilder()
                .withNewMetadata()
                    .withName(appName)
                    .withNamespace(namespace)
                    .withLabels(labels)
                .endMetadata()
                .withNewSpec()
                    .withSelector(Map.of("app.kubernetes.io/name", appName))
                    .withPorts(servicePort)
                    .withType(config.getServiceType().getKubernetesValue())
                .endSpec()
                .build();

        kubernetesClient.services()
                .inNamespace(namespace)
                .resource(service)
                .serverSideApply();
    }

    ServicePort buildServicePort(DeploymentConfig config) {
        ServicePortBuilder builder = new ServicePortBuilder()
                .withPort(80)
                .withNewTargetPort(config.getContainerPort())
                .withProtocol("TCP");
        if (config.getServiceType() == KubernetesServiceType.NODE_PORT && config.getNodePort() != null) {
            builder.withNodePort(config.getNodePort());
        }
        return builder.build();
    }

    private void createOrUpdateIngress(String appName, String domainUrl, int containerPort,
                                        Map<String, String> labels) {
        Ingress ingress = new IngressBuilder()
                .withNewMetadata()
                    .withName(appName)
                    .withNamespace(namespace)
                    .withLabels(labels)
                .endMetadata()
                .withNewSpec()
                    .addNewRule()
                        .withHost(domainUrl)
                        .withNewHttp()
                            .addNewPath()
                                .withPath("/")
                                .withPathType("Prefix")
                                .withNewBackend()
                                    .withNewService()
                                        .withName(appName)
                                        .withNewPort()
                                            .withNumber(80)
                                        .endPort()
                                    .endService()
                                .endBackend()
                            .endPath()
                        .endHttp()
                    .endRule()
                .endSpec()
                .build();

        kubernetesClient.network().v1().ingresses()
                .inNamespace(namespace)
                .resource(ingress)
                .serverSideApply();
    }
}
