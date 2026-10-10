package klepaas.backend.infra.kubernetes;

import io.fabric8.kubernetes.client.KubernetesClientException;

/**
 * 사용자에게 보여 줄 예외 메시지 (#92). Fabric8 예외 메시지에는 Kubernetes API 서버 주소가 들어 있어
 * Kubernetes가 돌려준 status 메시지만 쓴다. 전체 예외는 호출부가 서버 로그에 남긴다.
 */
public final class KubernetesErrorMessages {

    private static final int MAX_LENGTH = 300;

    private KubernetesErrorMessages() {
    }

    /** 원인 체인에 Kubernetes 클라이언트 예외가 있으면 그 status 메시지를, 없으면 원래 메시지를 준다. */
    public static String userMessage(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof KubernetesClientException k) {
                return clientMessage(k);
            }
        }
        return e.getMessage();
    }

    static String clientMessage(KubernetesClientException e) {
        if (e.getStatus() != null && e.getStatus().getMessage() != null) {
            String message = e.getStatus().getMessage();
            return message.length() <= MAX_LENGTH ? message : message.substring(0, MAX_LENGTH) + "…";
        }
        return e.getCode() > 0 ? "Kubernetes API 오류 (HTTP " + e.getCode() + ")" : "Kubernetes API에 연결하지 못했습니다";
    }
}
