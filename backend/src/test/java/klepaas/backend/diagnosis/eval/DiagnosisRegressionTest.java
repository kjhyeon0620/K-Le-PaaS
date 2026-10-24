package klepaas.backend.diagnosis.eval;

import com.fasterxml.jackson.databind.JsonNode;
import klepaas.backend.ai.diagnosis.DiagnosisPrompt;
import klepaas.backend.ai.diagnosis.SignatureRules;
import klepaas.backend.diagnosis.eval.DiagnosisEval.Case;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * 평가 회귀 게이트 (#172, 스펙 0172). 이미 측정한 기준 실행의 모델 원문(baselines/*.json)을 모델 호출 없이 지금 코드로 다시 채점해
 * 기대 요약(baselines/expected.json)과 같은지 본다. 파서·라벨·채점기·정답·근거 묶음·규칙이 바뀌어 결과가 달라지면 실패한다.
 * 의도한 변경이면 DIAGNOSIS_BASELINE_WRITE=1로 기대 요약을 다시 쓰고 데이터셋 CHANGELOG에 이유를 적는다.
 */
class DiagnosisRegressionTest {

    static final Path BASELINES = Path.of("src/test/resources/diagnosis-eval/baselines");
    static final Path EXPECTED = BASELINES.resolve("expected.json");
    static final Path REPORT = Path.of("build/diagnosis-eval/regression.md");

    /** 사례 하나의 호출별 원시 판정: E 호출 실패, J JSON 아님, A 보류, C 맞는 단정, W 틀린 단정. 무효 근거가 있으면 뒤에 ! */
    record Result(Map<String, Integer> summary, Map<String, String> cases) {
    }

    @Test
    void storedBaselinesRescoreToExpectedSummary() throws Exception {
        Map<String, Result> actual = new TreeMap<>();
        for (Path file : baselineFiles()) {
            JsonNode b = DiagnosisEval.JSON.readTree(file.toFile());
            String id = b.path("baseline_id").asText();
            assertThat(b.path("prompt_version").asText())
                    .as(id + ": 프롬프트가 바뀌어 저장된 응답으로는 비교할 수 없다. 새 기준 실행이 필요하다")
                    .isEqualTo(DiagnosisPrompt.VERSION);
            actual.put(id, rescore(b));
        }
        writeReport(actual);
        if ("1".equals(System.getenv("DIAGNOSIS_BASELINE_WRITE"))) {
            DiagnosisEval.JSON.writerWithDefaultPrettyPrinter().writeValue(EXPECTED.toFile(), actual);
            return;
        }
        Map<String, Result> expected = new TreeMap<>();
        DiagnosisEval.JSON.readTree(EXPECTED.toFile()).properties()
                .forEach(e -> expected.put(e.getKey(), DiagnosisEval.JSON.convertValue(e.getValue(), Result.class)));
        String diff = diff(expected, actual);
        if (!diff.isEmpty()) {
            Files.writeString(REPORT, "\n#### 기대 요약과 다른 항목\n\n" + diff, java.nio.file.StandardOpenOption.APPEND);
            fail("평가 회귀: 저장된 응답의 재채점 결과가 기대 요약과 다르다. 의도한 변경이면 DIAGNOSIS_BASELINE_WRITE=1로 갱신하고 "
                    + "데이터셋 CHANGELOG에 이유를 적는다.\n" + diff);
        }
    }

    static Result rescore(JsonNode baseline) throws Exception {
        Map<String, Case> cases = DiagnosisEval.loadCaseSet(baseline.path("case_set").asText()).stream()
                .collect(Collectors.toMap(Case::id, Function.identity()));
        Map<String, Integer> s = new LinkedHashMap<>();
        for (String k : List.of("calls", "errors", "json_fail", "out_of_list", "relation_mismatch", "invalid_pointer_calls", "invalid_pointers",
                "abstain_with_label", "raw_asserted", "raw_confident_wrong", "raw_root_correct", "raw_evidence_judged", "raw_evidence_sufficient",
                "post_unreadable", "post_root_correct", "post_confident_wrong", "post_abstain_correct",
                "rule_only_root_correct", "rule_only_confident_wrong", "llm_only_root_correct", "llm_only_confident_wrong",
                "rule_first_root_correct", "rule_first_confident_wrong", "rule_first_llm_calls")) {
            s.put(k, 0);
        }
        Map<String, String> verdicts = new TreeMap<>();
        for (var entry : (Iterable<Map.Entry<String, JsonNode>>) baseline.path("runs").properties()::iterator) {
            Case c = cases.get(entry.getKey());
            if (c == null) fail(baseline.path("baseline_id").asText() + ": 기준 사례가 사례 묶음에 없다: " + entry.getKey());
            var e = c.expected();
            var rule = SignatureRules.diagnose(c.input());
            boolean ruleMatch = rule.state() == SignatureRules.State.MATCH;
            String input = DiagnosisPrompt.redact(c.input().toString());
            List<String> codes = new ArrayList<>();
            for (JsonNode run : entry.getValue()) {
                s.merge("calls", 1, Integer::sum);
                if (run.isNull()) {
                    s.merge("errors", 1, Integer::sum);
                    codes.add("E");
                    continue;
                }
                String raw = run.asText();
                RawDiagnosis.Verdict v = RawDiagnosis.judge(c, raw, 0, true);
                if (!v.json()) s.merge("json_fail", 1, Integer::sum);
                if (!v.labelsInList()) s.merge("out_of_list", 1, Integer::sum);
                if (!v.relationOk()) s.merge("relation_mismatch", 1, Integer::sum);
                if (v.invalidPointers() > 0) s.merge("invalid_pointer_calls", 1, Integer::sum);
                s.merge("invalid_pointers", v.invalidPointers(), Integer::sum);
                if (v.abstainWithLabel()) s.merge("abstain_with_label", 1, Integer::sum);
                if (v.asserted()) s.merge("raw_asserted", 1, Integer::sum);
                if (v.confidentWrong()) s.merge("raw_confident_wrong", 1, Integer::sum);
                if (v.asserted() && !v.confidentWrong()) s.merge("raw_root_correct", 1, Integer::sum);
                if (v.sufficient() != null) {
                    s.merge("raw_evidence_judged", 1, Integer::sum);
                    if (v.sufficient()) s.merge("raw_evidence_sufficient", 1, Integer::sum);
                }
                codes.add((!v.json() ? "J" : !v.asserted() ? "A" : v.confidentWrong() ? "W" : "C") + (v.invalidPointers() > 0 ? "!" : ""));

                // 서버 보정 후(지금 파서)와 정책
                String postRoot = null;
                boolean postAbstain = true;
                try {
                    var d = DiagnosisPrompt.parse(input, raw);
                    postAbstain = d.abstain();
                    postRoot = d.rootCause();
                } catch (IllegalArgumentException ex) {
                    s.merge("post_unreadable", 1, Integer::sum);
                }
                tally(s, "post", e, postAbstain, postRoot);
                if (e.abstain() && postAbstain) s.merge("post_abstain_correct", 1, Integer::sum);
                tally(s, "rule_only", e, !ruleMatch, ruleMatch ? rule.rootCause() : null);
                tally(s, "llm_only", e, postAbstain, postRoot);
                tally(s, "rule_first", e, !ruleMatch && postAbstain, ruleMatch ? rule.rootCause() : postRoot);
                if (!ruleMatch) s.merge("rule_first_llm_calls", 1, Integer::sum);
            }
            verdicts.put(entry.getKey(), String.join(" ", codes));
        }
        return new Result(s, verdicts);
    }

    /** 단정했으면 정답·확신 오답을 센다. 보류가 정답인 사례에서 단정하면 확신 오답이다 */
    private static void tally(Map<String, Integer> s, String prefix, DiagnosisEval.Expected e, boolean abstain, String rootCause) {
        if (abstain || rootCause == null) return;
        if (!e.abstain() && e.accepts(rootCause)) s.merge(prefix + "_root_correct", 1, Integer::sum);
        else s.merge(prefix + "_confident_wrong", 1, Integer::sum);
    }

    static String diff(Map<String, Result> expected, Map<String, Result> actual) {
        StringBuilder out = new StringBuilder();
        for (String id : new java.util.TreeSet<>(java.util.stream.Stream.concat(expected.keySet().stream(), actual.keySet().stream()).toList())) {
            Result e = expected.get(id), a = actual.get(id);
            if (e == null || a == null) {
                out.append("| ").append(id).append(" | 기준 ").append(e == null ? "추가됨" : "없어짐").append(" |\n");
                continue;
            }
            e.summary().forEach((k, v) -> {
                if (!v.equals(a.summary().get(k))) out.append("| ").append(id).append(" | ").append(k).append(" | ").append(v)
                        .append(" → ").append(a.summary().get(k)).append(" |\n");
            });
            e.cases().forEach((k, v) -> {
                if (!v.equals(a.cases().get(k))) out.append("| ").append(id).append(" | 사례 ").append(k).append(" | ").append(v)
                        .append(" → ").append(a.cases().get(k)).append(" |\n");
            });
        }
        return out.isEmpty() ? "" : "| 기준 | 항목 | 기대 → 지금 |\n|---|---|---|\n" + out;
    }

    /** CI 작업 요약에 붙일 표 (#172). 기준별 핵심 지표 */
    static void writeReport(Map<String, Result> actual) throws Exception {
        StringBuilder md = new StringBuilder("### 진단 평가 회귀 (저장 응답 재채점, 모델 호출 없음)\n\n")
                .append("| 기준 | 호출 | 무효 근거 호출 | 원시 확신 오답 | 원시 구체 원인 정답 | 원시 근거 충분 | 규칙 단독 확신 오답 | LLM 단독 확신 오답 | 규칙 우선 확신 오답 | 규칙 우선 LLM 호출 |\n")
                .append("|---|---|---|---|---|---|---|---|---|---|\n");
        actual.forEach((id, r) -> {
            var s = r.summary();
            md.append("| ").append(id).append(" | ").append(s.get("calls")).append(" | ").append(s.get("invalid_pointer_calls"))
                    .append(" | ").append(s.get("raw_confident_wrong")).append(" | ").append(s.get("raw_root_correct"))
                    .append(" | ").append(s.get("raw_evidence_sufficient")).append("/").append(s.get("raw_evidence_judged"))
                    .append(" | ").append(s.get("rule_only_confident_wrong")).append(" | ").append(s.get("llm_only_confident_wrong"))
                    .append(" | ").append(s.get("rule_first_confident_wrong")).append(" | ").append(s.get("rule_first_llm_calls")).append(" |\n");
        });
        Files.createDirectories(REPORT.getParent());
        Files.writeString(REPORT, md);
        System.out.println(md);
    }

    static List<Path> baselineFiles() throws Exception {
        try (Stream<Path> f = Files.list(BASELINES)) {
            return f.filter(p -> p.toString().endsWith(".json") && !p.equals(EXPECTED)).sorted().toList();
        }
    }
}
