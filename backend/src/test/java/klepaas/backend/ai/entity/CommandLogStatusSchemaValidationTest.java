package klepaas.backend.ai.entity;

import org.junit.jupiter.api.Test;
import org.hibernate.SessionFactory;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

import jakarta.persistence.EntityManagerFactory;
import java.util.List;
import java.util.Map;

import klepaas.backend.deployment.entity.ContainerResources;
import klepaas.backend.deployment.entity.DeploymentConfig;
import klepaas.backend.deployment.entity.HealthProbe;
import klepaas.backend.global.config.JpaConfig;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import(JpaConfig.class)
class CommandLogStatusSchemaValidationTest {

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private EntityManager entityManager;

    @Test
    void flywaySchemaValidatesAgainstMappings() {
        assertThatCode(entityManagerFactory.unwrap(SessionFactory.class).getSchemaManager()::validateMappedObjects)
                .doesNotThrowAnyException();
    }

    @Test
    void textBackedJsonFieldsRoundTripLegacyValues() {
        DeploymentConfig config = DeploymentConfig.builder()
                .domainUrl("json.example.test")
                .envVars(Map.of("한글", "quote\\\" and slash\\\\"))
                .envFromConfigMaps(List.of("runtime-config", "한글-config"))
                .envFromSecrets(List.of("app-secret"))
                .build();
        entityManager.persist(config);
        entityManager.flush();
        Long id = config.getId();
        entityManager.clear();

        DeploymentConfig stored = entityManager.find(DeploymentConfig.class, id);
        assertThat(stored.getEnvVars()).containsExactly(Map.entry("한글", "quote\\\" and slash\\\\"));
        assertThat(stored.getEnvFromConfigMaps()).containsExactly("runtime-config", "한글-config");
        assertThat(stored.getEnvFromSecrets()).containsExactly("app-secret");

        entityManager.createNativeQuery("""
                update deployment_configs
                set env_vars = :envVars, env_from_config_maps = :configMaps, env_from_secrets = :secrets
                where id = :id
                """)
                .setParameter("envVars", "{\"legacy\":\"한글\\\"quoted\\\"\"}")
                .setParameter("configMaps", "[\"legacy-config\",\"한글-config\"]")
                .setParameter("secrets", "[]")
                .setParameter("id", id)
                .executeUpdate();
        entityManager.clear();

        DeploymentConfig reloaded = entityManager.find(DeploymentConfig.class, id);
        assertThat(reloaded.getEnvVars()).containsExactly(Map.entry("legacy", "한글\"quoted\""));
        assertThat(reloaded.getEnvFromConfigMaps()).containsExactly("legacy-config", "한글-config");
        assertThat(reloaded.getEnvFromSecrets()).isEmpty();
    }

    @Test
    void probeAndResourceColumnsRoundTripAndStayNullForExistingApps() {
        var probe = new HealthProbe("/health", 9090, 5, 10, 3, 30);
        var resources = new ContainerResources("100m", "500m", null, "256Mi");
        DeploymentConfig configured = DeploymentConfig.builder()
                .domainUrl("probe.example.test").healthProbe(probe).resources(resources).build();
        DeploymentConfig legacy = DeploymentConfig.builder().domainUrl("legacy.example.test").build();
        entityManager.persist(configured);
        entityManager.persist(legacy);
        entityManager.flush();
        entityManager.clear();

        DeploymentConfig storedConfigured = entityManager.find(DeploymentConfig.class, configured.getId());
        assertThat(storedConfigured.getHealthProbe()).isEqualTo(probe);
        assertThat(storedConfigured.getResources()).isEqualTo(resources);
        DeploymentConfig storedLegacy = entityManager.find(DeploymentConfig.class, legacy.getId());
        assertThat(storedLegacy.getHealthProbe()).isNull();
        assertThat(storedLegacy.getResources()).isNull();
    }
}
