package cn.gulupai.server.it;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实 PostgreSQL 迁移验证；需要 application-test.yml 指向的测试库。
 * 由 failsafe 在 ./mvnw verify 阶段执行，避免无数据库的单元测试环境被阻塞。
 */
@SpringBootTest
@ActiveProfiles("test")
class FlywayMigrationIT {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Flyway flyway;

    @Test
    void baselineMigrationIsApplied() {
        assertThat(flyway.info().current()).isNotNull();
        assertThat(flyway.info().applied())
                .extracting(info -> info.getVersion() == null ? null : info.getVersion().getVersion())
                .contains("001", "002", "003", "004", "005", "006", "007", "008", "009", "010", "011", "012", "013", "014", "015", "016", "017", "018", "019", "020", "021", "022");

        Integer rows = jdbcTemplate.queryForObject(
                "select count(*) from app_meta where meta_key in ('schema_baseline', 'contract_version')",
                Integer.class);
        assertThat(rows).isEqualTo(2);

        String baseline = jdbcTemplate.queryForObject(
                "select meta_value from app_meta where meta_key = 'schema_baseline'", String.class);
        assertThat(baseline).isEqualTo("P00");

        Integer reportTables = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                WHERE table_schema = current_schema()
                  AND table_name IN ('project_reflection', 'overall_reflection', 'delivery_release_draft',
                                     'delivery_release_preview', 'delivery_release', 'report_snapshot')
                """, Integer.class);
        assertThat(reportTables).isEqualTo(6);

        Integer archiveColumn = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema = current_schema() AND table_name = 'project' AND column_name = 'archived_at'
                """, Integer.class);
        assertThat(archiveColumn).isEqualTo(1);

        Integer profileTables = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                WHERE table_schema = current_schema()
                  AND table_name IN ('work', 'work_version', 'peer_review',
                                     'peer_review_version', 'ai_reference')
                """, Integer.class);
        assertThat(profileTables).isEqualTo(5);

        Integer diceTables = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                WHERE table_schema = current_schema()
                  AND table_name IN ('dice_session', 'dice_participant', 'dice_roll', 'dice_round')
                """, Integer.class);
        assertThat(diceTables).isEqualTo(4);

        Integer communityTables = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                WHERE table_schema = current_schema()
                  AND table_name IN ('board_topic', 'board_reply', 'content_revision', 'topic_follow',
                                     'chat_counter', 'chat_message', 'chat_change', 'chat_read_state',
                                     'notification_outbox')
                """, Integer.class);
        assertThat(communityTables).isEqualTo(9);

        Integer noticeTables = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                WHERE table_schema = current_schema()
                  AND table_name IN ('announcement', 'announcement_version', 'announcement_recipient',
                                     'announcement_receipt', 'announcement_preview',
                                     'announcement_reminder', 'notification',
                                     'notification_preference')
                """, Integer.class);
        assertThat(noticeTables).isEqualTo(8);

        Integer materialVersionTables = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                WHERE table_schema = current_schema()
                  AND table_name IN ('material_response', 'material_review')
                """, Integer.class);
        assertThat(materialVersionTables).isEqualTo(2);

        Integer helpTables = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                WHERE table_schema = current_schema()
                  AND table_name IN ('help_article', 'help_feedback', 'support_route', 'support_ticket',
                                     'support_reply', 'support_history', 'operator_grant',
                                     'content_report', 'report_handling')
                """, Integer.class);
        assertThat(helpTables).isEqualTo(9);

        Integer timeColumns = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema = current_schema() AND table_name = 'material_response'
                  AND column_name IN ('created_at_confidence', 'legacy_recorded_at', 'time_source',
                                      'time_evidence', 'time_recovered_by', 'time_recovered_at')
                """, Integer.class);
        assertThat(timeColumns).isEqualTo(6);

        Integer timeConstraints = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM information_schema.table_constraints
                WHERE table_schema = current_schema() AND table_name = 'material_response'
                  AND constraint_name IN ('ck_material_response_time_confidence',
                                          'ck_material_response_time_source',
                                          'ck_material_response_legacy_time',
                                          'ck_material_response_recovery')
                """, Integer.class);
        assertThat(timeConstraints).isEqualTo(4);
    }

    @Test
    void schemaHistoryIsOwnedByFlyway() {
        Integer history = jdbcTemplate.queryForObject(
                "select count(*) from flyway_schema_history where version = '001' and success = true",
                Integer.class);
        assertThat(history).isEqualTo(1);
    }
}
