package klepaas.backend.diagnosis.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import klepaas.backend.diagnosis.eval.DiagnosisEval.Case;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 진단 평가 데이터셋 검사 (#166, 명세는 docs/eval/diagnosis-eval-spec.md). 사례 메타데이터·근거 묶음·분할 규칙을 확인하고,
 * manifest의 사례 목록·해시가 지금 파일과 같은지 본다. 사례 파일을 바꾸면 manifest와 CHANGELOG를 함께 고쳐야 통과한다.
 */
class DiagnosisDatasetTest {

    static final Path ROOT = Path.of("src/test/resources/diagnosis-eval");
    static final Path MANIFEST = ROOT.resolve("manifest.json");
    /** 데이터셋에 속한 사례 디렉터리. RAG 가공 사례와 #123 조정용·과거 사건은 평가 분할에 넣지 않는다 */
    static final List<Path> DIRS = List.of(DiagnosisEval.CASES, DiagnosisEval.BLIND_CASES.resolve("test"), DiagnosisEval.HARD_CASES);
    static final List<String> SPLITS = List.of("dev", "test", "robustness");
    static final Set<String> SLICES = Set.of("SUPPORTED", "UNSUPPORTED_OBSERVED", "INSUFFICIENT", "CONFLICT", "ROBUSTNESS");
    static final Set<String> AUDIT = Set.of("kept", "corrected", "multi_answer", "undecidable");

    /** MANIFEST_WRITE=1이면 지금 파일로 manifest의 splits를 다시 쓴다. 사례를 바꾼 뒤 CHANGELOG와 함께 실행한다 */
    @Test
    @EnabledIfEnvironmentVariable(named = "MANIFEST_WRITE", matches = "1")
    void writeManifest() throws Exception {
        ObjectNode manifest = (ObjectNode) DiagnosisEval.JSON.readTree(MANIFEST.toFile());
        manifest.set("splits", splits());
        DiagnosisEval.JSON.writerWithDefaultPrettyPrinter().writeValue(MANIFEST.toFile(), manifest);
    }

    @Test
    void manifestMatchesCaseFiles() throws Exception {
        JsonNode manifest = DiagnosisEval.JSON.readTree(MANIFEST.toFile());
        assertThat(manifest.path("version").asText()).isNotBlank();
        assertThat(manifest.get("splits")).isEqualTo(splits());
    }

    @Test
    void casesFollowDatasetRules() throws Exception {
        Map<String, Set<String>> episodeSplits = new TreeMap<>();
        for (Path file : files()) {
            Case c = DiagnosisEval.JSON.readValue(file.toFile(), Case.class);
            var e = c.expected();
            String id = c.id();
            assertThat(c.episodeId()).as(id).isNotBlank();
            assertThat(c.split()).as(id).isIn(SPLITS);
            assertThat(c.slice()).as(id).isIn(SLICES);
            String audit = c.audit().path("status").asText();
            assertThat(audit).as(id).isIn(AUDIT);
            assertThat(audit.equals("multi_answer")).as(id + " 복수 정답과 감사 상태").isEqualTo(!e.acceptedRootCauses().isEmpty());
            // 보류가 정답인 갈래와 원인을 정하는 갈래
            boolean abstainSlice = Set.of("UNSUPPORTED_OBSERVED", "INSUFFICIENT", "CONFLICT").contains(c.slice());
            assertThat(e.abstain()).as(id + " 갈래와 보류").isEqualTo(abstainSlice);
            if (e.abstain() || audit.equals("undecidable")) {
                assertThat(e.evidenceSets()).as(id).isEmpty();
            } else {
                assertThat(e.evidenceSets()).as(id + " 근거 묶음").isNotEmpty();
            }
            for (List<String> set : e.evidenceSets()) {
                assertThat(set).as(id).isNotEmpty();
                set.forEach(p -> assertThat(c.input().at(p).isMissingNode()).as(id + " " + p).isFalse());
            }
            episodeSplits.computeIfAbsent(c.episodeId(), k -> new java.util.TreeSet<>()).add(c.split());
        }
        // test 분할의 에피소드는 다른 분할에 나타나지 않는다 (같은 장애의 시점·변형이 개발에 새지 않게)
        episodeSplits.forEach((episode, splits) -> {
            if (splits.contains("test")) assertThat(splits).as(episode).containsExactly("test");
        });
    }

    @Test
    void sufficiencyCoversDescendantsOnly() {
        var e = new DiagnosisEval.Expected("READINESS", "probe_port_closed", List.of(),
                List.of(List.of("/logs/events/0", "/detail/config/container_port"), List.of("/detail/deployment/fail_reason")),
                false, List.of(), null, null, null, null);
        assertThat(DiagnosisEval.evidenceSufficient(e, List.of("/logs/events/0/message", "/detail/config/container_port"))).isTrue();
        assertThat(DiagnosisEval.evidenceSufficient(e, List.of("/detail/deployment/fail_reason"))).isTrue();
        // 묶음 일부만, 또는 위 위치(/detail/config)만 인용하면 충분하지 않다
        assertThat(DiagnosisEval.evidenceSufficient(e, List.of("/logs/events/0", "/detail/config"))).isFalse();
        assertThat(DiagnosisEval.unrelatedCitations(e, List.of("/logs/events/0", "/detail/config", "/logs/events/3"))).isEqualTo(1);
        assertThat(DiagnosisEval.evidenceSufficient(new DiagnosisEval.Expected(null, null, List.of(), true, List.of(), null), List.of()))
                .isNull();
    }

    /** 분할별 사례 경로·ID·에피소드·갈래·SHA-256과 갈래 분포 */
    static JsonNode splits() throws Exception {
        Map<String, List<Map<String, String>>> cases = new TreeMap<>();
        Map<String, Map<String, Integer>> slices = new TreeMap<>();
        SPLITS.forEach(s -> cases.put(s, new ArrayList<>()));
        for (Path file : files()) {
            Case c = DiagnosisEval.JSON.readValue(file.toFile(), Case.class);
            cases.get(c.split()).add(Map.of("path", ROOT.relativize(file).toString(), "id", c.id(), "episode_id", c.episodeId(),
                    "slice", c.slice(), "sha256", sha256(file)));
        }
        ObjectNode out = DiagnosisEval.JSON.createObjectNode();
        for (String split : SPLITS) {
            List<Map<String, String>> list = cases.get(split);
            slices.put(split, list.stream().collect(Collectors.groupingBy(m -> m.get("slice"), TreeMap::new, Collectors.summingInt(m -> 1))));
            ObjectNode s = out.putObject(split);
            s.put("count", list.size());
            s.set("slices", DiagnosisEval.JSON.valueToTree(slices.get(split)));
            s.set("cases", DiagnosisEval.JSON.valueToTree(list.stream().map(TreeMap::new).toList()));
        }
        return out;
    }

    static List<Path> files() throws Exception {
        List<Path> out = new ArrayList<>();
        for (Path dir : DIRS) {
            try (Stream<Path> f = Files.list(dir)) {
                f.filter(p -> p.toString().endsWith(".json")).sorted().forEach(out::add);
            }
        }
        return out;
    }

    private static String sha256(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }
}
