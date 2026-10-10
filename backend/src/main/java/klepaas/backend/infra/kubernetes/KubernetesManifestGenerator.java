package klepaas.backend.infra.kubernetes;

import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.api.model.apps.DeploymentCondition;
import io.fabric8.kubernetes.api.model.apps.ReplicaSet;
import io.fabric8.kubernetes.api.model.networking.v1.Ingress;
import io.fabric8.kubernetes.api.model.networking.v1.IngressBuilder;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import klepaas.backend.deployment.entity.ContainerResources;
import klepaas.backend.deployment.entity.DeploymentConfig;
import klepaas.backend.deployment.entity.HealthProbe;
import klepaas.backend.deployment.entity.KubernetesServiceType;
import klepaas.backend.deployment.service.RuntimeResourcePolicy;
import klepaas.backend.global.exception.BusinessException;
import klepaas.backend.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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

    static final String DEPLOYMENT_ID_ANNOTATION = "klepaas.io/deployment-id";
    private static final String REVISION_ANNOTATION = "deployment.kubernetes.io/revision";
    // 시간이 지나도 회복되지 않을 가능성이 높아 타임아웃 전에 실패로 보는 컨테이너 대기 사유.
    // ErrImagePull은 첫 시도의 일시 오류일 수 있어 제외한다 (곧 ImagePullBackOff로 바뀐다)
    private static final Set<String> FAILED_WAITING_REASONS = Set.of(
            "ImagePullBackOff", "InvalidImageName", "ErrImageNeverPull",
            "CreateContainerConfigError", "CrashLoopBackOff");
    private static final int MAX_REASON_MESSAGE_LENGTH = 300;

    /**
     * K8s Deployment + Service + Ingress 생성/업데이트. 적용한 Deployment의 metadata.generation을 반환한다.
     */
    public long deploy(String appName, String imageUri, DeploymentConfig config, Long repoId, Long deploymentId) {
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
            long generation = createOrUpdateDeployment(appName, imageUri, config, labels, existingDeployment, deploymentId);
            createOrUpdateService(appName, config, labels, existingService);

            if (config.getDomainUrl() != null && !config.getDomainUrl().isBlank()) {
                createOrUpdateIngress(appName, config.getDomainUrl(), config.getContainerPort(), labels, existingIngress);
            }

            log.info("K8s resources deployed: app={}, namespace={}, generation={}", appName, namespace, generation);
            return generation;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("K8s deployment failed: app={}, error={}", appName, e.getMessage(), e);
            throw new BusinessException(ErrorCode.DEPLOY_FAILED, "K8s 배포 실패: " + e.getMessage());
        }
    }

    /**
     * K8s 리소스 스케일링. 변경 전 replica 수를 반환한다.
     */
    public int scale(String appName, int replicas, Long repoId) {
        Deployment existing = getOwnedDeployment(appName, repoId);
        Integer previous = existing.getSpec().getReplicas();
        existing.getSpec().setReplicas(replicas);
        replaceWithObservedVersion(existing);
        log.info("Scaled: app={}, replicas={} -> {}", appName, previous, replicas);
        // replicas 미지정 Deployment는 Kubernetes 기본값 1로 동작한다
        return previous == null ? 1 : previous;
    }

    /**
     * Pod template annotation을 갱신해 replica 수를 유지한 채 rolling restart한다. (kubectl rollout restart와 동일)
     */
    public void restart(String appName, Long repoId) {
        Deployment existing = getOwnedDeployment(appName, repoId);
        PodTemplateSpec template = existing.getSpec().getTemplate();
        if (template.getMetadata() == null) template.setMetadata(new ObjectMeta());
        Map<String, String> annotations = template.getMetadata().getAnnotations() == null
                ? new HashMap<>() : new HashMap<>(template.getMetadata().getAnnotations());
        annotations.put("kubectl.kubernetes.io/restartedAt", Instant.now().toString());
        template.getMetadata().setAnnotations(annotations);
        replaceWithObservedVersion(existing);
        log.info("Restarted: app={}, replicas={}", appName, existing.getSpec().getReplicas());
    }

    private Deployment getOwnedDeployment(String appName, Long repoId) {
        Deployment existing = kubernetesClient.apps().deployments().inNamespace(namespace).withName(appName).get();
        if (existing == null) throw new BusinessException(ErrorCode.ENTITY_NOT_FOUND);
        rejectForeignResource(existing, repoId);
        return existing;
    }

    private Deployment replaceWithObservedVersion(Deployment deployment) {
        // managedFields는 서버가 관리한다. 요청에서 빼면 서버는 기존 값을 유지한다.
        // 그대로 보내면 Fabric8 7.2.0이 replace() 전 복제에서 Jackson 2.20과 맞지 않아 실패한다 (#86)
        deployment.getMetadata().setManagedFields(null);
        return kubernetesClient.apps().deployments().inNamespace(namespace).resource(deployment)
                .lockResourceVersion(deployment.getMetadata().getResourceVersion()).replace();
    }

    private void rejectForeignResource(HasMetadata resource, Long repoId) {
        if (resource != null && (resource.getMetadata() == null || resource.getMetadata().getLabels() == null
                || !String.valueOf(repoId).equals(resource.getMetadata().getLabels().get("klepaas.io/repository-id"))
                || resource.getMetadata().getResourceVersion() == null)) {
            throw new BusinessException(ErrorCode.ENTITY_NOT_FOUND);
        }
    }

    /**
     * 이 요청이 적용한 generation의 rollout만 판정한다. 다른 변경으로 generation이 바뀌면 SUPERSEDED,
     * 새 Pod가 회복되기 어려운 사유로 대기하면 즉시 FAILED, 시간 안에 결론이 없으면 타임아웃 FAILED.
     */
    public RolloutResult waitForRollout(String appName, long generation) {
        long deadline = System.currentTimeMillis() + rolloutTimeoutMs;
        Deployment last = null;
        while (System.currentTimeMillis() <= deadline) {
            try {
                last = kubernetesClient.apps().deployments().inNamespace(namespace).withName(appName).get();
                RolloutResult result = evaluateDeployment(last, generation);
                if (result == null && isObserved(last, generation)) {
                    result = evaluatePods(findNewReplicaSetPods(last), generation);
                }
                if (result != null) {
                    log.info("Rollout judged: app={}, generation={}, outcome={}, reason={}",
                            appName, generation, result.outcome(), result.reason());
                    return result;
                }
            } catch (KubernetesClientException e) {
                log.warn("Rollout status read failed, retrying: app={}, error={}", appName, e.getMessage());
            }
            sleepBeforeNextRolloutCheck(appName);
        }
        return RolloutResult.failed("rollout 타임아웃(" + rolloutTimeoutMs / 1000 + "초, generation=" + generation + "): "
                + describeReplicas(last) + describePodsAtTimeout(last, generation));
    }

    // 타임아웃 시 관측한 새 Pod 상태를 근거로 덧붙인다. 원인을 추정하지 않고 상태만 적는다
    private String describePodsAtTimeout(Deployment last, long generation) {
        if (last == null || !isObserved(last, generation)
                || valueOrZero(last.getMetadata().getGeneration()) != generation) return "";
        try {
            String pods = describePods(findNewReplicaSetPods(last));
            return pods.isEmpty() ? "" : "; " + pods;
        } catch (KubernetesClientException e) {
            return "";
        }
    }

    String describePods(List<Pod> pods) {
        return pods.stream().map(this::describePod).filter(d -> !d.isEmpty()).collect(Collectors.joining("; "));
    }

    private String describePod(Pod pod) {
        String name = "pod=" + pod.getMetadata().getName();
        if (pod.getStatus() == null) return "";
        if (pod.getStatus().getConditions() != null) {
            var unschedulable = pod.getStatus().getConditions().stream()
                    .filter(c -> "PodScheduled".equals(c.getType()) && "False".equals(c.getStatus())).findFirst();
            if (unschedulable.isPresent()) {
                return name + " 스케줄 불가: " + truncate(unschedulable.get().getMessage());
            }
        }
        if (pod.getStatus().getContainerStatuses() == null) return "";
        for (ContainerStatus status : pod.getStatus().getContainerStatuses()) {
            if (status.getState() != null && status.getState().getWaiting() != null) {
                return name + " 대기: " + status.getState().getWaiting().getReason() + describeLastTermination(status);
            }
            if (status.getState() != null && status.getState().getRunning() != null && !Boolean.TRUE.equals(status.getReady())) {
                return name + " 실행 중이나 Ready 아님 (restarts=" + status.getRestartCount() + describeLastTermination(status) + ")";
            }
        }
        return "";
    }

    private String describeLastTermination(ContainerStatus status) {
        var terminated = status.getLastState() == null ? null : status.getLastState().getTerminated();
        return terminated == null ? "" : ", 마지막 종료=" + terminated.getReason() + ", exit=" + terminated.getExitCode();
    }

    /** Deployment 수준에서 결론이 나면 결과를, 아직 진행 중이면 null을 반환한다. */
    RolloutResult evaluateDeployment(Deployment deployment, long generation) {
        if (deployment == null || deployment.getMetadata() == null) {
            return RolloutResult.failed("rollout 실패: Deployment를 찾을 수 없음 (generation=" + generation + ")");
        }
        long current = valueOrZero(deployment.getMetadata().getGeneration());
        if (current != generation) {
            return RolloutResult.superseded("다른 변경으로 대체됨: generation " + generation + " → " + current);
        }
        if (!isObserved(deployment, generation)) {
            return null;
        }
        if (isDeploymentRolloutComplete(deployment)) {
            return RolloutResult.succeeded();
        }
        return deployment.getStatus().getConditions() == null ? null : deployment.getStatus().getConditions().stream()
                .filter(c -> "Progressing".equals(c.getType()) && "False".equals(c.getStatus()))
                .findFirst()
                .map(c -> RolloutResult.failed("rollout 실패: " + c.getReason() + " (generation=" + generation + "): "
                        + truncate(c.getMessage())))
                .orElse(null);
    }

    /** 새 ReplicaSet의 Pod 중 하나라도 회복되기 어려운 사유로 대기 중이면 FAILED, 아니면 null. */
    RolloutResult evaluatePods(List<Pod> pods, long generation) {
        for (Pod pod : pods) {
            if (pod.getStatus() == null || pod.getStatus().getContainerStatuses() == null) continue;
            for (ContainerStatus status : pod.getStatus().getContainerStatuses()) {
                ContainerStateWaiting waiting = status.getState() == null ? null : status.getState().getWaiting();
                if (waiting != null && FAILED_WAITING_REASONS.contains(waiting.getReason())) {
                    return RolloutResult.failed("rollout 실패: " + waiting.getReason() + " (pod=" + pod.getMetadata().getName()
                            + ", generation=" + generation + describeLastTermination(status) + "): "
                            + truncate(waiting.getMessage()));
                }
            }
        }
        return null;
    }

    private boolean isObserved(Deployment deployment, long generation) {
        return deployment.getStatus() != null && valueOrZero(deployment.getStatus().getObservedGeneration()) >= generation;
    }

    // 현재 revision의 ReplicaSet(= 이 generation의 Pod template)이 만든 Pod만 본다. 이전 ReplicaSet의 Pod는 제외한다
    private List<Pod> findNewReplicaSetPods(Deployment deployment) {
        return currentReplicaSet(kubernetesClient, namespace, deployment)
                .map(rs -> kubernetesClient.pods().inNamespace(namespace)
                        .withLabels(rs.getSpec().getSelector().getMatchLabels()).list().getItems())
                .orElse(List.of());
    }

    /** Deployment가 소유하고 revision이 Deployment의 현재 revision과 같은 ReplicaSet. */
    static Optional<ReplicaSet> currentReplicaSet(KubernetesClient client, String namespace, Deployment deployment) {
        String revision = annotation(deployment.getMetadata(), REVISION_ANNOTATION);
        if (revision == null) return Optional.empty();
        return client.apps().replicaSets().inNamespace(namespace)
                .withLabel("app.kubernetes.io/name", deployment.getMetadata().getName()).list().getItems().stream()
                .filter(rs -> revision.equals(annotation(rs.getMetadata(), REVISION_ANNOTATION)))
                .filter(rs -> rs.getMetadata().getOwnerReferences() != null && rs.getMetadata().getOwnerReferences().stream()
                        .anyMatch(owner -> owner.getUid() != null && owner.getUid().equals(deployment.getMetadata().getUid())))
                .findFirst();
    }

    static String annotation(ObjectMeta metadata, String key) {
        return metadata == null || metadata.getAnnotations() == null ? null : metadata.getAnnotations().get(key);
    }

    private String describeReplicas(Deployment deployment) {
        if (deployment == null || deployment.getStatus() == null) return "상태를 관측하지 못함";
        var status = deployment.getStatus();
        return "observedGeneration=" + status.getObservedGeneration() + ", updated=" + valueOrZero(status.getUpdatedReplicas())
                + ", available=" + valueOrZero(status.getAvailableReplicas())
                + ", unavailable=" + valueOrZero(status.getUnavailableReplicas());
    }

    private String truncate(String message) {
        if (message == null) return "";
        return message.length() <= MAX_REASON_MESSAGE_LENGTH ? message : message.substring(0, MAX_REASON_MESSAGE_LENGTH) + "…";
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

    private long createOrUpdateDeployment(String appName, String imageUri, DeploymentConfig config,
                                          Map<String, String> labels, Deployment existing, Long deploymentId) {
        Deployment deployment = buildDeployment(appName, imageUri, config, labels);
        // 이 spec을 적용한 배포 요청. Pod template이 아닌 metadata에 두어 새 rollout을 만들지 않는다 (#57 대조용)
        deployment.getMetadata().setAnnotations(Map.of(DEPLOYMENT_ID_ANNOTATION, String.valueOf(deploymentId)));
        Deployment applied;
        if (existing == null) {
            applied = kubernetesClient.apps().deployments().inNamespace(namespace).resource(deployment).create();
        } else {
            // 전체 spec을 교체한다. apply는 create·scale이 남긴 다른 field manager 때문에 변경 시 409가 나고,
            // 설정에서 뺀 필드(probe, envFrom 등)를 지우지 못한다.
            deployment.getMetadata().setResourceVersion(existing.getMetadata().getResourceVersion());
            applied = replaceWithObservedVersion(deployment);
        }
        if (applied == null || applied.getMetadata() == null || applied.getMetadata().getGeneration() == null) {
            throw new BusinessException(ErrorCode.DEPLOY_FAILED, "K8s 배포 실패: 적용한 Deployment의 generation을 알 수 없습니다");
        }
        return applied.getMetadata().getGeneration();
    }

    Deployment buildDeployment(String appName, String imageUri,
                               DeploymentConfig config, Map<String, String> labels) {
        List<EnvVar> envVars = config.getEnvVars().entrySet().stream()
                .map(e -> new EnvVarBuilder().withName(e.getKey()).withValue(e.getValue()).build())
                .collect(Collectors.toList());
        List<EnvFromSource> envFromSources = buildEnvFromSources(config);
        String imagePullSecretName = runtimeResourcePolicy.resolveImagePullSecretName(config);
        List<LocalObjectReference> imagePullSecrets = imagePullSecretName == null
                ? List.of() : List.of(new LocalObjectReferenceBuilder().withName(imagePullSecretName).build());

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
                            .withImagePullSecrets(imagePullSecrets)
                            .withContainers(new ContainerBuilder()
                                    .withName(appName)
                                    .withImage(imageUri)
                                    .withPorts(new ContainerPortBuilder()
                                            .withContainerPort(config.getContainerPort())
                                            .build())
                                    .withEnv(envVars)
                                    .withEnvFrom(envFromSources)
                                    .withReadinessProbe(buildProbe(config, false))
                                    .withStartupProbe(buildProbe(config, true))
                                    .withResources(buildResources(config.getResources()))
                                    .build())
                        .endSpec()
                    .endTemplate()
                .endSpec()
                .build();
    }

    // 설정이 없으면 null을 반환해 기존 manifest와 동일하게 probe를 생략한다
    private Probe buildProbe(DeploymentConfig config, boolean startup) {
        HealthProbe probe = config.getHealthProbe();
        if (probe == null || (startup && probe.startupFailureThreshold() == null)) {
            return null;
        }
        return new ProbeBuilder()
                .withNewHttpGet()
                    .withPath(probe.path())
                    .withNewPort(probe.port() != null ? probe.port() : config.getContainerPort())
                .endHttpGet()
                .withInitialDelaySeconds(probe.initialDelaySeconds())
                .withPeriodSeconds(probe.periodSeconds())
                .withFailureThreshold(startup ? probe.startupFailureThreshold() : probe.failureThreshold())
                .build();
    }

    private ResourceRequirements buildResources(ContainerResources resources) {
        if (resources == null) {
            return null;
        }
        return new ResourceRequirementsBuilder()
                .withRequests(quantities(resources.cpuRequest(), resources.memoryRequest()))
                .withLimits(quantities(resources.cpuLimit(), resources.memoryLimit()))
                .build();
    }

    private Map<String, Quantity> quantities(String cpu, String memory) {
        Map<String, Quantity> quantities = new HashMap<>();
        if (cpu != null) quantities.put("cpu", new Quantity(cpu));
        if (memory != null) quantities.put("memory", new Quantity(memory));
        return quantities.isEmpty() ? null : quantities;
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
            kubernetesClient.services().inNamespace(namespace).resource(service).forceConflicts().serverSideApply();
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
            kubernetesClient.network().v1().ingresses().inNamespace(namespace).resource(ingress).forceConflicts().serverSideApply();
        }
    }
}
