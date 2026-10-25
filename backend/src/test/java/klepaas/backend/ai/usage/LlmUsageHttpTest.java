package klepaas.backend.ai.usage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.client.KubernetesClient;
import klepaas.backend.ai.client.GeminiClient;
import klepaas.backend.auth.oauth.GitHubAppClient;
import klepaas.backend.auth.token.dto.CreateCliAccessTokenRequest;
import klepaas.backend.auth.token.entity.CliTokenScope;
import klepaas.backend.auth.token.service.CliAccessTokenService;
import klepaas.backend.infra.kubernetes.KubernetesManifestGenerator;
import klepaas.backend.user.entity.Role;
import klepaas.backend.user.entity.User;
import klepaas.backend.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import software.amazon.awssdk.services.s3.S3Client;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** LLM 사용량 조회·예산 (#176): 본인 사용량만 보이고, 예산을 넘은 자연어 명령은 모델 호출 없이 429 AI_007 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "jwt.secret=usage-local-jwt-fixture-key-at-least-thirty-two-bytes",
        "spring.jpa.open-in-view=false",
        "llm.budget.daily-usd-per-user=0.0005"
})
@ActiveProfiles("test")
class LlmUsageHttpTest {

    @Value("${local.server.port}") private int port;
    @Autowired private ObjectMapper mapper;
    @Autowired private UserRepository users;
    @Autowired private LlmUsageRepository usages;
    @Autowired private CliAccessTokenService cliTokens;

    @MockitoBean private GitHubAppClient github;
    @MockitoBean private GeminiClient gemini;
    @MockitoBean(name = "nlpLlm") private klepaas.backend.ai.llm.LlmClient nlpLlm;
    @MockitoBean private KubernetesClient kubernetes;
    @MockitoBean private KubernetesManifestGenerator generator;
    @MockitoBean private S3Client s3;

    private final HttpClient http = HttpClient.newHttpClient();

    private static LlmUsage row(Long userId, String feature, String cost) {
        return new LlmUsage(userId, feature, "openai", "gpt-6-luna", "success", 1000L, 0L, 100L, 0L,
                cost == null ? null : new BigDecimal(cost), 3000L, null);
    }

    @Test
    void usageShowsOnlyOwnRowsAndBudgetBlocksModelCall() throws Exception {
        User owner = users.save(User.builder().name("usage-owner").email("usage-owner@example.test").role(Role.USER).build());
        User other = users.save(User.builder().name("usage-other").email("usage-other@example.test").role(Role.USER).build());
        usages.save(row(owner.getId(), "nlp", "0.000300"));
        usages.save(row(owner.getId(), "diagnosis", "0.000400"));
        usages.save(row(other.getId(), "nlp", "0.009000"));
        String readOnly = cliTokens.createToken(owner.getId(), new CreateCliAccessTokenRequest("read", 1, CliTokenScope.READ_ONLY)).token();
        String full = cliTokens.createToken(owner.getId(), new CreateCliAccessTokenRequest("full", 1, CliTokenScope.FULL)).token();

        var response = send("GET", "/api/v1/llm/usage?days=7", readOnly, null);
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode data = mapper.readTree(response.body()).path("data");
        assertThat(data.path("timezone").asText()).isEqualTo("Asia/Seoul");
        assertThat(data.path("daily_limit_usd").decimalValue()).isEqualByComparingTo("0.0005");
        assertThat(data.path("spent_today_usd").decimalValue()).isEqualByComparingTo("0.000700");
        assertThat(data.path("remaining_today_usd").decimalValue()).isEqualByComparingTo("0");
        assertThat(data.path("days")).hasSize(2); // 다른 사용자의 nlp 행은 없다
        long calls = 0;
        for (JsonNode d : data.path("days")) calls += d.path("calls").asLong();
        assertThat(calls).isEqualTo(2);

        assertThat(send("GET", "/api/v1/llm/usage?days=40", readOnly, null).statusCode()).isEqualTo(400);
        assertThat(send("GET", "/api/v1/llm/usage", null, null).statusCode()).isEqualTo(401);

        // 오늘 0.0007 ≥ 상한 0.0005: 자연어 명령은 모델을 부르지 않고 429
        var rejected = send("POST", "/api/v1/nlp/command", full, "{\"command\":\"저장소 목록 보여줘\"}");
        assertThat(rejected.statusCode()).isEqualTo(429);
        assertThat(mapper.readTree(rejected.body()).toString()).contains("AI_007");
        verify(nlpLlm, never()).completeJson(any());
    }

    private HttpResponse<String> send(String method, String path, String token, String json) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json))
                .header("Content-Type", "application/json");
        if (token != null) request.header("Authorization", "Bearer " + token);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
