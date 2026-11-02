package cn.gulupai.server.it;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F03/R07-T06：从 V018 带存量聊天数据升级。
 * 旧消息保留原 unique 键，request_hash 为 NULL（legacy/unverifiable）；
 * 新表 chat_sync_cursor / chat_delivery_range 建立且不修改已执行迁移。
 */
@SpringBootTest
@ActiveProfiles("test")
class ChatMigrationV019IT {

    private static final String SCHEMA = "gulupai_chat_v18_upgrade";
    private final String p = SCHEMA + ".";

    @Autowired
    private DataSource dataSource;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void v18ChatRowsSurviveUpgradeAsUnverifiableLegacy() {
        jdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        jdbc.execute("CREATE SCHEMA " + SCHEMA);
        try {
            Flyway v18 = Flyway.configure()
                    .dataSource(dataSource)
                    .schemas(SCHEMA)
                    .defaultSchema(SCHEMA)
                    .createSchemas(true)
                    .locations("classpath:db/migration")
                    .target("18")
                    .load();
            MigrateResult before = v18.migrate();
            assertThat(before.migrationsExecuted).isPositive();
            assertThat(v18.info().current().getVersion().getVersion()).isEqualTo("018");

            seedLegacyChat();

            Flyway v19 = Flyway.configure()
                    .dataSource(dataSource)
                    .schemas(SCHEMA)
                    .defaultSchema(SCHEMA)
                    .createSchemas(true)
                    .locations("classpath:db/migration")
                    .target("19")
                    .load();
            assertThat(v19.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(v19.info().current().getVersion().getVersion()).isEqualTo("019");
            jdbc.update("INSERT INTO " + p + "chat_sync_cursor(id, token_hash, user_id, project_id, "
                    + "authorization_epoch, last_change_sequence, expires_at, created_at) "
                    + "VALUES ('old-token', ?, 'u', 'p', 1, 2, '2026-11-10T00:00:00Z', "
                    + "'2026-11-02T00:00:00Z')", "b".repeat(64));

            Flyway latest = Flyway.configure()
                    .dataSource(dataSource)
                    .schemas(SCHEMA)
                    .defaultSchema(SCHEMA)
                    .createSchemas(true)
                    .locations("classpath:db/migration")
                    .load();
            MigrateResult upgrade = latest.migrate();
            assertThat(upgrade.migrationsExecuted).isEqualTo(3);
            assertThat(latest.info().current().getVersion().getVersion()).isEqualTo("022");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + p + "chat_sync_cursor",
                    Integer.class)).isZero();
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM information_schema.tables
                    WHERE table_schema = ? AND table_name = 'personnel_case_grant'
                    """, Integer.class, SCHEMA)).isEqualTo(1);

            Map<String, Object> normal = jdbc.queryForMap(
                    "SELECT content, request_hash, request_hash_version, withdrawn FROM "
                            + p + "chat_message WHERE id = 'legacy-normal'");
            assertThat(normal.get("content")).isEqualTo("旧正文");
            assertThat(normal.get("request_hash")).isNull();
            assertThat(normal.get("request_hash_version")).isNull();
            assertThat(normal.get("withdrawn")).isEqualTo(false);

            Map<String, Object> withdrawn = jdbc.queryForMap(
                    "SELECT content, request_hash, withdrawn, revision FROM "
                            + p + "chat_message WHERE id = 'legacy-withdrawn'");
            assertThat(withdrawn.get("content")).isNull();
            assertThat(withdrawn.get("request_hash")).isNull();
            assertThat(withdrawn.get("withdrawn")).isEqualTo(true);
            assertThat(withdrawn.get("revision")).isEqualTo(2);

            Integer tables = jdbc.queryForObject("""
                    SELECT count(*) FROM information_schema.tables
                    WHERE table_schema = ? AND table_name IN ('chat_sync_cursor', 'chat_delivery_range')
                    """, Integer.class, SCHEMA);
            assertThat(tables).isEqualTo(2);
            Integer uniqueKey = jdbc.queryForObject("""
                    SELECT count(*) FROM information_schema.table_constraints
                    WHERE table_schema = ? AND table_name = 'chat_message'
                      AND constraint_name = 'uq_chat_client_message'
                    """, Integer.class, SCHEMA);
            assertThat(uniqueKey).isEqualTo(1);
        } finally {
            jdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        }
    }

    private void seedLegacyChat() {
        jdbc.update("INSERT INTO " + p + "school(id, name, timezone) VALUES ('s','S','UTC')");
        jdbc.update("INSERT INTO " + p + "term(id, school_id, name, start_date, end_date, ordinal) "
                + "VALUES ('t','s','T','2026-01-01','2026-06-30',1)");
        jdbc.update("INSERT INTO " + p + "app_user(id, school_id, login_id, display_name, "
                + "password_hash, identity_status) VALUES ('u','s','login','用户','x','VERIFIED')");
        jdbc.update("INSERT INTO " + p + "square(id, school_id, term_id, name, teacher_name, "
                + "slots_json, canonical_key, square_no, status) VALUES "
                + "('sq','s','t','课程','教师','[]'::jsonb, ?, 'N1', 'ACTIVE')", "a".repeat(64));
        jdbc.update("INSERT INTO " + p + "assignment(id, square_id, name, requirements, origin, "
                + "confirmation_status, hard_due_at, min_members, max_members) VALUES "
                + "('asg','sq','作业','要求','STUDENT','CONFIRMED','2026-06-01T00:00:00Z',1,6)");
        jdbc.update("INSERT INTO " + p + "project(id, assignment_id, square_id, owner_id, mode, "
                + "capacity, status) VALUES ('p','asg','sq','u','QUICK',4,'ACTIVE')");
        jdbc.update("INSERT INTO " + p + "chat_message(id, project_id, sequence, client_message_id, "
                + "author_id, content, refs_json, mentions_json, withdrawn, revision, created_at, updated_at) "
                + "VALUES ('legacy-normal','p',1,'c1','u','旧正文','[]'::jsonb,'[]'::jsonb,false,1,"
                + "'2026-10-01T00:00:00Z','2026-10-01T00:00:00Z')");
        jdbc.update("INSERT INTO " + p + "chat_message(id, project_id, sequence, client_message_id, "
                + "author_id, content, refs_json, mentions_json, withdrawn, revision, created_at, "
                + "updated_at, withdrawn_at) VALUES "
                + "('legacy-withdrawn','p',2,'c2','u',NULL,'[]'::jsonb,'[]'::jsonb,true,2,"
                + "'2026-10-01T00:00:00Z','2026-10-01T00:00:00Z','2026-10-01T00:00:00Z')");
        jdbc.update("INSERT INTO " + p + "chat_counter(project_id, next_sequence, last_change_sequence) "
                + "VALUES ('p', 3, 2)");
    }
}
