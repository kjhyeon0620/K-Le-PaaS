package klepaas.backend.deployment.service;

import klepaas.backend.deployment.entity.BuildStrategy;
import klepaas.backend.deployment.entity.ContainerResources;
import klepaas.backend.deployment.entity.HealthProbe;
import klepaas.backend.deployment.entity.KubernetesServiceType;

import java.util.List;
import java.util.Map;

/**
 * 배포 요청이 적용한 배포 설정 (#95). env 값은 저장하지 않고 이름별 HMAC 지문만 둔다.
 * fingerprintKeyId가 다른 스냅샷끼리는 env 값 변경 여부를 판정할 수 없다 (지문 키가 바뀜).
 */
public record DeploymentConfigSnapshot(
        int version,
        String fingerprintKeyId,
        BuildStrategy buildStrategy,
        String imageUriTemplate,
        int minReplicas,
        int maxReplicas,
        int containerPort,
        String domainUrl,
        KubernetesServiceType serviceType,
        Integer nodePort,
        Map<String, String> envFingerprints,
        List<String> envFromConfigMaps,
        List<String> envFromSecrets,
        boolean imagePullSecretEnabled,
        String imagePullSecretName,
        HealthProbe healthProbe,
        ContainerResources resources
) {
    public static final int CURRENT_VERSION = 1;
}
