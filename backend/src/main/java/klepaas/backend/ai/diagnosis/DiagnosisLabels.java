package klepaas.backend.ai.diagnosis;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** 진단 라벨 목록 (스펙 0099). AI 진단과 평가 채점기가 같이 쓴다. 라벨을 늘리면 스펙도 갱신한다. */
public final class DiagnosisLabels {

    /** APP_CRASH는 규칙 기준선(CRASH_LOOP) 전용이다. AI 진단 선택지에는 없다. */
    public static final String APP_CRASH = "APP_CRASH";

    public static final Map<String, String> CATEGORIES = ordered(
            "IMAGE", "이미지를 받거나 참조하지 못함",
            "ENV_CONFIG", "컨테이너 환경 변수·설정 문제",
            "READINESS", "앱은 실행 중이나 readiness probe를 통과하지 못함",
            "SCHEDULING", "Pod가 노드에 배치되지 못함");

    public static final Map<String, String> ROOT_CAUSES = ordered(
            "image_not_pullable", "IMAGE: 레지스트리·이미지가 없거나 닿지 않아 받지 못함",
            "invalid_image_reference", "IMAGE: 이미지 참조 형식이 잘못됨",
            "required_env_missing", "ENV_CONFIG: 앱이 요구하는 env가 없음",
            "probe_protocol_mismatch", "READINESS: probe 프로토콜이 앱과 맞지 않음 (예: TCP 서비스에 HTTP probe)",
            "probe_port_closed", "READINESS: probe가 앱이 열지 않은 포트로 감",
            "insufficient_cpu", "SCHEDULING: 노드 CPU가 요청을 감당하지 못함");

    public static boolean isCategory(String value) {
        return CATEGORIES.containsKey(value);
    }

    public static boolean isRootCause(String value) {
        return ROOT_CAUSES.containsKey(value);
    }

    /** 평가 채점기가 허용하는 범주 (규칙 기준선의 APP_CRASH 포함). */
    public static Set<String> scoredCategories() {
        var all = new java.util.HashSet<>(CATEGORIES.keySet());
        all.add(APP_CRASH);
        return Set.copyOf(all);
    }

    private static Map<String, String> ordered(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) map.put(pairs[i], pairs[i + 1]);
        return java.util.Collections.unmodifiableMap(map);
    }

    private DiagnosisLabels() {
    }
}
