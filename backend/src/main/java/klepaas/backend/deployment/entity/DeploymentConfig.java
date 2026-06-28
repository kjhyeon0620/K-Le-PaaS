package klepaas.backend.deployment.entity;

import jakarta.persistence.*;
import klepaas.backend.global.entity.BaseTimeEntity;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.HashMap;
import java.util.Map;

@Entity
@Getter
@Table(name = "deployment_configs")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DeploymentConfig extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "repository_id")
    private SourceRepository sourceRepository;

    private int minReplicas;

    private int maxReplicas;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "TEXT")
    private Map<String, String> envVars = new HashMap<>();

    @Column(nullable = false)
    private int containerPort;

    @Column(nullable = false)
    private String domainUrl;

    @Enumerated(EnumType.STRING)
    private BuildStrategy buildStrategy;

    private String imageUriTemplate;

    private String imagePullSecretName;

    @Builder
    public DeploymentConfig(SourceRepository sourceRepository, int minReplicas, int maxReplicas,
                            Map<String, String> envVars, int containerPort, String domainUrl,
                            BuildStrategy buildStrategy, String imageUriTemplate, String imagePullSecretName) {
        this.sourceRepository = sourceRepository;
        this.minReplicas = minReplicas;
        this.maxReplicas = maxReplicas;
        this.envVars = envVars != null ? envVars : new HashMap<>();
        this.containerPort = containerPort > 0 ? containerPort : 8080;
        this.domainUrl = domainUrl;
        this.buildStrategy = buildStrategy != null ? buildStrategy : BuildStrategy.KANIKO;
        this.imageUriTemplate = imageUriTemplate;
        this.imagePullSecretName = imagePullSecretName;
    }

    public BuildStrategy getBuildStrategy() {
        return buildStrategy != null ? buildStrategy : BuildStrategy.KANIKO;
    }

    public void updateConfig(int min, int max, Map<String, String> envVars, int containerPort, String domainUrl,
                             BuildStrategy buildStrategy, String imageUriTemplate, String imagePullSecretName) {
        this.minReplicas = min;
        this.maxReplicas = max;
        this.envVars = envVars != null ? envVars : new HashMap<>();
        this.containerPort = containerPort > 0 ? containerPort : 8080;
        this.domainUrl = domainUrl;
        this.buildStrategy = buildStrategy != null ? buildStrategy : getBuildStrategy();
        this.imageUriTemplate = imageUriTemplate != null ? imageUriTemplate : this.imageUriTemplate;
        this.imagePullSecretName = imagePullSecretName != null ? imagePullSecretName : this.imagePullSecretName;
    }
}
