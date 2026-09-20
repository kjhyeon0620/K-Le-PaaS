package klepaas.backend.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.client.KubernetesClient;
import klepaas.backend.ai.client.GeminiClient;
import klepaas.backend.ai.client.dto.GeminiResponse;
import klepaas.backend.auth.jwt.JwtTokenProvider;
import klepaas.backend.auth.oauth.GitHubAppClient;
import klepaas.backend.auth.token.dto.CreateCliAccessTokenRequest;
import klepaas.backend.auth.token.service.CliAccessTokenService;
import klepaas.backend.deployment.entity.CloudVendor;
import klepaas.backend.deployment.entity.Deployment;
import klepaas.backend.deployment.entity.DeploymentConfig;
import klepaas.backend.deployment.entity.SourceRepository;
import klepaas.backend.deployment.repository.DeploymentConfigRepository;
import klepaas.backend.deployment.repository.DeploymentRepository;
import klepaas.backend.deployment.repository.SourceRepositoryRepository;
import klepaas.backend.deployment.service.DeploymentPipelineService;
import klepaas.backend.deployment.service.RuntimeResourcePolicy;
import klepaas.backend.infra.kubernetes.KubernetesManifestGenerator;
import klepaas.backend.user.entity.Role;
import klepaas.backend.user.entity.User;
import klepaas.backend.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "jwt.secret=stage2-local-jwt-fixture-key-at-least-thirty-two-bytes",
        "cloud.ncp.credentials.access-key=test-only",
        "cloud.ncp.credentials.secret-key=test-only",
        "cloud.ncp.storage.bucket=test-only",
        "cloud.ncp.container-registry.endpoint=test-only",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.open-in-view=false",
        "spring.main.lazy-initialization=false"
})
@ActiveProfiles("test")
class Stage2HttpAccessTest {
    private static final Path EVIDENCE = Path.of("../.local/evidence/multi-project-deploy/2/2026-09-20/http-stage2-access.txt");

    @DynamicPropertySource
    static void isolatedDatabase(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> "jdbc:h2:mem:stage2_http_" + DATABASE_ID + ";DB_CLOSE_DELAY=-1");
    }

    private static final String DATABASE_ID = UUID.randomUUID().toString().replace("-", "");

    @Value("${local.server.port}") private int port;
    @Autowired private ObjectMapper mapper;
    @Autowired private UserRepository users;
    @Autowired private SourceRepositoryRepository repositories;
    @Autowired private DeploymentConfigRepository configs;
    @Autowired private DeploymentRepository deployments;
    @Autowired private RuntimeResourcePolicy resourcePolicy;
    @Autowired private JwtTokenProvider jwt;
    @Autowired private CliAccessTokenService cliTokens;

    @MockitoBean private GitHubAppClient github;
    @MockitoBean private GeminiClient gemini;
    @MockitoBean private KubernetesClient kubernetes;
    @MockitoBean private KubernetesManifestGenerator generator;
    @MockitoBean private DeploymentPipelineService pipeline;
    @MockitoBean private S3Client s3;

    private final HttpClient http = HttpClient.newHttpClient();
    private final StringBuilder evidence = new StringBuilder("Stage 2: RANDOM_PORT real HTTP -> Spring Security -> controllers -> services -> in-memory H2\n");

    @AfterEach
    void captureEvidence() throws Exception {
        Files.createDirectories(EVIDENCE.getParent());
        Files.writeString(EVIDENCE, evidence.toString());
    }

    @Test
    void ownerAndStrangerAreSeparatedAtRealHttpBoundary() throws Exception {
        User a = users.save(User.builder().name("A").email("a-stage2@example.test").role(Role.USER).build());
        User b = users.save(User.builder().name("B").email("b-stage2@example.test").role(Role.USER).build());
        SourceRepository repo = repositories.save(SourceRepository.builder().user(a).owner("a-stage2")
                .repoName("service").gitUrl("https://example.test/a/service.git").cloudVendor(CloudVendor.NCP).build());
        configs.save(DeploymentConfig.builder().sourceRepository(repo).minReplicas(1).maxReplicas(2)
                .containerPort(8080).domainUrl("a-stage2.example.test").build());
        Deployment deployment = deployments.save(Deployment.builder().sourceRepository(repo).branchName("main")
                .commitHash("abcdef0").build());

        RuntimeResourcePolicy.AllowedReferences allowed = new RuntimeResourcePolicy.AllowedReferences();
        allowed.setConfigMaps(List.of("runtime"));
        allowed.setSecrets(List.of("app-env"));
        allowed.setImagePullSecrets(List.of("ncp-cr"));
        resourcePolicy.setRepositories(Map.of(repo.getId(), allowed));

        String aJwt = jwt.createAccessToken(a.getId(), a.getEmail(), a.getRole());
        String bJwt = jwt.createAccessToken(b.getId(), b.getEmail(), b.getRole());
        String bCli = cliTokens.createToken(b.getId(), new CreateCliAccessTokenRequest("stage2-test", 1)).token();
        String repoUrl = "/api/v1/repositories/" + repo.getId();
        String deploymentUrl = "/api/v1/deployments/" + deployment.getId();
        String configBody = "{\"min_replicas\":1,\"max_replicas\":2,\"container_port\":8080,"
                + "\"env_from_config_maps\":[\"runtime\"],\"env_from_secrets\":[\"app-env\"],"
                + "\"image_pull_secret_name\":\"ncp-cr\"}";

        assertEquals(repo.getId().longValue(), ok("A JWT own repository", send("GET", repoUrl, aJwt, null))
                .path("data").path("id").asLong());
        assertEquals(repo.getId().longValue(), ok("A JWT own config", send("GET", repoUrl + "/config", aJwt, null))
                .path("data").path("repository_id").asLong());
        assertEquals(deployment.getId().longValue(), ok("A JWT own deployment", send("GET", deploymentUrl, aJwt, null))
                .path("data").path("id").asLong());
        ok("A JWT own deployment status", send("GET", deploymentUrl + "/status", aJwt, null));
        assertEquals(deployment.getId().longValue(), ok("A JWT own logs", send("GET", deploymentUrl + "/logs", aJwt, null))
                .path("data").path("deployment_id").asLong());
        ok("A JWT own deployment list", send("GET", "/api/v1/deployments?repositoryId=" + repo.getId(), aJwt, null));
        ok("A JWT allowed config references", send("PUT", repoUrl + "/config", aJwt, configBody));

        for (String token : List.of(bJwt, bCli)) {
            String identity = token.equals(bCli) ? "B CLI" : "B JWT";
            hidden(identity + " repository", send("GET", repoUrl, token, null));
            hidden(identity + " repository config", send("GET", repoUrl + "/config", token, null));
            hidden(identity + " deployment", send("GET", deploymentUrl, token, null));
            hidden(identity + " deployment status", send("GET", deploymentUrl + "/status", token, null));
            hidden(identity + " deployment logs", send("GET", deploymentUrl + "/logs", token, null));
            hidden(identity + " repository deployments", send("GET", "/api/v1/deployments?repositoryId=" + repo.getId(), token, null));
            hidden(identity + " scaling history", send("GET", repoUrl + "/scaling-history", token, null));
            hidden(identity + " update config", send("PUT", repoUrl + "/config", token, configBody));
            hidden(identity + " scale", send("POST", deploymentUrl + "/scale", token, "{\"replicas\":3}"));
            hidden(identity + " restart", send("POST", deploymentUrl + "/restart", token, "{}"));
            hidden(identity + " create deployment", send("POST", "/api/v1/deployments", token,
                    "{\"repository_id\":" + repo.getId() + ",\"branch_name\":\"main\",\"commit_hash\":\"HEAD\"}"));
            hidden(identity + " delete repository", send("DELETE", repoUrl, token, null));
        }
        assertEquals(1, deployments.count(), "B cannot create deployments");
        assertTrue(repositories.existsById(repo.getId()), "B cannot delete A's repository");
        assertEquals(List.of("runtime"), configs.findBySourceRepositoryId(repo.getId()).orElseThrow().getEnvFromConfigMaps());
        verifyNoInteractions(generator, pipeline);

        reject("A JWT unauthorized config-map reference", 400, send("PUT", repoUrl + "/config", aJwt,
                configBody.replace("runtime", "foreign")));
        reject("A JWT unauthorized secret reference", 400, send("PUT", repoUrl + "/config", aJwt,
                configBody.replace("app-env", "foreign-secret")));
        reject("A JWT unauthorized image pull secret", 400, send("PUT", repoUrl + "/config", aJwt,
                configBody.replace("ncp-cr", "foreign-pull")));
        assertEquals(List.of("runtime"), configs.findBySourceRepositoryId(repo.getId()).orElseThrow().getEnvFromConfigMaps());
        reject("anonymous repository", 401, send("GET", repoUrl, null, null));
        reject("anonymous deployment", 401, send("GET", deploymentUrl, null, null));
        reject("anonymous NLP", 401, send("POST", "/api/v1/nlp/confirm", null,
                "{\"command_log_id\":1,\"confirmed\":true}"));

        JsonNode created = send("POST", "/api/v1/deployments", aJwt,
                "{\"repository_id\":" + repo.getId() + ",\"branch_name\":\"main\",\"commit_hash\":\"HEAD\"}");
        reject("A JWT create own deployment", 201, created);
        long createdId = created.path("data").path("id").asLong();
        assertTrue(createdId > deployment.getId());
        assertEquals(repo.getId().longValue(), created.path("data").path("repository_id").asLong());
        assertEquals(repo.getId(), deployments.findById(createdId).orElseThrow().getSourceRepository().getId());
        verify(pipeline).executePipeline(createdId);

        when(gemini.generate(any())).thenReturn(new GeminiResponse(List.of(new GeminiResponse.Candidate(
                new GeminiResponse.Content(List.of(new GeminiResponse.Part(
                        "{\"intent\":\"SCALE\",\"args\":{\"deployment_id\":" + deployment.getId()
                                + ",\"replicas\":3},\"confidence\":1,\"message\":\"Scale\"}")), "model")))));
        JsonNode command = ok("A JWT create pending NLP command", send("POST", "/api/v1/nlp/command", aJwt,
                "{\"command\":\"scale to three\"}")).path("data");
        long commandId = command.path("command_log_id").asLong();
        assertTrue(commandId > 0);
        String sessionId = command.path("session_id").asText();
        assertFalse(sessionId.isBlank());
        hidden("B JWT cannot reuse A NLP session", send("POST", "/api/v1/nlp/command", bJwt,
                mapper.writeValueAsString(Map.of("command", "scale to three", "session_id", sessionId))));
        hidden("B CLI cannot confirm A command", send("POST", "/api/v1/nlp/confirm", bCli,
                confirmBody(commandId, true)));
        verifyNoInteractions(generator);

        JsonNode cancelled = ok("A JWT cancel pending command", send("POST", "/api/v1/nlp/confirm", aJwt,
                confirmBody(commandId, false)));
        assertEquals(commandId, cancelled.path("data").path("command_log_id").asLong());
        reject("A JWT cannot confirm cancelled command", 409,
                send("POST", "/api/v1/nlp/confirm", aJwt, confirmBody(commandId, true)));
        verifyNoInteractions(generator);

        long concurrentId = ok("A JWT create second pending NLP command", send("POST", "/api/v1/nlp/command", aJwt,
                "{\"command\":\"scale to three\"}")).path("data").path("command_log_id").asLong();
        assertTrue(concurrentId > commandId);
        CountDownLatch start = new CountDownLatch(1);
        CompletableFuture<JsonNode> first = CompletableFuture.supplyAsync(() -> confirmAfter(start, aJwt, concurrentId));
        CompletableFuture<JsonNode> second = CompletableFuture.supplyAsync(() -> confirmAfter(start, aJwt, concurrentId));
        start.countDown();
        int firstStatus = first.get(20, TimeUnit.SECONDS).path("_http_status").asInt();
        int secondStatus = second.get(20, TimeUnit.SECONDS).path("_http_status").asInt();
        assertEquals(List.of(200, 409), List.of(firstStatus, secondStatus).stream().sorted().toList());
        evidence.append("A concurrent confirmations: statuses ").append(firstStatus).append(',').append(secondStatus).append('\n');
        verify(generator, times(1)).scale(eq("a-stage2-service"), eq(3), eq(repo.getId()));
    }

    private JsonNode confirmAfter(CountDownLatch start, String token, long commandId) {
        try {
            start.await();
            return send("POST", "/api/v1/nlp/confirm", token, confirmBody(commandId, true));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String confirmBody(long id, boolean confirmed) {
        return "{\"command_log_id\":" + id + ",\"confirmed\":" + confirmed + "}";
    }

    private JsonNode send(String method, String path, String bearer, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (bearer != null) builder.header("Authorization", "Bearer " + bearer);
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        JsonNode result;
        try {
            result = response.body().isBlank() ? mapper.createObjectNode() : mapper.readTree(response.body());
        } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) {
            result = mapper.createObjectNode();
        }
        ((com.fasterxml.jackson.databind.node.ObjectNode) result).put("_http_status", response.statusCode());
        return result;
    }

    private JsonNode ok(String scenario, JsonNode response) {
        return reject(scenario, 200, response);
    }

    private void hidden(String scenario, JsonNode response) {
        reject(scenario, 404, response);
    }

    private JsonNode reject(String scenario, int status, JsonNode response) {
        int actual = response.path("_http_status").asInt();
        evidence.append(scenario).append(": HTTP ").append(actual)
                .append(" code=").append(response.path("code").asText("-")).append('\n');
        assertEquals(status, actual, scenario);
        return response;
    }
}
