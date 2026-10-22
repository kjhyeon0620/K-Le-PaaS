package klepaas.backend.diagnosis.eval;

import klepaas.backend.diagnosis.eval.DiagnosisEval.Case;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RawDiagnosisTest {

    private static Case byId(String id) throws Exception {
        return DiagnosisEval.loadCases().stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("정답 단정·유효 근거면 계약 위반 없이 정답이고 근거가 일치한다")
    void correctAssertion() throws Exception {
        var r = RawDiagnosis.judge(byId("env-postgres-password-missing"), """
                ```json
                {"category":"ENV_CONFIG","root_cause":"required_env_missing","abstain":false,"cited":["/logs/pods/0/previous_logs/0"]}
                ```""", 0, true);

        assertThat(r.contractViolation()).isFalse();
        assertThat(r.asserted()).isTrue();
        assertThat(r.confidentWrong()).isFalse();
        assertThat(r.evidenceHit()).isTrue();
    }

    @Test
    @DisplayName("없는 근거 위치·범주 불일치·목록 밖 라벨은 서버가 지우기 전의 계약 위반으로 센다")
    void contractViolations() throws Exception {
        Case env = byId("env-postgres-password-missing");

        var mismatched = RawDiagnosis.judge(env,
                "{\"category\":\"IMAGE\",\"root_cause\":\"required_env_missing\",\"abstain\":false,\"cited\":[\"/logs/pods/3/logs/0\",\"/detail/changes\"]}", 0, true);
        assertThat(mismatched.relationOk()).isFalse();
        assertThat(mismatched.invalidPointers()).isEqualTo(1);
        assertThat(mismatched.pointers()).isEqualTo(2);
        assertThat(mismatched.contractViolation()).isTrue();

        var unknown = RawDiagnosis.judge(env, "{\"category\":\"ENV_CONFIG\",\"root_cause\":\"missing_password\",\"abstain\":false,\"cited\":[]}", 0, true);
        assertThat(unknown.labelsInList()).isFalse();
        assertThat(unknown.confidentWrong()).isTrue();

        assertThat(RawDiagnosis.judge(env, "not json", 0, true).json()).isFalse();
    }

    @Test
    @DisplayName("보류가 정답인 사례에서 단정하면 확신 오답, 보류하면서 라벨을 내면 계약 위반")
    void abstainCases() throws Exception {
        Case insufficient = byId("insufficient-observation-unavailable");

        var asserted = RawDiagnosis.judge(insufficient,
                "{\"category\":\"READINESS\",\"root_cause\":\"probe_port_closed\",\"abstain\":false,\"cited\":[]}", 0, true);
        assertThat(asserted.confidentWrong()).isTrue();
        assertThat(asserted.evidenceHit()).isNull();

        var labelled = RawDiagnosis.judge(insufficient,
                "{\"category\":\"READINESS\",\"root_cause\":null,\"abstain\":true,\"cited\":[]}", 0, true);
        assertThat(labelled.asserted()).isFalse();
        assertThat(labelled.abstainWithLabel()).isTrue();
        assertThat(labelled.confidentWrong()).isFalse();
    }

    @Test
    @DisplayName("지식 위치는 넣은 지식 수로, 입력을 바꾼 변형은 근거 위치를 판정하지 않는다")
    void knowledgeAndUncheckedPointers() throws Exception {
        Case env = byId("env-postgres-password-missing");
        String raw = "{\"category\":\"ENV_CONFIG\",\"root_cause\":\"required_env_missing\",\"abstain\":false,\"cited\":[\"/knowledge/1\",\"/knowledge/4\"]}";

        assertThat(RawDiagnosis.judge(env, raw, 2, true).invalidPointers()).isEqualTo(1);
        assertThat(RawDiagnosis.judge(env, raw, 2, false).invalidPointers()).isZero();
    }
}
