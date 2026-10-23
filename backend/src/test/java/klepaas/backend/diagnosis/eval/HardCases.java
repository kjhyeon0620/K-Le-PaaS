package klepaas.backend.diagnosis.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import klepaas.backend.diagnosis.eval.DiagnosisEval.Case;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * #99 사례를 기계적으로 바꾼 회귀·보류 검사 (#158, 규칙은 스펙 0158). 어려운 평가가 아니라 잡음·입력 추가에 결론이 흔들리지 않는지,
 * 증거가 없을 때 보류하는지를 본다. 정답 근거 위치가 바뀌지 않도록 배열에는 뒤에만 붙인다.
 */
final class HardCases {

    /** 기초 잡음 회귀 / Pod·로그 추가 입력 / 파생 증거 부족(보류) / 감사용(자동 채점 제외) */
    static final String DECOY = "NOISE-DECOY", LONG = "NOISE-EXTRA-PODS", MISSING = "INSUFFICIENT-DERIVED", AUDIT = "AUDIT-ONLY";

    /** 증거를 지우면 원인을 정할 수 없게 되는 사례만 고른다 (스펙 0158 결정 기록) */
    static final List<String> MISSING_BASES = List.of("env-postgres-password-missing", "composite-image-and-env-changed");
    static final List<String> CONFLICT_BASES = List.of("env-postgres-password-missing", "readiness-probe-port-closed");

    private HardCases() {
    }

    static List<ObjectNode> derive(List<Case> base) {
        List<ObjectNode> out = new ArrayList<>();
        List<Case> withCause = base.stream().filter(c -> !c.expected().abstain()).toList();
        for (Case c : withCause) out.add(decoy(c));
        for (Case c : base) if (MISSING_BASES.contains(c.id())) out.add(missing(c));
        for (Case c : base) if (CONFLICT_BASES.contains(c.id())) out.add(conflict(c));
        for (Case c : withCause) out.add(longLogs(c));
        return out;
    }

    /** 미끼: 몇 시간 전 이전 Pod의 다른 원인 경고 이벤트와 무관한 env 추가 변경을 붙인다. 정답은 그대로 */
    static ObjectNode decoy(Case c) {
        ObjectNode n = copy(c, "decoy", DECOY);
        String image = c.input().at("/detail/deployment/image_uri").asText();
        String oldPod = appName(c) + "-5d9c7f6b44-old01";
        String lastSeen = Instant.parse(c.input().at("/detail/deployment/started_at").asText())
                .minus(3, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS).toString();
        boolean imageCase = "IMAGE".equals(c.expected().category());
        ObjectNode event = n.objectNode()
                .put("type", "Warning")
                .put("reason", imageCase ? "Unhealthy" : "Failed")
                .put("message", imageCase
                        ? "Readiness probe failed: Get \"http://10.42.0.3:8080/ready\": context deadline exceeded (Client.Timeout exceeded while awaiting headers)"
                        : "Failed to pull image \"" + repository(image) + ":previous\": rpc error: code = NotFound desc = failed to pull and unpack image: not found")
                .put("object", "Pod/" + oldPod)
                .put("count", 3)
                .put("last_seen", lastSeen);
        ((ArrayNode) n.at("/input/logs/events")).add(event);
        ((ArrayNode) n.at("/input/detail/changes")).add(n.objectNode()
                .put("field", "env:LOG_LEVEL").putNull("before").putNull("after").put("kind", "ADDED"));
        return note(n, "derived: " + c.id() + "에 미끼 추가 (3시간 전 이전 Pod " + oldPod + "의 "
                        + (imageCase ? "readiness 실패" : "이미지 pull 실패") + " 이벤트, 무관한 env:LOG_LEVEL 추가 변경)",
                "원인은 원본과 같다. 미끼 이벤트는 다른 Pod·3시간 전 기록이고 LOG_LEVEL 추가는 실패와 무관하다.");
    }

    /** 증거 제거: Pod 로그를 조회하지 못한 상태로 만든다. 복합 사례는 만료된 이벤트도 지운다. 정답은 판단 보류 */
    static ObjectNode missing(Case c) {
        ObjectNode n = neutralImage(copy(c, "missing", MISSING));
        for (JsonNode pod : n.at("/input/logs/pods")) {
            ObjectNode p = (ObjectNode) pod;
            String container = appName(c);
            p.putArray("logs");
            p.putArray("previous_logs");
            p.put("logs_error", "직전 로그 조회 실패: previous terminated container \"" + container + "\" in pod \""
                    + p.path("name").asText() + "\" not found");
        }
        boolean composite = c.id().startsWith("composite-");
        if (composite) ((ArrayNode) n.at("/input/logs/events")).removeAll();
        abstain(n);
        allowHypothesis(n);
        return note(n, "derived: " + c.id() + "에서 Pod 로그를 조회 실패로 바꿈" + (composite ? ", 만료된 이벤트 삭제" : ""),
                (composite
                        ? "이미지와 env 하나가 함께 바뀌었고, 둘 중 무엇이 원인인지 가릴 로그·이벤트가 없다."
                        : "CrashLoopBackOff·exit 1만 남고 원인을 가리킬 로그·변경이 없다.")
                        + " 이미지를 사내 앱 이미지로 바꿔 공개 이미지에 대한 사전 지식(postgres는 비밀번호 env가 필요)으로 단정할 수 없게 했다."
                        + " 판단 보류가 정답이고, 가능한 원인을 가설로 제시하는 것은 허용한다.");
    }

    /** 원래 "모순" 변형: env 사례는 증거 부족(보류), readiness 사례는 감사용으로 둔다 (#158 정비, Codex 토론 합의) */
    static ObjectNode conflict(Case c) {
        ObjectNode n = copy(c, "conflict", c.id().equals("readiness-probe-port-closed") ? AUDIT : MISSING);
        ObjectNode pod = (ObjectNode) n.at("/input/logs/pods/0");
        switch (c.id()) {
            case "env-postgres-password-missing" -> {
                n = neutralImage(n);
                pod = (ObjectNode) n.at("/input/logs/pods/0");
                ArrayNode ready = n.arrayNode()
                        .add("2026-10-11T05:24:50.912Z  INFO 1 --- [main] c.a.orders.OrdersApiApplication : Starting OrdersApiApplication v2.3.1 using Java 17.0.12 with PID 1")
                        .add("2026-10-11T05:24:52.204Z  INFO 1 --- [main] o.s.b.w.embedded.tomcat.TomcatWebServer : Tomcat started on port 8080 (http) with context path '/'")
                        .add("2026-10-11T05:24:52.231Z  INFO 1 --- [main] c.a.orders.OrdersApiApplication : Started OrdersApiApplication in 1.611 seconds (process running for 1.98)");
                pod.set("logs", ready);
                pod.set("previous_logs", ready.deepCopy());
                abstain(n);
                allowHypothesis(n);
                return note(n, "derived: " + c.id() + "의 컨테이너 로그를 정상 기동 완료 로그로 바꿈 (이미지도 사내 앱으로)",
                        "Pod는 exit 1로 CrashLoopBackOff이고 로그는 정상 기동 완료만 보인다. 정상 기동 뒤 종료하는 것도 가능해 모순은 아니다. "
                                + "입력으로 종료 원인을 알 수 없어 판단 보류가 정답이다. env 문제 등을 가설로 제시하는 것은 허용한다.");
            }
            case "readiness-probe-port-closed" -> {
                pod.set("logs", n.arrayNode()
                        .add("1791696413: mosquitto version 2.0.22 starting")
                        .add("1791696413: Config loaded from /mosquitto/config/mosquitto.conf.")
                        .add("1791696413: Opening ipv4 listen socket on port 8080.")
                        .add("1791696413: Opening ipv6 listen socket on port 8080.")
                        .add("1791696413: mosquitto version 2.0.22 running"));
                abstain(n);
                return note(n, "derived: " + c.id() + "의 로그를 8080 대기로 바꿈 (감사용, 자동 채점 제외)",
                        "로그는 8080에서 대기한다는데 probe는 Pod IP의 8080에서 연결 거부다. 바인딩 주소·시점 차이로 양립할 수 있고 "
                                + "현 라벨(probe_port_closed)의 경계도 모호해 정답을 하나로 정하지 않는다. 관측 설명과 추가 확인의 적절성만 사람이 검토한다.");
            }
            default -> throw new IllegalArgumentException("변형 규칙이 없는 사례: " + c.id());
        }
    }

    /** 긴 로그: 같은 상태의 레플리카 2개와 이전 버전의 정상 Pod 2개(긴 로그 40줄씩)를 뒤에 붙인다. 정답은 그대로 */
    static ObjectNode longLogs(Case c) {
        ObjectNode n = copy(c, "long", LONG);
        ArrayNode pods = (ArrayNode) n.at("/input/logs/pods");
        JsonNode first = pods.get(0);
        String name = first.path("name").asText();
        for (String suffix : List.of("r2a7x", "r3b8y")) {
            ObjectNode replica = first.deepCopy();
            replica.put("name", name.substring(0, name.lastIndexOf('-') + 1) + suffix);
            pods.add(replica);
        }
        String image = c.input().at("/detail/deployment/image_uri").asText();
        for (String suffix : List.of("k4c9z", "m5d2w")) {
            ObjectNode old = n.objectNode()
                    .put("name", appName(c) + "-5d9c7f6b44-" + suffix)
                    .put("phase", "Running").put("ready", true).put("restart_count", 0).put("state", "running")
                    .putNull("state_reason").putNull("state_message").putNull("last_termination_reason").putNull("last_exit_code");
            ArrayNode logs = old.putArray("logs");
            noise(image, suffix).forEach(logs::add);
            old.putArray("previous_logs");
            old.putNull("logs_error");
            pods.add(old);
        }
        return note(n, "derived: " + c.id() + "에 같은 상태 레플리카 2개와 이전 버전 정상 Pod 2개(로그 40줄씩) 추가",
                "원인은 원본과 같다. 정답 근거는 첫 Pod에 그대로 있고 복제 레플리카에도 같은 근거가 있다. 추가한 이전 버전 Pod는 정상 동작 로그다. "
                        + "원본이 항상 첫 위치라 장문 추론 능력을 재는 사례가 아니다.");
    }

    /** 이미지 계열별 정상 동작 로그 40줄. 원인을 가리키는 문구(비밀번호·probe·이미지 오류)는 넣지 않는다 */
    static List<String> noise(String image, String seed) {
        String img = image.toLowerCase();
        List<String> lines = new ArrayList<>();
        int base = Math.abs(seed.hashCode() % 900);
        for (int i = 0; i < 40; i++) {
            String ts = String.format("2026-10-11 0%d:%02d:%02d.%03d", 2 + i / 30, (i * 2) % 60, (i * 7) % 60, (base + i * 37) % 1000);
            if (img.contains("postgres")) {
                lines.add(i % 2 == 0
                        ? ts + " UTC [" + (60 + i) + "] LOG:  checkpoint complete: wrote " + (100 + i * 3) + " buffers (0." + (i % 9)
                        + "%); 0 WAL file(s) added, 0 removed, 1 recycled; write=13.2" + i % 10 + " s, sync=0.004 s, total=13.219 s; sync files="
                        + (30 + i) + ", longest=0.002 s, average=0.001 s; distance=1092 kB, estimate=1311 kB"
                        : ts + " UTC [" + (60 + i) + "] LOG:  automatic vacuum of table \"app.public.orders_" + (i % 5)
                        + "\": index scans: 1, pages: 0 removed, " + (812 + i) + " remain, tuples: " + (120 + i) + " removed, "
                        + (48000 + i * 11) + " remain, system usage: CPU: user: 0.0" + i % 10 + " s, system: 0.00 s, elapsed: 0.1" + i % 10 + " s");
            } else if (img.contains("mysql")) {
                lines.add(ts.replace(' ', 'T') + "Z " + (i + 8) + " [Note] [MY-0108" + (i % 10) + "] [InnoDB] Buffer pool(s) load progress: "
                        + (i * 2 + 10) + "% of " + (8192 + i) + " pages, read " + (1200 + i * 31) + " pages, flushed " + (i * 17)
                        + " dirty pages to disk in 0." + (i % 9) + "s; page cleaner took " + (300 + i) + "ms for flush list batch of " + (64 + i) + " pages");
            } else if (img.contains("redis")) {
                lines.add("1:M 11 Oct 2026 0" + (2 + i / 30) + ":" + String.format("%02d:%02d.%03d", (i * 2) % 60, (i * 7) % 60, (base + i) % 1000)
                        + (i % 3 == 0 ? " * " + (100 + i) + " changes in 300 seconds. Saving... Background saving started by pid " + (200 + i)
                        + "; DB saved on disk; Fork CoW for RDB: current " + (i % 4) + " MB, peak " + (i % 6) + " MB, average " + (i % 3) + " MB"
                        : " * Client id=" + (1000 + i) + " addr=10.42.0." + (20 + i % 30) + ":" + (40000 + i * 13) + " laddr=10.42.0.9:6379 fd="
                        + (9 + i) + " name= age=" + (i * 60) + " idle=" + (i % 7) + " flags=N db=0 sub=0 psub=0 multi=-1 qbuf=0 cmd=get user=default"));
            } else if (img.contains("mosquitto")) {
                lines.add((1791690000 + i * 61) + ": " + (i % 2 == 0
                        ? "New client connected from 10.42.0." + (30 + i % 20) + ":" + (51000 + i * 7) + " as sensor-" + (100 + i)
                        + " (p2, c1, k60, u'device-" + (100 + i) + "'); session restored with " + (i % 4) + " queued messages, subscriptions=3"
                        : "Received PUBLISH from sensor-" + (99 + i) + " (d0, q1, r0, m" + (2000 + i) + ", 'factory/line-" + (i % 4)
                        + "/temperature', ... (" + (48 + i % 20) + " bytes)); sending PUBACK to sensor-" + (99 + i) + " (m" + (2000 + i) + ", rc0)"));
            } else {
                lines.add("{\"ts\":\"2026-10-11T0" + (2 + i / 30) + String.format(":%02d:%02d.%03dZ", (i * 2) % 60, (i * 7) % 60, (base + i) % 1000)
                        + "\",\"level\":\"INFO\",\"logger\":\"http.access\",\"method\":\"" + (i % 3 == 0 ? "POST" : "GET")
                        + "\",\"path\":\"/api/v1/invoices/" + (30000 + i * 17) + "\",\"status\":200,\"duration_ms\":" + (8 + i % 40)
                        + ",\"trace_id\":\"" + Integer.toHexString(0x5f3a0000 + base * 41 + i * 977) + "\",\"user_agent\":\"billing-worker/1.4.1\"}");
            }
        }
        return lines;
    }

    /**
     * 공개 이미지 이름을 사내 앱 이미지로 바꾼다. 원인을 증거가 아니라 이미지에 대한 사전 지식(예: postgres는 비밀번호 env 필요)으로
     * 맞히지 못하게 하려는 것이다. 입력의 모든 문자열에서 바꾼다.
     */
    static ObjectNode neutralImage(ObjectNode n) {
        String text = n.get("input").toString()
                .replace("postgres:16-alpine", "ghcr.io/acme/orders-api:2.3.1")
                .replace("postgres:16", "ghcr.io/acme/orders-api:2.4.0");
        try {
            n.set("input", DiagnosisEval.JSON.readTree(text));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return n;
    }

    /** 변형은 원본과 같은 에피소드이고 robustness 분할이다 (#166). 주입 기록은 원본에만 둔다 */
    private static ObjectNode copy(Case c, String suffix, String group) {
        ObjectNode n = DiagnosisEval.JSON.valueToTree(c);
        n.set("input", c.input().deepCopy());
        n.put("id", c.id() + "--" + suffix);
        n.put("group", group);
        n.put("episode_id", c.episodeId());
        n.put("split", "robustness");
        n.put("slice", switch (group) {
            case MISSING -> "INSUFFICIENT";
            case AUDIT -> "CONFLICT";
            default -> "ROBUSTNESS";
        });
        n.remove(List.of("knowledge", "injection"));
        ObjectNode expected = (ObjectNode) n.get("expected");
        expected.remove(List.of("knowledge_ids", "accepted_root_causes")); // 기본 사례에는 없다
        if (expected.path("allowed_hypotheses").isEmpty()) expected.remove("allowed_hypotheses");
        if (expected.path("forbidden_assertions").isEmpty()) expected.remove("forbidden_assertions");
        ObjectNode audit = n.putObject("audit");
        audit.put("status", group.equals(AUDIT) ? "undecidable" : "kept");
        audit.put("reason", group.equals(AUDIT) ? "#158: 정답을 하나로 정하지 않는다(감사용, 자동 채점 제외)" : "#158 변형 규칙의 기대 답");
        audit.put("at", "2026-10-23");
        n.set("notes", n.remove("notes")); // notes를 끝에 둔다
        return n;
    }

    private static void abstain(ObjectNode n) {
        ObjectNode e = (ObjectNode) n.get("expected");
        e.putNull("category");
        e.putNull("root_cause");
        e.putArray("evidence");
        e.putArray("evidence_sets");
        e.put("abstain", true);
        e.putArray("forbidden");
    }

    /** 증거 부족 변형에서 원래 원인을 가설로 제시하는 것은 허용한다 (스펙 0158 결정) */
    private static void allowHypothesis(ObjectNode n) {
        ((ObjectNode) n.get("expected")).putArray("allowed_hypotheses").add("필수 환경 변수 누락 등 설정 문제 가능성(가설로서)");
    }

    private static ObjectNode note(ObjectNode n, String provenance, String notes) {
        n.put("provenance", provenance);
        n.put("notes", notes);
        return n;
    }

    /** 첫 Pod 이름에서 ReplicaSet·Pod 접미사를 뺀 앱(컨테이너) 이름 */
    private static String appName(Case c) {
        String pod = c.input().at("/logs/pods/0/name").asText();
        String[] parts = pod.split("-");
        return String.join("-", java.util.Arrays.copyOf(parts, parts.length - 2));
    }

    private static String repository(String image) {
        int colon = image.lastIndexOf(':');
        return colon > image.lastIndexOf('/') ? image.substring(0, colon) : image;
    }
}
