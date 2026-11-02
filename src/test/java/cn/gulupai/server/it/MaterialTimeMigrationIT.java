package cn.gulupai.server.it;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * U02：V020 升级、V021 已执行实例升级、新库全量和重复启动。
 *
 * <p>升级夹具的提交、审核、撤回时间显式分开；V021 回填的 updated_at 只作为
 * legacy_recorded_at 诊断值，不能成为 created_at。只对 V021 的确定性 legacy ID
 * 矫正，真实 UUID 新提交不受影响。
 */
@SpringBootTest
@ActiveProfiles("test")
class MaterialTimeMigrationIT {

    private static final String V020_SCHEMA = "gulupai_u02_v020";
    private static final String V021_SCHEMA = "gulupai_u02_v021";
    private static final String EMPTY_SCHEMA = "gulupai_u02_empty";
    private static final List<String> SCHEMAS = List.of(V020_SCHEMA, V021_SCHEMA, EMPTY_SCHEMA);

    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbc;

    @AfterEach
    void dropUpgradeSchemas() {
        for (String schema : SCHEMAS) {
            jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @Test
    void v020UpgradeDoesNotTurnReviewOrWithdrawTimeIntoSubmissionTime() {
        resetSchema(V020_SCHEMA);
        flyway(V020_SCHEMA, "20").migrate();
        seedBase(V020_SCHEMA);
        seedLegacyMaterials(V020_SCHEMA);

        MigrateResult result = flyway(V020_SCHEMA, null).migrate();
        assertThat(result.migrationsExecuted).isEqualTo(2);
        assertThat(currentVersion(V020_SCHEMA)).isEqualTo("022");

        // U02-A：只提交、无可靠提交证据，不凭 updated_at 猜时间。
        assertUnknownLegacy(V020_SCHEMA, "legacy-mat_responded", "2026-11-01T10:00:00Z");
        // U02-B：10:00 提交、11:00 审核；11:00 只能进 legacy_recorded_at。
        assertUnknownLegacy(V020_SCHEMA, "legacy-mat_reviewed", "2026-11-01T11:00:00Z");
        // U02-C：10:00 提交、12:00 撤回；仍撤回占位，不恢复正文。
        assertUnknownLegacy(V020_SCHEMA, "legacy-mat_withdrawn", "2026-11-01T12:00:00Z");
        assertThat(queryBoolean(V020_SCHEMA,
                "SELECT withdrawn FROM material_response WHERE id = 'legacy-mat_withdrawn'")).isTrue();
        assertThat(queryString(V020_SCHEMA,
                "SELECT content FROM material_response WHERE id = 'legacy-mat_withdrawn'"))
                .isEqualTo("被撤回的历史正文");

        // 版本、作者、正文不因时间矫正而改变；response_version=0 的材料没有历史版本行。
        assertThat(queryString(V020_SCHEMA,
                "SELECT author_id FROM material_response WHERE id = 'legacy-mat_reviewed'"))
                .isEqualTo("user_u02_student");
        assertThat(queryString(V020_SCHEMA,
                "SELECT content FROM material_response WHERE id = 'legacy-mat_reviewed'"))
                .isEqualTo("审核过的历史正文");
        assertThat(queryInt(V020_SCHEMA, "SELECT version FROM material_response WHERE id = 'legacy-mat_reviewed'"))
                .isEqualTo(1);
        assertThat(queryInt(V020_SCHEMA,
                "SELECT count(*) FROM material_response WHERE material_id = 'mat_empty'")).isZero();
        assertThat(queryInt(V020_SCHEMA, "SELECT count(*) FROM material_response")).isEqualTo(3);

        // material_request.updated_at 仍表示最后修改时间，不被迁移改写。
        assertThat(queryBoolean(V020_SCHEMA,
                "SELECT updated_at = '2026-11-01T11:00:00Z'::timestamptz FROM material_request "
                        + "WHERE id = 'mat_reviewed'")).isTrue();
        assertThat(queryBoolean(V020_SCHEMA,
                "SELECT updated_at = '2026-11-01T12:00:00Z'::timestamptz FROM material_request "
                        + "WHERE id = 'mat_withdrawn'")).isTrue();

        // U02-G：重复启动不重复迁移、不重复行。
        MigrateResult again = flyway(V020_SCHEMA, null).migrate();
        assertThat(again.migrationsExecuted).isZero();
        assertThat(queryInt(V020_SCHEMA, "SELECT count(*) FROM material_response")).isEqualTo(3);
    }

    @Test
    void v021InstanceUpgradeOnlyCorrectsLegacyRowsAndKeepsRealNewSubmission() {
        resetSchema(V021_SCHEMA);
        flyway(V021_SCHEMA, "20").migrate();
        seedBase(V021_SCHEMA);
        seedLegacyMaterials(V021_SCHEMA);
        flyway(V021_SCHEMA, "21").migrate();

        // V021 已执行：先确认历史回填确实使用了最后修改时间（作为对照，不重写 V021）。
        assertThat(queryBoolean(V021_SCHEMA,
                "SELECT created_at = '2026-11-01T11:00:00Z'::timestamptz FROM material_response "
                        + "WHERE id = 'legacy-mat_reviewed'")).isTrue();

        // 模拟 V021 之后的新提交：服务端 UUID + 真实 13:00 时间。
        jdbc.update("""
                INSERT INTO %s.material_request
                    (id, case_id, recipient_id, status, requirements, due_at, response_version, response_json,
                     revision, created_at, updated_at)
                VALUES ('mat_new', 'case_u02', 'user_u02_student', 'RESPONDED', '新要求',
                        '2026-11-10T15:59:59Z'::timestamptz, 1,
                        '{"content":"新提交正文","version":1}'::jsonb, 2,
                        '2026-11-01T13:00:00Z'::timestamptz, '2026-11-01T13:00:00Z'::timestamptz)
                """.formatted(V021_SCHEMA));
        jdbc.update("""
                INSERT INTO %s.material_response
                    (id, material_id, version, author_id, content, evidence_json, withdrawn, created_at)
                VALUES ('11111111-1111-1111-1111-111111111111', 'mat_new', 1, 'user_u02_student',
                        '新提交正文', '[]'::jsonb, false, '2026-11-01T13:00:00Z'::timestamptz)
                """.formatted(V021_SCHEMA));

        MigrateResult result = flyway(V021_SCHEMA, null).migrate();
        assertThat(result.migrationsExecuted).isEqualTo(1);
        assertThat(currentVersion(V021_SCHEMA)).isEqualTo("022");

        // U02-D：仅 legacy 行被矫正；真实 UUID 新提交保留原时间并标记 EXACT。
        assertUnknownLegacy(V021_SCHEMA, "legacy-mat_responded", "2026-11-01T10:00:00Z");
        assertUnknownLegacy(V021_SCHEMA, "legacy-mat_reviewed", "2026-11-01T11:00:00Z");
        assertUnknownLegacy(V021_SCHEMA, "legacy-mat_withdrawn", "2026-11-01T12:00:00Z");
        assertThat(queryBoolean(V021_SCHEMA,
                "SELECT created_at = '2026-11-01T13:00:00Z'::timestamptz FROM material_response "
                        + "WHERE id = '11111111-1111-1111-1111-111111111111'")).isTrue();
        assertThat(queryString(V021_SCHEMA,
                "SELECT created_at_confidence FROM material_response "
                        + "WHERE id = '11111111-1111-1111-1111-111111111111'")).isEqualTo("EXACT");
        assertThat(queryString(V021_SCHEMA,
                "SELECT time_source FROM material_response "
                        + "WHERE id = '11111111-1111-1111-1111-111111111111'")).isEqualTo("SERVER_SUBMISSION");
        assertThat(queryBoolean(V021_SCHEMA,
                "SELECT legacy_recorded_at IS NULL FROM material_response "
                        + "WHERE id = '11111111-1111-1111-1111-111111111111'")).isTrue();

        MigrateResult again = flyway(V021_SCHEMA, null).migrate();
        assertThat(again.migrationsExecuted).isZero();
        assertThat(queryInt(V021_SCHEMA, "SELECT count(*) FROM material_response")).isEqualTo(4);
    }

    @Test
    void freshDatabaseReachesV022AndRejectsUnlabelledOrUnsafeTimeCombinations() {
        resetSchema(EMPTY_SCHEMA);
        MigrateResult result = flyway(EMPTY_SCHEMA, null).migrate();
        assertThat(result.migrationsExecuted).isGreaterThanOrEqualTo(22);
        assertThat(currentVersion(EMPTY_SCHEMA)).isEqualTo("022");
        assertThat(queryInt(EMPTY_SCHEMA, "SELECT count(*) FROM material_response")).isZero();

        seedBase(EMPTY_SCHEMA);
        seedLegacyMaterials(EMPTY_SCHEMA);
        // 新库没有 V021 回填，不受 legacy 识别影响；显式写入 EXACT 成功。
        insertResponse(EMPTY_SCHEMA, "resp_exact", "mat_responded", 1, "正文",
                "2026-11-01T10:00:00Z", "EXACT", "SERVER_SUBMISSION");
        assertThat(queryString(EMPTY_SCHEMA,
                "SELECT created_at_confidence FROM material_response WHERE id = 'resp_exact'")).isEqualTo("EXACT");

        // 不填写可信度必须失败：新应用写入显式填写，不能靠默认值冒充准确。
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO %s.material_response
                    (id, material_id, version, author_id, content, evidence_json, withdrawn, created_at)
                VALUES ('resp_missing', 'mat_responded', 2, 'user_u02_student', '正文', '[]'::jsonb, false,
                        '2026-11-01T10:00:00Z'::timestamptz)
                """.formatted(EMPTY_SCHEMA))).isInstanceOf(DataAccessException.class);

        // EXACT 必须非空；UNKNOWN 必须为空。
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO %s.material_response
                    (id, material_id, version, author_id, content, evidence_json, withdrawn, created_at,
                     created_at_confidence, time_source)
                VALUES ('resp_bad_exact', 'mat_responded', 3, 'user_u02_student', '正文', '[]'::jsonb, false,
                        NULL, 'EXACT', 'SERVER_SUBMISSION')
                """.formatted(EMPTY_SCHEMA))).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO %s.material_response
                    (id, material_id, version, author_id, content, evidence_json, withdrawn, created_at,
                     created_at_confidence, time_source)
                VALUES ('resp_bad_unknown', 'mat_responded', 4, 'user_u02_student', '正文', '[]'::jsonb, false,
                        '2026-11-01T10:00:00Z'::timestamptz, 'UNKNOWN', 'LEGACY_UNKNOWN')
                """.formatted(EMPTY_SCHEMA))).isInstanceOf(DataAccessException.class);

        // 恢复来源必须同时登记依据和恢复时间。
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO %s.material_response
                    (id, material_id, version, author_id, content, evidence_json, withdrawn, created_at,
                     created_at_confidence, time_source)
                VALUES ('resp_bad_recovery', 'mat_responded', 5, 'user_u02_student', '正文', '[]'::jsonb, false,
                        '2026-11-01T10:00:00Z'::timestamptz, 'EXACT', 'RECOVERED_EVIDENCE')
                """.formatted(EMPTY_SCHEMA))).isInstanceOf(DataAccessException.class);

        jdbc.update("""
                INSERT INTO %s.material_response
                    (id, material_id, version, author_id, content, evidence_json, withdrawn, created_at,
                     created_at_confidence, time_source, legacy_recorded_at, time_evidence,
                     time_recovered_by, time_recovered_at)
                VALUES ('resp_unknown', 'mat_responded', 2, 'user_u02_student', '正文', '[]'::jsonb, false,
                        NULL, 'UNKNOWN', 'LEGACY_UNKNOWN', '2026-11-01T11:00:00Z'::timestamptz,
                        NULL, NULL, NULL)
                """.formatted(EMPTY_SCHEMA));
        assertThat(queryBoolean(EMPTY_SCHEMA,
                "SELECT created_at IS NULL FROM material_response WHERE id = 'resp_unknown'")).isTrue();

        MigrateResult again = flyway(EMPTY_SCHEMA, null).migrate();
        assertThat(again.migrationsExecuted).isZero();
    }

    // ---- fixture / query helpers ----

    private void resetSchema(String schema) {
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

    private String currentVersion(String schema) {
        return flyway(schema, null).info().current().getVersion().getVersion();
    }

    private void seedBase(String schema) {
        jdbc.update("INSERT INTO " + schema + ".school (id, name, timezone) VALUES "
                + "('school_u02', 'U02测试学校', 'Asia/Shanghai')");
        jdbc.update("INSERT INTO " + schema + ".term "
                + "(id, school_id, name, start_date, end_date, ordinal) VALUES "
                + "('term_u02', 'school_u02', '2026秋季', DATE '2026-09-01', DATE '2027-01-31', 1)");
        jdbc.update("INSERT INTO " + schema + ".app_user "
                + "(id, school_id, login_id, display_name, password_hash, identity_status) VALUES "
                + "('user_u02_student', 'school_u02', 'u02_student', 'U02学生', 'x', 'VERIFIED')");
        jdbc.update("INSERT INTO " + schema + ".square "
                + "(id, school_id, term_id, name, teacher_name, slots_json, canonical_key, square_no, status) "
                + "VALUES ('square_u02', 'school_u02', 'term_u02', 'U02广场', 'U02教师', '[]'::jsonb, "
                + "repeat('a', 64), 'SQ-U02', 'ACTIVE')");
        jdbc.update("INSERT INTO " + schema + ".assignment "
                + "(id, square_id, name, requirements, origin, confirmation_status, hard_due_at, "
                + "min_members, max_members) VALUES ('assignment_u02', 'square_u02', 'U02作业', '完成', "
                + "'TEACHER', 'CONFIRMED', '2026-12-15T15:59:59Z'::timestamptz, 2, 6)");
        jdbc.update("INSERT INTO " + schema + ".project "
                + "(id, assignment_id, square_id, owner_id, mode, capacity, status) VALUES "
                + "('project_u02', 'assignment_u02', 'square_u02', 'user_u02_student', 'QUICK', 4, 'ACTIVE')");
        jdbc.update("INSERT INTO " + schema + ".personnel_case "
                + "(id, square_id, project_id, subject_user_id, assignee_id, title, status, member_change_id, "
                + "revision, created_at, updated_at) VALUES ('case_u02', 'square_u02', 'project_u02', "
                + "'user_u02_student', NULL, 'U02事项', 'IN_PROGRESS', NULL, 1, "
                + "'2026-10-01T00:00:00Z'::timestamptz, '2026-10-01T00:00:00Z'::timestamptz)");
    }

    private void seedLegacyMaterials(String schema) {
        insertMaterialRequest(schema, "mat_responded", "RESPONDED", "只提交未审核",
                "2026-11-01T10:00:00Z", "2026-11-01T10:00:00Z");
        insertMaterialRequest(schema, "mat_reviewed", "ACCEPTED", "审核过的历史正文",
                "2026-11-01T10:00:00Z", "2026-11-01T11:00:00Z");
        insertMaterialRequest(schema, "mat_withdrawn", "WITHDRAWN", "被撤回的历史正文",
                "2026-11-01T10:00:00Z", "2026-11-01T12:00:00Z");
        jdbc.update("""
                INSERT INTO %s.material_request
                    (id, case_id, recipient_id, status, requirements, due_at, response_version, response_json,
                     revision, created_at, updated_at)
                VALUES ('mat_empty', 'case_u02', 'user_u02_student', 'OPEN', '空版本',
                        '2026-11-10T15:59:59Z'::timestamptz, 0, '{}'::jsonb, 1,
                        '2026-11-01T10:00:00Z'::timestamptz, '2026-11-01T10:00:00Z'::timestamptz)
                """.formatted(schema));
    }

    private void insertMaterialRequest(String schema, String id, String status, String content,
                                       String createdAt, String updatedAt) {
        jdbc.update("""
                INSERT INTO %s.material_request
                    (id, case_id, recipient_id, status, requirements, due_at, response_version, response_json,
                     revision, created_at, updated_at)
                VALUES (?, 'case_u02', 'user_u02_student', ?, '历史要求',
                        '2026-11-10T15:59:59Z'::timestamptz, 1,
                        jsonb_build_object('content', ?, 'version', 1), 1,
                        ?::timestamptz, ?::timestamptz)
                """.formatted(schema), id, status, content, createdAt, updatedAt);
    }

    private void insertResponse(String schema, String id, String materialId, int version, String content,
                                String createdAt, String confidence, String timeSource) {
        jdbc.update("""
                INSERT INTO %s.material_response
                    (id, material_id, version, author_id, content, evidence_json, withdrawn, created_at,
                     created_at_confidence, time_source)
                VALUES (?, ?, ?, 'user_u02_student', ?, '[]'::jsonb, false, ?::timestamptz, ?, ?)
                """.formatted(schema), id, materialId, version, content, createdAt, confidence, timeSource);
    }

    private void assertUnknownLegacy(String schema, String id, String legacyTime) {
        assertThat(queryBoolean(schema,
                "SELECT created_at IS NULL FROM material_response WHERE id = '" + id + "'")).isTrue();
        assertThat(queryString(schema,
                "SELECT created_at_confidence FROM material_response WHERE id = '" + id + "'"))
                .isEqualTo("UNKNOWN");
        assertThat(queryString(schema,
                "SELECT time_source FROM material_response WHERE id = '" + id + "'"))
                .isEqualTo("LEGACY_UNKNOWN");
        assertThat(queryBoolean(schema,
                "SELECT legacy_recorded_at = '" + legacyTime + "'::timestamptz FROM material_response "
                        + "WHERE id = '" + id + "'")).isTrue();
    }

    private int queryInt(String schema, String sql) {
        Integer value = jdbc.queryForObject(scoped(schema, sql), Integer.class);
        return value == null ? 0 : value;
    }

    private String queryString(String schema, String sql) {
        return jdbc.queryForObject(scoped(schema, sql), String.class);
    }

    private boolean queryBoolean(String schema, String sql) {
        Boolean value = jdbc.queryForObject(scoped(schema, sql), Boolean.class);
        return Boolean.TRUE.equals(value);
    }

    /** 断言 SQL 使用不带 schema 的表名，执行前补上目标升级 schema。 */
    private String scoped(String schema, String sql) {
        return sql.replace("FROM material_response", "FROM " + schema + ".material_response")
                .replace("FROM material_request", "FROM " + schema + ".material_request");
    }
}
