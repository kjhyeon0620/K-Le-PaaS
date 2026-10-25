package klepaas.backend.ai.eval;

import com.fasterxml.jackson.databind.JsonNode;
import klepaas.backend.ai.eval.NlpEval.Case;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * 자연어 명령 평가 회귀 게이트 (#180, 스펙 0180). 이미 측정한 기준 실행의 모델 원문(baselines/*.json)을 모델 호출 없이 지금 코드로 다시 채점해
 * 기대 요약(baselines/expected.json)과 같은지 본다. 파서·위험도·변경 intent 목록·채점기·정답이 바뀌어 결과가 달라지면 실패한다.
 * 의도한 변경이면 NLP_BASELINE_WRITE=1로 기대 요약을 다시 쓰고 nlp-eval/CHANGELOG.md에 이유를 적는다.
 */
class NlpRegressionTest {

    static final Path BASELINES = Path.of("src/test/resources/nlp-eval/baselines");
    static final Path EXPECTED = BASELINES.resolve("expected.json");
    static final Path REPORT = Path.of("build/nlp-eval/regression.md");

    /**
     * 사례 하나의 호출별 판정: E 호출 실패, X 파싱 실패, I intent 오답, A 인자 오답, P 통과.
     * 뒤에 붙는 표시: + 과대, - 과소, F 변경 intent 날조, f 그 밖의 날조, U 확인 없이 변경
     */
    record Result(Map<String, Object> summary, Map<String, String> cases) {
    }

    @Test
    void storedBaselinesRescoreToExpectedSummary() throws Exception {
        String promptHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(NlpEval.systemPrompt().getBytes(StandardCharsets.UTF_8)));
        Map<String, Case> cases = NlpEval.loadCases().stream().collect(Collectors.toMap(Case::id, Function.identity()));
        Map<String, Result> actual = new TreeMap<>();
        for (Path file : baselineFiles()) {
            JsonNode b = NlpEval.JSON.readTree(file.toFile());
            String id = b.path("baseline_id").asText();
            assertThat(b.path("system_prompt_sha256").asText())
                    .as(id + ": 시스템 프롬프트가 바뀌어 저장된 응답으로는 비교할 수 없다. 새 기준 실행이 필요하다")
                    .isEqualTo(promptHash);
            actual.put(id, rescore(b, cases));
        }
        writeReport(actual);
        if ("1".equals(System.getenv("NLP_BASELINE_WRITE"))) {
            NlpEval.JSON.writerWithDefaultPrettyPrinter().writeValue(EXPECTED.toFile(), actual);
            return;
        }
        Map<String, Result> expected = new TreeMap<>();
        NlpEval.JSON.readTree(EXPECTED.toFile()).properties()
                .forEach(e -> expected.put(e.getKey(), NlpEval.JSON.convertValue(e.getValue(), Result.class)));
        String diff = diff(expected, actual);
        if (!diff.isEmpty()) {
            Files.writeString(REPORT, "\n#### 기대 요약과 다른 항목\n\n" + diff, StandardOpenOption.APPEND);
            fail("자연어 명령 평가 회귀: 저장된 응답의 재채점 결과가 기대 요약과 다르다. 의도한 변경이면 NLP_BASELINE_WRITE=1로 갱신하고 "
                    + "nlp-eval/CHANGELOG.md에 이유를 적는다.\n" + diff);
        }
    }

    static Result rescore(JsonNode baseline, Map<String, Case> cases) {
        List<Map<String, Boolean>> scores = new ArrayList<>();
        List<NlpEval.CaseRuns> caseRuns = new ArrayList<>();
        Map<String, Integer> raw = new LinkedHashMap<>();
        NlpEval.RAW_ITEMS.forEach(item -> raw.put(item, 0));
        Map<String, String> verdicts = new TreeMap<>();
        int calls = 0, errors = 0;
        for (var entry : (Iterable<Map.Entry<String, JsonNode>>) baseline.path("runs").properties()::iterator) {
            Case c = cases.get(entry.getKey());
            if (c == null) fail(baseline.path("baseline_id").asText() + ": 기준 사례가 사례 파일에 없다: " + entry.getKey());
            List<Map<String, Boolean>> perCase = new ArrayList<>();
            List<String> codes = new ArrayList<>();
            for (JsonNode run : entry.getValue()) {
                calls++;
                if (run.isNull()) {
                    errors++;
                    perCase.add(null);
                    codes.add("E");
                    continue;
                }
                NlpEval.rawContract(run.asText()).forEach((k, v) -> raw.merge(k, v ? 1 : 0, Integer::sum));
                Map<String, Boolean> s = NlpEval.score(c, run.asText()).score();
                scores.add(s);
                perCase.add(s);
                codes.add(code(s));
            }
            caseRuns.add(new NlpEval.CaseRuns(c.group(), perCase));
            verdicts.put(entry.getKey(), String.join(" ", codes));
        }
        Map<String, String> totals = new LinkedHashMap<>();
        for (String item : NlpEval.ITEMS) {
            long applicable = scores.stream().filter(s -> s.get(item) != null).count();
            long hit = scores.stream().filter(s -> Boolean.TRUE.equals(s.get(item))).count();
            totals.put(item, hit + "/" + applicable);
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("calls", calls);
        summary.put("errors", errors);
        summary.put("totals", totals);
        summary.put("raw_contract", raw);
        summary.put("consistency", NlpEval.JSON.convertValue(NlpEval.consistency(caseRuns), Map.class));
        return new Result(summary, verdicts);
    }

    static String code(Map<String, Boolean> s) {
        String base = !Boolean.TRUE.equals(s.get("parse_ok")) ? "X" : !Boolean.TRUE.equals(s.get("intent")) ? "I"
                : Boolean.FALSE.equals(s.get("args")) ? "A" : "P";
        StringBuilder out = new StringBuilder(base);
        if (Boolean.TRUE.equals(s.get("over"))) out.append('+');
        if (Boolean.TRUE.equals(s.get("under"))) out.append('-');
        if (Boolean.TRUE.equals(s.get("fabricated_change"))) out.append('F');
        else if (Boolean.TRUE.equals(s.get("fabricated"))) out.append('f');
        if (Boolean.TRUE.equals(s.get("unconfirmed_change"))) out.append('U');
        return out.toString();
    }

    static String diff(Map<String, Result> expected, Map<String, Result> actual) {
        StringBuilder out = new StringBuilder();
        for (String id : new TreeSet<>(Stream.concat(expected.keySet().stream(), actual.keySet().stream()).toList())) {
            Result e = expected.get(id), a = actual.get(id);
            if (e == null || a == null) {
                out.append("| ").append(id).append(" | 기준 ").append(e == null ? "추가됨" : "없어짐").append(" |\n");
                continue;
            }
            flatten("", NlpEval.JSON.valueToTree(e.summary()), new TreeMap<>()).forEach((k, v) -> {
                String now = flatten("", NlpEval.JSON.valueToTree(a.summary()), new TreeMap<>()).get(k);
                if (!v.equals(now)) out.append("| ").append(id).append(" | ").append(k).append(" | ").append(v).append(" → ").append(now).append(" |\n");
            });
            e.cases().forEach((k, v) -> {
                if (!v.equals(a.cases().get(k))) out.append("| ").append(id).append(" | 사례 ").append(k).append(" | ").append(v)
                        .append(" → ").append(a.cases().get(k)).append(" |\n");
            });
        }
        return out.isEmpty() ? "" : "| 기준 | 항목 | 기대 → 지금 |\n|---|---|---|\n" + out;
    }

    private static Map<String, String> flatten(String prefix, JsonNode node, Map<String, String> out) {
        if (node.isObject()) node.properties().forEach(f -> flatten(prefix.isEmpty() ? f.getKey() : prefix + "." + f.getKey(), f.getValue(), out));
        else out.put(prefix, node.asText());
        return out;
    }

    /** CI 작업 요약에 붙일 표 (#180) */
    @SuppressWarnings("unchecked")
    static void writeReport(Map<String, Result> actual) throws Exception {
        StringBuilder md = new StringBuilder("### 자연어 명령 평가 회귀 (저장 응답 재채점, 모델 호출 없음)\n\n")
                .append("| 기준 | 호출 | 파싱 | intent | 인자 | 날조 | 변경 intent 날조 | 과소 | 과대 | 확인 없이 변경 | pass^1 · pass^n |\n")
                .append("|---|---|---|---|---|---|---|---|---|---|---|\n");
        actual.forEach((id, r) -> {
            var t = (Map<String, String>) r.summary().get("totals");
            var c = (Map<String, Object>) r.summary().get("consistency");
            md.append("| ").append(id).append(" | ").append(r.summary().get("calls"));
            NlpEval.ITEMS.forEach(item -> md.append(" | ").append(t.get(item)));
            md.append(" | ").append(fmt(c.get("pass_hat1"))).append(" · ").append(fmt(c.get("pass_hat_n"))).append(" |\n");
        });
        Files.createDirectories(REPORT.getParent());
        Files.writeString(REPORT, md);
        System.out.println(md);
    }

    private static String fmt(Object v) {
        return v instanceof Number n ? String.format("%.3f", n.doubleValue()) : String.valueOf(v);
    }

    static List<Path> baselineFiles() throws Exception {
        try (Stream<Path> f = Files.list(BASELINES)) {
            return f.filter(p -> p.toString().endsWith(".json") && !p.equals(EXPECTED)).sorted().toList();
        }
    }
}
