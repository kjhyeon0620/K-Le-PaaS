package klepaas.backend.diagnosis.eval;

import com.fasterxml.jackson.databind.JsonNode;
import klepaas.backend.ai.diagnosis.DiagnosisLabels;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 시그니처 규칙 진단기 (#161, 판정 가이드는 스펙 0161). LLM과 같은 입력에서 관측 시그니처만으로 구체 원인을 정한다.
 * 사례 ID·정답·notes·failure_kind·규칙 기반 explanation은 보지 않는다. 확신도 대신 적용 조건 상태를 낸다.
 */
final class SignatureRuleBaseline {

    enum State { MATCH, NO_MATCH, CONFLICT, INSUFFICIENT }

    /** rule·rootCause·category는 MATCH일 때만 있다. evidence는 일치한 관측의 JSON Pointer, matched는 일치한 규칙 ID 전부 */
    record Result(State state, String rule, String category, String rootCause, List<String> evidence, List<String> matched) {
    }

    static final String RULE_VERSION = "signature-v1.2";

    private static final Pattern ENV_MISSING = Pattern.compile(
            "(?i)(missing required environment variable|environment variable is missing|\\b[A-Z][A-Z0-9_]{2,}\\b is missing"
                    + "|password (option )?is not specified|must specify|need to specify)");
    private static final Pattern REFUSED_PORT = Pattern.compile("dial tcp [^:\\s]+:(\\d+): connect: connection refused");
    private static final Pattern PROTOCOL_BROKEN = Pattern.compile("\": EOF$|malformed HTTP response|transport connection broken");

    private SignatureRuleBaseline() {
    }

    static Result diagnose(JsonNode input) {
        JsonNode logs = input.path("logs");
        JsonNode pods = logs.path("pods");
        JsonNode events = logs.path("events");
        if (!"AVAILABLE".equals(logs.path("observation").asText()) || (pods.isEmpty() && events.isEmpty())) {
            return new Result(State.INSUFFICIENT, null, null, null, List.of(), List.of());
        }
        events = currentTargetEvents(events, pods);
        Map<String, Hit> hits = new LinkedHashMap<>();
        imageReference(pods, events).ifPresentPut(hits);
        if (!hits.containsKey("R-IMG-REF")) imagePull(pods, events).ifPresentPut(hits);
        scheduling(pods, events).ifPresentPut(hits);
        envMissing(pods).ifPresentPut(hits);
        JsonNode config = input.path("detail").path("config");
        probePort(events, config).ifPresentPut(hits);
        probeProtocol(events, config).ifPresentPut(hits);

        List<String> matched = new ArrayList<>(hits.keySet());
        long causes = hits.values().stream().map(Hit::rootCause).distinct().count();
        if (causes == 0) return new Result(State.NO_MATCH, null, null, null, List.of(), matched);
        if (causes > 1) return new Result(State.CONFLICT, null, null, null, List.of(), matched);
        Hit hit = hits.values().iterator().next();
        return new Result(State.MATCH, hit.rule(), categoryOf(hit.rootCause()), hit.rootCause(), hit.evidence(), matched);
    }

    private record Hit(String rule, String rootCause, List<String> evidence) {
        void ifPresentPut(Map<String, Hit> hits) {
            if (!evidence.isEmpty()) hits.put(rule, this);
        }
    }

    private static Hit imageReference(JsonNode pods, JsonNode events) {
        List<String> ev = new ArrayList<>();
        for (int i = 0; i < pods.size(); i++) {
            if ("InvalidImageName".equals(pods.get(i).path("state_reason").asText())) ev.add("/logs/pods/" + i + "/state_reason");
        }
        eventsMatching(events, m -> m.contains("couldn't parse image name") || m.contains("invalid reference format"), ev);
        return new Hit("R-IMG-REF", "invalid_image_reference", ev);
    }

    private static Hit imagePull(JsonNode pods, JsonNode events) {
        List<String> ev = new ArrayList<>();
        for (int i = 0; i < pods.size(); i++) {
            String reason = pods.get(i).path("state_reason").asText();
            if (reason.equals("ImagePullBackOff") || reason.equals("ErrImagePull")) ev.add("/logs/pods/" + i + "/state_reason");
        }
        eventsMatching(events, m -> m.startsWith("Failed to pull image"), ev);
        return new Hit("R-IMG-PULL", "image_not_pullable", ev);
    }

    /** 컨테이너 상태가 없는(배치되지 않은) Pod가 있을 때만 FailedScheduling을 원인으로 본다. 이미 실행·종료한 Pod의 이벤트는 오래된 것이다 */
    private static Hit scheduling(JsonNode pods, JsonNode events) {
        boolean unscheduled = false;
        for (JsonNode pod : pods) {
            boolean noContainerState = pod.path("state").isNull() || pod.path("state").isMissingNode();
            unscheduled |= noContainerState && pod.path("restart_count").asInt(0) == 0
                    && pod.path("logs").isEmpty() && pod.path("previous_logs").isEmpty();
        }
        List<String> ev = new ArrayList<>();
        if (unscheduled) {
            for (int i = 0; i < events.size(); i++) {
                JsonNode e = events.get(i);
                if ("FailedScheduling".equals(e.path("reason").asText()) && e.path("message").asText().contains("Insufficient cpu")) {
                    ev.add("/logs/events/" + i);
                }
            }
        }
        return new Hit("R-SCHED-CPU", "insufficient_cpu", ev);
    }

    /** 반복 종료하는 컨테이너의 로그에 설정 누락 문장이 있을 때만. 종료하지 않는 Pod의 같은 경고는 무시한다 */
    private static Hit envMissing(JsonNode pods) {
        List<String> ev = new ArrayList<>();
        for (int i = 0; i < pods.size(); i++) {
            JsonNode pod = pods.get(i);
            boolean crashing = "CrashLoopBackOff".equals(pod.path("state_reason").asText())
                    || "Error".equals(pod.path("last_termination_reason").asText());
            if (!crashing) continue;
            for (String field : List.of("previous_logs", "logs")) {
                JsonNode lines = pod.path(field);
                for (int j = 0; j < lines.size(); j++) {
                    if (ENV_MISSING.matcher(lines.get(j).asText()).find()) {
                        ev.add("/logs/pods/" + i + "/" + field + "/" + j);
                        break;
                    }
                }
            }
        }
        return new Hit("R-ENV", "required_env_missing", ev);
    }

    /** 거부된 포트가 probe 포트이고 컨테이너 포트와 다를 때만. 컨테이너 포트에서 거부되면 원인을 정하지 않는다(바인딩 주소·느린 기동 등) */
    private static Hit probePort(JsonNode events, JsonNode config) {
        int probePort = effectiveProbePort(config);
        int containerPort = config.path("container_port").asInt(-2);
        List<String> ev = new ArrayList<>();
        if (probePort > 0 && probePort != containerPort) {
            for (int i = 0; i < events.size(); i++) {
                String m = events.get(i).path("message").asText();
                Matcher refused = REFUSED_PORT.matcher(m);
                if (m.startsWith("Readiness probe failed") && refused.find() && Integer.parseInt(refused.group(1)) == probePort) {
                    ev.add("/logs/events/" + i);
                }
            }
        }
        return new Hit("R-PROBE-PORT", "probe_port_closed", ev);
    }

    private static Hit probeProtocol(JsonNode events, JsonNode config) {
        int probePort = effectiveProbePort(config);
        List<String> ev = new ArrayList<>();
        if (probePort > 0 && probePort == config.path("container_port").asInt(-2)) {
            for (int i = 0; i < events.size(); i++) {
                String m = events.get(i).path("message").asText();
                if (m.startsWith("Readiness probe failed: Get \"http") && PROTOCOL_BROKEN.matcher(m).find()) ev.add("/logs/events/" + i);
            }
        }
        return new Hit("R-PROBE-PROTO", "probe_protocol_mismatch", ev);
    }

    /**
     * Pod 대상 이벤트 중 지금 관측한 Pod의 것만 남긴다(v1.2). 이전 버전 Pod의 오래된 이벤트가 다른 원인을 가리키는 것을 막는다.
     * Pod가 아닌 대상(ReplicaSet 등) 이벤트는 그대로 둔다. 근거 위치를 바꾸지 않도록 걸러낸 자리는 빈 이벤트로 둔다.
     */
    private static JsonNode currentTargetEvents(JsonNode events, JsonNode pods) {
        java.util.Set<String> current = new java.util.HashSet<>();
        pods.forEach(p -> current.add("Pod/" + p.path("name").asText()));
        com.fasterxml.jackson.databind.node.ArrayNode kept = DiagnosisEval.JSON.createArrayNode();
        for (JsonNode e : events) {
            String object = e.path("object").asText("");
            kept.add(object.startsWith("Pod/") && !current.contains(object) ? DiagnosisEval.JSON.createObjectNode() : e);
        }
        return kept;
    }

    /** probe 포트가 비어 있으면 플랫폼은 컨테이너 포트를 쓴다(`KubernetesManifestGenerator`). probe가 없으면 -1 (v1.1) */
    private static int effectiveProbePort(JsonNode config) {
        JsonNode probe = config.path("health_probe");
        if (probe.isNull() || probe.isMissingNode()) return -1;
        JsonNode port = probe.path("port");
        return port.isNull() || port.isMissingNode() ? config.path("container_port").asInt(-1) : port.asInt(-1);
    }

    private static void eventsMatching(JsonNode events, java.util.function.Predicate<String> test, List<String> ev) {
        for (int i = 0; i < events.size(); i++) {
            if (test.test(events.get(i).path("message").asText())) ev.add("/logs/events/" + i);
        }
    }

    private static String categoryOf(String rootCause) {
        String description = DiagnosisLabels.ROOT_CAUSES.get(rootCause);
        return description.substring(0, description.indexOf(':'));
    }
}
