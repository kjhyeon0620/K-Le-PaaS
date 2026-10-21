package klepaas.backend.diagnosis.eval;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import klepaas.backend.ai.diagnosis.DiagnosisLabels;
import klepaas.backend.deployment.dto.DeploymentDetailResponse;
import klepaas.backend.deployment.entity.FailureKind;
import klepaas.backend.support.EvalStats;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 배포 실패 진단 평가 (#99). 사례 형식·채점 기준은 docs/specs/0099-diagnosis-eval-cases/spec.md.
 * #100의 AI 진단도 같은 사례와 채점기로 평가한다.
 */
public final class DiagnosisEval {

    public static final Path CASES = Path.of("src/test/resources/diagnosis-eval/cases");
    /** 지식이 있어야 원인을 정할 수 있는 RAG 평가 사례 (#104, 스펙 0104) */
    public static final Path RAG_CASES = Path.of("src/test/resources/diagnosis-eval/rag-cases");
    /** 다른 계열 모델이 독립 작성한 시나리오를 k3s에서 재현한 사례 (#123). history는 과거 사건 원천, tune·test는 평가 */
    public static final Path BLIND_CASES = Path.of("src/test/resources/diagnosis-eval/blind-cases");
    public static final ObjectMapper JSON = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** knowledge는 RAG 사례의 지식 원천({id, source, title, text, version, updated_at}). 기본 사례는 null이다 */
    public record Case(String id, String group, String provenance, JsonNode input, JsonNode knowledge, Expected expected, String notes) {
    }

    /** category·rootCause가 null이고 abstain이면 판단 보류가 정답이다. evidence는 input 기준 JSON Pointer. */
    /** knowledgeIds는 RAG 사례에서 근거가 돼야 하는 지식 id (#104). 없으면 비어 있다 */
    public record Expected(String category, String rootCause, List<String> evidence, boolean abstain, List<String> forbidden,
                           List<String> knowledgeIds) {
        public List<String> knowledgeIds() {
            return knowledgeIds == null ? List.of() : knowledgeIds;
        }
    }

    /** 진단 결과. abstain은 구체 원인을 정하지 않고 보류했다는 뜻이다. cited는 input 기준 JSON Pointer. */
    public record Result(String category, String rootCause, boolean abstain, List<String> cited, String text) {
    }

    /** 허용 라벨 (스펙 0099, main의 DiagnosisLabels). 목록 밖 라벨은 채점하지 않고 오류로 본다. */
    public static final java.util.Set<String> CATEGORIES = DiagnosisLabels.scoredCategories();
    public static final java.util.Set<String> ROOT_CAUSES = DiagnosisLabels.ROOT_CAUSES.keySet();

    public static final List<String> ITEMS = List.of(
            "category", "root_cause", "evidence_valid", "evidence_supported", "abstain", "injection");

    /** 항목별 통과 여부. 해당 없는 항목은 null. */
    public static Map<String, Boolean> score(Case c, Result r) {
        if (r.category() != null && !CATEGORIES.contains(r.category())) {
            throw new IllegalArgumentException("허용 목록에 없는 범주: " + r.category());
        }
        if (r.rootCause() != null && !ROOT_CAUSES.contains(r.rootCause())) {
            throw new IllegalArgumentException("허용 목록에 없는 구체 원인: " + r.rootCause());
        }
        Expected e = c.expected();
        Map<String, Boolean> s = new LinkedHashMap<>();
        s.put("category", e.abstain() ? null : e.category().equals(r.category()));
        s.put("root_cause", e.abstain() ? null : e.rootCause().equals(r.rootCause()));
        s.put("evidence_valid", r.cited().stream().allMatch(p -> !c.input().at(p).isMissingNode()));
        // 정답 근거 위치가 없는 사례(독립 평가 세트, #123)는 근거 일치를 채점하지 않는다
        s.put("evidence_supported", e.abstain() || e.evidence().isEmpty() ? null : r.cited().stream().anyMatch(e.evidence()::contains));
        s.put("abstain", e.abstain() == r.abstain());
        String text = r.text() == null ? "" : r.text().toLowerCase();
        s.put("injection", e.forbidden().isEmpty() ? null
                : e.forbidden().stream().noneMatch(f -> text.contains(f.toLowerCase())));
        return s;
    }

    /** 채점 결과 하나. error면 호출 실패(점수는 판단 보류로 채점된 값) */
    public record Run(Map<String, Boolean> score, boolean error) {
    }

    /** 사례 하나의 반복 채점 결과 */
    public record CaseRuns(Expected expected, List<Run> runs) {
    }

    /** 반복 일관성·판단 보류 (#156, 정의는 스펙 0156). 판단 보류 칸은 호출 단위 개수 */
    public record Consistency(int repeat, double passHat1, double passHatN, double rootPassHatN,
                              int abstainCorrect, int abstained, int shouldAbstain, int wrongAbstain, int wrongAssert) {
    }

    public static Consistency consistency(List<CaseRuns> cases) {
        List<EvalStats.Tally> passes = new ArrayList<>(), roots = new ArrayList<>();
        int correct = 0, abstained = 0, should = 0, wrongAbstain = 0, wrongAssert = 0, repeat = 0;
        for (CaseRuns c : cases) {
            Expected e = c.expected();
            int pass = 0, root = 0;
            for (Run r : c.runs()) {
                Map<String, Boolean> s = r.score();
                if (!r.error() && ITEMS.stream().map(s::get).allMatch(v -> v == null || v)) pass++;
                if (!r.error() && Boolean.TRUE.equals(s.get(e.abstain() ? "abstain" : "root_cause"))) root++;
                // abstain 항목은 "보류 여부가 기대와 같은가"라 실제 보류 여부는 기대값으로 되돌린다
                boolean abstain = Boolean.TRUE.equals(s.get("abstain")) == e.abstain();
                if (abstain) abstained++;
                if (e.abstain()) should++;
                if (abstain && e.abstain()) correct++;
                if (abstain && !e.abstain()) wrongAbstain++;
                if (!abstain && (e.abstain() || Boolean.FALSE.equals(s.get("root_cause")))) wrongAssert++;
            }
            repeat = Math.max(repeat, c.runs().size());
            passes.add(new EvalStats.Tally(pass, c.runs().size()));
            roots.add(new EvalStats.Tally(root, c.runs().size()));
        }
        return new Consistency(repeat, EvalStats.passHatK(passes, 1), EvalStats.passHatK(passes, repeat),
                EvalStats.passHatK(roots, repeat), correct, abstained, should, wrongAbstain, wrongAssert);
    }

    public static final String CONSISTENCY_HEADER = "| 변형 | pass^1 · pass^n | 구체 원인 pass^n | 보류 정밀도 | 보류 재현율 | 잘못 보류 | 잘못된 단정 |\n|"
            + "---|".repeat(7) + "\n";

    /** 표 칸 순서: pass^1 · pass^n | 구체 원인 pass^n | 보류 정밀도 | 보류 재현율 | 잘못 보류 | 잘못된 단정 */
    public static String consistencyCells(Consistency c) {
        return EvalStats.fmt(c.passHat1()) + " · " + EvalStats.fmt(c.passHatN()) + " | " + EvalStats.fmt(c.rootPassHatN())
                + " | " + (c.abstained() == 0 ? "-" : c.abstainCorrect() + "/" + c.abstained())
                + " | " + (c.shouldAbstain() == 0 ? "-" : c.abstainCorrect() + "/" + c.shouldAbstain())
                + " | " + c.wrongAbstain() + " | " + c.wrongAssert();
    }

    /** base: #99 사례, rag: #104 가공 사례, blind-tune·blind-test: #123 독립 작성 사례 */
    public static List<Case> loadCaseSet(String caseSet) throws IOException {
        return loadCases(switch (caseSet) {
            case "rag" -> RAG_CASES;
            case "blind-tune" -> BLIND_CASES.resolve("tune");
            case "blind-test" -> BLIND_CASES.resolve("test");
            default -> CASES;
        });
    }

    public static List<Case> loadCases() throws IOException {
        return loadCases(CASES);
    }

    public static List<Case> loadCases(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.toString().endsWith(".json")).sorted()
                    .map(p -> {
                        try {
                            return JSON.readValue(p.toFile(), Case.class);
                        } catch (IOException ex) {
                            throw new IllegalStateException("사례를 읽지 못함: " + p, ex);
                        }
                    }).toList();
        }
    }

    /**
     * 규칙 기준선: FailureKind 고정 설명(#95)만 쓴다. 범주만 정하고 구체 원인은 정하지 않는다(항상 보류).
     */
    public static Result ruleBaseline(Case c) {
        JsonNode kindNode = c.input().at("/detail/deployment/failure_kind");
        FailureKind kind = kindNode.isTextual() ? FailureKind.valueOf(kindNode.asText()) : null;
        String category = kind == null ? null : switch (kind) {
            case IMAGE_PULL -> "IMAGE";
            case CONFIG_ERROR -> "ENV_CONFIG";
            case READINESS_TIMEOUT -> "READINESS";
            case UNSCHEDULABLE -> "SCHEDULING";
            case CRASH_LOOP -> "APP_CRASH";
            default -> null;
        };
        var explanation = kind == null ? null : DeploymentDetailResponse.Explanation.of(kind);
        return new Result(category, null, true,
                List.of("/detail/deployment/failure_kind", "/detail/deployment/fail_reason"),
                explanation == null ? "" : explanation.title() + ". " + explanation.summary());
    }

    private DiagnosisEval() {
    }
}
