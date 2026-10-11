package klepaas.backend.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import klepaas.backend.ai.client.GeminiClient;
import klepaas.backend.ai.entity.CommandStatus;
import klepaas.backend.ai.repository.CommandLogRepository;
import klepaas.backend.auth.oauth.GitHubAppClient;
import klepaas.backend.auth.token.dto.CreateCliAccessTokenRequest;
import klepaas.backend.auth.token.entity.CliTokenScope;
import klepaas.backend.auth.token.service.CliAccessTokenService;
import klepaas.backend.deployment.dto.DeploymentLogResponse;
import klepaas.backend.deployment.entity.BuildStrategy;
import klepaas.backend.deployment.entity.CloudVendor;
import klepaas.backend.deployment.entity.Deployment;
import klepaas.backend.deployment.entity.DeploymentConfig;
import klepaas.backend.deployment.entity.SourceRepository;
import klepaas.backend.deployment.repository.DeploymentConfigRepository;
import klepaas.backend.deployment.repository.DeploymentRepository;
import klepaas.backend.deployment.repository.SourceRepositoryRepository;
import klepaas.backend.deployment.service.DeploymentConfigSnapshots;
import klepaas.backend.deployment.service.DeploymentPipelineService;
import klepaas.backend.infra.kubernetes.DeploymentObservationReader;
import klepaas.backend.infra.kubernetes.KubernetesManifestGenerator;
import klepaas.backend.user.entity.Role;
import klepaas.backend.user.entity.User;
import klepaas.backend.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * MCP 서버 (#103): 공식 SDK 클라이언트 → 실제 HTTP → Spring Security → 도구 → 서비스 → PostgreSQL.
 * 도구는 REST와 같은 서비스·scope 규칙을 쓰고, 제안 도구는 승인 대기 명령만 만든다.
 */
// Stage2HttpAccessTest와 같은 설정·mock으로 Spring 컨텍스트를 공유한다 (테스트 DB 연결 수를 늘리지 않는다)
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
class McpServerHttpTest {

    @Value("${local.server.port}") private int port;
    @Autowired private UserRepository users;
    @Autowired private SourceRepositoryRepository repositories;
    @Autowired private DeploymentConfigRepository configs;
    @Autowired private DeploymentRepository deployments;
    @Autowired private DeploymentConfigSnapshots snapshots;
    @Autowired private CommandLogRepository commandLogs;
    @Autowired private CliAccessTokenService cliTokens;
    @Autowired private JdbcTemplate jdbc;

    @MockitoBean private GitHubAppClient github;
    @MockitoBean private GeminiClient gemini;
    @MockitoBean private KubernetesClient kubernetes;
    @MockitoBean private KubernetesManifestGenerator generator;
    @MockitoBean private DeploymentObservationReader observationReader;
    @MockitoBean private DeploymentPipelineService pipeline;
    @MockitoBean private S3Client s3;

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void agentQueriesAndProposesThroughMcpWithRestScopesAndOwnership() throws Exception {
        User owner = users.save(User.builder().name("owner").email("mcp-owner@example.test").role(Role.USER).build());
        User stranger = users.save(User.builder().name("stranger").email("mcp-stranger@example.test").role(Role.USER).build());
        SourceRepository repo = repositories.save(SourceRepository.builder().user(owner).owner("mcp").repoName("app")
                .gitUrl("https://example.test/mcp/app.git").cloudVendor(CloudVendor.ON_PREMISE).build());
        DeploymentConfig config = configs.save(DeploymentConfig.builder().sourceRepository(repo).minReplicas(1).maxReplicas(1)
                .containerPort(8080).domainUrl("mcp-app.example.test").buildStrategy(BuildStrategy.PREBUILT_IMAGE)
                .imagePullSecretEnabled(false).build());
        long success = deployment(repo, "aaaaaaa1", "ghcr.io/mcp/app:v1", "SUCCESS", null, config);
        long failed = deployment(repo, "bbbbbbb2", "ghcr.io/mcp/app:v1", "FAILED", "ROLLOUT_TIMEOUT", config);
        when(observationReader.observe(any(), any(), any(), anyInt()))
                .thenReturn(DeploymentLogResponse.Observed.notCurrent("현재 Deployment에 적용한 배포 요청 기록이 없습니다", null));
        String agent = token(owner, CliTokenScope.PROPOSE_ONLY, null);
        String readOnly = token(owner, CliTokenScope.READ_ONLY, null);
        String deploy = token(owner, CliTokenScope.DEPLOY, repo.getId());
        String strangerAgent = token(stranger, CliTokenScope.PROPOSE_ONLY, null);
        long deploymentsBefore = deployments.count();

        // 인증·scope 1차 검사는 Spring Security가 한다
        assertThat(rawPost(null).statusCode()).isEqualTo(401);
        assertThat(rawPost(deploy).statusCode()).isEqualTo(403);

        try (McpSyncClient client = client(agent)) {
            client.initialize();
            assertThat(client.listTools().tools()).extracting(McpSchema.Tool::name).containsExactlyInAnyOrder(
                    "list_repositories", "list_deployments", "get_deployment_detail", "get_deployment_logs",
                    "diagnose_deployment", "get_recovery_plan", "propose_recovery");

            assertThat(ok(client, "list_repositories", Map.of()).findValuesAsText("repo_name")).containsExactly("app");
            assertThat(ok(client, "list_deployments", Map.of("repository_id", repo.getId())).path("content"))
                    .extracting(n -> n.path("id").asLong()).containsExactlyInAnyOrder(success, failed);
            assertThat(ok(client, "get_deployment_detail", Map.of("deployment_id", failed))
                    .path("deployment").path("failure_kind").asText()).isEqualTo("ROLLOUT_TIMEOUT");
            assertThat(ok(client, "get_deployment_logs", Map.of("deployment_id", failed, "lines", 20))
                    .path("observation").asText()).isEqualTo("NOT_CURRENT");
            // Pod·이벤트·설정 변경 없는 ROLLOUT_TIMEOUT: 모델 없이 판단 보류 (#117)
            JsonNode diagnosis = ok(client, "diagnose_deployment", Map.of("deployment_id", failed));
            assertThat(diagnosis.path("model").asText()).isEqualTo("rule");
            assertThat(ok(client, "get_recovery_plan", Map.of("target_deployment_id", success))
                    .path("executable").asBoolean()).isTrue();

            JsonNode proposal = ok(client, "propose_recovery", Map.of("target_deployment_id", success, "reason", "v2 rollout 실패"));
            long commandId = proposal.path("command_log_id").asLong();
            assertThat(proposal.path("requires_confirmation").asBoolean()).isTrue();
            assertThat(commandLogs.findById(commandId).orElseThrow().getStatus()).isEqualTo(CommandStatus.PENDING);

            // 입력 스키마 검사 (reason 200자 이하), 범위 밖 lines
            assertThat(call(client, "propose_recovery", Map.of("target_deployment_id", success, "reason", "x".repeat(201))).isError()).isTrue();
            assertThat(call(client, "get_deployment_logs", Map.of("deployment_id", failed, "lines", 201)).isError()).isTrue();
        }

        try (McpSyncClient client = client(readOnly)) {
            client.initialize();
            assertThat(ok(client, "list_repositories", Map.of()).size()).isEqualTo(1);
            assertThat(error(client, "propose_recovery", Map.of("target_deployment_id", success))).isEqualTo("CLI_005");
        }

        try (McpSyncClient client = client(strangerAgent)) {
            client.initialize();
            assertThat(ok(client, "list_repositories", Map.of()).size()).isZero();
            assertThat(error(client, "get_deployment_detail", Map.of("deployment_id", failed))).isEqualTo("DEPLOY_001");
            assertThat(error(client, "propose_recovery", Map.of("target_deployment_id", success))).isEqualTo("DEPLOY_001");
        }

        // 이 사용자의 제안 1건만 남고 실행·배포는 없다 (진단은 model=rule로 모델을 부르지 않았다)
        assertThat(commandLogs.findAll()).filteredOn(c -> c.getUser().getId().equals(owner.getId())).hasSize(1);
        assertThat(deployments.count()).isEqualTo(deploymentsBefore);
        verifyNoInteractions(pipeline, generator);
    }

    private long deployment(SourceRepository repo, String commit, String image, String status, String failureKind, DeploymentConfig config) {
        Deployment d = Deployment.builder().sourceRepository(repo).branchName("main").commitHash(commit).build();
        d.setImageUri(image);
        d.recordAppliedConfig(snapshots.toJson(snapshots.capture(configs.findById(config.getId()).orElseThrow())));
        deployments.saveAndFlush(d);
        jdbc.update("update deployments set status = ?, failure_kind = ? where id = ?", status, failureKind, d.getId());
        return d.getId();
    }

    private String token(User user, CliTokenScope scope, Long repositoryId) {
        return cliTokens.createToken(user.getId(), new CreateCliAccessTokenRequest(scope.name(), 1, scope, repositoryId)).token();
    }

    private McpSyncClient client(String token) {
        var transport = HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + port)
                .endpoint(McpServerConfig.ENDPOINT)
                .requestBuilder(HttpRequest.newBuilder().header("Authorization", "Bearer " + token))
                .build();
        return McpClient.sync(transport).build();
    }

    private static McpSchema.CallToolResult call(McpSyncClient client, String tool, Map<String, Object> args) {
        return client.callTool(new McpSchema.CallToolRequest(tool, args));
    }

    private JsonNode ok(McpSyncClient client, String tool, Map<String, Object> args) throws Exception {
        McpSchema.CallToolResult result = call(client, tool, args);
        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        assertThat(result.isError()).as(tool + " " + text).isFalse();
        return json.readTree(text);
    }

    private String error(McpSyncClient client, String tool, Map<String, Object> args) throws Exception {
        McpSchema.CallToolResult result = call(client, tool, args);
        assertThat(result.isError()).as(tool).isTrue();
        return json.readTree(((McpSchema.TextContent) result.content().get(0)).text()).path("code").asText();
    }

    private HttpResponse<String> rawPost(String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + McpServerConfig.ENDPOINT))
                .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"))
                .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream");
        if (token != null) request.header("Authorization", "Bearer " + token);
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
