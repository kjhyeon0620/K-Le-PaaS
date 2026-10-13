package klepaas.backend.ai.eval;

import com.fasterxml.jackson.databind.JsonNode;
import klepaas.backend.ai.client.GeminiClient;
import klepaas.backend.ai.client.dto.GeminiRequest;
import klepaas.backend.ai.client.dto.GeminiResponse;
import klepaas.backend.ai.eval.NlpEval.Case;
import klepaas.backend.ai.llm.LlmClient;
import klepaas.backend.ai.llm.OpenAiLlmClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #58 자연어 명령 해석을 실제 모델로 평가한다. NLP_EVAL_MODELS=provider:model,...(gemini·openai)와 해당 키가 있을 때만 실행한다.
 * 두 모델에 운영 GeminiRequest.of와 같은 문자열(지시문 + "사용자 입력: " + 입력)을 보낸다.
 * gemini는 운영 경로(GeminiClient, temperature 0.1, 출력 1,024), openai는 진단용 OpenAiLlmClient(JSON 모드, 출력 2,048)를 쓴다.
 * 정확도로는 실패시키지 않고, 상태를 바꾸는 intent가 승인 없이 실행될 해석이 나오면 실패한다.
 * 결과는 build/nlp-eval/result-<시각>.json·.md.
 * NLP_EVAL_REPLAY=<이전 결과 json>이면 모델을 호출하지 않고 저장된 원문 응답을 같은 사례·반복 번호로 다시 채점한다(채점 규칙 수정 뒤 재채점).
 */
@EnabledIfEnvironmentVariable(named = "NLP_EVAL_MODELS", matches = ".+")
class NlpModelEvalTest {

    private static final int PARALLEL = 4;

    private record Reply(String text, String model, long input, long output, long reasoning, long latencyMs) {
    }

    @FunctionalInterface
    private interface Caller {
        Reply call(Case c, int rep, String systemPrompt);
    }

    @Test
    void evaluateModelsOnCases() throws Exception {
        List<Case> cases = NlpEval.loadCases();
        String systemPrompt = NlpEval.systemPrompt();
        String promptHash = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(systemPrompt.getBytes(StandardCharsets.UTF_8)));
        int repeat = Integer.parseInt(System.getenv().getOrDefault("NLP_EVAL_REPEAT", "5"));
        String replayPath = System.getenv("NLP_EVAL_REPLAY");
        JsonNode replay = replayPath == null || replayPath.isBlank() ? null : NlpEval.JSON.readTree(Path.of(replayPath).toFile());
        JsonNode pricing = NlpEval.JSON.readTree(Path.of("src/test/resources/diagnosis-eval/pricing.json").toFile()).path("models");

        Map<String, Object> report = new LinkedHashMap<>();
        StringBuilder table = new StringBuilder("| 모델 | 호출 | 오류 | 파싱 | intent | 인자 | 날조 | 변경 intent 날조 | 과소 | 과대 "
                + "| 확인 없이 변경 | 사례 다수결 intent | 입력 | 출력 (추론) | 금액 USD | 지연 중앙·p90 ms |\n|" + "---|".repeat(16) + "\n");
        long unconfirmed = 0;

        for (String spec : System.getenv("NLP_EVAL_MODELS").split(",")) {
            String[] pm = spec.trim().split(":", 2);
            String provider = pm[0], model = pm[1];
            Caller caller = replay != null ? replayer(replay.path("models").path(spec.trim())) : caller(provider, model);

            List<Callable<Map<String, Object>>> calls = new ArrayList<>();
            for (Case c : cases) {
                for (int rep = 0; rep < repeat; rep++) {
                    int r = rep;
                    calls.add(() -> run(caller, c, r, systemPrompt));
                }
            }
            ExecutorService pool = Executors.newFixedThreadPool(PARALLEL);
            List<Map<String, Object>> runs = new ArrayList<>();
            try {
                for (Future<Map<String, Object>> f : pool.invokeAll(calls)) runs.add(f.get());
            } finally {
                pool.shutdown();
            }

            long input = 0, output = 0, reasoning = 0;
            int errors = 0;
            String reportedModel = null;
            List<Long> latencies = new ArrayList<>();
            List<Map<String, Boolean>> scores = new ArrayList<>();
            Map<String, int[]> perCase = new LinkedHashMap<>(); // [정답 수, 오류 아닌 호출 수]
            for (Map<String, Object> run : runs) {
                perCase.computeIfAbsent((String) run.get("case"), k -> new int[2]);
                if (run.containsKey("error")) {
                    errors++;
                    continue;
                }
                Reply reply = (Reply) run.remove("reply");
                reportedModel = reply.model();
                input += reply.input();
                output += reply.output();
                reasoning += reply.reasoning();
                latencies.add(reply.latencyMs());
                @SuppressWarnings("unchecked") Map<String, Boolean> score = (Map<String, Boolean>) run.get("score");
                scores.add(score);
                int[] counts = perCase.get((String) run.get("case"));
                counts[1]++;
                if (Boolean.TRUE.equals(score.get("intent"))) counts[0]++;
            }
            Map<String, String> totals = new LinkedHashMap<>();
            for (String item : NlpEval.ITEMS) {
                long applicable = scores.stream().filter(s -> s.get(item) != null).count();
                long hit = scores.stream().filter(s -> Boolean.TRUE.equals(s.get(item))).count();
                totals.put(item, hit + "/" + applicable);
            }
            unconfirmed += scores.stream().filter(s -> Boolean.TRUE.equals(s.get("unconfirmed_change"))).count();
            long majority = perCase.values().stream().filter(n -> n[1] > 0 && n[0] * 2 > n[1]).count();
            JsonNode price = pricing.path(model);
            Double cost = price.isMissingNode() ? null
                    : (input * price.path("input").asDouble() + output * price.path("output").asDouble()) / 1_000_000;
            latencies.sort(Long::compare);
            long median = percentile(latencies, 50), p90 = percentile(latencies, 90);

            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("provider", provider);
            summary.put("reported_model", reportedModel);
            summary.put("calls", runs.size());
            summary.put("errors", errors);
            summary.put("totals", totals);
            summary.put("case_majority_intent", majority + "/" + cases.size());
            summary.put("input_tokens", input);
            summary.put("output_tokens", output);
            summary.put("reasoning_tokens", reasoning);
            summary.put("cost_usd", cost == null ? "가격표 없음" : cost);
            summary.put("latency_ms_median", median);
            summary.put("latency_ms_p90", p90);
            summary.put("runs", runs);
            report.put(spec.trim(), summary);

            table.append("| ").append(spec.trim()).append(" | ").append(runs.size()).append(" | ").append(errors);
            for (String item : List.of("parse_ok", "intent", "args", "fabricated", "fabricated_change", "under", "over", "unconfirmed_change")) {
                table.append(" | ").append(totals.get(item));
            }
            table.append(" | ").append(majority).append("/").append(cases.size())
                    .append(" | ").append(input).append(" | ").append(output).append(" (").append(reasoning).append(")")
                    .append(" | ").append(cost == null ? "-" : String.format("%.4f", cost))
                    .append(" | ").append(median).append(" · ").append(p90).append(" |\n");
        }

        String stamp = Instant.now().toString().replace(":", "-");
        Path dir = Path.of("build/nlp-eval");
        Files.createDirectories(dir);
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("measured_at", Instant.now().toString());
        header.put("cases", cases.size());
        header.put("repeat", repeat);
        header.put("system_prompt_sha256", promptHash);
        if (replay != null) header.put("replayed_from", replayPath);
        header.put("call_paths", Map.of(
                "gemini", "운영 GeminiClient + GeminiRequest.of (temperature 0.1, maxOutputTokens 1024)",
                "openai", "OpenAiLlmClient.completeJson (response_format json_object, max_completion_tokens 2048), 같은 프롬프트 문자열"));
        header.put("models", report);
        NlpEval.JSON.writerWithDefaultPrettyPrinter().writeValue(dir.resolve("result-" + stamp + ".json").toFile(), header);
        Files.writeString(dir.resolve("result-" + stamp + ".md"), "사례 " + cases.size() + "개 × " + repeat + "회, 프롬프트 "
                + promptHash.substring(0, 12) + ", " + stamp + "\n\n" + table);
        System.out.println(table);

        assertThat(report).isNotEmpty();
        assertThat(unconfirmed).as("상태를 바꾸는 intent가 승인 없이 실행될 해석").isZero();
    }

    /** 호출 실패는 1회 다시 시도하고, 그래도 실패하면 오류로 기록해 정확도 분모에서 뺀다 */
    private static Map<String, Object> run(Caller caller, Case c, int rep, String systemPrompt) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("case", c.id());
        entry.put("group", c.group());
        entry.put("repeat", rep);
        Reply reply;
        try {
            reply = caller.call(c, rep, systemPrompt);
        } catch (RuntimeException first) {
            try {
                reply = caller.call(c, rep, systemPrompt);
            } catch (RuntimeException e) {
                entry.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
                return entry;
            }
        }
        var scored = NlpEval.score(c, reply.text());
        entry.put("reply", reply);
        entry.put("raw", reply.text());
        entry.put("intent", scored.intent());
        entry.put("args", scored.args());
        entry.put("risk", scored.risk());
        entry.put("fabricated_keys", scored.fabricatedKeys());
        entry.put("score", scored.score());
        entry.put("latency_ms", reply.latencyMs());
        entry.put("reported_model", reply.model());
        entry.put("tokens", Map.of("input", reply.input(), "output", reply.output(), "reasoning", reply.reasoning()));
        return entry;
    }

    private static Caller caller(String provider, String model) {
        return switch (provider) {
            case "gemini" -> {
                GeminiClient client = new GeminiClient(
                        RestClient.builder().baseUrl("https://generativelanguage.googleapis.com").build(), require("GEMINI_API_KEY"), model);
                yield (c, rep, system) -> {
                    long started = System.currentTimeMillis();
                    GeminiResponse r = client.generate(model, GeminiRequest.of(system, c.input()));
                    var u = r.usageMetadata();
                    long thoughts = u == null || u.thoughtsTokenCount() == null ? 0 : u.thoughtsTokenCount();
                    long candidates = u == null || u.candidatesTokenCount() == null ? 0 : u.candidatesTokenCount();
                    return new Reply(r.extractText(), r.modelVersion(),
                            u == null || u.promptTokenCount() == null ? 0 : u.promptTokenCount(),
                            candidates + thoughts, thoughts, System.currentTimeMillis() - started);
                };
            }
            case "openai" -> {
                LlmClient client = new OpenAiLlmClient(require("OPENAI_API_KEY"), model, null);
                yield (c, rep, system) -> {
                    LlmClient.Response r = client.completeJson(NlpEval.prompt(system, c.input()));
                    return new Reply(r.text(), r.model(), r.usage().inputTokens(), r.usage().outputTokens(),
                            r.usage().reasoningTokens() == null ? 0 : r.usage().reasoningTokens(), r.latencyMs());
                };
            }
            default -> throw new IllegalArgumentException("provider는 gemini·openai: " + provider);
        };
    }

    /** 저장된 결과에서 같은 사례·반복 번호의 원문을 돌려준다. 원문이 없던 호출(오류)은 다시 오류로 남긴다 */
    private static Caller replayer(JsonNode saved) {
        if (saved.isMissingNode()) throw new IllegalArgumentException("재채점할 결과에 이 모델이 없습니다");
        Map<String, JsonNode> byKey = new LinkedHashMap<>();
        saved.path("runs").forEach(run -> byKey.put(run.path("case").asText() + "#" + run.path("repeat").asInt(), run));
        return (c, rep, system) -> {
            JsonNode run = byKey.get(c.id() + "#" + rep);
            if (run == null || !run.hasNonNull("raw")) throw new IllegalStateException("저장된 응답 없음: " + c.id() + "#" + rep);
            JsonNode t = run.path("tokens");
            return new Reply(run.path("raw").asText(), run.path("reported_model").asText(saved.path("reported_model").asText(null)),
                    t.path("input").asLong(), t.path("output").asLong(), t.path("reasoning").asLong(), run.path("latency_ms").asLong());
        };
    }

    private static long percentile(List<Long> sorted, int p) {
        return sorted.isEmpty() ? 0 : sorted.get(Math.min(sorted.size() - 1, sorted.size() * p / 100));
    }

    private static String require(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + "가 없습니다");
        return value;
    }
}
