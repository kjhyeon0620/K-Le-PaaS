package klepaas.backend.webhook.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import klepaas.backend.deployment.dto.CreateDeploymentRequest;
import klepaas.backend.deployment.entity.SourceRepository;
import klepaas.backend.deployment.repository.SourceRepositoryRepository;
import klepaas.backend.deployment.service.DeploymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class GitHubWebhookService {

    @Value("${github.webhook.secret:}")
    private String webhookSecret;

    private final SourceRepositoryRepository sourceRepositoryRepository;
    private final DeploymentService deploymentService;
    private final ObjectMapper objectMapper;

    public boolean verifySignature(String payload, String signature) {
        if (webhookSecret == null || webhookSecret.isBlank()) {
            log.warn("GitHub webhook secret not configured. Rejecting request.");
            return false;
        }

        if (signature == null || signature.isBlank()) {
            return false;
        }

        try {
            String sigHex = signature.startsWith("sha256=") ? signature.substring(7) : signature;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] computed = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            byte[] expected = HexFormat.of().parseHex(sigHex);
            return MessageDigest.isEqual(computed, expected);
        } catch (Exception e) {
            log.warn("GitHub Webhook 서명 검증 중 오류 발생", e);
            return false;
        }
    }

    public void handlePushEvent(String payload) {
        try {
            JsonNode root = objectMapper.readTree(payload);

            String owner = root.path("repository").path("owner").path("login").asText();
            String repoName = root.path("repository").path("name").asText();
            String branchRef = root.path("ref").asText();
            String branch = branchRef.replace("refs/heads/", "");
            String commitHash = root.path("after").asText();

            if (root.path("deleted").asBoolean(false)) {
                log.info("브랜치 삭제 이벤트 무시: repo={}/{}, branch={}", owner, repoName, branch);
                return;
            }

            Optional<SourceRepository> repoOpt = sourceRepositoryRepository.findByOwnerAndRepoName(owner, repoName);
            if (repoOpt.isEmpty()) {
                log.info("등록되지 않은 레포지토리: owner={}, repoName={}", owner, repoName);
                return;
            }

            SourceRepository repo = repoOpt.get();
            log.info("GitHub push 이벤트 처리: repo={}/{}, branch={}, commit={}", owner, repoName, branch, commitHash);
            deploymentService.createDeployment(new CreateDeploymentRequest(repo.getId(), branch, commitHash), null);

        } catch (Exception e) {
            log.error("GitHub push 이벤트 처리 중 오류 발생", e);
        }
    }
}
