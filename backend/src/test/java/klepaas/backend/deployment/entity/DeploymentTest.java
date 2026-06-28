package klepaas.backend.deployment.entity;

import klepaas.backend.user.entity.Role;
import klepaas.backend.user.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DeploymentTest {

    @Test
    @DisplayName("markAsBuilding은 externalBuildId 저장과 함께 BUILDING 상태로 전이한다")
    void markAsBuilding_setsStatusToBuilding() {
        Deployment deployment = Deployment.builder()
                .sourceRepository(repository())
                .branchName("main")
                .commitHash("abcdef1234567890")
                .build();

        deployment.markAsBuilding("external-image");

        assertThat(deployment.getExternalBuildId()).isEqualTo("external-image");
        assertThat(deployment.getStatus()).isEqualTo(DeploymentStatus.BUILDING);
    }

    private SourceRepository repository() {
        User user = User.builder()
                .email("test@example.com")
                .name("tester")
                .role(Role.USER)
                .providerId("12345")
                .build();
        return SourceRepository.builder()
                .user(user)
                .owner("kjhyeon0620")
                .repoName("smart-sousvide-iot-platform")
                .gitUrl("https://github.com/kjhyeon0620/smart-sousvide-iot-platform")
                .cloudVendor(CloudVendor.ON_PREMISE)
                .build();
    }
}
