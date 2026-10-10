package klepaas.backend.infra.kubernetes;

import klepaas.backend.deployment.entity.FailureKind;

/**
 * 한 배포 요청이 적용한 Deployment generation의 rollout 판정 결과. 타임아웃은 FAILED로 돌려준다.
 * failureKind는 실패·대체일 때, imageDigest는 성공 시 새 Pod에서 관측했을 때만 있다 (#95).
 * UNKNOWN은 재시작 대조의 한 번 관측(#57)에서만 나오며 waitForRollout은 돌려주지 않는다.
 */
public record RolloutResult(Outcome outcome, String reason, FailureKind failureKind, String imageDigest) {

    public enum Outcome { SUCCEEDED, FAILED, SUPERSEDED, UNKNOWN }

    public RolloutResult(Outcome outcome, String reason) {
        this(outcome, reason, null, null);
    }

    static RolloutResult succeeded(String imageDigest) {
        return new RolloutResult(Outcome.SUCCEEDED, null, null, imageDigest);
    }

    static RolloutResult failed(String reason, FailureKind failureKind) {
        return new RolloutResult(Outcome.FAILED, reason, failureKind, null);
    }

    static RolloutResult superseded(String reason) {
        return new RolloutResult(Outcome.SUPERSEDED, reason, FailureKind.SUPERSEDED, null);
    }

    static RolloutResult unknown(String reason) {
        return new RolloutResult(Outcome.UNKNOWN, reason, null, null);
    }

    RolloutResult withImageDigest(String digest) {
        return new RolloutResult(outcome, reason, failureKind, digest);
    }
}
