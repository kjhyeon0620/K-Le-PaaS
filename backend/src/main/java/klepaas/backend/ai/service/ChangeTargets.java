package klepaas.backend.ai.service;

import klepaas.backend.ai.entity.Intent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 변경 명령에 실행할 대상이 있는지 본다 (#135). 대상이 빠진 변경 명령은 승인 대기로 만들지 않고 되묻는다.
 * 인자의 존재·형식만 보고 DB·Kubernetes를 조회하지 않는다. 소유권은 실행 시점에 ResourceAccessService가 본다.
 */
final class ChangeTargets {

    /** intent별 필수 인자와 되물을 문장. ActionDispatcher가 실행 때 읽는 인자와 같다 */
    private static final Map<Intent, Map<String, String>> REQUIRED = Map.of(
            Intent.DEPLOY, ordered("repository_id", "배포할 저장소 ID"),
            Intent.SCALE, ordered("deployment_id", "대상 배포 ID", "replicas", "레플리카 수(1 이상)"),
            Intent.RESTART, ordered("deployment_id", "대상 배포 ID"),
            Intent.ROLLBACK, ordered("owner", "저장소 owner", "repo", "저장소 이름"),
            Intent.ROLLBACK_EXECUTION, ordered("owner", "저장소 owner", "repo", "저장소 이름"));

    private ChangeTargets() {
    }

    /** 빠지거나 형식이 맞지 않는 필수 인자 이름. 변경 명령이 아니면 비어 있다 */
    static List<String> missing(Intent intent, Map<String, Object> args) {
        List<String> out = new ArrayList<>();
        Map<String, Object> given = args == null ? Map.of() : args;
        for (String key : REQUIRED.getOrDefault(intent, Map.of()).keySet()) {
            if (!valid(key, given.get(key))) out.add(key);
        }
        return out;
    }

    /** 사용자에게 보일 되묻는 문장 */
    static String question(Intent intent, List<String> missing) {
        Map<String, String> labels = REQUIRED.get(intent);
        List<String> parts = missing.stream().map(labels::get).toList();
        return "실행할 대상이 부족해 승인 요청을 만들지 않았습니다. 다음을 알려 주세요: " + String.join(", ", parts);
    }

    private static boolean valid(String key, Object value) {
        if (value == null) return false;
        if (key.equals("owner") || key.equals("repo")) return !value.toString().isBlank();
        // ID와 레플리카 수는 1 이상의 정수 (문자열 숫자도 실행 코드가 받는다)
        try {
            double number = value instanceof Number n ? n.doubleValue() : Double.parseDouble(value.toString().trim());
            return number >= 1 && number == Math.rint(number);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static Map<String, String> ordered(String... keyAndLabel) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < keyAndLabel.length; i += 2) map.put(keyAndLabel[i], keyAndLabel[i + 1]);
        return map;
    }
}
