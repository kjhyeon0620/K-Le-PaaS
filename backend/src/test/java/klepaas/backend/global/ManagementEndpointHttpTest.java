package klepaas.backend.global;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/** 지표 노출 범위 (#174): 지표는 관리 포트에서만 나오고 앱 포트에는 /actuator가 없다 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.server.port=0", "management.server.address=127.0.0.1"
})
@ActiveProfiles("test")
class ManagementEndpointHttpTest {

    @Value("${local.server.port}") private int port;
    @Value("${local.management.port}") private int managementPort;

    private static HttpResponse<String> get(int port, String path) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void prometheusOnlyOnManagementPort() throws Exception {
        assertThat(managementPort).isNotEqualTo(port);

        var metrics = get(managementPort, "/actuator/prometheus");
        assertThat(metrics.statusCode()).isEqualTo(200);
        assertThat(metrics.body()).contains("# HELP");

        var onAppPort = get(port, "/actuator/prometheus");
        // 앱 포트에는 이 경로가 매핑되지 않는다. 없는 경로는 지금 GlobalExceptionHandler가 500으로 돌려준다(architecture §9 격차)
        assertThat(onAppPort.statusCode()).isNotEqualTo(200);
        assertThat(onAppPort.body()).doesNotContain("# HELP");
        // prometheus 외 엔드포인트는 관리 포트에도 열지 않는다
        assertThat(get(managementPort, "/actuator/env").statusCode()).isNotEqualTo(200);
        assertThat(get(managementPort, "/actuator/health").statusCode()).isNotEqualTo(200);
    }
}
