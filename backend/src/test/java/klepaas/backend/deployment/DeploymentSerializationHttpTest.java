package klepaas.backend.deployment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.client.KubernetesClient;
import klepaas.backend.ai.client.GeminiClient;
import klepaas.backend.auth.jwt.JwtTokenProvider;
import klepaas.backend.auth.oauth.GitHubAppClient;
import klepaas.backend.deployment.entity.CloudVendor;
import klepaas.backend.deployment.entity.Deployment;
import klepaas.backend.deployment.entity.DeploymentStatus;
import klepaas.backend.deployment.entity.SourceRepository;
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
import org.springframework.core.task.TaskRejectedException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import software.amazon.awssdk.services.s3.S3Client;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;

/**
 * 같은 저장소의 배포 직렬화와 webhook·CI 재시도 중복 제거를 실제 HTTP와 H2로 확인한다 (#52).
 * 파이프라인은 실행하지 않으므로 생성된 배포는 PENDING(진행 중)으로 남는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "jwt.secret=serialize-local-jwt-fixture-key-at-least-thirty-two-bytes",
        "github.webhook.secret=serialize-webhook-secret",
        "spring.jpa.open-in-view=false"
})
@ActiveProfiles("test")
class DeploymentSerializationHttpTest {

    private static final String DATABASE_ID = UUID.randomUUID().toString().replace("-", "");

    @DynamicPropertySource
    static void isolatedDatabase(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> "jdbc:h2:mem:serialize_http_" + DATABASE_ID + ";DB_CLOSE_DELAY=-1");
    }

    @Value("${local.server.port}") private int port;
    @Autowired private ObjectMapper mapper;
    @Autowired private UserRepository users;
    @Autowired private SourceRepositoryRepository repositories;
    @Autowired private DeploymentRepository deployments;
    @Autowired private JwtTokenProvider jwt;
    @Autowired private JdbcTemplate jdbc;

    @MockitoBean private GitHubAppClient github;
    @MockitoBean private GeminiClient gemini;
    @MockitoBean private KubernetesClient kubernetes;
    @MockitoBean private KubernetesManifestGenerator generator;
    @MockitoBean private DeploymentPipelineService pipeline;
    @MockitoBean private S3Client s3;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void concurrentRequestsForOneRepositoryCreateOneDeploymentWithoutBlockingOtherRepositories() throws Exception {
        User owner = owner("concurrent");
        SourceRepository repo = repository(owner, "concurrent-a");
        SourceRepository other = repository(owner, "concurrent-b");
        String token = jwt.createAccessToken(owner.getId(), owner.getEmail(), owner.getRole());

        CyclicBarrier start = new CyclicBarrier(3);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            List<Future<HttpResponse<String>>> responses = pool.invokeAll(List.<Callable<HttpResponse<String>>>of(
                    () -> { start.await(); return deploy(token, repo, "aaaaaaa", null); },
                    () -> { start.await(); return deploy(token, repo, "bbbbbbb", null); },
                    () -> { start.await(); return deploy(token, other, "ccccccc", null); }));

            assertThat(List.of(responses.get(0).get().statusCode(), responses.get(1).get().statusCode()))
                    .containsExactlyInAnyOrder(201, 409);
            assertThat(responses.get(2).get().statusCode()).isEqualTo(201);
        } finally {
            pool.shutdownNow();
        }
        assertThat(countFor(repo)).isEqualTo(1);
        assertThat(countFor(other)).isEqualTo(1);
    }

    @Test
    void retryOfInProgressRequestReturnsSameDeploymentAndConflictNamesIt() throws Exception {
        User owner = owner("retry");
        SourceRepository repo = repository(owner, "retry");
        String token = jwt.createAccessToken(owner.getId(), owner.getEmail(), owner.getRole());
        String image = "ghcr.io/retry/app:sha-abcdef1";

        HttpResponse<String> first = deploy(token, repo, "abcdef1", image);
        HttpResponse<String> retry = deploy(token, repo, "abcdef1", image);
        HttpResponse<String> different = deploy(token, repo, "abcdef1", "ghcr.io/retry/app:sha-other");

        long firstId = id(first);
        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(retry.statusCode()).isEqualTo(200);
        assertThat(id(retry)).isEqualTo(firstId);
        assertThat(different.statusCode()).isEqualTo(409);
        assertThat(mapper.readTree(different.body()).path("code").asText()).isEqualTo("DEPLOY_003");
        assertThat(mapper.readTree(different.body()).path("message").asText()).contains("deployment_id=" + firstId);

        complete(firstId);
        HttpResponse<String> redeploy = deploy(token, repo, "abcdef1", image);
        assertThat(redeploy.statusCode()).isEqualTo(201);
        assertThat(id(redeploy)).isNotEqualTo(firstId);
    }

    @Test
    void staleUnfinishedDeploymentDoesNotBlockAndKeepsItsStatus() throws Exception {
        User owner = owner("stale");
        SourceRepository repo = repository(owner, "stale");
        Deployment stuck = deployments.save(Deployment.builder().sourceRepository(repo).branchName("main")
                .commitHash("aaaaaaa").build());
        jdbc.update("update deployments set status = 'DEPLOYING', updated_at = ? where id = ?",
                LocalDateTime.now().minusHours(2), stuck.getId());
        String token = jwt.createAccessToken(owner.getId(), owner.getEmail(), owner.getRole());

        assertThat(deploy(token, repo, "bbbbbbb", null).statusCode()).isEqualTo(201);
        assertThat(deployments.findById(stuck.getId()).orElseThrow().getStatus()).isEqualTo(DeploymentStatus.DEPLOYING);
    }

    @Test
    void rejectedPipelineMarksDeploymentFailedSoItDoesNotBlock() throws Exception {
        User owner = owner("rejected");
        SourceRepository repo = repository(owner, "rejected");
        String token = jwt.createAccessToken(owner.getId(), owner.getEmail(), owner.getRole());
        doThrow(new TaskRejectedException("queue full")).when(pipeline).executePipeline(anyLong());

        HttpResponse<String> response = deploy(token, repo, "aaaaaaa", null);

        assertThat(response.statusCode()).isEqualTo(201);
        Deployment rejected = deployments.findById(id(response)).orElseThrow();
        assertThat(rejected.getStatus()).isEqualTo(DeploymentStatus.FAILED);
        assertThat(rejected.getFailReason()).contains("대기열");
    }

    @Test
    void webhookDeliveryCreatesOneDeploymentAndConflictingPushCanBeRedelivered() throws Exception {
        User owner = owner("hook");
        SourceRepository repo = repository(owner, "hook");
        String delivery = UUID.randomUUID().toString();
        String push = pushPayload(repo, "aaaaaaa");

        CyclicBarrier start = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<HttpResponse<String>>> responses = pool.invokeAll(List.<Callable<HttpResponse<String>>>of(
                    () -> { start.await(); return webhook(push, delivery); },
                    () -> { start.await(); return webhook(push, delivery); }));
            assertThat(responses.get(0).get().statusCode()).isEqualTo(200);
            assertThat(responses.get(1).get().statusCode()).isEqualTo(200);
        } finally {
            pool.shutdownNow();
        }
        assertThat(countFor(repo)).isEqualTo(1);
        long firstId = deployments.findByGithubDeliveryId(delivery).orElseThrow().getId();

        String laterDelivery = UUID.randomUUID().toString();
        String laterPush = pushPayload(repo, "bbbbbbb");
        assertThat(webhook(laterPush, laterDelivery).statusCode()).isEqualTo(409);
        assertThat(deployments.findByGithubDeliveryId(laterDelivery)).isEmpty();

        complete(firstId);
        assertThat(webhook(laterPush, laterDelivery).statusCode()).isEqualTo(200);
        assertThat(countFor(repo)).isEqualTo(2);
        // 완료 후 첫 delivery를 다시 보내도 예전 커밋을 다시 배포하지 않는다
        assertThat(webhook(push, delivery).statusCode()).isEqualTo(200);
        assertThat(countFor(repo)).isEqualTo(2);
    }

    private User owner(String name) {
        return users.save(User.builder().name(name).email(name + "@serialize.example.test").role(Role.USER).build());
    }

    private SourceRepository repository(User owner, String name) {
        return repositories.save(SourceRepository.builder().user(owner).owner("serialize").repoName(name)
                .gitUrl("https://example.test/serialize/" + name + ".git").cloudVendor(CloudVendor.NCP).build());
    }

    private long countFor(SourceRepository repo) {
        return jdbc.queryForObject("select count(*) from deployments where repository_id = ?", Long.class, repo.getId());
    }

    private void complete(long deploymentId) {
        jdbc.update("update deployments set status = 'SUCCESS' where id = ?", deploymentId);
    }

    private long id(HttpResponse<String> response) throws Exception {
        JsonNode body = mapper.readTree(response.body());
        return body.path("data").path("id").asLong();
    }

    private HttpResponse<String> deploy(String token, SourceRepository repo, String commit, String image) throws Exception {
        String json = "{\"repository_id\":" + repo.getId() + ",\"branch_name\":\"main\",\"commit_hash\":\"" + commit + "\""
                + (image == null ? "" : ",\"image_uri\":\"" + image + "\"") + "}";
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/deployments"))
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token).build(), HttpResponse.BodyHandlers.ofString());
    }

    private String pushPayload(SourceRepository repo, String commit) {
        return "{\"ref\":\"refs/heads/main\",\"after\":\"" + commit + "\",\"repository\":{\"name\":\""
                + repo.getRepoName() + "\",\"owner\":{\"login\":\"" + repo.getOwner() + "\"}}}";
    }

    private HttpResponse<String> webhook(String payload, String delivery) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("serialize-webhook-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = "sha256=" + HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/webhooks/github"))
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .header("Content-Type", "application/json")
                .header("X-GitHub-Event", "push")
                .header("X-GitHub-Delivery", delivery)
                .header("X-Hub-Signature-256", signature).build(), HttpResponse.BodyHandlers.ofString());
    }
}
