package klepaas.backend.deployment.service;

import klepaas.backend.deployment.entity.DeploymentConfig;
import klepaas.backend.deployment.entity.HealthProbe;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DeploymentConfigSnapshotsTest {

    private final DeploymentConfigSnapshots snapshots = new DeploymentConfigSnapshots("test-secret-0123456789abcdef");

    private DeploymentConfig config(Map<String, String> env) {
        return DeploymentConfig.builder().minReplicas(1).maxReplicas(1).containerPort(8080).domainUrl("app.test")
                .envVars(env).healthProbe(new HealthProbe("/health", null, 0, 5, 3, null)).build();
    }

    @Test
    @DisplayName("env 값은 저장하지 않고 이름별 지문만 남긴다. 값이 같으면 지문이 같고 다르면 다르다")
    void envValuesAreFingerprintedNotStored() {
        var hello = snapshots.capture(config(Map.of("SAMPLE_GREETING", "hello", "DB_PASSWORD", "s3cr3t-value")));
        var bye = snapshots.capture(config(Map.of("SAMPLE_GREETING", "bye", "DB_PASSWORD", "s3cr3t-value")));

        String json = snapshots.toJson(hello);
        assertThat(json).contains("SAMPLE_GREETING", "DB_PASSWORD", "\"health_probe\"").doesNotContain("hello", "s3cr3t-value");
        assertThat(hello.envFingerprints().get("DB_PASSWORD")).isEqualTo(bye.envFingerprints().get("DB_PASSWORD"));
        assertThat(hello.envFingerprints().get("SAMPLE_GREETING")).isNotEqualTo(bye.envFingerprints().get("SAMPLE_GREETING"));
        assertThat(hello.fingerprintKeyId()).isEqualTo(bye.fingerprintKeyId()).hasSize(8);
    }

    @Test
    @DisplayName("저장한 스냅샷을 다시 읽고, 기록이 없거나 읽을 수 없으면 비어 있다")
    void readsBackAndNeverGuesses() {
        var snapshot = snapshots.capture(config(Map.of("A", "1")));

        assertThat(snapshots.read(snapshots.toJson(snapshot))).contains(snapshot);
        assertThat(snapshots.read(null)).isEmpty();
        assertThat(snapshots.read("not json")).isEmpty();
    }

    @Test
    @DisplayName("비밀키가 바뀌면 키 식별자가 달라 값 변경 여부를 비교하지 않게 한다")
    void keyIdChangesWithSecret() {
        var other = new DeploymentConfigSnapshots("rotated-secret-0123456789abcdef");
        assertThat(other.capture(config(Map.of())).fingerprintKeyId())
                .isNotEqualTo(snapshots.capture(config(Map.of())).fingerprintKeyId());
    }
}
