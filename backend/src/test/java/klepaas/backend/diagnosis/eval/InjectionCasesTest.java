package klepaas.backend.diagnosis.eval;

import com.fasterxml.jackson.databind.node.ObjectNode;
import klepaas.backend.ai.diagnosis.SignatureRules;
import klepaas.backend.diagnosis.eval.DiagnosisEval.Case;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InjectionCasesTest {

    /** INJECTION_CASES_WRITE=1이면 사례를 다시 쓴다. 측정 뒤 다시 쓰면 그 결과는 동결 측정이 아니다 */
    @Test
    @EnabledIfEnvironmentVariable(named = "INJECTION_CASES_WRITE", matches = "1")
    void writeCases() throws Exception {
        Files.createDirectories(DiagnosisEval.INJECTION_CASES);
        for (ObjectNode n : InjectionCases.derive(InjectionCases.bases())) {
            DiagnosisEval.JSON.writerWithDefaultPrettyPrinter()
                    .writeValue(DiagnosisEval.INJECTION_CASES.resolve(n.get("id").asText() + ".json").toFile(), n);
        }
    }

    @Test
    void storedCasesMatchGenerator() throws Exception {
        List<ObjectNode> derived = InjectionCases.derive(InjectionCases.bases());
        assertThat(derived).hasSize(24);
        List<Case> stored = DiagnosisEval.loadCaseSet("injection");
        assertThat(stored).hasSameSizeAs(derived);
        for (ObjectNode n : derived) {
            Case want = DiagnosisEval.JSON.treeToValue(n, Case.class);
            assertThat(stored).as(want.id()).contains(want);
        }
    }

    @Test
    void basesCoverRuleAndModelPaths() throws Exception {
        List<Case> bases = InjectionCases.bases();
        assertThat(bases).doesNotContainNull().hasSize(4);
        assertThat(bases.stream().map(c -> SignatureRules.diagnose(c.input()).state()).toList()).containsExactly(
                SignatureRules.State.MATCH, SignatureRules.State.MATCH,
                SignatureRules.State.NO_MATCH, SignatureRules.State.NO_MATCH);
        assertThat(bases).allSatisfy(c -> assertThat(c.split()).isNotEqualTo("test"));
    }
}
