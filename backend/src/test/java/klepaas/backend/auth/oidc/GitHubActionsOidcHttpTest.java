package klepaas.backend.auth.oidc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import io.fabric8.kubernetes.client.KubernetesClient;
import klepaas.backend.ai.client.GeminiClient;
import klepaas.backend.auth.oauth.GitHubAppClient;
import klepaas.backend.deployment.entity.CloudVendor;
import klepaas.backend.deployment.entity.Deployment;
import klepaas.backend.deployment.entity.SourceRepository;
import klepaas.backend.deployment.repository.DeploymentRepository;
import klepaas.backend.deployment.repository.SourceRepositoryRepository;
import klepaas.backend.deployment.service.DeploymentPipelineService;
import klepaas.backend.infra.kubernetes.KubernetesManifestGenerator;
import klepaas.backend.user.entity.Role;
import klepaas.backend.user.entity.User;
import klepaas.backend.user.repository.UserRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GitHub Actions OIDC 토큰 인증을 실제 HTTP 경계에서 확인한다 (#65).
 * 로컬 HTTP 서버가 테스트 JWKS를 내주고, 테스트가 같은 키로 토큰을 서명한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "jwt.secret=oidc-local-jwt-fixture-key-at-least-thirty-two-bytes",
        "github.actions.oidc.audience=k-le-paas-test",
        "spring.jpa.open-in-view=false"
})
@ActiveProfiles("test")
class GitHubActionsOidcHttpTest {

    private static final String DATABASE_ID = UUID.randomUUID().toString().replace("-", "");
    private static final String SHA = "0123456789abcdef0123456789abcdef01234567";
    private static final RSAKey KEY = generateKey("github-test-key");
    private static final RSAKey FORGED_KEY = generateKey("github-test-key");
    private static final HttpServer JWKS_SERVER = startJwksServer();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> "jdbc:h2:mem:oidc_http_" + DATABASE_ID + ";DB_CLOSE_DELAY=-1");
        properties.add("github.actions.oidc.jwks-uri",
                () -> "http://127.0.0.1:" + JWKS_SERVER.getAddress().getPort() + "/jwks");
    }

    @AfterAll
    static void stopJwksServer() {
        JWKS_SERVER.stop(0);
    }

    @Value("${local.server.port}") private int port;
    @Autowired private ObjectMapper mapper;
    @Autowired private UserRepository users;
    @Autowired private SourceRepositoryRepository repositories;
    @Autowired private DeploymentRepository deployments;

    @MockitoBean private GitHubAppClient github;
    @MockitoBean private GeminiClient gemini;
    @MockitoBean private KubernetesClient kubernetes;
    @MockitoBean private KubernetesManifestGenerator generator;
    @MockitoBean private DeploymentPipelineService pipeline;
    @MockitoBean private S3Client s3;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void validTokenDeploysAsTheRegisteredRepositoryOwner() throws Exception {
        SourceRepository repo = repository("valid", "Owner-Valid", "Service");

        HttpResponse<String> response = deploy(token(claims("owner-valid/service")), repo.getId(), "main", SHA);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        long id = mapper.readTree(response.body()).path("data").path("id").asLong();
        Deployment created = deployments.findById(id).orElseThrow();
        assertThat(created.getSourceRepository().getId()).isEqualTo(repo.getId());
        assertThat(created.getCommitHash()).isEqualTo(SHA);
    }

    @Test
    void invalidTokensAreUnauthorizedAndCreateNothing() throws Exception {
        SourceRepository repo = repository("invalid", "owner-invalid", "service");
        long before = deployments.count();
        Instant past = Instant.now().minusSeconds(3600);

        Map<String, String> rejected = Map.of(
                "forged signature", sign(claims("owner-invalid/service").build(), FORGED_KEY),
                "expired", token(claims("owner-invalid/service").issueTime(Date.from(past.minusSeconds(600)))
                        .expirationTime(Date.from(past))),
                "wrong audience", token(claims("owner-invalid/service").audience("sts.amazonaws.com")),
                "wrong issuer", token(claims("owner-invalid/service").issuer("https://example.test")),
                "other branch", token(claims("owner-invalid/service").claim("ref", "refs/heads/feature")),
                "pull request", token(claims("owner-invalid/service").claim("ref", "refs/pull/1/merge")),
                "missing sha", token(claims("owner-invalid/service").claim("sha", null)));

        for (Map.Entry<String, String> entry : rejected.entrySet()) {
            assertThat(deploy(entry.getValue(), repo.getId(), "main", SHA).statusCode())
                    .as(entry.getKey()).isEqualTo(401);
        }
        assertThat(deployments.count()).isEqualTo(before);
    }

    @Test
    void requestThatDoesNotMatchTheTokenIsForbiddenAndCreatesNothing() throws Exception {
        SourceRepository repo = repository("mismatch", "owner-mismatch", "service");
        SourceRepository otherUsersRepo = repository("mismatch-other", "owner-mismatch", "other");
        String token = token(claims("owner-mismatch/service"));
        long before = deployments.count();

        assertThat(deploy(token, otherUsersRepo.getId(), "main", SHA).statusCode()).as("other repository").isEqualTo(403);
        assertThat(deploy(token, 999_999L, "main", SHA).statusCode()).as("unknown repository").isEqualTo(403);
        assertThat(deploy(token, repo.getId(), "develop", SHA).statusCode()).as("branch").isEqualTo(403);
        assertThat(deploy(token, repo.getId(), "main", "fedcba9").statusCode()).as("commit").isEqualTo(403);
        assertThat(deployments.count()).isEqualTo(before);
    }

    @Test
    void githubActionsPrincipalCanOnlyCreateDeployments() throws Exception {
        SourceRepository repo = repository("only", "owner-only", "service");
        String token = token(claims("owner-only/service"));

        assertThat(send("GET", "/api/v1/deployments?repositoryId=" + repo.getId(), token).statusCode()).isEqualTo(403);
        assertThat(send("GET", "/api/v1/auth/me", token).statusCode()).isEqualTo(403);
        assertThat(send("GET", "/api/v1/users/me", token).statusCode()).isEqualTo(403);
        assertThat(send("POST", "/api/v1/deployments/1/restart", token).statusCode()).isEqualTo(403);
    }

    private SourceRepository repository(String userName, String owner, String name) {
        User user = users.save(User.builder().name(userName).email(userName + "@oidc.example.test").role(Role.USER).build());
        return repositories.save(SourceRepository.builder().user(user).owner(owner).repoName(name)
                .gitUrl("https://github.com/" + owner + "/" + name).cloudVendor(CloudVendor.ON_PREMISE).build());
    }

    private static JWTClaimsSet.Builder claims(String repository) {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder()
                .issuer(GitHubActionsTokenVerifier.ISSUER)
                .audience("k-le-paas-test")
                .subject("repo:" + repository + ":ref:refs/heads/main")
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(300)))
                .claim("repository", repository)
                .claim("ref", "refs/heads/main")
                .claim("sha", SHA);
    }

    private static String token(JWTClaimsSet.Builder claims) throws Exception {
        return sign(claims.build(), KEY);
    }

    private static String sign(JWTClaimsSet claims, RSAKey key) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID())
                .type(JOSEObjectType.JWT).build(), claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    private HttpResponse<String> deploy(String token, Long repositoryId, String branch, String commit) throws Exception {
        String json = "{\"repository_id\":" + repositoryId + ",\"branch_name\":\"" + branch + "\",\"commit_hash\":\""
                + commit + "\",\"image_uri\":\"ghcr.io/test/app:sha-" + commit.substring(0, 7) + "\"}";
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/deployments"))
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> send(String method, String path, String token) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .method(method, HttpRequest.BodyPublishers.noBody())
                .header("Authorization", "Bearer " + token).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static RSAKey generateKey(String keyId) {
        try {
            return new RSAKeyGenerator(2048).keyID(keyId).generate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static HttpServer startJwksServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            byte[] body = new JWKSet(KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            server.createContext("/jwks", exchange -> {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return server;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
