package klepaas.backend.webhook.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import klepaas.backend.deployment.service.DeploymentOrigin;
import klepaas.backend.deployment.dto.CreateDeploymentRequest;
import klepaas.backend.deployment.entity.BuildStrategy;
import klepaas.backend.deployment.entity.CloudVendor;
import klepaas.backend.deployment.entity.SourceRepository;
import klepaas.backend.deployment.repository.DeploymentConfigRepository;
import klepaas.backend.deployment.repository.SourceRepositoryRepository;
import klepaas.backend.deployment.service.DeploymentService;
import klepaas.backend.global.exception.BusinessException;
import klepaas.backend.global.exception.ErrorCode;
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
    private final DeploymentConfigRepository deploymentConfigRepository;
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

    public enum PushResult { ACCEPTED, UNAUTHORIZED, CONFLICT }

    public PushResult handleVerifiedPushEvent(String payload, String signature, String deliveryId) {
        if (!verifySignature(payload, signature)) {
            return PushResult.UNAUTHORIZED;
        }
        try {
            JsonNode root = objectMapper.readTree(payload);

            String owner = root.path("repository").path("owner").path("login").asText();
            String repoName = root.path("repository").path("name").asText();
            String branchRef = root.path("ref").asText();
            String branch = branchRef.replace("refs/heads/", "");
            String commitHash = root.path("after").asText();

            if (root.path("deleted").asBoolean(false)) {
                log.info("브랜치 삭제 이벤트 무시: repo={}/{}, branch={}", owner, repoName, branch);
                return PushResult.ACCEPTED;
            }

            Optional<SourceRepository> repoOpt = sourceRepositoryRepository.findByOwnerAndRepoName(owner, repoName);
            if (repoOpt.isEmpty()) {
                log.info("등록되지 않은 레포지토리: owner={}, repoName={}", owner, repoName);
                return PushResult.ACCEPTED;
            }

            SourceRepository repo = repoOpt.get();
            if (repo.getId() == null || repo.getUser() == null || repo.getUser().getId() == null) {
                log.warn("소유자가 없는 레포지토리의 push 이벤트 무시: repo={}/{}", owner, repoName);
                return PushResult.ACCEPTED;
            }
            if (shouldSkipPushDeployment(repo)) {
                log.info("외부 이미지 전략 저장소의 raw push 배포 무시: repo={}/{}, branch={}, commit={}",
                        owner, repoName, branch, commitHash);
                return PushResult.ACCEPTED;
            }

            log.info("GitHub push 이벤트 처리: repo={}/{}, branch={}, commit={}, delivery={}",
                    owner, repoName, branch, commitHash, deliveryId);
            deploymentService.createDeployment(new CreateDeploymentRequest(repo.getId(), branch, commitHash),
                    repo.getUser().getId(), normalizeDeliveryId(deliveryId), DeploymentOrigin.webhook());

        } catch (BusinessException e) {
            if (e.getErrorCode() == ErrorCode.DEPLOYMENT_IN_PROGRESS) {
                // GitHub delivery 기록에 실패로 남겨, 진행 중 배포가 끝난 뒤 Redeliver할 수 있게 한다
                log.info("진행 중 배포가 있어 push 배포 거절: delivery={}, {}", deliveryId, e.getMessage());
                return PushResult.CONFLICT;
            }
            log.error("GitHub push 이벤트 처리 중 오류 발생", e);
        } catch (Exception e) {
            log.error("GitHub push 이벤트 처리 중 오류 발생", e);
        }
        return PushResult.ACCEPTED;
    }

    // 형식이 다른 값은 중복 제거에 쓰지 않는다 (GitHub delivery는 GUID)
    private String normalizeDeliveryId(String deliveryId) {
        return deliveryId != null && deliveryId.matches("[A-Za-z0-9-]{1,64}") ? deliveryId : null;
    }

    private boolean shouldSkipPushDeployment(SourceRepository repository) {
        BuildStrategy buildStrategy = deploymentConfigRepository.findBySourceRepositoryId(repository.getId())
                .map(config -> config.getBuildStrategy())
                .orElse(defaultBuildStrategy(repository));
        return switch (buildStrategy) {
            case KANIKO -> false;
            case GITHUB_ACTIONS_GHCR, PREBUILT_IMAGE -> true;
        };
    }

    private BuildStrategy defaultBuildStrategy(SourceRepository repository) {
        if (repository.getCloudVendor() == CloudVendor.ON_PREMISE) {
            return BuildStrategy.GITHUB_ACTIONS_GHCR;
        }
        return BuildStrategy.KANIKO;
    }
}
