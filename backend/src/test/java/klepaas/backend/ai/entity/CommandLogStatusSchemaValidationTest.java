package klepaas.backend.ai.entity;

import org.junit.jupiter.api.Test;
import org.hibernate.SessionFactory;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import jakarta.persistence.EntityManagerFactory;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import klepaas.backend.deployment.entity.DeploymentConfig;
import klepaas.backend.global.config.JpaConfig;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import(JpaConfig.class)
class CommandLogStatusSchemaValidationTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private EntityManager entityManager;

    @Test
    void migratedStatusColumnValidatesAtStartup() throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE command_log DROP COLUMN status");
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/manual/command-log-status.sql"));
        }

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
}
