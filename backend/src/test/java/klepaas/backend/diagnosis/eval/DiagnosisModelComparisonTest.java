package klepaas.backend.diagnosis.eval;

import com.fasterxml.jackson.databind.JsonNode;
import klepaas.backend.ai.client.GeminiClient;
import klepaas.backend.ai.diagnosis.DiagnosisPrompt;
import klepaas.backend.ai.llm.AnthropicLlmClient;
import klepaas.backend.ai.llm.GeminiLlmClient;
import klepaas.backend.ai.llm.LlmClient;
import klepaas.backend.ai.llm.OpenAiLlmClient;
import klepaas.backend.diagnosis.eval.DiagnosisEval.Case;
import klepaas.backend.diagnosis.eval.DiagnosisEval.Result;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.client.RestClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #99 사례로 공급사·모델·절감 변형별 진단 품질·사용량·비용·지연을 비교한다 (#100, #115).
 * DIAGNOSIS_EVAL_MODELS=provider:model[@옵션+옵션],... 와 해당 공급사 키가 있을 때만 실행한다. CI에서는 건너뛴다.
 * 옵션: effort=<reasoning_effort>(OpenAI), prompt=compact(출력 분량 제한), input=trim(null·중복 로그 제거).
 * DIAGNOSIS_EVAL_REPEAT=N이면 사례마다 N번 호출한다. 호출마다 지시문 뒤·입력 앞에 고유 값을 넣어
 * 이전 호출의 입력이 캐시되지 않게 한다 (운영처럼 공통 지시문만 캐시될 수 있다).
 * 정확도로 실패시키지 않는다. 결과는 build/diagnosis-eval/compare-<시각>.json·.md에 남기고 스펙에 기록한다.
 */
@EnabledIfEnvironmentVariable(named = "DIAGNOSIS_EVAL_MODELS", matches = ".+")
class DiagnosisModelComparisonTest {

    private static final int PARALLEL = 4;

    @Test
    void compareModelsOnCases() throws Exception {
        JsonNode pricing = DiagnosisEval.JSON.readTree(Path.of("src/test/resources/diagnosis-eval/pricing.json").toFile()).path("models");
        List<Case> cases = DiagnosisEval.loadCases();
        int repeat = Integer.parseInt(System.getenv().getOrDefault("DIAGNOSIS_EVAL_REPEAT", "1"));
        Map<String, Object> report = new LinkedHashMap<>();
        StringBuilder table = new StringBuilder("| 변형 | 호출 | 범주 | 구체 원인 | 근거 유효 | 근거 일치 | 판단 보류 | 지시문 | 일반 입력 | 캐시 입력 | 출력 (추론) | 금액 USD | 호출당 USD | 지연 중앙·p90 ms | 오류 |\n|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");

        for (String spec : System.getenv("DIAGNOSIS_EVAL_MODELS").split(",")) {
            Variant v = Variant.parse(spec.trim());
            LlmClient llm = v.client();
            List<Callable<Map<String, Object>>> calls = new ArrayList<>();
            for (Case c : cases) {
                for (int rep = 0; rep < repeat; rep++) calls.add(() -> call(llm, v, c));
            }
            ExecutorService pool = Executors.newFixedThreadPool(PARALLEL);
            List<Map<String, Object>> runs = new ArrayList<>();
            try {
                for (Future<Map<String, Object>> f : pool.invokeAll(calls)) runs.add(f.get());
            } finally {
                pool.shutdown();
            }

            long input = 0, cached = 0, output = 0, reasoning = 0;
            int errors = 0;
            List<Long> latencies = new ArrayList<>();
            Map<String, Map<String, Boolean>> scores = new LinkedHashMap<>();
            Map<String, List<Map<String, Object>>> perCase = new LinkedHashMap<>();
            for (Map<String, Object> run : runs) {
                @SuppressWarnings("unchecked") Map<String, Boolean> score = (Map<String, Boolean>) run.get("score");
                scores.put(run.get("case") + "#" + scores.size(), score);
                perCase.computeIfAbsent((String) run.get("case"), k -> new ArrayList<>()).add(run);
                if (run.containsKey("error")) {
                    errors++;
                    continue;
                }
                LlmClient.Usage u = (LlmClient.Usage) run.get("usage");
                input += u.inputTokens();
                output += u.outputTokens();
                cached += u.cachedInputTokens() == null ? 0 : u.cachedInputTokens();
                reasoning += u.reasoningTokens() == null ? 0 : u.reasoningTokens();
                latencies.add((Long) run.get("latency_ms"));
            }

            Map<String, String> totals = totals(scores);
            JsonNode price = pricing.path(llm.model());
            Double cost = price.isMissingNode() ? null
                    : ((input - cached) * price.path("input").asDouble() + cached * price.path("cached_input").asDouble()
                    + output * price.path("output").asDouble()) / 1_000_000;
            latencies.sort(Long::compare);
            long median = percentile(latencies, 50), p90 = percentile(latencies, 90);
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("calls", runs.size());
            summary.put("totals", totals);
            summary.put("input_tokens", input);
            summary.put("uncached_input_tokens", input - cached);
            summary.put("cached_input_tokens", cached);
            summary.put("output_tokens", output);
            summary.put("reasoning_tokens", reasoning);
            summary.put("cost_usd", cost == null ? "가격표 없음" : cost);
            summary.put("latency_ms_median", median);
            summary.put("latency_ms_p90", p90);
            summary.put("errors", errors);
            summary.put("cases", perCase);
            report.put(spec, summary);
            table.append("| ").append(spec).append(" | ").append(runs.size());
            DiagnosisEval.ITEMS.forEach(item -> table.append(" | ").append(totals.get(item)));
            table.append(" | ").append(input - cached).append(" | ").append(cached)
                    .append(" | ").append(output).append(" (").append(reasoning).append(")")
                    .append(" | ").append(cost == null ? "-" : String.format("%.4f", cost))
                    .append(" | ").append(cost == null ? "-" : String.format("%.6f", cost / runs.size()))
                    .append(" | ").append(median).append(" · ").append(p90)
                    .append(" | ").append(errors).append(" |\n");
        }

        String stamp = Instant.now().toString().replace(":", "-");
        Path dir = Path.of("build/diagnosis-eval");
        Files.createDirectories(dir);
        DiagnosisEval.JSON.writerWithDefaultPrettyPrinter().writeValue(dir.resolve("compare-" + stamp + ".json").toFile(),
                Map.of("cases", cases.size(), "repeat", repeat, "models", report));
        Files.writeString(dir.resolve("compare-" + stamp + ".md"),
                "사례 " + cases.size() + "개 × " + repeat + "회, " + stamp + "\n\n" + table);
        System.out.println(table);
        assertThat(report).isNotEmpty();
    }

    private static Map<String, Object> call(LlmClient llm, Variant v, Case c) {
        String in = DiagnosisPrompt.redact(v.trim() ? DiagnosisPrompt.trim(c.input().toString()) : c.input().toString());
        String prompt = DiagnosisPrompt.build(in, v.compact());
        int at = prompt.lastIndexOf("<input>\n");
        prompt = prompt.substring(0, at) + "평가 실행 " + UUID.randomUUID() + "\n" + prompt.substring(at);
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("case", c.id());
        Result result;
        try {
            LlmClient.Response r = llm.completeJson(prompt);
            entry.put("model", r.model());
            entry.put("usage", r.usage());
            entry.put("latency_ms", r.latencyMs());
            entry.put("raw", r.text());
            var d = DiagnosisPrompt.parse(in, r.text());
            entry.put("diagnosis", d);
            result = new Result(d.category(), d.rootCause(), d.abstain(),
                    d.cited().stream().map(DiagnosisPrompt.Citation::pointer).toList(), d.allText());
        } catch (RuntimeException e) {
            // 호출 실패·읽을 수 없는 응답은 근거 없는 판단 보류로 채점하고 따로 센다
            entry.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            result = new Result(null, null, true, List.of(), "");
        }
        entry.put("score", DiagnosisEval.score(c, result));
        return entry;
    }

    private static long percentile(List<Long> sorted, int p) {
        return sorted.isEmpty() ? 0 : sorted.get(Math.min(sorted.size() - 1, sorted.size() * p / 100));
    }

    /** provider:model@effort=low+prompt=compact+input=trim */
    private record Variant(String provider, String model, String effort, boolean compact, boolean trim) {
        static Variant parse(String spec) {
            String[] parts = spec.split("@", 2);
            String effort = null;
            boolean compact = false, trim = false;
            if (parts.length > 1) {
                for (String opt : parts[1].split("\\+")) {
                    switch (opt) {
                        case "prompt=compact" -> compact = true;
                        case "input=trim" -> trim = true;
                        default -> {
                            if (!opt.startsWith("effort=")) throw new IllegalArgumentException("알 수 없는 옵션: " + opt);
                            effort = opt.substring("effort=".length());
                        }
                    }
                }
            }
            int colon = parts[0].indexOf(':');
            return new Variant(parts[0].substring(0, colon), parts[0].substring(colon + 1), effort, compact, trim);
        }

        LlmClient client() {
            if (effort != null && !provider.equals("openai")) throw new IllegalArgumentException("effort는 openai만: " + provider);
            return switch (provider) {
                case "gemini" -> new GeminiLlmClient(new GeminiClient(
                        RestClient.builder().baseUrl("https://generativelanguage.googleapis.com").build(),
                        require("GEMINI_API_KEY"), model), model);
                case "anthropic" -> new AnthropicLlmClient(require("ANTHROPIC_API_KEY"), model);
                case "openai" -> new OpenAiLlmClient(require("OPENAI_API_KEY"), model, effort);
                default -> throw new IllegalArgumentException("provider는 gemini·anthropic·openai: " + provider);
            };
        }
    }

    private static Map<String, String> totals(Map<String, Map<String, Boolean>> scores) {
        Map<String, String> totals = new LinkedHashMap<>();
        for (String item : DiagnosisEval.ITEMS) {
            long applicable = scores.values().stream().filter(s -> s.get(item) != null).count();
            long passed = scores.values().stream().filter(s -> Boolean.TRUE.equals(s.get(item))).count();
            totals.put(item, passed + "/" + applicable);
        }
        return totals;
    }

    private static String require(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + "가 없습니다");
        return value;
    }
}
