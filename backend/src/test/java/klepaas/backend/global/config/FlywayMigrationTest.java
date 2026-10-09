package klepaas.backend.global.config;

import klepaas.backend.support.PostgresTestDatabase;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FlywayMigrationTest {

    @Test
    void freshDatabaseAppliesBaselineAndFollowUpMigrations() throws Exception {
        DataSource dataSource = PostgresTestDatabase.newDataSource();

        flyway(dataSource).migrate();
        assertThat(flyway(dataSource).migrate().migrationsExecuted).isZero();

        assertThat(appliedVersions(dataSource)).startsWith("1:SQL").endsWith("9999:SQL")
                .allMatch(v -> v.endsWith(":SQL"));
        // 이전 릴리스가 status 없이 insert해도 실행 대상이 되지 않는 값으로 남아야 한다
        assertThat(columnDefault(dataSource, "command_log", "status")).startsWith("'UNKNOWN'");
    }

    private Flyway flyway(DataSource dataSource) {
        // application.yaml의 spring.flyway 설정(기본값)에 테스트 전용 V9999 위치만 추가한다
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration", "classpath:db/testmigration")
                .load();
    }

    private List<String> appliedVersions(DataSource dataSource) {
        MigrationInfo[] applied = flyway(dataSource).info().applied();
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
}
