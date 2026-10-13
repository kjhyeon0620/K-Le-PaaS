package klepaas.backend.deployment.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum CloudVendor {

    // Bean Name과 매핑하여 Factory에서 자동으로 구현체를 찾게 함
    NCP("ncpInfraService"),
    AWS("awsInfraService"),
    ON_PREMISE("k8sInfraService");

    private final String beanName;
}
