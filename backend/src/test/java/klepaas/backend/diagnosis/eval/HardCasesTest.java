package klepaas.backend.diagnosis.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import klepaas.backend.diagnosis.eval.DiagnosisEval.Case;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** #158 어려운 갈래: 저장된 사례가 생성기 결과와 같고, 기대 답이 갈래 규칙을 따른다 */
class HardCasesTest {

    /** HARD_CASES_WRITE=1이면 #99 사례에서 다시 만들어 hard-cases에 쓴다. 규칙을 바꾼 뒤에만 실행한다 */
    @Test
    @EnabledIfEnvironmentVariable(named = "HARD_CASES_WRITE", matches = "1")
    void writeHardCases() throws Exception {
        Files.createDirectories(DiagnosisEval.HARD_CASES);
        try (var old = Files.list(DiagnosisEval.HARD_CASES)) {
            for (Path p : old.filter(f -> f.toString().endsWith(".json")).toList()) Files.delete(p);
        }
        for (ObjectNode n : HardCases.derive(DiagnosisEval.loadCases())) {
            DiagnosisEval.JSON.writerWithDefaultPrettyPrinter()
                    .writeValue(DiagnosisEval.HARD_CASES.resolve(n.get("id").asText() + ".json").toFile(), n);
        }
    }

    @Test
    void storedCasesMatchGenerator() throws Exception {
        Map<String, JsonNode> generated = HardCases.derive(DiagnosisEval.loadCases()).stream()
                .collect(Collectors.toMap(n -> n.get("id").asText(), n -> DiagnosisEval.JSON.convertValue(n, JsonNode.class)));
        Map<String, JsonNode> stored;
        try (var files = Files.list(DiagnosisEval.HARD_CASES)) {
            stored = files.filter(f -> f.toString().endsWith(".json")).collect(Collectors.toMap(
                    f -> f.getFileName().toString().replace(".json", ""), f -> read(f)));
        }
        assertThat(stored.keySet()).isEqualTo(generated.keySet());
        generated.forEach((id, n) -> assertThat(stored.get(id)).as(id).isEqualTo(n));
    }

    @Test
    void groupsAndExpectedAnswersFollowRules() throws Exception {
        List<Case> base = DiagnosisEval.loadCases();
        List<Case> hard = DiagnosisEval.loadCaseSet("hard");
        Map<String, Long> groups = hard.stream().collect(Collectors.groupingBy(Case::group, Collectors.counting()));
        assertThat(groups).isEqualTo(Map.of(HardCases.DECOY, 10L, HardCases.MISSING, 3L, HardCases.LONG, 10L));
        // 감사용 사례는 저장소에 있지만 자동 채점 묶음에서 빠진다
        assertThat(DiagnosisEval.loadCases(DiagnosisEval.HARD_CASES)).filteredOn(c -> c.group().equals(HardCases.AUDIT))
                .extracting(Case::id).containsExactly("readiness-probe-port-closed--conflict");

        for (Case c : hard) {
            Case origin = base.stream().filter(b -> c.id().startsWith(b.id() + "--")).findFirst().orElseThrow();
            assertThat(c.provenance()).as(c.id()).startsWith("derived: " + origin.id());
            var e = c.expected();
            if (c.group().equals(HardCases.DECOY) || c.group().equals(HardCases.LONG)) {
                // 원인을 유지하는 갈래: 정답과 근거 위치가 원본과 같고, 근거가 바뀐 입력에서도 같은 값을 가리킨다
                assertThat(e).as(c.id()).isEqualTo(origin.expected());
                e.evidence().forEach(p -> assertThat(c.input().at(p)).as(c.id() + " " + p).isEqualTo(origin.input().at(p)));
                e.evidenceSets().stream().flatMap(List::stream)
                        .forEach(p -> assertThat(c.input().at(p)).as(c.id() + " " + p).isEqualTo(origin.input().at(p)));
                assertThat(c.episodeId()).as(c.id()).isEqualTo(origin.episodeId());
            } else {
                assertThat(e.abstain()).as(c.id()).isTrue();
                assertThat(e.category()).as(c.id()).isNull();
                assertThat(e.evidence()).as(c.id()).isEmpty();
                assertThat(e.evidenceSets()).as(c.id()).isEmpty();
            }
        }
        // 증거 부족 사례에는 원래 원인을 직접 말하는 로그가 남지 않는다
        for (String id : List.of("env-postgres-password-missing--missing", "env-postgres-password-missing--conflict",
                "composite-image-and-env-changed--missing")) {
            Case c = hard.stream().filter(h -> h.id().equals(id)).findFirst().orElseThrow();
            assertThat(c.input().at("/logs/pods/0/logs").toString()).as(id).doesNotContain("POSTGRES_PASSWORD");
            assertThat(c.input().at("/logs/pods/0/previous_logs").toString()).as(id).doesNotContain("POSTGRES_PASSWORD");
            // 공개 이미지 사전 지식으로 맞히지 못하게 이미지 이름도 바꿨다
            assertThat(c.input().toString()).as(id).doesNotContain("postgres");
        }
        // 긴 로그 갈래는 이전 버전 정상 Pod 2개에 40줄씩 붙는다
        hard.stream().filter(c -> c.group().equals(HardCases.LONG)).forEach(c -> {
            JsonNode pods = c.input().at("/logs/pods");
            assertThat(pods.size()).as(c.id()).isEqualTo(5);
            assertThat(pods.get(3).path("logs").size()).as(c.id()).isEqualTo(40);
            assertThat(pods.get(4).path("ready").asBoolean()).as(c.id()).isTrue();
        });
    }

    private static JsonNode read(Path f) {
        try {
            return DiagnosisEval.JSON.readTree(f.toFile());
        } catch (Exception e) {
            throw new IllegalStateException(f.toString(), e);
        }
    }
}
