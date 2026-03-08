package klepaas.backend.ai.service;

import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.networking.v1.Ingress;
import io.fabric8.kubernetes.api.model.networking.v1.IngressRule;
import io.fabric8.kubernetes.client.KubernetesClient;
import klepaas.backend.ai.dto.FormattedResponseDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;

/**
 * kubectl 명령어에 해당하는 Kubernetes 작업을 Fabric8 클라이언트로 실행하는 서비스.
 * 각 메서드는 프론트엔드 NLPResponse 타입과 일치하는 FormattedResponseDto를 반환합니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KubectlService {

    private final KubernetesClient kubernetesClient;

    @Value("${kubernetes.namespace:default}")
    private String defaultNamespace;

    // ─── LIST PODS ───────────────────────────────────────────────────────────

    public FormattedResponseDto listPods(String namespace) {
        String ns = resolveNamespace(namespace);
        try {
            PodList podList = kubernetesClient.pods().inNamespace(ns).list();
            List<Map<String, Object>> pods = podList.getItems().stream()
                    .map(this::toPodInfo)
                    .collect(Collectors.toList());

            long running = pods.stream().filter(p -> "Running".equals(p.get("status"))).count();
            long pending = pods.stream().filter(p -> "Pending".equals(p.get("status"))).count();
            long failed = pods.stream().filter(p -> "Failed".equals(p.get("status"))).count();

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("total", pods.size());
            metadata.put("namespace", ns);
            metadata.put("running", running);
            metadata.put("pending", pending);
            metadata.put("failed", failed);

            return FormattedResponseDto.of("list_pods",
                    ns + " 네임스페이스의 파드 목록입니다. 총 " + pods.size() + "개 (실행 중: " + running + ")",
                    "파드 " + pods.size() + "개",
                    pods, metadata);
        } catch (Exception e) {
            log.error("listPods failed: namespace={}", ns, e);
            return errorResponse("list_pods", "파드 목록 조회 실패: " + e.getMessage());
        }
    }

    // ─── POD STATUS ──────────────────────────────────────────────────────────

    public FormattedResponseDto getPodStatus(String namespace, String appName) {
        String ns = resolveNamespace(namespace);
        try {
            PodList podList;
            String labelSelector = "";

            if (appName != null && !appName.isBlank()) {
                labelSelector = "app.kubernetes.io/name=" + appName;
                podList = kubernetesClient.pods().inNamespace(ns)
                        .withLabel("app.kubernetes.io/name", appName).list();
                if (podList.getItems().isEmpty()) {
                    labelSelector = "app=" + appName;
                    podList = kubernetesClient.pods().inNamespace(ns)
                            .withLabel("app", appName).list();
                }
            } else {
                podList = kubernetesClient.pods().inNamespace(ns).list();
            }

            List<Map<String, Object>> pods = podList.getItems().stream().map(pod -> {
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("name", pod.getMetadata().getName());
                p.put("phase", Optional.ofNullable(pod.getStatus().getPhase()).orElse("Unknown"));
                p.put("ready", computeReadyString(pod));
                p.put("restarts", getTotalRestarts(pod));
                p.put("node", Optional.ofNullable(pod.getSpec().getNodeName()).orElse(""));
                p.put("labels", Optional.ofNullable(pod.getMetadata().getLabels()).orElse(Map.of()));
                p.put("creation_timestamp", Optional.ofNullable(pod.getMetadata().getCreationTimestamp()).orElse(""));
                return p;
            }).collect(Collectors.toList());

            long running = pods.stream().filter(p -> "Running".equals(p.get("phase"))).count();
            long pending = pods.stream().filter(p -> "Pending".equals(p.get("phase"))).count();
            long failed = pods.stream().filter(p -> "Failed".equals(p.get("phase"))).count();

            Map<String, Object> formatted = new LinkedHashMap<>();
            formatted.put("namespace", ns);
            formatted.put("label_selector", labelSelector);
            formatted.put("total_pods", pods.size());
            formatted.put("running", running);
            formatted.put("pending", pending);
            formatted.put("failed", failed);
            formatted.put("pods", pods);

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("namespace", ns);
            metadata.put("total_pods", pods.size());
            metadata.put("running", running);
            metadata.put("pending", pending);
            metadata.put("failed", failed);
            metadata.put("is_healthy", failed == 0 && pending == 0);

            return FormattedResponseDto.of("pod_status",
                    "파드 상태를 조회했습니다. 총 " + pods.size() + "개 (실행 중: " + running + ")",
                    "파드 " + pods.size() + "개",
                    formatted, metadata);
        } catch (Exception e) {
            log.error("getPodStatus failed: ns={}, app={}", ns, appName, e);
            return errorResponse("pod_status", "파드 상태 조회 실패: " + e.getMessage());
        }
    }

    // ─── SERVICE STATUS ──────────────────────────────────────────────────────

    public FormattedResponseDto getServiceStatus(String name, String namespace) {
        String ns = resolveNamespace(namespace);
        try {
            Service svc = kubernetesClient.services().inNamespace(ns).withName(name).get();
            if (svc == null) {
                return errorResponse("service_status", "서비스를 찾을 수 없습니다: " + name);
            }

            List<Map<String, Object>> ports = Optional.ofNullable(svc.getSpec().getPorts())
                    .orElse(List.of()).stream().map(p -> {
                        Map<String, Object> port = new LinkedHashMap<>();
                        port.put("port", p.getPort());
                        port.put("target_port", p.getTargetPort() != null ? p.getTargetPort().toString() : "");
                        port.put("protocol", p.getProtocol());
                        return port;
                    }).collect(Collectors.toList());

            int readyEndpoints = 0;
            try {
                Endpoints endpoints = kubernetesClient.endpoints().inNamespace(ns).withName(name).get();
                if (endpoints != null && endpoints.getSubsets() != null) {
                    readyEndpoints = endpoints.getSubsets().stream()
                            .mapToInt(s -> s.getAddresses() != null ? s.getAddresses().size() : 0)
                            .sum();
                }
            } catch (Exception ignored) {}

            Map<String, Object> formatted = new LinkedHashMap<>();
            formatted.put("name", svc.getMetadata().getName());
            formatted.put("namespace", ns);
            formatted.put("type", Optional.ofNullable(svc.getSpec().getType()).orElse("ClusterIP"));
            formatted.put("cluster_ip", Optional.ofNullable(svc.getSpec().getClusterIP()).orElse(""));
            formatted.put("ports", ports);
            formatted.put("selector", Optional.ofNullable(svc.getSpec().getSelector()).orElse(Map.of()));
            formatted.put("ready_endpoints", readyEndpoints);
            formatted.put("creation_timestamp", Optional.ofNullable(svc.getMetadata().getCreationTimestamp()).orElse(""));

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("namespace", ns);
            metadata.put("is_healthy", readyEndpoints > 0);

            return FormattedResponseDto.of("service_status",
                    name + " 서비스 상태를 조회했습니다.",
                    "서비스: " + name,
                    formatted, metadata);
        } catch (Exception e) {
            log.error("getServiceStatus failed: name={}, ns={}", name, ns, e);
            return errorResponse("service_status", "서비스 상태 조회 실패: " + e.getMessage());
        }
    }

    // ─── DEPLOYMENT STATUS ───────────────────────────────────────────────────

    public FormattedResponseDto getDeploymentStatus(String name, String namespace) {
        String ns = resolveNamespace(namespace);
        try {
            Deployment dep = kubernetesClient.apps().deployments().inNamespace(ns).withName(name).get();
            if (dep == null) {
                return errorResponse("deployment_status", "디플로이먼트를 찾을 수 없습니다: " + name);
            }

            Map<String, Object> replicas = new LinkedHashMap<>();
            replicas.put("desired", Optional.ofNullable(dep.getSpec().getReplicas()).orElse(0));
            replicas.put("current", Optional.ofNullable(dep.getStatus().getReplicas()).orElse(0));
            replicas.put("ready", Optional.ofNullable(dep.getStatus().getReadyReplicas()).orElse(0));
            replicas.put("available", Optional.ofNullable(dep.getStatus().getAvailableReplicas()).orElse(0));

            List<Map<String, Object>> conditions = Optional.ofNullable(dep.getStatus().getConditions())
                    .orElse(List.of()).stream().map(c -> {
                        Map<String, Object> cond = new LinkedHashMap<>();
                        cond.put("type", c.getType());
                        cond.put("status", c.getStatus());
                        cond.put("reason", Optional.ofNullable(c.getReason()).orElse(""));
                        cond.put("message", Optional.ofNullable(c.getMessage()).orElse(""));
                        return cond;
                    }).collect(Collectors.toList());

            List<Map<String, Object>> pods = new ArrayList<>();
            Map<String, String> selector = dep.getSpec().getSelector().getMatchLabels();
            if (selector != null && !selector.isEmpty()) {
                String labelKey = selector.keySet().iterator().next();
                String labelVal = selector.get(labelKey);
                PodList pl = kubernetesClient.pods().inNamespace(ns)
                        .withLabel(labelKey, labelVal).list();
                pods = pl.getItems().stream().map(pod -> {
                    Map<String, Object> p = new LinkedHashMap<>();
                    p.put("name", pod.getMetadata().getName());
                    p.put("phase", Optional.ofNullable(pod.getStatus().getPhase()).orElse("Unknown"));
                    p.put("ready", computeReadyString(pod));
                    p.put("restarts", getTotalRestarts(pod));
                    p.put("node", Optional.ofNullable(pod.getSpec().getNodeName()).orElse(""));
                    return p;
                }).collect(Collectors.toList());
            }

            Map<String, Object> formatted = new LinkedHashMap<>();
            formatted.put("name", name);
            formatted.put("namespace", ns);
            formatted.put("replicas", replicas);
            formatted.put("conditions", conditions);
            formatted.put("pods", pods);
            formatted.put("creation_timestamp", Optional.ofNullable(dep.getMetadata().getCreationTimestamp()).orElse(""));

            int desired = (Integer) replicas.get("desired");
            int ready = (Integer) replicas.get("ready");

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("namespace", ns);
            metadata.put("desired", desired);
            metadata.put("ready", ready);
            metadata.put("is_healthy", desired > 0 && desired == ready);

            return FormattedResponseDto.of("deployment_status",
                    name + " 디플로이먼트: " + ready + "/" + desired + " 준비됨",
                    "디플로이먼트: " + name,
                    formatted, metadata);
        } catch (Exception e) {
            log.error("getDeploymentStatus failed: name={}, ns={}", name, ns, e);
            return errorResponse("deployment_status", "디플로이먼트 상태 조회 실패: " + e.getMessage());
        }
    }

    // ─── LIST SERVICES ───────────────────────────────────────────────────────

    public FormattedResponseDto listServices(String namespace) {
        String ns = resolveNamespace(namespace);
        try {
            ServiceList svcList = kubernetesClient.services().inNamespace(ns).list();
            List<Map<String, Object>> services = svcList.getItems().stream().map(svc -> {
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("name", svc.getMetadata().getName());
                s.put("namespace", svc.getMetadata().getNamespace());
                s.put("type", Optional.ofNullable(svc.getSpec().getType()).orElse("ClusterIP"));
                s.put("cluster_ip", Optional.ofNullable(svc.getSpec().getClusterIP()).orElse(""));
                s.put("external_ip", getExternalIp(svc));
                s.put("ports", formatPorts(svc.getSpec().getPorts()));
                s.put("age", computeAge(svc.getMetadata().getCreationTimestamp()));
                return s;
            }).collect(Collectors.toList());

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("namespace", ns);
            metadata.put("total", services.size());

            return FormattedResponseDto.of("list_services",
                    ns + " 네임스페이스의 서비스 목록입니다. 총 " + services.size() + "개",
                    "서비스 " + services.size() + "개",
                    services, metadata);
        } catch (Exception e) {
            log.error("listServices failed: ns={}", ns, e);
            return errorResponse("list_services", "서비스 목록 조회 실패: " + e.getMessage());
        }
    }

    // ─── LIST INGRESSES ──────────────────────────────────────────────────────

    public FormattedResponseDto listIngresses(String namespace) {
        String ns = resolveNamespace(namespace);
        try {
            var ingressList = kubernetesClient.network().v1().ingresses().inNamespace(ns).list();
            List<Map<String, Object>> ingresses = ingressList.getItems().stream().map(ing -> {
                Map<String, Object> i = new LinkedHashMap<>();
                i.put("name", ing.getMetadata().getName());
                i.put("namespace", ing.getMetadata().getNamespace());
                i.put("class", Optional.ofNullable(ing.getSpec().getIngressClassName()).orElse(""));

                List<String> hosts = Optional.ofNullable(ing.getSpec().getRules())
                        .orElse(List.of()).stream()
                        .map(IngressRule::getHost)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());
                i.put("hosts", hosts);

                boolean hasTls = ing.getSpec().getTls() != null && !ing.getSpec().getTls().isEmpty();
                i.put("has_tls", hasTls);
                i.put("urls", hosts.stream().map(h -> (hasTls ? "https://" : "http://") + h).collect(Collectors.toList()));

                Optional.ofNullable(ing.getSpec().getRules())
                        .flatMap(r -> r.stream().findFirst())
                        .flatMap(r -> Optional.ofNullable(r.getHttp()))
                        .flatMap(h -> Optional.ofNullable(h.getPaths()))
                        .flatMap(p -> p.stream().findFirst())
                        .ifPresent(path -> {
                            if (path.getBackend() != null && path.getBackend().getService() != null) {
                                i.put("service_name", path.getBackend().getService().getName());
                                i.put("port", path.getBackend().getService().getPort().getNumber());
                                i.put("path", path.getPath());
                            }
                        });

                String address = Optional.ofNullable(ing.getStatus())
                        .flatMap(s -> Optional.ofNullable(s.getLoadBalancer()))
                        .flatMap(lb -> Optional.ofNullable(lb.getIngress()))
                        .flatMap(list -> list.stream().findFirst())
                        .map(ia -> ia.getIp() != null ? ia.getIp() : Optional.ofNullable(ia.getHostname()).orElse(""))
                        .orElse("");
                i.put("address", address);
                i.put("ports", "80,443");
                i.put("age", computeAge(ing.getMetadata().getCreationTimestamp()));
                return i;
            }).collect(Collectors.toList());

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("namespace", ns);
            metadata.put("total", ingresses.size());

            return FormattedResponseDto.of("list_ingresses",
                    ns + " 네임스페이스의 인그레스 목록입니다. 총 " + ingresses.size() + "개",
                    "인그레스 " + ingresses.size() + "개",
                    ingresses, metadata);
        } catch (Exception e) {
            log.error("listIngresses failed: ns={}", ns, e);
            return errorResponse("list_ingresses", "인그레스 목록 조회 실패: " + e.getMessage());
        }
    }

    // ─── LIST NAMESPACES ─────────────────────────────────────────────────────

    public FormattedResponseDto listNamespaces() {
        try {
            NamespaceList nsList = kubernetesClient.namespaces().list();
            List<Map<String, Object>> namespaces = nsList.getItems().stream().map(ns -> {
                Map<String, Object> n = new LinkedHashMap<>();
                n.put("name", ns.getMetadata().getName());
                n.put("status", Optional.ofNullable(ns.getStatus().getPhase()).orElse("Active"));
                n.put("age", computeAge(ns.getMetadata().getCreationTimestamp()));
                return n;
            }).collect(Collectors.toList());

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("total", namespaces.size());

            return FormattedResponseDto.of("list_namespaces",
                    "클러스터의 네임스페이스 목록입니다. 총 " + namespaces.size() + "개",
                    "네임스페이스 " + namespaces.size() + "개",
                    namespaces, metadata);
        } catch (Exception e) {
            log.error("listNamespaces failed", e);
            return errorResponse("list_namespaces", "네임스페이스 목록 조회 실패: " + e.getMessage());
        }
    }

    // ─── LIST ENDPOINTS ──────────────────────────────────────────────────────

    public FormattedResponseDto listEndpoints(String namespace) {
        String ns = resolveNamespace(namespace);
        try {
            ServiceList svcList = kubernetesClient.services().inNamespace(ns).list();
            var ingressList = kubernetesClient.network().v1().ingresses().inNamespace(ns).list();

            Map<String, List<Map<String, Object>>> ingressByService = new HashMap<>();
            for (Ingress ing : ingressList.getItems()) {
                if (ing.getSpec().getRules() == null) continue;
                for (var rule : ing.getSpec().getRules()) {
                    if (rule.getHttp() == null || rule.getHttp().getPaths() == null) continue;
                    for (var path : rule.getHttp().getPaths()) {
                        if (path.getBackend() == null || path.getBackend().getService() == null) continue;
                        String svcName = path.getBackend().getService().getName();
                        Map<String, Object> domain = new LinkedHashMap<>();
                        domain.put("domain", Optional.ofNullable(rule.getHost()).orElse("*"));
                        domain.put("path", Optional.ofNullable(path.getPath()).orElse("/"));
                        domain.put("ingress_name", ing.getMetadata().getName());
                        ingressByService.computeIfAbsent(svcName, k -> new ArrayList<>()).add(domain);
                    }
                }
            }

            List<Map<String, Object>> endpoints = svcList.getItems().stream().map(svc -> {
                Map<String, Object> ep = new LinkedHashMap<>();
                String svcName = svc.getMetadata().getName();
                ep.put("service_name", svcName);
                ep.put("service_type", Optional.ofNullable(svc.getSpec().getType()).orElse("ClusterIP"));
                ep.put("cluster_ip", Optional.ofNullable(svc.getSpec().getClusterIP()).orElse(""));

                List<Map<String, Object>> ports = Optional.ofNullable(svc.getSpec().getPorts())
                        .orElse(List.of()).stream().map(p -> {
                            Map<String, Object> portMap = new LinkedHashMap<>();
                            portMap.put("port", p.getPort());
                            portMap.put("target_port", p.getTargetPort() != null ? p.getTargetPort().toString() : "");
                            portMap.put("protocol", p.getProtocol());
                            if (p.getNodePort() != null) portMap.put("node_port", p.getNodePort());
                            return portMap;
                        }).collect(Collectors.toList());
                ep.put("ports", ports);

                if (!ports.isEmpty()) {
                    ep.put("service_endpoint", svc.getSpec().getClusterIP() + ":" + ports.get(0).get("port"));
                }

                ep.put("ingress_domains", ingressByService.getOrDefault(svcName, List.of()));

                if ("LoadBalancer".equals(svc.getSpec().getType()) || "NodePort".equals(svc.getSpec().getType())) {
                    Map<String, Object> external = new LinkedHashMap<>();
                    external.put("type", svc.getSpec().getType());
                    external.put("address", getExternalIp(svc));
                    external.put("ports", ports);
                    ep.put("external_access", external);
                }
                return ep;
            }).collect(Collectors.toList());

            long withIngress = endpoints.stream()
                    .filter(e -> !((List<?>) e.get("ingress_domains")).isEmpty()).count();
            long withExternal = endpoints.stream()
                    .filter(e -> e.containsKey("external_access")).count();

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("namespace", ns);
            metadata.put("total_services", endpoints.size());
            metadata.put("services_with_ingress", withIngress);
            metadata.put("services_with_external", withExternal);

            return FormattedResponseDto.of("list_endpoints",
                    ns + " 네임스페이스의 엔드포인트 목록입니다. 총 " + endpoints.size() + "개 서비스",
                    "엔드포인트 " + endpoints.size() + "개",
                    endpoints, metadata);
        } catch (Exception e) {
            log.error("listEndpoints failed: ns={}", ns, e);
            return errorResponse("list_endpoints", "엔드포인트 목록 조회 실패: " + e.getMessage());
        }
    }

    // ─── GET SERVICE ─────────────────────────────────────────────────────────

    public FormattedResponseDto getService(String name, String namespace) {
        String ns = resolveNamespace(namespace);
        try {
            Service svc = kubernetesClient.services().inNamespace(ns).withName(name).get();
            if (svc == null) {
                return errorResponse("get_service", "서비스를 찾을 수 없습니다: " + name);
            }

            Map<String, Object> formatted = new LinkedHashMap<>();
            formatted.put("name", svc.getMetadata().getName());
            formatted.put("namespace", svc.getMetadata().getNamespace());
            formatted.put("type", Optional.ofNullable(svc.getSpec().getType()).orElse("ClusterIP"));
            formatted.put("cluster_ip", Optional.ofNullable(svc.getSpec().getClusterIP()).orElse(""));
            formatted.put("external_ip", getExternalIp(svc));
            formatted.put("ports", formatPorts(svc.getSpec().getPorts()));
            formatted.put("selector", Optional.ofNullable(svc.getSpec().getSelector()).orElse(Map.of()));
            formatted.put("labels", Optional.ofNullable(svc.getMetadata().getLabels()).orElse(Map.of()));
            formatted.put("age", computeAge(svc.getMetadata().getCreationTimestamp()));
            formatted.put("creation_timestamp", Optional.ofNullable(svc.getMetadata().getCreationTimestamp()).orElse(""));

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("namespace", ns);
            metadata.put("name", name);

            return FormattedResponseDto.of("get_service",
                    name + " 서비스 상세 정보입니다.",
                    "서비스: " + name,
                    formatted, metadata);
        } catch (Exception e) {
            log.error("getService failed: name={}, ns={}", name, ns, e);
            return errorResponse("get_service", "서비스 조회 실패: " + e.getMessage());
        }
    }

    // ─── GET DEPLOYMENT ──────────────────────────────────────────────────────

    public FormattedResponseDto getDeploymentDetail(String name, String namespace) {
        String ns = resolveNamespace(namespace);
        try {
            Deployment dep = kubernetesClient.apps().deployments().inNamespace(ns).withName(name).get();
            if (dep == null) {
                return errorResponse("get_deployment", "디플로이먼트를 찾을 수 없습니다: " + name);
            }

            Map<String, Object> formatted = new LinkedHashMap<>();
            formatted.put("name", dep.getMetadata().getName());
            formatted.put("namespace", dep.getMetadata().getNamespace());
            formatted.put("replicas", Optional.ofNullable(dep.getSpec().getReplicas()).orElse(0));
            formatted.put("ready_replicas", Optional.ofNullable(dep.getStatus().getReadyReplicas()).orElse(0));
            formatted.put("labels", Optional.ofNullable(dep.getMetadata().getLabels()).orElse(Map.of()));

            if (dep.getSpec().getTemplate().getSpec().getContainers() != null &&
                    !dep.getSpec().getTemplate().getSpec().getContainers().isEmpty()) {
                Container c = dep.getSpec().getTemplate().getSpec().getContainers().get(0);
                formatted.put("image", c.getImage());
                if (c.getPorts() != null && !c.getPorts().isEmpty()) {
                    formatted.put("container_port", c.getPorts().get(0).getContainerPort());
                }
            }

            formatted.put("age", computeAge(dep.getMetadata().getCreationTimestamp()));
            formatted.put("creation_timestamp", Optional.ofNullable(dep.getMetadata().getCreationTimestamp()).orElse(""));

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("namespace", ns);
            metadata.put("name", name);

            return FormattedResponseDto.of("get_deployment",
                    name + " 디플로이먼트 상세 정보입니다.",
                    "디플로이먼트: " + name,
                    formatted, metadata);
        } catch (Exception e) {
            log.error("getDeploymentDetail failed: name={}, ns={}", name, ns, e);
            return errorResponse("get_deployment", "디플로이먼트 조회 실패: " + e.getMessage());
        }
    }

    // ─── POD LOGS ────────────────────────────────────────────────────────────

    public FormattedResponseDto getPodLogs(String podOrAppName, String namespace, int lines) {
        String ns = resolveNamespace(namespace);
        try {
            String actualPodName = resolveActualPodName(podOrAppName, ns);

            String logContent = kubernetesClient.pods().inNamespace(ns)
                    .withName(actualPodName)
                    .tailingLines(lines)
                    .getLog();

            String[] logLinesArr = logContent != null ? logContent.split("\n") : new String[0];
            List<String> logLineList = Arrays.asList(logLinesArr);

            Map<String, Object> formatted = new LinkedHashMap<>();
            formatted.put("pod_name", actualPodName);
            formatted.put("namespace", ns);
            formatted.put("lines", lines);
            formatted.put("log_lines", logLineList);
            formatted.put("total_lines", logLineList.size());

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("namespace", ns);
            metadata.put("lines_requested", lines);
            metadata.put("lines_returned", logLineList.size());

            return FormattedResponseDto.of("logs",
                    actualPodName + " 파드 로그입니다. " + logLineList.size() + "줄",
                    "로그 " + logLineList.size() + "줄",
                    formatted, metadata);
        } catch (Exception e) {
            log.error("getPodLogs failed: pod={}, ns={}", podOrAppName, ns, e);
            return errorResponse("logs", "로그 조회 실패: " + e.getMessage());
        }
    }

    // ─── OVERVIEW ────────────────────────────────────────────────────────────

    public FormattedResponseDto getOverview() {
        try {
            NodeList nodeList = kubernetesClient.nodes().list();
            NamespaceList nsList = kubernetesClient.namespaces().list();
            PodList allPods = kubernetesClient.pods().inAnyNamespace().list();
            ServiceList allSvcs = kubernetesClient.services().inAnyNamespace().list();

            int totalNodes = nodeList.getItems().size();
            int totalNamespaces = nsList.getItems().size();
            int totalPods = allPods.getItems().size();
            int totalServices = allSvcs.getItems().size();

            long runningPods = allPods.getItems().stream()
                    .filter(p -> "Running".equals(p.getStatus().getPhase())).count();
            long pendingPods = allPods.getItems().stream()
                    .filter(p -> "Pending".equals(p.getStatus().getPhase())).count();
            long failedPods = allPods.getItems().stream()
                    .filter(p -> "Failed".equals(p.getStatus().getPhase())).count();

            // ── cluster_info section ────────────────────────────────────────
            List<Map<String, Object>> nodeItems = nodeList.getItems().stream().map(node -> {
                Map<String, Object> n = new LinkedHashMap<>();
                n.put("name", node.getMetadata().getName());
                String status = Optional.ofNullable(node.getStatus().getConditions())
                        .orElse(List.of()).stream()
                        .filter(c -> "Ready".equals(c.getType()))
                        .findFirst()
                        .map(c -> "True".equals(c.getStatus()) ? "Ready" : "NotReady")
                        .orElse("Unknown");
                n.put("status", status);
                return n;
            }).collect(Collectors.toList());

            Map<String, Object> clusterData = new LinkedHashMap<>();
            clusterData.put("cluster_name", "klepaas-cluster");
            clusterData.put("total_nodes", totalNodes);
            clusterData.put("nodes", nodeItems);

            Map<String, Object> clusterInfoSection = new LinkedHashMap<>();
            clusterInfoSection.put("title", "클러스터 정보");
            clusterInfoSection.put("type", "cluster_info");
            clusterInfoSection.put("data", clusterData);

            // ── critical + workloads section (namespace loop) ───────────────
            List<Map<String, Object>> criticalItems = new ArrayList<>();
            List<Map<String, Object>> workloadData = new ArrayList<>();
            int totalDeployments = 0;
            int criticalIssues = 0;

            for (Namespace ns : nsList.getItems()) {
                String nsName = ns.getMetadata().getName();
                var depList = kubernetesClient.apps().deployments().inNamespace(nsName).list();
                int nsDeployments = depList.getItems().size();
                totalDeployments += nsDeployments;
                int nsPodCount = (int) allPods.getItems().stream()
                        .filter(p -> nsName.equals(p.getMetadata().getNamespace())).count();

                Map<String, Object> workloadEntry = new LinkedHashMap<>();
                workloadEntry.put("namespace", nsName);
                workloadEntry.put("deployments", nsDeployments);
                workloadEntry.put("pods", nsPodCount);
                workloadData.add(workloadEntry);

                for (Deployment dep : depList.getItems()) {
                    int desired = Optional.ofNullable(dep.getSpec().getReplicas()).orElse(0);
                    int ready = Optional.ofNullable(dep.getStatus().getReadyReplicas()).orElse(0);
                    if (desired > 0 && ready < desired) {
                        criticalIssues++;
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("type", "deployment_warning");
                        item.put("namespace", nsName);
                        item.put("name", dep.getMetadata().getName());
                        item.put("ready", ready + "/" + desired);
                        criticalItems.add(item);
                    }
                }
            }

            allPods.getItems().stream().filter(p -> "Pending".equals(p.getStatus().getPhase()))
                    .forEach(p -> {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("type", "pending_pod");
                        item.put("namespace", p.getMetadata().getNamespace());
                        item.put("name", p.getMetadata().getName());
                        criticalItems.add(item);
                    });

            allPods.getItems().stream().filter(p -> "Failed".equals(p.getStatus().getPhase()))
                    .forEach(p -> {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("type", "failed_pod");
                        item.put("namespace", p.getMetadata().getNamespace());
                        item.put("name", p.getMetadata().getName());
                        item.put("status", "Failed");
                        criticalItems.add(item);
                    });

            Map<String, Object> criticalSection = new LinkedHashMap<>();
            criticalSection.put("title", "심각한 문제");
            criticalSection.put("type", "critical");
            criticalSection.put("items", criticalItems);

            // ── warning section ─────────────────────────────────────────────
            List<Map<String, Object>> warningItems = allPods.getItems().stream()
                    .filter(p -> getTotalRestarts(p) > 5)
                    .map(p -> {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("namespace", p.getMetadata().getNamespace());
                        item.put("name", p.getMetadata().getName());
                        item.put("restarts", getTotalRestarts(p));
                        return item;
                    }).collect(Collectors.toList());

            Map<String, Object> warningSection = new LinkedHashMap<>();
            warningSection.put("title", "경고");
            warningSection.put("type", "warning");
            warningSection.put("items", warningItems);

            // ── workloads section ───────────────────────────────────────────
            Map<String, Object> workloadsSection = new LinkedHashMap<>();
            workloadsSection.put("title", "워크로드 현황");
            workloadsSection.put("type", "workloads");
            workloadsSection.put("data", workloadData);

            // ── external_services section ───────────────────────────────────
            List<Map<String, Object>> lbServices = new ArrayList<>();
            List<Map<String, Object>> npServices = new ArrayList<>();
            allSvcs.getItems().forEach(svc -> {
                String type = Optional.ofNullable(svc.getSpec().getType()).orElse("ClusterIP");
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("namespace", svc.getMetadata().getNamespace());
                s.put("name", svc.getMetadata().getName());
                if ("LoadBalancer".equals(type)) lbServices.add(s);
                else if ("NodePort".equals(type)) npServices.add(s);
            });

            Map<String, Object> externalData = new LinkedHashMap<>();
            externalData.put("load_balancer", lbServices);
            externalData.put("node_port", npServices);

            Map<String, Object> externalSection = new LinkedHashMap<>();
            externalSection.put("title", "외부 접속 서비스");
            externalSection.put("type", "external_services");
            externalSection.put("data", externalData);

            // ── assemble sections ───────────────────────────────────────────
            List<Map<String, Object>> sections = new ArrayList<>();
            sections.add(clusterInfoSection);
            if (!criticalItems.isEmpty()) sections.add(criticalSection);
            if (!warningItems.isEmpty()) sections.add(warningSection);
            sections.add(workloadsSection);
            if (!lbServices.isEmpty() || !npServices.isEmpty()) sections.add(externalSection);

            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("cluster_name", "klepaas-cluster");
            summary.put("total_nodes", totalNodes);
            summary.put("total_namespaces", totalNamespaces);
            summary.put("total_deployments", totalDeployments);
            summary.put("total_pods", totalPods);
            summary.put("total_services", totalServices);
            summary.put("running_pods", runningPods);
            summary.put("critical_deployment_issues", criticalIssues);
            summary.put("pending_pod_issues", pendingPods);
            summary.put("failed_pod_issues", failedPods);

            Map<String, Object> formatted = new LinkedHashMap<>();
            formatted.put("report_sections", sections);
            formatted.put("summary", summary);

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("cluster_name", "klepaas-cluster");
            metadata.put("total_nodes", totalNodes);
            metadata.put("total_namespaces", totalNamespaces);
            metadata.put("total_deployments", totalDeployments);
            metadata.put("total_pods", totalPods);
            metadata.put("total_services", totalServices);
            metadata.put("critical_issues_count", criticalIssues);
            metadata.put("warnings_count", warningItems.size());

            return FormattedResponseDto.of("overview",
                    "클러스터 전체 현황입니다. 노드: " + totalNodes + ", 디플로이먼트: " + totalDeployments + ", 파드: " + totalPods,
                    "클러스터 개요",
                    formatted, metadata);
        } catch (Exception e) {
            log.error("getOverview failed", e);
            return errorResponse("overview", "전체 현황 조회 실패: " + e.getMessage());
        }
    }

    // ─── LIST COMMANDS ────────────────────────────────────────────────────────

    public FormattedResponseDto listCommands() {
        List<Map<String, Object>> categories = List.of(
                buildCategory("조회 명령어", "🔍", List.of(
                        buildCommand("파드 목록", "list_pods", "파드 목록 보여줘", "kubectl get pods"),
                        buildCommand("서비스 목록", "list_services", "서비스 목록 보여줘", "kubectl get services"),
                        buildCommand("인그레스 목록", "list_ingresses", "인그레스 목록 보여줘", "kubectl get ingresses"),
                        buildCommand("네임스페이스 목록", "list_namespaces", "네임스페이스 목록 보여줘", "kubectl get namespaces"),
                        buildCommand("엔드포인트 목록", "list_endpoints", "접속 주소 목록 보여줘", "엔드포인트 목록 보여줘"),
                        buildCommand("디플로이먼트 목록", "list_deployments", "디플로이먼트 목록 보여줘", "kubectl get deployments")
                )),
                buildCategory("상태 확인", "📊", List.of(
                        buildCommand("파드 상태", "pod_status", "[앱명] 파드 상태 보여줘", "kubectl get pods -l app=[name]"),
                        buildCommand("서비스 상태", "service_status", "[서비스명] 서비스 상태 보여줘", "kubectl get service [name]"),
                        buildCommand("디플로이먼트 상태", "deployment_status", "[이름] 디플로이먼트 상태 보여줘", "kubectl get deployment [name]"),
                        buildCommand("전체 현황", "overview", "전체 현황 보여줘", "클러스터 전체 상태")
                )),
                buildCategory("운영 명령어", "⚙️", List.of(
                        buildCommand("배포", "deploy", "[저장소명] 배포해줘", "새 버전 배포"),
                        buildCommand("스케일링", "scale", "[앱명] 레플리카 [N]개로 조정해줘", "kubectl scale --replicas=[n]"),
                        buildCommand("재시작", "restart", "[앱명] 재시작해줘", "kubectl rollout restart"),
                        buildCommand("로그 조회", "logs", "[파드명] 로그 보여줘", "kubectl logs [pod]"),
                        buildCommand("롤백 목록", "list_rollback", "[저장소] 롤백 가능한 버전 보여줘", "배포 히스토리 기반"),
                        buildCommand("롤백 실행", "rollback", "[커밋해시]로 롤백해줘", "이전 버전으로 되돌리기")
                ))
        );

        int totalCommands = categories.stream()
                .mapToInt(c -> ((List<?>) c.get("commands")).size())
                .sum();

        Map<String, Object> formatted = new LinkedHashMap<>();
        formatted.put("categories", categories);
        formatted.put("total_commands", totalCommands);
        formatted.put("help_text", List.of(
                "자연어로 명령어를 입력하세요.",
                "예: '파드 목록 보여줘', '레플리카 3개로 늘려줘'",
                "한국어, 영어 모두 지원합니다."
        ));

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("total_commands", totalCommands);
        metadata.put("category_count", categories.size());

        return FormattedResponseDto.of("list_commands",
                "사용 가능한 명령어 목록입니다.",
                "명령어 " + totalCommands + "개",
                formatted, metadata);
    }

    // ─── COST ANALYSIS ───────────────────────────────────────────────────────

    public FormattedResponseDto getCostAnalysis() {
        try {
            PodList allPods = kubernetesClient.pods().inAnyNamespace().list();
            int runningPods = (int) allPods.getItems().stream()
                    .filter(p -> "Running".equals(p.getStatus().getPhase())).count();

            double baseMonthlyPerPod = 30000.0;
            double currentCost = runningPods * baseMonthlyPerPod;

            List<Map<String, Object>> optimizations = new ArrayList<>();

            long idlePods = allPods.getItems().stream()
                    .filter(p -> getTotalRestarts(p) > 10).count();
            if (idlePods > 0) {
                Map<String, Object> opt = new LinkedHashMap<>();
                opt.put("type", "restart_reduction");
                opt.put("description", "재시작이 잦은 파드(" + idlePods + "개) 점검으로 안정성 및 비용 절감 가능");
                opt.put("potential_savings", idlePods * baseMonthlyPerPod * 0.1);
                optimizations.add(opt);
            }

            try {
                var depList = kubernetesClient.apps().deployments().inAnyNamespace().list();
                for (var dep : depList.getItems()) {
                    int replicas = Optional.ofNullable(dep.getSpec().getReplicas()).orElse(1);
                    if (replicas > 3) {
                        Map<String, Object> opt = new LinkedHashMap<>();
                        opt.put("type", "scale_down");
                        opt.put("description", dep.getMetadata().getName() + " 레플리카 수(" + replicas + ") 검토를 통한 비용 절감 가능");
                        opt.put("potential_savings", (replicas - 2) * baseMonthlyPerPod * 0.8);
                        optimizations.add(opt);
                    }
                }
            } catch (Exception ignored) {}

            double totalSavings = optimizations.stream()
                    .mapToDouble(o -> ((Number) o.get("potential_savings")).doubleValue())
                    .sum();

            Map<String, Object> formatted = new LinkedHashMap<>();
            formatted.put("current_cost", currentCost);
            formatted.put("optimizations", optimizations);

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("current_cost", currentCost);
            metadata.put("optimization_count", optimizations.size());

            return FormattedResponseDto.of("cost_analysis",
                    String.format("현재 예상 월 비용: %.0f원, 절감 가능 금액: %.0f원", currentCost, totalSavings),
                    "월 비용 분석",
                    formatted, metadata);
        } catch (Exception e) {
            log.error("getCostAnalysis failed", e);
            return errorResponse("cost_analysis", "비용 분석 실패: " + e.getMessage());
        }
    }

    // ─── HELPERS ─────────────────────────────────────────────────────────────

    private String resolveNamespace(String namespace) {
        return (namespace != null && !namespace.isBlank()) ? namespace : defaultNamespace;
    }

    private String resolveActualPodName(String podOrAppName, String ns) {
        if (podOrAppName == null) return "";
        // Try as direct pod name first
        Pod directPod = null;
        try {
            directPod = kubernetesClient.pods().inNamespace(ns).withName(podOrAppName).get();
        } catch (Exception ignored) {}
        if (directPod != null) return podOrAppName;

        // Try by app.kubernetes.io/name label
        PodList pl = kubernetesClient.pods().inNamespace(ns)
                .withLabel("app.kubernetes.io/name", podOrAppName).list();
        if (!pl.getItems().isEmpty()) {
            return pl.getItems().get(0).getMetadata().getName();
        }

        // Try by app label
        pl = kubernetesClient.pods().inNamespace(ns).withLabel("app", podOrAppName).list();
        if (!pl.getItems().isEmpty()) {
            return pl.getItems().get(0).getMetadata().getName();
        }

        return podOrAppName;
    }

    private Map<String, Object> toPodInfo(Pod pod) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("name", pod.getMetadata().getName());
        p.put("status", Optional.ofNullable(pod.getStatus().getPhase()).orElse("Unknown"));
        p.put("ready", computeReadyString(pod));
        p.put("restarts", getTotalRestarts(pod));
        p.put("age", computeAge(pod.getMetadata().getCreationTimestamp()));
        p.put("node", Optional.ofNullable(pod.getSpec().getNodeName()).orElse(""));
        p.put("namespace", pod.getMetadata().getNamespace());
        return p;
    }

    private String computeReadyString(Pod pod) {
        List<ContainerStatus> statuses = pod.getStatus().getContainerStatuses();
        if (statuses == null || statuses.isEmpty()) return "0/0";
        long ready = statuses.stream().filter(ContainerStatus::getReady).count();
        return ready + "/" + statuses.size();
    }

    private int getTotalRestarts(Pod pod) {
        List<ContainerStatus> statuses = pod.getStatus().getContainerStatuses();
        if (statuses == null) return 0;
        return statuses.stream().mapToInt(ContainerStatus::getRestartCount).sum();
    }

    private String computeAge(String timestamp) {
        if (timestamp == null || timestamp.isBlank()) return "unknown";
        try {
            OffsetDateTime created = OffsetDateTime.parse(timestamp);
            Duration duration = Duration.between(created, OffsetDateTime.now(ZoneOffset.UTC));
            long days = duration.toDays();
            if (days > 0) return days + "d";
            long hours = duration.toHours();
            if (hours > 0) return hours + "h";
            long minutes = duration.toMinutes();
            return Math.max(minutes, 0) + "m";
        } catch (Exception e) {
            return "unknown";
        }
    }

    private String getExternalIp(Service svc) {
        if (svc.getStatus() != null && svc.getStatus().getLoadBalancer() != null) {
            var ingresses = svc.getStatus().getLoadBalancer().getIngress();
            if (ingresses != null && !ingresses.isEmpty()) {
                LoadBalancerIngress lbi = ingresses.get(0);
                if (lbi.getIp() != null) return lbi.getIp();
                if (lbi.getHostname() != null) return lbi.getHostname();
            }
        }
        return "<none>";
    }

    private String formatPorts(List<ServicePort> ports) {
        if (ports == null || ports.isEmpty()) return "";
        return ports.stream()
                .map(p -> p.getPort() + "/" + p.getProtocol())
                .collect(Collectors.joining(","));
    }

    private FormattedResponseDto errorResponse(String type, String message) {
        Map<String, Object> formatted = new LinkedHashMap<>();
        formatted.put("error", message);
        formatted.put("command", type);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("command", type);
        metadata.put("has_error", true);
        return FormattedResponseDto.of("error", message, "오류", formatted, metadata);
    }

    private Map<String, Object> buildCategory(String category, String icon, List<Map<String, Object>> commands) {
        Map<String, Object> cat = new LinkedHashMap<>();
        cat.put("category", category);
        cat.put("icon", icon);
        cat.put("commands", commands);
        return cat;
    }

    private Map<String, Object> buildCommand(String name, String nameKo, String desc, String example) {
        Map<String, Object> cmd = new LinkedHashMap<>();
        cmd.put("name", name);
        cmd.put("name_ko", nameKo);
        cmd.put("desc", desc);
        cmd.put("example", example);
        return cmd;
    }
}
