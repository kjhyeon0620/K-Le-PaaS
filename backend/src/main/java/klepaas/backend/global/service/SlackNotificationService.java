package klepaas.backend.global.service;

import klepaas.backend.deployment.entity.Deployment;
import klepaas.backend.deployment.entity.SourceRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Slf4j
@Service
public class SlackNotificationService implements NotificationService {

    @Value("${slack.webhook.url}")
    private String slackWebhookUrl;

    private final RestClient restClient = RestClient.create();

    @Override
    public void notifyDeploymentStarted(Deployment deployment) {
        String message = "🚀 배포 시작 - %s (브랜치: %s)"
                .formatted(formatRepository(deployment), deployment.getBranchName());
        sendMessage(message);
    }

    @Override
    public void notifyDeploymentSuccess(Deployment deployment) {
        String message = "✅ 배포 성공 - %s".formatted(formatRepository(deployment));
        sendMessage(message);
    }

    @Override
    public void notifyDeploymentFailed(Deployment deployment, String reason) {
        String message = "❌ 배포 실패 - %s: %s"
                .formatted(formatRepository(deployment), reason == null ? "알 수 없는 오류" : reason);
        sendMessage(message);
    }

    private void sendMessage(String message) {
        if (slackWebhookUrl == null || slackWebhookUrl.isBlank()) {
            log.warn("Slack webhook URL is empty. Skip notification: {}", message);
            return;
        }

        try {
            restClient.post()
                    .uri(slackWebhookUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new SlackWebhookRequest(message))
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.warn("Failed to send Slack notification: {}", e.getMessage());
        }
    }

    private String formatRepository(Deployment deployment) {
        SourceRepository repo = deployment.getSourceRepository();
        return repo.getOwner() + "/" + repo.getRepoName();
    }

    private record SlackWebhookRequest(String text) {
    }
}
