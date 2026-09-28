package klepaas.backend.global.config;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class FlywayMigrationTest {

    @Test
    void freshDatabaseAppliesBaselineAndFollowUpMigrations() throws Exception {
        DataSource dataSource = newDatabase();

        flyway(dataSource).migrate();

        assertThat(appliedVersions(dataSource)).startsWith("1:SQL").endsWith("9999:SQL")
                .allMatch(v -> v.endsWith(":SQL"));
        assertThat(columnDefault(dataSource, "COMMAND_LOG", "STATUS")).isEqualTo("'UNKNOWN'");
    }

    @Test
    void existingDatabaseWithoutHistoryIsBaselinedAndKeepsData() throws Exception {
        DataSource dataSource = newDatabase();
        // Flyway 도입 전 운영 DB처럼 이력 테이블 없이 스키마와 데이터가 이미 존재하는 상태
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V1__baseline_schema.sql"));
            statement.execute("insert into users (created_at, updated_at, email, name) "
                    + "values (current_timestamp, current_timestamp, 'legacy@example.test', 'legacy')");
        }

        flyway(dataSource).migrate();
        assertThat(flyway(dataSource).migrate().migrationsExecuted).isZero();

        // V1은 실행되지 않고 baseline으로만 기록되며, 이후 버전만 적용된다
        assertThat(appliedVersions(dataSource)).startsWith("1:BASELINE").endsWith("9999:SQL")
                .doesNotContain("1:SQL");
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("select email, flyway_sample from users")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("legacy@example.test");
            assertThat(rs.getString(2)).isEqualTo("sample");
        }
    }

    private Flyway flyway(DataSource dataSource) {
        // application.yaml의 spring.flyway 설정과 동일하게 맞추고 테스트 전용 V9999 위치만 추가한다
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration", "classpath:db/testmigration")
                .baselineOnMigrate(true)
                .baselineVersion("1")
                .load();
    }

    private List<String> appliedVersions(DataSource dataSource) {
        MigrationInfo[] applied = Flyway.configure().dataSource(dataSource)
                .locations("classpath:db/migration", "classpath:db/testmigration").load().info().applied();
        return Arrays.stream(applied).map(m -> m.getVersion() + ":" + m.getType()).toList();
    }

    private String columnDefault(DataSource dataSource, String table, String column) throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("select column_default from information_schema.columns "
                     + "where table_name = '" + table + "' and column_name = '" + column + "'")) {
            assertThat(rs.next()).isTrue();
            return rs.getString(1);
        }
    }

    private DataSource newDatabase() {
        return new DriverManagerDataSource("jdbc:h2:mem:flyway_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
    }
}
