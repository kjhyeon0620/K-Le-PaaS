package klepaas.backend.support;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;

/**
 * 테스트용 PostgreSQL (운영과 같은 엔진).
 * JVM 전체가 컨테이너 하나를 공유하고, Spring 컨텍스트마다 빈 데이터베이스를 새로 만들어 서로 격리한다.
 * META-INF/spring.factories로 모든 테스트 컨텍스트에 등록된다. 컨테이너는 JVM 종료 시 Testcontainers가 정리한다.
 */
public class PostgresTestDatabase implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    private static final PostgreSQLContainer CONTAINER = start();

    private static PostgreSQLContainer start() {
        PostgreSQLContainer container = new PostgreSQLContainer("postgres:16-alpine");
        container.start();
        return container;
    }

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("postgresTestDatabase", Map.of(
                "spring.datasource.url", createDatabase(),
                "spring.datasource.username", CONTAINER.getUsername(),
                "spring.datasource.password", CONTAINER.getPassword(),
                // @DataJpaTest가 내장 DB로 바꾸지 않게 한다
                "spring.test.database.replace", "none")));
    }

    /** 빈 데이터베이스를 새로 만들어 반환한다. */
    public static DataSource newDataSource() {
        return new DriverManagerDataSource(createDatabase(), CONTAINER.getUsername(), CONTAINER.getPassword());
    }

    private static String createDatabase() {
        String name = "test_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = DriverManager.getConnection(
                CONTAINER.getJdbcUrl(), CONTAINER.getUsername(), CONTAINER.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("create database " + name);
        } catch (SQLException e) {
            throw new IllegalStateException("테스트 데이터베이스를 만들지 못했습니다", e);
        }
        return "jdbc:postgresql://" + CONTAINER.getHost() + ":" + CONTAINER.getMappedPort(5432) + "/" + name;
    }
}
