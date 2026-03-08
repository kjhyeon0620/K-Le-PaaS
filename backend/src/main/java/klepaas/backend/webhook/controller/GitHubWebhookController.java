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
            @RequestBody String payload) {

        if (!webhookService.verifySignature(payload, signature)) {
            log.warn("GitHub Webhook 서명 검증 실패");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        if ("push".equals(event)) {
            webhookService.handlePushEvent(payload);
        }

        return ResponseEntity.ok().build();
    }
}
