package klepaas.backend.auth.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "github.app")
public class GitHubAppConfig {

    private Long appId;
    private String privateKey;
    private String appSlug;
}
