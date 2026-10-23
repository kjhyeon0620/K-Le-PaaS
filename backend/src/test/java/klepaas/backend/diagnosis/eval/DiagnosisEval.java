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
    /** #99 사례를 기계적으로 바꾼 회귀·보류 검사 (#158) */
    public static final Path HARD_CASES = Path.of("src/test/resources/diagnosis-eval/hard-cases");
    public static final ObjectMapper JSON = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /**
     * knowledge는 RAG 사례의 지식 원천({id, source, title, text, version, updated_at}). 기본 사례는 null이다.
     * episodeId·split·slice·injection·audit는 데이터셋 메타데이터다 (#166, 정의는 docs/eval/diagnosis-eval-spec.md). 없는 사례 묶음(RAG 등)은 null이다
     */
    public record Case(String id, String group, String provenance, String episodeId, String split, String slice, JsonNode injection,
                       JsonNode input, JsonNode knowledge, Expected expected, JsonNode audit, String notes) {
    }

    /** category·rootCause가 null이고 abstain이면 판단 보류가 정답이다. evidence는 input 기준 JSON Pointer. */
    /** knowledgeIds는 RAG 사례에서 근거가 돼야 하는 지식 id (#104). 없으면 비어 있다 */
    /** acceptedRootCauses는 감사로 함께 인정한 구체 원인 (#159·#161, 예: 관측과 주입 의도가 다른 사례). 없으면 비어 있다 */
    /** evidenceSets는 대안적인 최소 충분 근거 묶음, allowedHypotheses·forbiddenAssertions는 설명 채점용 기록이다 (#166). 없으면 비어 있다 */
    public record Expected(String category, String rootCause, List<String> evidence, List<List<String>> evidenceSets, boolean abstain,
                           List<String> forbidden, List<String> knowledgeIds, List<String> acceptedRootCauses,
                           List<String> allowedHypotheses, List<String> forbiddenAssertions) {
        public Expected(String category, String rootCause, List<String> evidence, boolean abstain, List<String> forbidden,
                        List<String> knowledgeIds) {
            this(category, rootCause, evidence, null, abstain, forbidden, knowledgeIds, null, null, null);
        }

        public List<List<String>> evidenceSets() {
            return evidenceSets == null ? List.of() : evidenceSets;
        }

        public List<String> allowedHypotheses() {
            return allowedHypotheses == null ? List.of() : allowedHypotheses;
        }

        public List<String> forbiddenAssertions() {
            return forbiddenAssertions == null ? List.of() : forbiddenAssertions;
        }

        public List<String> knowledgeIds() {
            return knowledgeIds == null ? List.of() : knowledgeIds;
        }

        public List<String> acceptedRootCauses() {
            return acceptedRootCauses == null ? List.of() : acceptedRootCauses;
        }

        /** 정답 구체 원인이거나 감사로 함께 인정한 원인인가 */
        public boolean accepts(String rootCause) {
            return rootCause != null && (rootCause.equals(this.rootCause) || acceptedRootCauses().contains(rootCause));
        }
    }

    /**
     * 최소 충분 근거 묶음 중 하나를 빠짐없이 인용했는가 (#166). 인용이 묶음의 위치와 같거나 그 아래 위치면 그 위치를 덮는다.
     * 묶음이 없는 사례(보류 정답·묶음 미정)는 null
     */
    public static Boolean evidenceSufficient(Expected e, List<String> cited) {
        if (e.evidenceSets().isEmpty()) return null;
        return e.evidenceSets().stream().anyMatch(set -> set.stream().allMatch(req -> cited.stream().anyMatch(c -> covers(c, req))));
    }

    /** 근거 묶음 밖 인용 수: 어느 묶음 위치와도 같거나 위·아래 관계가 아닌 인용 (#166). 변경 이력 등 맥락 인용도 여기 들어가므로 오류 수가 아니다. 묶음이 없으면 0 */
    public static int unrelatedCitations(Expected e, List<String> cited) {
        if (e.evidenceSets().isEmpty()) return 0;
        List<String> all = e.evidenceSets().stream().flatMap(List::stream).toList();
        return (int) cited.stream().filter(c -> all.stream().noneMatch(req -> covers(c, req) || covers(req, c))).count();
    }

    private static boolean covers(String cited, String required) {
        return cited.equals(required) || cited.startsWith(required + "/");
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
        s.put("root_cause", e.abstain() ? null : e.accepts(r.rootCause()));
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

    /**
     * 변형 하나의 표 행. byGroup이면 사례 갈래별 행을 뒤에 붙인다(#158 어려운 갈래).
     * runsById에 없는 사례는 뺀다.
     */
    public static String consistencyRows(String variant, List<Case> cases, Map<String, List<Run>> runsById, boolean byGroup) {
        StringBuilder rows = new StringBuilder();
        List<Case> present = cases.stream().filter(c -> !runsById.getOrDefault(c.id(), List.of()).isEmpty()).toList();
        java.util.function.Function<List<Case>, Consistency> of = list -> consistency(
                list.stream().map(c -> new CaseRuns(c.expected(), runsById.get(c.id()))).toList());
        rows.append("| ").append(variant).append(" | ").append(consistencyCells(of.apply(present))).append(" |\n");
        if (byGroup) {
            present.stream().map(Case::group).distinct().forEach(g -> rows.append("| ").append(variant).append(" [").append(g).append("] | ")
                    .append(consistencyCells(of.apply(present.stream().filter(c -> c.group().equals(g)).toList()))).append(" |\n"));
        }
        return rows.toString();
    }

    /** 표 칸 순서: pass^1 · pass^n | 구체 원인 pass^n | 보류 정밀도 | 보류 재현율 | 잘못 보류 | 잘못된 단정 */
    public static String consistencyCells(Consistency c) {
        return EvalStats.fmt(c.passHat1()) + " · " + EvalStats.fmt(c.passHatN()) + " | " + EvalStats.fmt(c.rootPassHatN())
                + " | " + (c.abstained() == 0 ? "-" : c.abstainCorrect() + "/" + c.abstained())
                + " | " + (c.shouldAbstain() == 0 ? "-" : c.abstainCorrect() + "/" + c.shouldAbstain())
                + " | " + c.wrongAbstain() + " | " + c.wrongAssert();
    }

    /**
     * base: #99 사례, rag: #104 가공 사례, blind-tune·blind-test: #123 독립 작성 사례,
     * hard: #158 기계 변형 회귀·보류 검사(감사용 사례는 자동 채점에서 뺀다)
     */
    public static List<Case> loadCaseSet(String caseSet) throws IOException {
        if (caseSet.equals("hard")) {
            return loadCases(HARD_CASES).stream().filter(c -> !c.group().equals("AUDIT-ONLY")).toList();
        }
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
