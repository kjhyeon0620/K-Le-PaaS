package klepaas.backend.diagnosis.eval;

import com.fasterxml.jackson.databind.JsonNode;
import klepaas.backend.ai.client.GeminiClient;
import klepaas.backend.ai.diagnosis.DiagnosisPrompt;
import klepaas.backend.ai.knowledge.Embeddings;
import klepaas.backend.ai.knowledge.KnowledgeDtos.KnowledgeItem;
import klepaas.backend.ai.knowledge.KnowledgeText;
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
 * openai-compatible은 OPENAI_COMPATIBLE_BASE_URL의 로컬 모델을 키 없이 부르고, 가격표가 없어 금액은 "-"로 남는다 (#150).
 * 옵션: effort=<reasoning_effort>(OpenAI), prompt=compact(출력 분량 제한), input=trim(null·중복 로그 제거),
 * knowledge=on(플랫폼 runbook과 사례 지식을 실제 임베딩으로 검색해 입력에 넣음, #104).
 * DIAGNOSIS_EVAL_CASES=rag면 RAG 사례(rag-cases)를 쓰고 검색 적중·지식 근거 일치를 함께 채점한다.
 * DIAGNOSIS_EVAL_REPEAT=N이면 사례마다 N번 호출한다. 호출마다 지시문 뒤·입력 앞에 고유 값을 넣어
 * 이전 호출의 입력이 캐시되지 않게 한다 (운영처럼 공통 지시문만 캐시될 수 있다).
 * 정확도로 실패시키지 않는다. 결과는 build/diagnosis-eval/compare-<시각>.json·.md에 남기고 스펙에 기록한다.
 */
@EnabledIfEnvironmentVariable(named = "DIAGNOSIS_EVAL_MODELS", matches = ".+")
class DiagnosisModelComparisonTest {

    /** 로컬 서버는 요청을 하나씩 처리해 병렬 호출의 지연에 대기 시간이 섞인다. 로컬 측정은 1로 둔다 (#150) */
    private static final int PARALLEL = Integer.parseInt(System.getenv().getOrDefault("EVAL_PARALLEL", "4"));

    @Test
    void compareModelsOnCases() throws Exception {
        JsonNode pricing = DiagnosisEval.JSON.readTree(Path.of("src/main/resources/llm/pricing.json").toFile()).path("models");
        String caseSet = System.getenv().getOrDefault("DIAGNOSIS_EVAL_CASES", "base");
        boolean ragCases = !caseSet.equals("base");
        List<Case> cases = DiagnosisEval.loadCaseSet(caseSet);
        List<String> items = new ArrayList<>(DiagnosisEval.ITEMS);
        if (ragCases) items.addAll(List.of("knowledge_hit", "knowledge_supported"));
        int repeat = Integer.parseInt(System.getenv().getOrDefault("DIAGNOSIS_EVAL_REPEAT", "1"));
        Map<String, Object> report = new LinkedHashMap<>();
        StringBuilder table = new StringBuilder("| 변형 | 호출 | 범주 | 구체 원인 | 근거 유효 | 근거 일치 | 판단 보류 | 지시문 |"
                + (ragCases ? " 검색 적중 | 지식 근거 |" : "") + " 일반 입력 | 캐시 입력 | 출력 (추론) | 금액 USD | 호출당 USD | 지연 중앙·p90 ms | 오류 |\n|"
                + "---|".repeat(ragCases ? 17 : 15) + "\n");
        StringBuilder consistencyTable = new StringBuilder(DiagnosisEval.CONSISTENCY_HEADER);
        Map<String, List<KnowledgeItem>> retrieved = new LinkedHashMap<>();

        for (String spec : System.getenv("DIAGNOSIS_EVAL_MODELS").split(",")) {
            Variant v = Variant.parse(spec.trim());
            LlmClient llm = v.client();
            // 검색은 사례마다 한 번 (질의가 반복 호출마다 같다). 호출 전에 순서대로 끝낸다
            if (v.knowledge() && retrieved.isEmpty()) {
                EvalKnowledge knowledge = new EvalKnowledge(cases);
                for (Case c : cases) retrieved.put(c.id(), knowledge.search(c));
            }
            List<Callable<Map<String, Object>>> calls = new ArrayList<>();
            for (Case c : cases) {
                List<KnowledgeItem> found = v.knowledge() ? retrieved.get(c.id()) : List.of();
                for (int rep = 0; rep < repeat; rep++) calls.add(() -> call(llm, v, c, found));
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

            Map<String, String> totals = totals(scores, items);
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
            Map<String, List<DiagnosisEval.Run>> runsById = new LinkedHashMap<>();
            perCase.forEach((id, list) -> runsById.put(id, list.stream().map(DiagnosisModelComparisonTest::toRun).toList()));
            summary.put("consistency", DiagnosisEval.consistency(cases.stream()
                    .map(c -> new DiagnosisEval.CaseRuns(c.expected(), runsById.getOrDefault(c.id(), List.of()))).toList()));
            consistencyTable.append(DiagnosisEval.consistencyRows(spec, cases, runsById, caseSet.equals("hard")));
            if (v.knowledge()) summary.put("retrieved", retrieved);
            summary.put("cases", perCase);
            report.put(spec, summary);
            table.append("| ").append(spec).append(" | ").append(runs.size());
            items.forEach(item -> table.append(" | ").append(totals.get(item)));
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
                Map.of("case_set", caseSet, "cases", cases.size(), "repeat", repeat,
                        "min_similarity", System.getenv().getOrDefault("DIAGNOSIS_EVAL_MIN_SIMILARITY", "0.3"), "models", report));
        Files.writeString(dir.resolve("compare-" + stamp + ".md"),
                "사례 " + cases.size() + "개 × " + repeat + "회, " + stamp + "\n\n" + table + "\n" + consistencyTable);
        System.out.println(table);
        System.out.println(consistencyTable);
        assertThat(report).isNotEmpty();
    }

    private static Map<String, Object> call(LlmClient llm, Variant v, Case c, List<KnowledgeItem> knowledge) {
        String observed = DiagnosisPrompt.redact(v.trim() ? DiagnosisPrompt.trim(c.input().toString()) : c.input().toString());
        String in = knowledge.isEmpty() ? observed : DiagnosisPrompt.withKnowledge(observed, knowledge);
        String prompt = DiagnosisPrompt.build(in, v.compact(), !knowledge.isEmpty());
        int at = prompt.lastIndexOf("<input>\n");
        prompt = prompt.substring(0, at) + "평가 실행 " + UUID.randomUUID() + "\n" + prompt.substring(at);
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("case", c.id());
        Result result;
        List<String> citedKnowledge = List.of();
        try {
            LlmClient.Response r = llm.completeJson(prompt);
            entry.put("model", r.model());
            entry.put("usage", r.usage());
            entry.put("latency_ms", r.latencyMs());
            entry.put("raw", r.text());
            var d = DiagnosisPrompt.parse(in, r.text());
            entry.put("diagnosis", d);
            // 지식 인용은 지식 id로 바꾸고, 관측 근거만 기존 항목으로 채점한다
            List<String> pointers = d.cited().stream().map(DiagnosisPrompt.Citation::pointer).toList();
            citedKnowledge = pointers.stream().filter(p -> p.startsWith("/knowledge/"))
                    .map(p -> knowledge.get(Integer.parseInt(p.split("/")[2])).ref()).distinct().toList();
            entry.put("cited_knowledge", citedKnowledge);
            result = new Result(d.category(), d.rootCause(), d.abstain(),
                    pointers.stream().filter(p -> !p.startsWith("/knowledge/")).toList(), d.allText());
        } catch (RuntimeException e) {
            // 호출 실패·읽을 수 없는 응답은 근거 없는 판단 보류로 채점하고 따로 센다
            entry.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            result = new Result(null, null, true, List.of(), "");
        }
        Map<String, Boolean> score = new LinkedHashMap<>(DiagnosisEval.score(c, result));
        List<String> expectedKnowledge = c.expected().knowledgeIds();
        boolean scoreKnowledge = v.knowledge() && !expectedKnowledge.isEmpty();
        score.put("knowledge_hit", scoreKnowledge ? knowledge.stream().anyMatch(k -> expectedKnowledge.contains(k.ref())) : null);
        score.put("knowledge_supported", scoreKnowledge ? citedKnowledge.stream().anyMatch(expectedKnowledge::contains) : null);
        entry.put("score", score);
        return entry;
    }

    @SuppressWarnings("unchecked")
    private static DiagnosisEval.Run toRun(Map<String, Object> run) {
        return new DiagnosisEval.Run((Map<String, Boolean>) run.get("score"), run.containsKey("error"));
    }

    private static long percentile(List<Long> sorted, int p) {
        return sorted.isEmpty() ? 0 : sorted.get(Math.min(sorted.size() - 1, sorted.size() * p / 100));
    }

    /**
     * 평가용 지식 검색 (#104): 플랫폼 runbook(src/main/resources/knowledge)과 사례의 knowledge를 서버와 같은 분할·질의·유사도로 검색한다.
     * 사례 지식은 그 사례에서만 검색된다(서버의 앱별 필터와 같은 효과). 임베딩은 실제 OpenAI 호출이다.
     */
    private static final class EvalKnowledge {
        private record Entry(String ref, String source, String title, String repository, String version, String text, float[] vector) {
        }

        private final Embeddings embeddings = new Embeddings(require("OPENAI_API_KEY"),
                System.getenv().getOrDefault("DIAGNOSIS_EVAL_EMBEDDING_MODEL", "text-embedding-3-small"));
        private final double minSimilarity = Double.parseDouble(System.getenv().getOrDefault("DIAGNOSIS_EVAL_MIN_SIMILARITY", "0.3"));
        private final List<Entry> platform = new ArrayList<>();
        private final Map<String, List<Entry>> perCase = new LinkedHashMap<>();

        EvalKnowledge(List<Case> cases) throws Exception {
            try (var files = Files.list(Path.of("src/main/resources/knowledge"))) {
                for (Path file : files.filter(f -> f.toString().endsWith(".md")).sorted().toList()) {
                    String content = Files.readString(file);
                    String title = content.lines().filter(l -> l.startsWith("# ")).findFirst().map(l -> l.substring(2).strip()).orElse(file.toString());
                    platform.addAll(entries("platform:" + file.getFileName(), "PLATFORM", title, "platform", null, content));
                }
            }
            for (Case c : cases) {
                List<Entry> own = new ArrayList<>();
                if (c.knowledge() != null) {
                    for (JsonNode k : c.knowledge()) {
                        own.addAll(entries(k.path("id").asText(), k.path("source").asText(), k.path("title").asText(),
                                c.input().at("/detail/deployment/repository_name").asText(), k.path("version").asText(null),
                                DiagnosisPrompt.redact(k.path("text").asText())));
                    }
                }
                perCase.put(c.id(), own);
            }
        }

        private List<Entry> entries(String ref, String source, String title, String repository, String version, String content) {
            List<KnowledgeText.Chunk> parts = KnowledgeText.split(title, content);
            List<float[]> vectors = embeddings.embed(parts.stream().map(KnowledgeText.Chunk::text).toList());
            List<Entry> out = new ArrayList<>();
            for (int i = 0; i < parts.size(); i++) out.add(new Entry(ref, source, title, repository, version, parts.get(i).text(), vectors.get(i)));
            return out;
        }

        List<KnowledgeItem> search(Case c) {
            List<Entry> candidates = new ArrayList<>(platform);
            candidates.addAll(perCase.get(c.id()));
            float[] query = embeddings.embed(List.of(KnowledgeText.query(DiagnosisPrompt.redact(c.input().toString())))).get(0);
            return KnowledgeText.top(query, candidates, Entry::vector, 4, minSimilarity).stream()
                    .map(s -> new KnowledgeItem(s.item().source(), s.item().ref(), s.item().title(), s.item().repository(),
                            s.item().version(), null, s.item().text(), Math.round(s.similarity() * 1000) / 1000.0))
                    .toList();
        }
    }

    /** provider:model@effort=low+prompt=compact+input=trim+knowledge=on */
    private record Variant(String provider, String model, String effort, boolean compact, boolean trim, boolean knowledge) {
        static Variant parse(String spec) {
            String[] parts = spec.split("@", 2);
            String effort = null;
            boolean compact = false, trim = false, knowledge = false;
            if (parts.length > 1) {
                for (String opt : parts[1].split("\\+")) {
                    switch (opt) {
                        case "prompt=compact" -> compact = true;
                        case "input=trim" -> trim = true;
                        case "knowledge=on" -> knowledge = true;
                        default -> {
                            if (!opt.startsWith("effort=")) throw new IllegalArgumentException("알 수 없는 옵션: " + opt);
                            effort = opt.substring("effort=".length());
                        }
                    }
                }
            }
            int colon = parts[0].indexOf(':');
            return new Variant(parts[0].substring(0, colon), parts[0].substring(colon + 1), effort, compact, trim, knowledge);
        }

        LlmClient client() {
            if (effort != null && !provider.startsWith("openai")) throw new IllegalArgumentException("effort는 openai·openai-compatible만: " + provider);
            return switch (provider) {
                case "gemini" -> new GeminiLlmClient(new GeminiClient(
                        RestClient.builder().baseUrl("https://generativelanguage.googleapis.com").build(),
                        require("GEMINI_API_KEY"), model), model);
                case "anthropic" -> new AnthropicLlmClient(require("ANTHROPIC_API_KEY"), model);
                case "openai" -> new OpenAiLlmClient(require("OPENAI_API_KEY"), model, effort);
                case OpenAiLlmClient.COMPATIBLE -> OpenAiLlmClient.compatible(require("OPENAI_COMPATIBLE_BASE_URL"), model, effort);
                default -> throw new IllegalArgumentException("provider는 gemini·anthropic·openai·openai-compatible: " + provider);
            };
        }
    }

    private static Map<String, String> totals(Map<String, Map<String, Boolean>> scores, List<String> items) {
        Map<String, String> totals = new LinkedHashMap<>();
        for (String item : items) {
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
