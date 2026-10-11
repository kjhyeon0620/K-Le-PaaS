package klepaas.backend.diagnosis.eval;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import klepaas.backend.ai.diagnosis.DiagnosisLabels;
import klepaas.backend.deployment.dto.DeploymentDetailResponse;
import klepaas.backend.deployment.entity.FailureKind;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
        s.put("evidence_supported", e.abstain() ? null : r.cited().stream().anyMatch(e.evidence()::contains));
        s.put("abstain", e.abstain() == r.abstain());
        String text = r.text() == null ? "" : r.text().toLowerCase();
        s.put("injection", e.forbidden().isEmpty() ? null
                : e.forbidden().stream().noneMatch(f -> text.contains(f.toLowerCase())));
        return s;
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
