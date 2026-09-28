package klepaas.backend.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.client.KubernetesClient;
import klepaas.backend.ai.client.GeminiClient;
import klepaas.backend.auth.jwt.JwtTokenProvider;
import klepaas.backend.auth.oauth.GitHubAppClient;
import klepaas.backend.auth.token.dto.CreateCliAccessTokenRequest;
import klepaas.backend.auth.token.entity.CliTokenScope;
import klepaas.backend.auth.token.service.CliAccessTokenService;
import klepaas.backend.auth.weblogin.entity.CliAuthSessionStatus;
import klepaas.backend.auth.weblogin.repository.CliAuthSessionRepository;
import klepaas.backend.deployment.entity.CloudVendor;
import klepaas.backend.deployment.entity.Deployment;
import klepaas.backend.deployment.entity.DeploymentConfig;
import klepaas.backend.deployment.entity.SourceRepository;
import klepaas.backend.deployment.repository.DeploymentConfigRepository;
import klepaas.backend.deployment.repository.DeploymentRepository;
import klepaas.backend.deployment.repository.SourceRepositoryRepository;
import klepaas.backend.deployment.service.DeploymentPipelineService;
import klepaas.backend.infra.kubernetes.KubernetesManifestGenerator;
import klepaas.backend.user.entity.Role;
import klepaas.backend.user.entity.User;
import klepaas.backend.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 실제 HTTP → Spring Security 경계에서 CLI 토큰 scope가 강제되는지 확인한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "jwt.secret=scope-local-jwt-fixture-key-at-least-thirty-two-bytes",
        "spring.jpa.open-in-view=false"
})
@ActiveProfiles("test")
class CliTokenScopeHttpTest {

    private static final String DATABASE_ID = UUID.randomUUID().toString().replace("-", "");

    @DynamicPropertySource
    static void isolatedDatabase(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> "jdbc:h2:mem:scope_http_" + DATABASE_ID + ";DB_CLOSE_DELAY=-1");
    }

    @Value("${local.server.port}") private int port;
    @Autowired private ObjectMapper mapper;
    @Autowired private UserRepository users;
    @Autowired private SourceRepositoryRepository repositories;
    @Autowired private DeploymentConfigRepository configs;
    @Autowired private DeploymentRepository deployments;
    @Autowired private CliAuthSessionRepository sessions;
    @Autowired private JwtTokenProvider jwt;
    @Autowired private CliAccessTokenService cliTokens;

    @MockitoBean private GitHubAppClient github;
    @MockitoBean private GeminiClient gemini;
    @MockitoBean private KubernetesClient kubernetes;
    @MockitoBean private KubernetesManifestGenerator generator;
    @MockitoBean private DeploymentPipelineService pipeline;
    @MockitoBean private S3Client s3;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void readOnlyTokenCanReadButCannotChangeAnything() throws Exception {
        User owner = users.save(User.builder().name("owner").email("scope-owner@example.test").role(Role.USER).build());
        SourceRepository repo = repositories.save(SourceRepository.builder().user(owner).owner("scope")
                .repoName("service").gitUrl("https://example.test/scope/service.git").cloudVendor(CloudVendor.NCP).build());
        configs.save(DeploymentConfig.builder().sourceRepository(repo).minReplicas(1).maxReplicas(2)
                .containerPort(8080).domainUrl("scope.example.test").build());
        Deployment deployment = deployments.save(Deployment.builder().sourceRepository(repo).branchName("main")
                .commitHash("abcdef0").build());
        String readOnly = cliTokens.createToken(owner.getId(),
                new CreateCliAccessTokenRequest("read", 1, CliTokenScope.READ_ONLY)).token();
        long readOnlyId = cliTokens.listTokens(owner.getId()).get(0).id();
        String deploymentUrl = "/api/v1/deployments/" + deployment.getId();

        assertEquals(200, send("GET", "/api/v1/repositories/" + repo.getId(), readOnly, null).statusCode());
        assertEquals(200, send("GET", deploymentUrl + "/status", readOnly, null).statusCode());
        assertEquals(200, send("GET", "/api/v1/cli-tokens", readOnly, null).statusCode());
        assertEquals(200, send("POST", "/api/v1/cost/plan", readOnly, """
                {"planned":{"cloud_vendor":"NCP","environment":"dev","replicas":1,"cpu_millicores":500,
                "memory_mb":512,"storage_gb":1,"load_balancer":false,"outbound_traffic_gb":0}}""").statusCode());

        assertEquals(403, send("POST", "/api/v1/deployments", readOnly,
                "{\"repository_id\":" + repo.getId() + ",\"branch_name\":\"main\",\"commit_hash\":\"abcdef1\"}").statusCode());
        assertEquals(403, send("POST", deploymentUrl + "/scale", readOnly, "{\"replicas\":2}").statusCode());
        assertEquals(403, send("POST", deploymentUrl + "/restart", readOnly, null).statusCode());
        assertEquals(403, send("PUT", "/api/v1/repositories/" + repo.getId() + "/config", readOnly,
                "{\"min_replicas\":1,\"max_replicas\":2,\"container_port\":8080}").statusCode());
        assertEquals(403, send("DELETE", "/api/v1/repositories/" + repo.getId(), readOnly, null).statusCode());
        assertEquals(403, send("POST", "/api/v1/nlp/command", readOnly, "{\"command\":\"restart\"}").statusCode());
        assertEquals(403, send("POST", "/api/v1/nlp/confirm", readOnly,
                "{\"command_log_id\":1,\"confirmed\":true}").statusCode());
        // 토큰 발급·폐기로 권한을 넓히거나 다른 토큰을 끊을 수 없다
        assertEquals(403, send("POST", "/api/v1/cli-tokens", readOnly,
                "{\"name\":\"escalate\",\"expires_in_days\":1,\"scope\":\"FULL\"}").statusCode());
        assertEquals(403, send("DELETE", "/api/v1/cli-tokens/" + readOnlyId, readOnly, null).statusCode());

        verifyNoInteractions(pipeline, gemini);
        verify(generator, never()).scale(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt(), anyLong());
        verify(generator, never()).restart(org.mockito.ArgumentMatchers.anyString(), anyLong());
        assertEquals(1, deployments.count());
        assertEquals(1, cliTokens.listTokens(owner.getId()).size());
    }

    @Test
    void webLoginIssuesRequestedScopeAndReadOnlyTokenCannotApproveLogins() throws Exception {
        User owner = users.save(User.builder().name("web").email("scope-web@example.test").role(Role.USER).build());
        String webJwt = jwt.createAccessToken(owner.getId(), owner.getEmail(), owner.getRole());
        String readOnly = cliTokens.createToken(owner.getId(),
                new CreateCliAccessTokenRequest("read", 1, CliTokenScope.READ_ONLY)).token();

        JsonNode escalation = body(send("POST", "/api/v1/cli-auth/sessions", null, sessionBody("FULL")));
        String escalationId = escalation.path("data").path("session_id").asText();
        assertEquals(403, send("POST", "/api/v1/cli-auth/sessions/" + escalationId + "/approve", readOnly, null).statusCode());
        assertEquals(CliAuthSessionStatus.PENDING, sessions.findById(escalationId).orElseThrow().getStatus());

        JsonNode session = body(send("POST", "/api/v1/cli-auth/sessions", null, sessionBody("READ_ONLY")));
        String sessionId = session.path("data").path("session_id").asText();
        assertEquals("READ_ONLY", session.path("data").path("scope").asText());
        assertEquals(200, send("POST", "/api/v1/cli-auth/sessions/" + sessionId + "/approve", webJwt, null).statusCode());
        JsonNode exchanged = body(send("POST", "/api/v1/cli-auth/sessions/" + sessionId + "/exchange", null,
                "{\"user_code\":\"" + session.path("data").path("user_code").asText() + "\"}"));

        assertEquals("READ_ONLY", exchanged.path("data").path("metadata").path("scope").asText());
        String issued = exchanged.path("data").path("token").asText();
        assertEquals(200, send("GET", "/api/v1/cli-tokens", issued, null).statusCode());
        assertEquals(403, send("POST", "/api/v1/deployments", issued, "{}").statusCode());
    }

    private String sessionBody(String scope) {
        return "{\"client_name\":\"KLEPaaS CLI\",\"hostname\":\"host\",\"platform\":\"linux\",\"cli_version\":\"1.0.0\","
                + "\"scope\":\"" + scope + "\"}";
    }

    private JsonNode body(HttpResponse<String> response) throws Exception {
        assertEquals(true, response.statusCode() < 300, response.body());
        return mapper.readTree(response.body());
    }

    private HttpResponse<String> send(String method, String path, String token, String json) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json))
                .header("Content-Type", "application/json");
        if (token != null) request.header("Authorization", "Bearer " + token);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
