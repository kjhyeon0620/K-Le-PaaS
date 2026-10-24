package klepaas.backend.ai.diagnosis;

import com.fasterxml.jackson.databind.JsonNode;
import klepaas.backend.ai.diagnosis.SignatureRules.State;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 시그니처 규칙이 원인을 정한 진단 결과 (#170). 모델을 부르지 않는다. 문구는 일치한 관측만 옮기고 관측보다 깊게 단정하지 않는다
 * (예: 이미지를 받지 못한 이유가 없는 태그인지 레지스트리 미접속인지는 정하지 않는다).
 */
final class RuleDiagnosis {

    /** response는 규칙이 MATCH일 때만 있다 */
    record Outcome(SignatureRules.Result rule, DeploymentDiagnosisResponse response) {
    }

    private static final Pattern POD_FIELD = Pattern.compile("^/logs/pods/(\\d+)/([a-z_]+)$");

    private RuleDiagnosis() {
    }

    /** 근거 위치를 사람이 읽는 관측 문장으로: 이벤트는 "reason: message", Pod 필드는 "Pod 이름 필드: 값", 그 밖(로그 줄·실패 사유)은 원문 */
    private static String fact(JsonNode input, String pointer) {
        JsonNode node = input.at(pointer);
        if (node.isObject() && node.has("message")) return node.path("reason").asText() + ": " + node.path("message").asText();
        Matcher pod = POD_FIELD.matcher(pointer);
        if (pod.matches() && node.isValueNode()) {
            return "Pod " + input.at("/logs/pods/" + pod.group(1) + "/name").asText() + " " + pod.group(2) + ": " + node.asText();
        }
        return DiagnosisPrompt.cite(input, pointer).excerpt();
    }

    static Outcome diagnose(Long deploymentId, String redactedInputJson) {
        JsonNode input = DiagnosisPrompt.read(redactedInputJson);
        var rule = SignatureRules.diagnose(input);
        if (rule.state() != State.MATCH) return new Outcome(rule, null);
        List<DiagnosisPrompt.Citation> cited = rule.evidence().stream().distinct().map(p -> DiagnosisPrompt.cite(input, p)).toList();
        JsonNode config = input.path("detail").path("config");
        int containerPort = config.path("container_port").asInt();
        int probePort = SignatureRules.effectiveProbePort(config);
        String summary = switch (rule.rule()) {
            case "R-IMG-REF" -> "이미지 참조 형식이 잘못돼 이미지를 받을 수 없습니다(InvalidImageName 또는 참조 형식 오류).";
            case "R-IMG-PULL" -> "노드가 이미지를 받지 못했습니다(ImagePullBackOff·ErrImagePull·이미지 받기 실패).";
            case "R-SCHED-CPU" -> "Pod가 CPU 요청을 채울 노드가 없어 배치되지 못했습니다(FailedScheduling: Insufficient cpu).";
            case "R-ENV" -> "컨테이너가 필요한 설정이 없다는 로그를 남기고 반복 종료했습니다.";
            case "R-PROBE-PORT" -> "readiness probe가 %d 포트에서 연결을 거부당했습니다. 컨테이너 포트는 %d입니다.".formatted(probePort, containerPort);
            case "R-PROBE-PROTO" -> "HTTP readiness probe가 컨테이너 포트 %d에서 HTTP 응답을 받지 못했습니다(EOF·잘못된 HTTP 응답).".formatted(containerPort);
            default -> throw new IllegalStateException("문구가 없는 규칙: " + rule.rule());
        };
        List<String> nextChecks = switch (rule.rule()) {
            case "R-IMG-REF" -> List.of("이미지 이름·태그 형식(소문자, 허용 문자, 태그 구분)을 확인합니다.");
            case "R-IMG-PULL" -> List.of("이미지 이름·태그가 레지스트리에 있는지 확인합니다.", "노드에서 레지스트리 주소·인증(pull secret)에 접근할 수 있는지 확인합니다.");
            case "R-SCHED-CPU" -> List.of("배포 설정의 CPU 요청값과 노드의 할당 가능 CPU를 비교합니다.");
            case "R-ENV" -> List.of("로그가 요구하는 환경 변수가 배포 설정에 있는지 확인합니다.", "직전 성공 배포와 env 변경을 비교합니다.");
            case "R-PROBE-PORT" -> List.of("readiness probe 포트와 앱이 실제로 대기하는 포트를 확인합니다.");
            case "R-PROBE-PROTO" -> List.of("앱이 그 포트에서 HTTP를 제공하는지, probe 경로·종류가 맞는지 확인합니다.");
            default -> List.of();
        };
        List<String> observed = rule.evidence().stream().map(p -> fact(input, p)).distinct().toList();
        return new Outcome(rule, new DeploymentDiagnosisResponse(deploymentId, DeploymentDiagnosisService.RULE_MODEL,
                SignatureRules.RULE_VERSION, rule.category(), rule.rootCause(), false, summary, observed, List.of(), nextChecks, cited,
                List.of("관측 시그니처 규칙(" + rule.rule() + ")으로 판정했습니다. 모델을 호출하지 않았습니다"),
                List.of(), List.of(), false, null));
    }
}
