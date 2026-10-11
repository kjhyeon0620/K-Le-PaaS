package klepaas.backend.deployment.service;

import klepaas.backend.auth.config.CustomUserDetails;
import klepaas.backend.auth.token.entity.CliTokenScope;
import klepaas.backend.deployment.entity.TriggerSource;

/** 배포 요청이 어떤 경로로, 누구에게서 왔는지 (#95). CI·webhook은 요청자가 없다. */
public record DeploymentOrigin(TriggerSource source, Long requestedByUserId, Long commandLogId,
                               String approvedConfigFingerprint) {

    public DeploymentOrigin(TriggerSource source, Long requestedByUserId, Long commandLogId) {
        this(source, requestedByUserId, commandLogId, null);
    }

    public static DeploymentOrigin of(CustomUserDetails principal) {
        if (principal.isGitHubActions()) {
            return new DeploymentOrigin(TriggerSource.CI_OIDC, null, null);
        }
        if (principal.getScope() == CliTokenScope.DEPLOY) {
            return new DeploymentOrigin(TriggerSource.CI_TOKEN, null, null);
        }
        return new DeploymentOrigin(principal.isCliToken() ? TriggerSource.CLI : TriggerSource.WEB,
                principal.getUserId(), null);
    }

    public static DeploymentOrigin webhook() {
        return new DeploymentOrigin(TriggerSource.WEBHOOK, null, null);
    }

    public static DeploymentOrigin nlp(Long userId, Long commandLogId) {
        return nlp(userId, commandLogId, null);
    }

    /** 복구 실행이 복원한 설정 지문으로 고정한다 (#101) */
    public DeploymentOrigin withApprovedConfigFingerprint(String fingerprint) {
        return new DeploymentOrigin(source, requestedByUserId, commandLogId, fingerprint);
    }

    /** approvedConfigFingerprint: 승인한 배포 설정 지문 (#56). apply 직전 비교한다 */
    public static DeploymentOrigin nlp(Long userId, Long commandLogId, String approvedConfigFingerprint) {
        return new DeploymentOrigin(TriggerSource.NLP, userId, commandLogId, approvedConfigFingerprint);
    }
}
