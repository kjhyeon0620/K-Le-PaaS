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
import klepaas.backend.deployment.service.RuntimeResourcePolicy;
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
    private final RuntimeResourcePolicy runtimeResourcePolicy;

    @Value("${kubernetes.namespace:default}")
    private String namespace;

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
            if (config.getSourceRepository() == null || !repoId.equals(config.getSourceRepository().getId())) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST, "저장소 설정이 일치하지 않습니다");
            }
            runtimeResourcePolicy.validateReferences(config.getSourceRepository(), config.getEnvFromConfigMaps(),
                    config.getEnvFromSecrets(), runtimeResourcePolicy.resolveImagePullSecretName(config));
            Deployment existingDeployment = kubernetesClient.apps().deployments().inNamespace(namespace).withName(appName).get();
            Service existingService = kubernetesClient.services().inNamespace(namespace).withName(appName).get();
            Ingress existingIngress = kubernetesClient.network().v1().ingresses().inNamespace(namespace).withName(appName).get();
            rejectForeignResource(existingDeployment, repoId);
            rejectForeignResource(existingService, repoId);
            rejectForeignResource(existingIngress, repoId);
            validateEnvFromRefs(config);
            createOrUpdateDeployment(appName, imageUri, config, labels, existingDeployment);
            createOrUpdateService(appName, config, labels, existingService);

            if (config.getDomainUrl() != null && !config.getDomainUrl().isBlank()) {
                createOrUpdateIngress(appName, config.getDomainUrl(), config.getContainerPort(), labels, existingIngress);
            }

            log.info("K8s resources deployed: app={}, namespace={}", appName, namespace);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("K8s deployment failed: app={}, error={}", appName, e.getMessage(), e);
            throw new BusinessException(ErrorCode.DEPLOY_FAILED, "K8s 배포 실패: " + e.getMessage());
        }
    }

    /**
     * K8s 리소스 스케일링
     */
    public void scale(String appName, int replicas, Long repoId) {
        Deployment existing = kubernetesClient.apps().deployments().inNamespace(namespace).withName(appName).get();
        if (existing == null) throw new BusinessException(ErrorCode.ENTITY_NOT_FOUND);
        rejectForeignResource(existing, repoId);
        existing.getSpec().setReplicas(replicas);
        kubernetesClient.apps().deployments().inNamespace(namespace).resource(existing)
                .lockResourceVersion(existing.getMetadata().getResourceVersion()).replace();
        log.info("Scaled: app={}, replicas={}", appName, replicas);
    }

    private void rejectForeignResource(HasMetadata resource, Long repoId) {
        if (resource != null && (resource.getMetadata() == null || resource.getMetadata().getLabels() == null
                || !String.valueOf(repoId).equals(resource.getMetadata().getLabels().get("klepaas.io/repository-id"))
                || resource.getMetadata().getResourceVersion() == null)) {
            throw new BusinessException(ErrorCode.ENTITY_NOT_FOUND);
        }
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
                                           DeploymentConfig config, Map<String, String> labels, Deployment existing) {
        Deployment deployment = buildDeployment(appName, imageUri, config, labels);
        if (existing == null) {
            kubernetesClient.apps().deployments().inNamespace(namespace).resource(deployment).create();
        } else {
            deployment.getMetadata().setResourceVersion(existing.getMetadata().getResourceVersion());
            kubernetesClient.apps().deployments().inNamespace(namespace).resource(deployment).serverSideApply();
        }
    }

    Deployment buildDeployment(String appName, String imageUri,
                               DeploymentConfig config, Map<String, String> labels) {
        List<EnvVar> envVars = config.getEnvVars().entrySet().stream()
                .map(e -> new EnvVarBuilder().withName(e.getKey()).withValue(e.getValue()).build())
                .collect(Collectors.toList());
        List<EnvFromSource> envFromSources = buildEnvFromSources(config);

        return new DeploymentBuilder()
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
                                    .withName(runtimeResourcePolicy.resolveImagePullSecretName(config))
                                    .build())
                            .withContainers(new ContainerBuilder()
                                    .withName(appName)
                                    .withImage(imageUri)
                                    .withPorts(new ContainerPortBuilder()
                                            .withContainerPort(config.getContainerPort())
                                            .build())
                                    .withEnv(envVars)
                                    .withEnvFrom(envFromSources)
                                    .build())
                        .endSpec()
                    .endTemplate()
                .endSpec()
                .build();
    }

    private List<EnvFromSource> buildEnvFromSources(DeploymentConfig config) {
        List<EnvFromSource> envFromSources = config.getEnvFromConfigMaps().stream()
                .map(name -> new EnvFromSourceBuilder()
                        .withConfigMapRef(new ConfigMapEnvSourceBuilder().withName(name).build())
                        .build())
                .collect(Collectors.toList());
        envFromSources.addAll(config.getEnvFromSecrets().stream()
                .map(name -> new EnvFromSourceBuilder()
                        .withSecretRef(new SecretEnvSourceBuilder().withName(name).build())
                        .build())
                .toList());
        return envFromSources;
    }

    void validateEnvFromRefs(DeploymentConfig config) {
        config.getEnvFromConfigMaps().forEach(this::validateConfigMapExists);
        config.getEnvFromSecrets().forEach(this::validateSecretExists);
    }

    private void validateConfigMapExists(String name) {
        if (!configMapExists(name)) {
            throw new BusinessException(
                    ErrorCode.DEPLOY_FAILED,
                    "K8s envFrom ConfigMap을 찾을 수 없습니다: namespace=" + namespace + ", name=" + name
            );
        }
    }

    private void validateSecretExists(String name) {
        if (!secretExists(name)) {
            throw new BusinessException(
                    ErrorCode.DEPLOY_FAILED,
                    "K8s envFrom Secret을 찾을 수 없습니다: namespace=" + namespace + ", name=" + name
            );
        }
    }

    boolean configMapExists(String name) {
        ConfigMap configMap = kubernetesClient.configMaps()
                .inNamespace(namespace)
                .withName(name)
                .get();
        return configMap != null;
    }

    boolean secretExists(String name) {
        Secret secret = kubernetesClient.secrets()
                .inNamespace(namespace)
                .withName(name)
                .get();
        return secret != null;
    }

    private void createOrUpdateService(String appName, DeploymentConfig config, Map<String, String> labels,
                                       Service existing) {
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

        if (existing == null) {
            kubernetesClient.services().inNamespace(namespace).resource(service).create();
        } else {
            service.getMetadata().setResourceVersion(existing.getMetadata().getResourceVersion());
            kubernetesClient.services().inNamespace(namespace).resource(service).serverSideApply();
        }
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
                                        Map<String, String> labels, Ingress existing) {
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

        if (existing == null) {
            kubernetesClient.network().v1().ingresses().inNamespace(namespace).resource(ingress).create();
        } else {
            ingress.getMetadata().setResourceVersion(existing.getMetadata().getResourceVersion());
            kubernetesClient.network().v1().ingresses().inNamespace(namespace).resource(ingress).serverSideApply();
        }
    }
}
