package klepaas.backend.webhook.controller;

import klepaas.backend.webhook.service.GitHubWebhookService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/v1/webhooks")
@RequiredArgsConstructor
public class GitHubWebhookController {

    private final GitHubWebhookService webhookService;

    @PostMapping("/github")
    public ResponseEntity<Void> handleGitHubWebhook(
            @RequestHeader(value = "X-GitHub-Event", required = false) String event,
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
            @RequestHeader(value = "X-GitHub-Delivery", required = false) String deliveryId,
            @RequestBody String payload) {

        if (!"push".equals(event)) {
            return webhookService.verifySignature(payload, signature)
                    ? ResponseEntity.ok().build() : ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        return switch (webhookService.handleVerifiedPushEvent(payload, signature, deliveryId)) {
            case UNAUTHORIZED -> {
                log.warn("GitHub Webhook 서명 검증 실패");
                yield ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }
            case CONFLICT -> ResponseEntity.status(HttpStatus.CONFLICT).build();
            case ACCEPTED -> ResponseEntity.ok().build();
        };
    }
}
