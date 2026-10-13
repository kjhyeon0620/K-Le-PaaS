package klepaas.backend.ai.service;

import klepaas.backend.ai.entity.Intent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 변경 명령의 필수 대상 인자 (#135) */
class ChangeTargetsTest {

    @Test
    @DisplayName("intent별 필수 인자가 모두 있으면 빠진 것이 없다")
    void completeTargets() {
        assertThat(ChangeTargets.missing(Intent.DEPLOY, Map.of("repository_id", 3))).isEmpty();
        assertThat(ChangeTargets.missing(Intent.SCALE, Map.of("deployment_id", "12", "replicas", 3))).isEmpty();
        assertThat(ChangeTargets.missing(Intent.RESTART, Map.of("deployment_id", 7))).isEmpty();
        assertThat(ChangeTargets.missing(Intent.ROLLBACK, Map.of("owner", "acme", "repo", "sample-node"))).isEmpty();
    }

    @Test
    @DisplayName("이름만 준 재시작, 숫자 없는 스케일, 레플리카 0, 숫자가 아닌 ID는 빠진 것으로 본다")
    void missingOrInvalidTargets() {
        assertThat(ChangeTargets.missing(Intent.RESTART, Map.of("app_name", "sample-node"))).containsExactly("deployment_id");
        assertThat(ChangeTargets.missing(Intent.SCALE, Map.of("deployment_id", 12))).containsExactly("replicas");
        assertThat(ChangeTargets.missing(Intent.SCALE, Map.of("replicas", 0))).containsExactly("deployment_id", "replicas");
        assertThat(ChangeTargets.missing(Intent.SCALE, Map.of("deployment_id", "sample-node", "replicas", 2.5)))
                .containsExactly("deployment_id", "replicas");
        assertThat(ChangeTargets.missing(Intent.DEPLOY, Map.of())).containsExactly("repository_id");
        assertThat(ChangeTargets.missing(Intent.ROLLBACK_EXECUTION, Map.of("owner", " "))).containsExactly("owner", "repo");
        assertThat(ChangeTargets.missing(Intent.RESTART, null)).containsExactly("deployment_id");
    }

    @Test
    @DisplayName("조회 intent는 판정하지 않고, 되묻는 문장에 빠진 인자를 사람이 읽게 적는다")
    void readIntentsAndQuestion() {
        assertThat(ChangeTargets.missing(Intent.STATUS, Map.of())).isEmpty();
        assertThat(ChangeTargets.question(Intent.SCALE, java.util.List.of("deployment_id", "replicas")))
                .endsWith("다음을 알려 주세요: 대상 배포 ID, 레플리카 수(1 이상)");
    }
}
