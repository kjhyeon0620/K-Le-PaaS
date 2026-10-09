package klepaas.backend.infra.kubernetes;

/**
 * 한 배포 요청이 적용한 Deployment generation의 rollout 판정 결과. 타임아웃은 FAILED로 돌려준다.
 */
public record RolloutResult(Outcome outcome, String reason) {

    public enum Outcome { SUCCEEDED, FAILED, SUPERSEDED }

    static RolloutResult succeeded() {
        return new RolloutResult(Outcome.SUCCEEDED, null);
    }

    static RolloutResult failed(String reason) {
        return new RolloutResult(Outcome.FAILED, reason);
    }

    static RolloutResult superseded(String reason) {
        return new RolloutResult(Outcome.SUPERSEDED, reason);
    }
}
