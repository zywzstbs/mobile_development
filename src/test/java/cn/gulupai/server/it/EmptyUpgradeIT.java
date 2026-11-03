package cn.gulupai.server.it;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 空 schema 从零迁到当前版本，再把数据和历史复制到独立 schema。
 * 删除源 schema 后，恢复侧仍能读到同一 Flyway 版本。
 */
@SpringBootTest
@ActiveProfiles("test")
class EmptyUpgradeIT {

    @Autowired
    private DataSource dataSource;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void emptySchemaUpgradesAndRestoredCopySurvivesSourceDrop() {
        jdbcTemplate.execute("DROP SCHEMA IF EXISTS gulupai_p16_empty CASCADE");
        jdbcTemplate.execute("DROP SCHEMA IF EXISTS gulupai_p16_restore CASCADE");
        jdbcTemplate.execute("CREATE SCHEMA gulupai_p16_empty");

        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .schemas("gulupai_p16_empty")
                .defaultSchema("gulupai_p16_empty")
                .createSchemas(true)
                .locations("classpath:db/migration")
                .load();
        MigrateResult first = flyway.migrate();
        assertThat(first.migrationsExecuted).isEqualTo(MigrationVersions.latestNumber());
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo(MigrationVersions.latest());

        MigrateResult second = flyway.migrate();
        assertThat(second.migrationsExecuted).isZero();

        String baseline = jdbcTemplate.queryForObject(
                "SELECT meta_value FROM gulupai_p16_empty.app_meta WHERE meta_key = 'schema_baseline'",
                String.class);
        assertThat(baseline).isEqualTo("P00");

        jdbcTemplate.execute("CREATE SCHEMA gulupai_p16_restore");
        jdbcTemplate.execute("""
                DO $$
                DECLARE row record;
                BEGIN
                  FOR row IN
                    SELECT tablename FROM pg_tables WHERE schemaname = 'gulupai_p16_empty'
                  LOOP
                    EXECUTE format(
                      'CREATE TABLE gulupai_p16_restore.%I (LIKE gulupai_p16_empty.%I INCLUDING ALL)',
                      row.tablename, row.tablename);
                    EXECUTE format(
                      'INSERT INTO gulupai_p16_restore.%I SELECT * FROM gulupai_p16_empty.%I',
                      row.tablename, row.tablename);
                  END LOOP;
                END $$
                """);

        String renewalNullable = jdbcTemplate.queryForObject("""
                SELECT is_nullable FROM information_schema.columns
                WHERE table_schema = 'gulupai_p16_empty'
                  AND table_name = 'agreement' AND column_name = 'renewal_reason'
                """, String.class);
        assertThat(renewalNullable).isEqualTo("YES");
        jdbcTemplate.execute("DROP SCHEMA gulupai_p16_empty CASCADE");
        String restoredVersion = jdbcTemplate.queryForObject("""
                SELECT version FROM gulupai_p16_restore.flyway_schema_history
                WHERE success = true ORDER BY installed_rank DESC LIMIT 1
                """, String.class);
        String restoredBaseline = jdbcTemplate.queryForObject(
                "SELECT meta_value FROM gulupai_p16_restore.app_meta WHERE meta_key = 'schema_baseline'",
                String.class);
        assertThat(restoredVersion).isEqualTo(MigrationVersions.latest());
        assertThat(restoredBaseline).isEqualTo("P00");
    }
}
