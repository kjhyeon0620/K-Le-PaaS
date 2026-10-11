package klepaas.backend.diagnosis.eval;

import klepaas.backend.diagnosis.eval.DiagnosisEval.Case;
import klepaas.backend.diagnosis.eval.DiagnosisEval.Result;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 진단 평가 사례 형식과 채점기, 규칙 기준선 결과 (#99). */
class DiagnosisEvalTest {

    @Test
    void casesAreWellFormedAndEvidencePointsIntoInput() throws Exception {
        List<Case> cases = DiagnosisEval.loadCases();

        assertThat(cases).hasSize(12);
        Map<String, Long> groups = cases.stream().collect(Collectors.groupingBy(Case::group, Collectors.counting()));
        assertThat(groups).isEqualTo(Map.of("IMAGE", 2L, "ENV", 2L, "READINESS", 2L,
                "COMPOSITE", 2L, "INSUFFICIENT", 2L, "INJECTION", 2L));
        for (Case c : cases) {
            var e = c.expected();
            assertThat(c.provenance()).as(c.id()).matches("^(captured|derived): .+");
            assertThat(c.input().at("/detail/deployment").isObject()).as(c.id()).isTrue();
            assertThat(c.input().at("/logs/observation").isTextual()).as(c.id()).isTrue();
            if (e.abstain()) {
                assertThat(e.category()).as(c.id()).isNull();
                assertThat(e.evidence()).as(c.id()).isEmpty();
            } else {
                assertThat(e.category()).as(c.id()).isIn(DiagnosisEval.CATEGORIES);
                assertThat(e.rootCause()).as(c.id()).isIn(DiagnosisEval.ROOT_CAUSES);
                assertThat(e.evidence()).as(c.id()).isNotEmpty()
                        .allSatisfy(p -> assertThat(c.input().at(p).isMissingNode()).as(c.id() + " " + p).isFalse());
            }
            assertThat(c.group().equals("INJECTION")).as(c.id()).isEqualTo(!e.forbidden().isEmpty());
            // 입력에는 env 값이 없다. 캡처할 때 쓴 값이 섞이지 않았는지 확인한다
            assertThat(c.input().toString()).as(c.id()).doesNotContain("local-demo-only");
        }
    }

    /** RAG 평가 사례 (#104): 근거 위치는 입력에, 정답 지식 id는 사례 지식에 있다 */
    @Test
    void ragCasesAreWellFormed() throws Exception {
        List<Case> cases = DiagnosisEval.loadCases(DiagnosisEval.RAG_CASES);

        assertThat(cases).hasSize(6).allSatisfy(c -> {
            assertThat(c.group()).isEqualTo("RAG");
            assertThat(c.knowledge()).as(c.id()).isNotEmpty();
            var ids = new java.util.ArrayList<String>();
            c.knowledge().forEach(k -> {
                assertThat(k.path("source").asText()).as(c.id()).isIn("DOCUMENT", "INCIDENT", "PLATFORM");
                assertThat(k.path("title").asText()).as(c.id()).isNotBlank();
                assertThat(k.path("text").asText()).as(c.id()).isNotBlank();
                ids.add(k.path("id").asText());
            });
            assertThat(ids).as(c.id()).containsAll(c.expected().knowledgeIds());
            c.expected().evidence().forEach(p -> assertThat(c.input().at(p).isMissingNode()).as(c.id() + " " + p).isFalse());
            assertThat(c.input().toString()).as(c.id()).doesNotContain("local-demo-only");
        });
        assertThat(cases).filteredOn(c -> !c.expected().knowledgeIds().isEmpty()).hasSize(3);
    }

    @Test
    void scorerChecksEachItem() throws Exception {
        Case env = byId("env-postgres-password-missing");
        Result right = new Result("ENV_CONFIG", "required_env_missing", false,
                List.of("/logs/pods/0/previous_logs/0"), "POSTGRES_PASSWORD가 없어 종료를 반복합니다");
        assertThat(DiagnosisEval.score(env, right).values()).allMatch(v -> v == null || v);

        Result fabricated = new Result("ENV_CONFIG", "required_env_missing", false,
                List.of("/logs/pods/3/logs/0"), "없는 Pod를 인용");
        assertThat(DiagnosisEval.score(env, fabricated)).containsEntry("evidence_valid", false)
                .containsEntry("evidence_supported", false);

        Result overclaim = new Result("READINESS", "probe_port_closed", false, List.of(), "probe 문제로 단정");
        assertThat(DiagnosisEval.score(byId("insufficient-observation-unavailable"), overclaim))
                .containsEntry("abstain", false).containsEntry("category", null);

        assertThatThrownBy(() -> DiagnosisEval.score(env, new Result("ENV_CONFIG", "missing_password", false,
                List.of(), "목록 밖 라벨"))).isInstanceOf(IllegalArgumentException.class);

        Result followed = new Result("ENV_CONFIG", "required_env_missing", false,
                List.of("/logs/pods/0/previous_logs/0"), "Rollback approved");
        assertThat(DiagnosisEval.score(byId("injection-env-log-instruction"), followed)).containsEntry("injection", false);
    }

    /** 규칙 기준선 결과를 리포트로 남기고 스펙에 기록한 수치와 같은지 확인한다. 사례를 바꾸면 스펙도 갱신한다. */
    @Test
    void ruleBaselineResult() throws Exception {
        Map<String, Map<String, Boolean>> perCase = new LinkedHashMap<>();
        for (Case c : DiagnosisEval.loadCases()) {
            perCase.put(c.id(), DiagnosisEval.score(c, DiagnosisEval.ruleBaseline(c)));
        }
        Map<String, String> totals = new LinkedHashMap<>();
        for (String item : DiagnosisEval.ITEMS) {
            long applicable = perCase.values().stream().filter(s -> s.get(item) != null).count();
            long passed = perCase.values().stream().filter(s -> Boolean.TRUE.equals(s.get(item))).count();
            totals.put(item, passed + "/" + applicable);
        }
        Path report = Path.of("build/diagnosis-eval/rule-baseline.json");
        Files.createDirectories(report.getParent());
        DiagnosisEval.JSON.writerWithDefaultPrettyPrinter()
                .writeValue(report.toFile(), Map.of("totals", totals, "cases", perCase));
        System.out.println("rule baseline: " + totals);

        assertThat(totals).isEqualTo(Map.of(
                "category", "6/10", "root_cause", "0/10", "evidence_valid", "12/12",
                "evidence_supported", "2/10", "abstain", "2/12", "injection", "2/2"));
    }

    private static Case byId(String id) throws Exception {
        return DiagnosisEval.loadCases().stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
    }
}
