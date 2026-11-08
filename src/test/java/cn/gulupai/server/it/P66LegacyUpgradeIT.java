package cn.gulupai.server.it;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 从 V030 升级：旧停用账号不能变成可登录，待审账号不被激活，历史审计保留。
 */
@SpringBootTest
@ActiveProfiles("test")
class P66LegacyUpgradeIT {

    private static final String SCHEMA = "gulupai_p66_upgrade";

    @Autowired
    private DataSource dataSource;
    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void drop() {
        jdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
    }

    @Test
    void v030KeepsLegacyDisabledPendingAndAudit() {
        jdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        jdbc.execute("CREATE SCHEMA " + SCHEMA);
        Flyway.configure().dataSource(dataSource).schemas(SCHEMA).defaultSchema(SCHEMA)
                .locations("classpath:db/migration").target(MigrationVersion.fromVersion("030")).load().migrate();
        jdbc.update("INSERT INTO " + SCHEMA + ".school (id, name, timezone) VALUES ('s','旧学校','Asia/Shanghai')");
        jdbc.update("INSERT INTO " + SCHEMA + ".app_user (id, school_id, login_id, display_name, password_hash, "
                + "identity_status, disabled, account_status) VALUES "
                + "('legacy_off','s','legacy_off','旧停用','x','VERIFIED',true,'ACTIVE')");
        jdbc.update("INSERT INTO " + SCHEMA + ".app_user (id, school_id, login_id, display_name, password_hash, "
                + "identity_status, disabled, account_status) VALUES "
                + "('legacy_pending','s','legacy_pending','旧待审','x','PENDING',false,'PENDING_REVIEW')");
        jdbc.update("INSERT INTO " + SCHEMA + ".app_user (id, school_id, login_id, display_name, password_hash, "
                + "identity_status, disabled, account_status) VALUES "
                + "('legacy_active','s','legacy_active','旧可用','x','VERIFIED',false,'ACTIVE')");
        jdbc.update("INSERT INTO " + SCHEMA + ".audit_event (id, actor_id, action, resource_type, resource_id, reason, at) "
                + "VALUES ('audit_old','cli','TEACHER_CLAIM_APPROVE','TEACHER_CLAIM','claim_old','历史','2026-11-01T00:00:00Z')");

        Flyway.configure().dataSource(dataSource).schemas(SCHEMA).defaultSchema(SCHEMA)
                .locations("classpath:db/migration").load().migrate();

        assertThat(text("SELECT account_status FROM app_user WHERE id='legacy_off'")).isEqualTo("DISABLED");
        assertThat(text("SELECT eligibility_source FROM app_user WHERE id='legacy_off'"))
                .isEqualTo("LEGACY_DISABLED_UNCLEAR");
        assertThat(text("SELECT account_status FROM app_user WHERE id='legacy_pending'")).isEqualTo("PENDING_REVIEW");
        assertThat(text("SELECT eligibility_source FROM app_user WHERE id='legacy_pending'"))
                .isEqualTo("REGISTRATION_PENDING");
        assertThat(text("SELECT eligibility_source FROM app_user WHERE id='legacy_active'")).isEqualTo("VERIFIED_ACTIVE");
        assertThat(text("SELECT actor_id FROM audit_event WHERE id='audit_old'")).isEqualTo("cli");
        assertThat(text("SELECT action FROM audit_event WHERE id='audit_old'")).isEqualTo("TEACHER_CLAIM_APPROVE");
        jdbc.update("INSERT INTO " + SCHEMA + ".user_role (user_id, role) VALUES ('legacy_active','SUPER_ADMIN')");
        assertThat(number("SELECT count(*) FROM platform_admin_lock")).isEqualTo(1);
        assertThat(number("SELECT count(*) FROM platform_admin_grant")).isZero();
    }

    private String text(String sql) {
        return jdbc.queryForObject(sql.replace(" FROM ", " FROM " + SCHEMA + "."), String.class);
    }

    private int number(String sql) {
        Integer value = jdbc.queryForObject(sql.replace(" FROM ", " FROM " + SCHEMA + "."), Integer.class);
        return value == null ? 0 : value;
    }
}
