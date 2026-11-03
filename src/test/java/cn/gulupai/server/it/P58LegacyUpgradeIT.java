package cn.gulupai.server.it;

import cn.gulupai.server.support.HelpArticleText;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V023～V027 对旧数据的效果：长正文不再被当成步骤，举报广场可追溯，
 * 登录游标补上主体，已撤下的最高 content_version 不会和旧缓存撞号。
 */
@SpringBootTest
@ActiveProfiles("test")
class P58LegacyUpgradeIT {

    private static final String BODY = "gulupai_p58_body";
    private static final String PACK = "gulupai_p58_pack";
    private static final List<String> SCHEMAS = List.of(BODY, PACK);
    private static final String SHORT_BODY = "短帮助正文";
    private static final String LONG_BODY = "长".repeat(600);

    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbc;

    @AfterEach
    void dropSchemas() {
        for (String schema : SCHEMAS) {
            jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @Test
    void oldHelpReportAndCursorSurviveLaterMigrations() {
        reset(BODY);
        flyway(BODY, "22").migrate();
        seedBodyWorld();

        flyway(BODY, "23").migrate();
        assertThat(text(BODY, "SELECT steps_json::text FROM help_article WHERE id='help_short'"))
                .isEqualTo("[\"" + SHORT_BODY + "\"]");
        assertThat(text(BODY, "SELECT steps_json::text FROM help_article WHERE id='help_long'"))
                .isEqualTo("[\"" + "长".repeat(500) + "\"]");
        assertThat(text(BODY, "SELECT content_type FROM content_report WHERE id='report_old'"))
                .isEqualTo("BOARD_TOPIC");
        assertThat(text(BODY, "SELECT status FROM content_report WHERE id='report_old'"))
                .isEqualTo("SUBMITTED");

        MigrateResult through27 = flyway(BODY, "27").migrate();
        assertThat(through27.migrationsExecuted)
                .isEqualTo(MigrationVersions.countAfter(23) - MigrationVersions.countAfter(27));
        jdbc.update("INSERT INTO " + BODY + ".project_stage(id, project_id, name, position) "
                + "VALUES ('stage_old','p','阶段',1)");
        jdbc.update("INSERT INTO " + BODY + ".task(id, project_id, stage_id, title, mode, status, created_at, updated_at) "
                + "VALUES ('task_old','p','stage_old','旧任务','ORDINARY','TODO',"
                + "'2026-10-01T00:00:00Z','2026-10-01T00:00:00Z')");
        jdbc.update("INSERT INTO " + BODY + ".submission(id, task_id, sequence, submitted_by, status, summary, "
                + "artifacts_json, unit_ids_json, criteria_snapshot, unit_due_snapshot_json, submitted_at, revision) "
                + "VALUES ('sub_old','task_old',1,'u','REVIEWED','说明','[]'::jsonb,'[]'::jsonb,'[]'::jsonb,"
                + "'{}'::jsonb,'2026-10-01T00:00:00Z',2)");
        jdbc.update("INSERT INTO " + BODY + ".acceptance(id, submission_id, reviewer_id, kind, checks_json, decision, "
                + "reason, accepted_at) VALUES ('acc_old','sub_old','u','SELF_CHECK','[]'::jsonb,'PASSED','原理由',"
                + "'2026-10-01T00:00:00Z')");

        MigrateResult rest = flyway(BODY, null).migrate();
        assertThat(rest.migrationsExecuted).isEqualTo(MigrationVersions.countAfter(27));
        assertThat(number(BODY, "SELECT revision FROM acceptance WHERE id='acc_old'")).isEqualTo(1);
        assertThat(text(BODY, "SELECT reason FROM acceptance WHERE id='acc_old'")).isEqualTo("原理由");
        assertThat(flyway(BODY, null).info().current().getVersion().getVersion())
                .isEqualTo(MigrationVersions.latest());

        assertThat(text(BODY, "SELECT body FROM help_article WHERE id='help_short'")).isEqualTo(SHORT_BODY);
        assertThat(bool(BODY, "SELECT published FROM help_article WHERE id='help_short'")).isTrue();
        assertThat(text(BODY, "SELECT steps_json::text FROM help_article WHERE id='help_short'"))
                .isEqualTo("[\"" + SHORT_BODY + "\"]");
        assertThat(number(BODY, "SELECT content_version FROM help_article WHERE id='help_short'")).isEqualTo(1);

        assertThat(text(BODY, "SELECT body FROM help_article WHERE id='help_long'")).isEqualTo(LONG_BODY);
        assertThat(bool(BODY, "SELECT published FROM help_article WHERE id='help_long'")).isFalse();
        assertThat(text(BODY, "SELECT steps_json::text FROM help_article WHERE id='help_long'")).isEqualTo("[]");
        assertThat(number(BODY, "SELECT content_version FROM help_article WHERE id='help_long'")).isEqualTo(2);
        assertThat(number(BODY, "SELECT char_length(body) FROM help_article WHERE id='help_long'")).isEqualTo(600);

        assertThat(text(BODY, "SELECT square_id FROM content_report WHERE id='report_old'")).isEqualTo("sq");
        assertThat(text(BODY, "SELECT subject_type FROM cursor_snapshot WHERE id='cursor_old'")).isEqualTo("USER");
        assertThat(text(BODY, "SELECT subject_key FROM cursor_snapshot WHERE id='cursor_old'")).isEqualTo("u");
        assertThat(text(BODY, "SELECT user_id FROM cursor_snapshot WHERE id='cursor_old'")).isEqualTo("u");
        assertThat(number(BODY, "SELECT epoch FROM cursor_snapshot WHERE id='cursor_old'")).isEqualTo(4);
        assertThat(jdbc.queryForObject("""
                SELECT character_maximum_length FROM information_schema.columns
                WHERE table_schema = ? AND table_name = 'help_article' AND column_name = 'body'
                """, Integer.class, BODY)).isEqualTo(HelpArticleText.MAX_DERIVED_BODY);

        jdbc.update("""
                INSERT INTO %s.cursor_snapshot
                    (id, token_hash, user_id, subject_type, subject_key, scope_key, query_hash, epoch,
                     ids_json, total, position, expires_at)
                VALUES ('cursor_anon', '%s', NULL, 'ANONYMOUS', 'PUBLIC', 'help', '%s', 1,
                        '[]'::jsonb, 0, 0, '2026-11-01T00:00:00Z')
                """.formatted(BODY, "c".repeat(64), "d".repeat(64)));
        assertThat(text(BODY, "SELECT user_id FROM cursor_snapshot WHERE id='cursor_anon'")).isNull();
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO %s.cursor_snapshot
                    (id, token_hash, user_id, subject_type, subject_key, scope_key, query_hash, epoch,
                     ids_json, total, position, expires_at)
                VALUES ('cursor_bad', '%s', 'u', 'USER', 'other', 'help', '%s', 4,
                        '[]'::jsonb, 0, 0, '2026-11-01T00:00:00Z')
                """.formatted(BODY, "e".repeat(64), "f".repeat(64))))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void withdrawnHighestVersionDoesNotCollideWithOldCache() {
        reset(PACK);
        flyway(PACK, "23").migrate();
        jdbc.update("""
                INSERT INTO %s.help_article
                    (id, audience, title, summary, body, category, app_version_range, published, revision,
                     updated_at, scope, steps_json, outcomes_json, prerequisites_json, roles_json,
                     failure_action, entry_json, content_version)
                VALUES
                    ('help_visible', 'GENERAL', '可见', '', '可见步骤', 'GENERAL', '2.0.*', true, 1,
                     '2026-10-01T00:00:00Z', 'GENERAL', '["可见步骤"]'::jsonb, '[]'::jsonb, '[]'::jsonb,
                     '[]'::jsonb, '保留', '{"type":"COURSE","id":null}'::jsonb, 9),
                    ('help_cached', 'GENERAL', '已撤下', '', '旧最高', 'GENERAL', '1.0.*', false, 1,
                     '2026-10-01T00:00:00Z', 'GENERAL', '["旧最高"]'::jsonb, '[]'::jsonb, '[]'::jsonb,
                     '[]'::jsonb, '保留', '{"type":"COURSE","id":null}'::jsonb, 10)
                """.formatted(PACK));

        flyway(PACK, "24").migrate();
        assertThat(number(PACK, "SELECT version FROM help_content_pack WHERE id='GENERAL'")).isEqualTo(10);

        flyway(PACK, null).migrate();
        assertThat(number(PACK, "SELECT version FROM help_content_pack WHERE id='GENERAL'")).isEqualTo(11);
        assertThat(number(PACK, "SELECT content_version FROM help_article WHERE id='help_visible'")).isEqualTo(9);
        assertThat(number(PACK, "SELECT content_version FROM help_article WHERE id='help_cached'")).isEqualTo(10);
        assertThat(text(PACK, "SELECT app_version_range FROM help_article WHERE id='help_visible'"))
                .isEqualTo("2.0.*");
        assertThat(bool(PACK, "SELECT published FROM help_article WHERE id='help_cached'")).isFalse();
        assertThat(text(PACK, "SELECT body FROM help_article WHERE id='help_visible'")).isEqualTo("可见步骤");
    }

    private void seedBodyWorld() {
        String p = BODY + ".";
        jdbc.update("INSERT INTO " + p + "school(id, name, timezone) VALUES ('s','S','UTC')");
        jdbc.update("INSERT INTO " + p + "term(id, school_id, name, start_date, end_date, ordinal) "
                + "VALUES ('t','s','T','2026-01-01','2026-06-30',1)");
        jdbc.update("INSERT INTO " + p + "app_user(id, school_id, login_id, display_name, password_hash, "
                + "identity_status) VALUES ('u','s','login','用户','x','VERIFIED')");
        jdbc.update("INSERT INTO " + p + "square(id, school_id, term_id, name, teacher_name, slots_json, "
                + "canonical_key, square_no, status) VALUES ('sq','s','t','课程','教师','[]'::jsonb, ?, 'N1', 'ACTIVE')",
                "a".repeat(64));
        jdbc.update("INSERT INTO " + p + "assignment(id, square_id, name, requirements, origin, "
                + "confirmation_status, hard_due_at, min_members, max_members) VALUES "
                + "('asg','sq','作业','要求','STUDENT','CONFIRMED','2026-06-01T00:00:00Z',1,6)");
        jdbc.update("INSERT INTO " + p + "project(id, assignment_id, square_id, owner_id, mode, capacity, status) "
                + "VALUES ('p','asg','sq','u','QUICK',4,'ACTIVE')");
        jdbc.update("INSERT INTO " + p + "board_topic(id, project_id, author_id, title, content, tag, status, "
                + "revision, activity_at, created_at, updated_at) VALUES ('topic','p','u','题','正文','QUESTION','OPEN',1,"
                + "'2026-10-01T00:00:00Z','2026-10-01T00:00:00Z','2026-10-01T00:00:00Z')");
        jdbc.update("INSERT INTO " + p + "help_article(id, audience, title, summary, body, published, updated_at) "
                + "VALUES ('help_short','GENERAL','短','',? ,true,'2026-10-01T00:00:00Z')", SHORT_BODY);
        jdbc.update("INSERT INTO " + p + "help_article(id, audience, title, summary, body, published, updated_at) "
                + "VALUES ('help_long','GENERAL','长','',? ,true,'2026-10-01T00:00:00Z')", LONG_BODY);
        jdbc.update("INSERT INTO " + p + "content_report(id, reporter_id, client_request_id, content_type, "
                + "content_id, source_version, excerpt, reason, status, created_at, updated_at) VALUES "
                + "('report_old','u','client-old','TOPIC','topic',1,'摘录','原因','OPEN',"
                + "'2026-10-01T00:00:00Z','2026-10-01T00:00:00Z')");
        jdbc.update("INSERT INTO " + p + "cursor_snapshot(id, token_hash, user_id, scope_key, query_hash, epoch, "
                + "ids_json, total, position, expires_at) VALUES ('cursor_old', ?, 'u', 'help', ?, 4, "
                + "'[]'::jsonb, 0, 0, '2026-11-01T00:00:00Z')", "a".repeat(64), "b".repeat(64));
    }

    private void reset(String schema) {
        jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        jdbc.execute("CREATE SCHEMA " + schema);
    }

    private Flyway flyway(String schema, String target) {
        var configuration = Flyway.configure()
                .dataSource(dataSource)
                .schemas(schema)
                .defaultSchema(schema)
                .createSchemas(true)
                .locations("classpath:db/migration");
        if (target != null) {
            configuration.target(MigrationVersion.fromVersion(target));
        }
        return configuration.load();
    }

    private String text(String schema, String sql) {
        return jdbc.queryForObject(qualify(schema, sql), String.class);
    }

    private int number(String schema, String sql) {
        Integer value = jdbc.queryForObject(qualify(schema, sql), Integer.class);
        return value == null ? 0 : value;
    }

    private boolean bool(String schema, String sql) {
        return Boolean.TRUE.equals(jdbc.queryForObject(qualify(schema, sql), Boolean.class));
    }

    private static String qualify(String schema, String sql) {
        return sql.replace(" FROM ", " FROM " + schema + ".")
                .replace(" INTO ", " INTO " + schema + ".");
    }
}
