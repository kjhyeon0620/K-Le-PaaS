package klepaas.backend.infra.kubernetes;

import io.fabric8.kubernetes.api.model.StatusBuilder;
import io.fabric8.kubernetes.client.KubernetesClientException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KubernetesErrorMessagesTest {

    @Test
    @DisplayName("Kubernetes 예외는 API 서버 주소 대신 status 메시지·HTTP 코드·연결 실패로만 표시한다")
    void kubernetesExceptionsHideApiServerAddress() {
        var conflict = new KubernetesClientException(new StatusBuilder().withCode(409)
                .withMessage("Operation cannot be fulfilled on deployments.apps \"app\": the object has been modified").build());
        assertThat(KubernetesErrorMessages.userMessage(conflict))
                .isEqualTo("Operation cannot be fulfilled on deployments.apps \"app\": the object has been modified");

        var forbidden = new KubernetesClientException("Failure executing: GET at: https://10.0.0.1:6443/apis/apps/v1", 403, null);
        assertThat(KubernetesErrorMessages.userMessage(forbidden)).isEqualTo("Kubernetes API 오류 (HTTP 403)");

        var refused = new KubernetesClientException("Failed to connect to https://10.0.0.1:6443");
        assertThat(KubernetesErrorMessages.userMessage(refused)).isEqualTo("Kubernetes API에 연결하지 못했습니다");
    }

    @Test
    @DisplayName("다른 예외에 감싸여 있어도 원인 체인의 Kubernetes 예외 기준으로 표시하고, 그 밖의 예외 메시지는 그대로 둔다")
    void wrappedAndNonKubernetesExceptions() {
        var wrapped = new IllegalStateException("Failure executing: PUT at: https://10.0.0.1:6443/apis",
                new KubernetesClientException("Failure executing: PUT at: https://10.0.0.1:6443/apis", 500, null));
        assertThat(KubernetesErrorMessages.userMessage(wrapped)).isEqualTo("Kubernetes API 오류 (HTTP 500)").doesNotContain("10.0.0.1");

        assertThat(KubernetesErrorMessages.userMessage(new IllegalArgumentException("replicas는 1 이상이어야 합니다")))
                .isEqualTo("replicas는 1 이상이어야 합니다");
    }
}
