package klepaas.backend.infra;

import klepaas.backend.deployment.entity.CloudVendor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CloudInfraProviderFactory {

    private final ApplicationContext applicationContext;

    public CloudInfraProvider getProvider(CloudVendor vendor) {
        return applicationContext.getBean(vendor.getBeanName(), CloudInfraProvider.class);
    }
}
