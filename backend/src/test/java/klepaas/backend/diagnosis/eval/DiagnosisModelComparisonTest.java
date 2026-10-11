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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #99 사례로 공급사·모델별 진단 품질·사용량·비용·지연을 비교한다 (#100).
 * DIAGNOSIS_EVAL_MODELS=provider:model,... 와 해당 공급사 키가 있을 때만 실행한다. CI에서는 건너뛴다.
 * 정확도로 실패시키지 않는다. 결과는 build/diagnosis-eval/compare-<시각>.json·.md에 남기고 스펙에 기록한다.
 */
@EnabledIfEnvironmentVariable(named = "DIAGNOSIS_EVAL_MODELS", matches = ".+")
class DiagnosisModelComparisonTest {

    @Test
    void compareModelsOnCases() throws Exception {
        JsonNode pricing = DiagnosisEval.JSON.readTree(Path.of("src/test/resources/diagnosis-eval/pricing.json").toFile()).path("models");
        List<Case> cases = DiagnosisEval.loadCases();
        Map<String, Object> report = new LinkedHashMap<>();
        StringBuilder table = new StringBuilder("| 모델 | 범주 | 구체 원인 | 근거 유효 | 근거 일치 | 판단 보류 | 지시문 | 입력 토큰 | 출력 토큰 (생각) | 비용 USD | 지연 합계·중앙값 ms | 응답 오류 |\n|---|---|---|---|---|---|---|---|---|---|---|---|\n");

        for (String spec : System.getenv("DIAGNOSIS_EVAL_MODELS").split(",")) {
            LlmClient llm = client(spec.trim());
            Map<String, Object> perCase = new LinkedHashMap<>();
            Map<String, Map<String, Boolean>> scores = new LinkedHashMap<>();
            long input = 0, cached = 0, output = 0, reasoning = 0;
            List<Long> latencies = new ArrayList<>();
            int errors = 0;
            for (Case c : cases) {
                String in = DiagnosisPrompt.redact(c.input().toString());
                Map<String, Object> entry = new LinkedHashMap<>();
                Result result;
                try {
                    LlmClient.Response r = llm.completeJson(DiagnosisPrompt.build(in));
                    input += r.usage().inputTokens();
                    output += r.usage().outputTokens();
                    cached += r.usage().cachedInputTokens() == null ? 0 : r.usage().cachedInputTokens();
                    reasoning += r.usage().reasoningTokens() == null ? 0 : r.usage().reasoningTokens();
                    latencies.add(r.latencyMs());
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
                    errors++;
                    entry.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
                    result = new Result(null, null, true, List.of(), "");
                }
                Map<String, Boolean> score = DiagnosisEval.score(c, result);
                entry.put("score", score);
                scores.put(c.id(), score);
                perCase.put(c.id(), entry);
            }

            Map<String, String> totals = totals(scores);
            JsonNode price = pricing.path(llm.model());
            Double cost = price.isMissingNode() ? null
                    : ((input - cached) * price.path("input").asDouble() + cached * price.path("cached_input").asDouble()
                    + output * price.path("output").asDouble()) / 1_000_000;
            latencies.sort(Long::compare);
            long latencySum = latencies.stream().mapToLong(Long::longValue).sum();
            long median = latencies.isEmpty() ? 0 : latencies.get(latencies.size() / 2);
            report.put(spec, Map.of("totals", totals, "input_tokens", input, "cached_input_tokens", cached,
                    "output_tokens", output, "reasoning_tokens", reasoning, "cost_usd", cost == null ? "가격표 없음" : cost,
                    "latency_ms_sum", latencySum, "latency_ms_median", median, "errors", errors, "cases", perCase));
            table.append("| ").append(spec);
            DiagnosisEval.ITEMS.forEach(item -> table.append(" | ").append(totals.get(item)));
            table.append(" | ").append(input).append(" | ").append(output).append(" (").append(reasoning).append(")")
                    .append(" | ").append(cost == null ? "-" : String.format("%.4f", cost))
                    .append(" | ").append(latencySum).append(" · ").append(median)
                    .append(" | ").append(errors).append(" |\n");
        }

        String stamp = Instant.now().toString().replace(":", "-");
        Path dir = Path.of("build/diagnosis-eval");
        Files.createDirectories(dir);
        DiagnosisEval.JSON.writerWithDefaultPrettyPrinter().writeValue(dir.resolve("compare-" + stamp + ".json").toFile(),
                Map.of("prompt_version", DiagnosisPrompt.VERSION, "cases", cases.size(), "models", report));
        Files.writeString(dir.resolve("compare-" + stamp + ".md"),
                "프롬프트 " + DiagnosisPrompt.VERSION + ", 사례 " + cases.size() + "개, " + stamp + "\n\n" + table);
        System.out.println(table);
        assertThat(report).isNotEmpty();
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

    private static LlmClient client(String spec) {
        String provider = spec.substring(0, spec.indexOf(':'));
        String model = spec.substring(spec.indexOf(':') + 1);
        return switch (provider) {
            case "gemini" -> new GeminiLlmClient(new GeminiClient(
                    RestClient.builder().baseUrl("https://generativelanguage.googleapis.com").build(),
                    require("GEMINI_API_KEY"), model), model);
            case "anthropic" -> new AnthropicLlmClient(require("ANTHROPIC_API_KEY"), model);
            case "openai" -> new OpenAiLlmClient(require("OPENAI_API_KEY"), model);
            default -> throw new IllegalArgumentException("provider는 gemini·anthropic·openai: " + spec);
        };
    }

    private static String require(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + "가 없습니다");
        return value;
    }
}
