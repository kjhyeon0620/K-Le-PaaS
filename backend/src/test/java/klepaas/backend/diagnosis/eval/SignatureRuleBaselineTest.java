package klepaas.backend.diagnosis.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import klepaas.backend.diagnosis.eval.DiagnosisEval.Case;
import klepaas.backend.diagnosis.eval.SignatureRuleBaseline.State;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SignatureRuleBaselineTest {

    private static Case byId(String id) throws Exception {
        List<Case> all = new ArrayList<>(DiagnosisEval.loadCases());
        all.addAll(DiagnosisEval.loadCaseSet("blind-test"));
        return all.stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("반례: 컨테이너 포트에서 거부된 연결은 포트 문제로 정하지 않는다")
    void refusedOnContainerPortIsNotDecided() throws Exception {
        var r = SignatureRuleBaseline.diagnose(byId("g1-mq-prb-proto").input());
        assertThat(r.state()).isEqualTo(State.NO_MATCH);
    }

    @Test
    @DisplayName("반례: 이미 실행·종료한 Pod의 FailedScheduling은 오래된 이벤트로 무시한다")
    void staleSchedulingEventIsIgnored() throws Exception {
        var r = SignatureRuleBaseline.diagnose(byId("g1-my-env-miss").input());
        assertThat(r.state()).isEqualTo(State.MATCH);
        assertThat(r.rootCause()).isEqualTo("required_env_missing");
        assertThat(r.matched()).containsExactly("R-ENV");
    }

    @Test
    @DisplayName("반례: 종료하지 않는 Pod의 설정 누락 경고는 env 원인이 아니다")
    void warningOnHealthyContainerIsIgnored() throws Exception {
        var r = SignatureRuleBaseline.diagnose(byId("g1-inf-prb-port").input());
        assertThat(r.matched()).doesNotContain("R-ENV");
        assertThat(r.rootCause()).isEqualTo("probe_port_closed");
        assertThat(r.evidence()).allSatisfy(p -> assertThat(p).startsWith("/logs/events/"));
    }

    @Test
    @DisplayName("반례(v1.2): 지금 관측하지 않은 이전 Pod의 이벤트는 원인 판정에 쓰지 않는다")
    void otherPodEventIsIgnored() throws Exception {
        ObjectNode input = byId("env-postgres-password-missing").input().deepCopy();
        ((ArrayNode) input.at("/logs/events")).addObject().put("type", "Warning").put("reason", "Failed")
                .put("message", "Failed to pull image \"postgres:previous\": not found").put("object", "Pod/demo-orders-db-5d9c7f6b44-old01");
        var r = SignatureRuleBaseline.diagnose(input);
        assertThat(r.state()).isEqualTo(State.MATCH);
        assertThat(r.matched()).containsExactly("R-ENV");
    }

    @Test
    @DisplayName("관측이 없으면 INSUFFICIENT, 서로 다른 원인이 함께 맞으면 CONFLICT")
    void insufficientAndConflict() throws Exception {
        assertThat(SignatureRuleBaseline.diagnose(byId("insufficient-observation-unavailable").input()).state())
                .isEqualTo(State.INSUFFICIENT);

        ObjectNode input = byId("env-postgres-password-missing").input().deepCopy();
        ((ArrayNode) input.at("/logs/events")).addObject().put("type", "Warning").put("reason", "Failed")
                .put("message", "Failed to pull image \"postgres:16-alpine\": not found");
        var r = SignatureRuleBaseline.diagnose(input);
        assertThat(r.state()).isEqualTo(State.CONFLICT);
        assertThat(r.matched()).containsExactlyInAnyOrder("R-ENV", "R-IMG-PULL");
    }

    @Test
    @DisplayName("규칙은 failure_kind·규칙 기반 설명을 보지 않는다")
    void ignoresServerClassification() throws Exception {
        Case c = byId("image-registry-unreachable");
        ObjectNode input = c.input().deepCopy();
        ((ObjectNode) input.at("/detail/deployment")).put("failure_kind", "CRASH_LOOP");
        ((ObjectNode) input.at("/detail")).putNull("explanation");
        assertThat(SignatureRuleBaseline.diagnose(input)).isEqualTo(SignatureRuleBaseline.diagnose(c.input()));
    }

    /** 개발 세트 51사례의 규칙 결과를 리포트로 남기고 요약을 고정한다. 규칙을 바꾸면 스펙의 결과표도 갱신한다 */
    @Test
    void developmentSetResult() throws Exception {
        List<Case> cases = new ArrayList<>(DiagnosisEval.loadCases());
        cases.addAll(DiagnosisEval.loadCaseSet("blind-test"));
        assertThat(report(cases, "signature-rule-dev.json")).doesNotContainKeys("match_wrong", "match_on_abstain_case");
    }

    /** #158 회귀·보류 검사 23사례: 잡음과 추가 입력에 규칙이 흔들리지 않는지 (#158) */
    @Test
    void noiseSetResult() throws Exception {
        assertThat(report(DiagnosisEval.loadCaseSet("hard"), "signature-rule-noise.json"))
                .doesNotContainKeys("match_wrong", "match_on_abstain_case");
    }

    private static Map<String, Integer> report(List<Case> cases, String file) throws Exception {
        Map<String, Integer> summary = new LinkedHashMap<>();
        Map<String, Object> perCase = new LinkedHashMap<>();
        for (Case c : cases) {
            var r = SignatureRuleBaseline.diagnose(c.input());
            String verdict = switch (r.state()) {
                case MATCH -> c.expected().abstain() ? "match_on_abstain_case"
                        : c.expected().accepts(r.rootCause()) ? "match_correct" : "match_wrong";
                case INSUFFICIENT -> c.expected().abstain() ? "insufficient_on_abstain_case" : "insufficient_on_cause_case";
                default -> r.state().name().toLowerCase();
            };
            summary.merge(verdict, 1, Integer::sum);
            perCase.put(c.id(), Map.of("state", r.state(), "rule", String.valueOf(r.rule()), "root_cause", String.valueOf(r.rootCause()),
                    "matched", r.matched(), "verdict", verdict));
        }
        Path report = Path.of("build/diagnosis-eval/" + file);
        Files.createDirectories(report.getParent());
        DiagnosisEval.JSON.writerWithDefaultPrettyPrinter().writeValue(report.toFile(),
                Map.of("rule_version", SignatureRuleBaseline.RULE_VERSION, "summary", summary, "cases", perCase));
        System.out.println(file + ": " + summary);
        return summary;
    }
}
