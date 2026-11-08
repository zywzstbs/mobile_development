package cn.gulupai.server.platform;

import cn.gulupai.server.common.clock.MutableClock;
import cn.gulupai.server.identity.cli.AdminImportService;
import cn.gulupai.server.it.TestSchemaCleaner;
import cn.gulupai.server.platform.application.PlatformSuperAdminService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * R1/R4 回归：管理写入的串行化与“最后可用全平台恢复管理员”保护。
 * 用真实连接与锁等待观察点验证顺序，不靠 sleep 猜测先后。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PlatformAdminTransactionIT.TestClockConfig.class)
class PlatformAdminTransactionIT {

    private static final Instant BASE = Instant.parse("2026-11-05T00:00:00Z");
    private static final String ADMIN = "user_demo_teacher_01";
    private static final String ADMIN_LOGIN = "teacher_demo_01";
    private static final String ADMIN_PASSWORD = "demo-password-03";
    private static final String SECOND = "user_demo_teacher_02";
    private static final String SECOND_LOGIN = "teacher_demo_02";
    private static final String SECOND_PASSWORD = "demo-password-05";
    private static final String TARGET = "user_demo_student_01";
    private static final String OTHER_TARGET = "user_demo_student_02";
    private static final String GRANT_PRECONDITION = "NO_ACTIVE_GRANT";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private MutableClock clock;
    @Autowired private AdminImportService importService;
    @Autowired private PlatformSuperAdminService superAdmins;

    @BeforeEach
    void setUp() {
        clearAll();
        clock.setInstant(BASE);
        importService.importUsers(Path.of("admin/fixtures/p01-demo-users.json"), false);
        jdbc.update("UPDATE school SET registration_enabled = true WHERE id = 'school_demo_01'");
        superAdmins.grantInitial(ADMIN, ADMIN, "隔离环境首位管理员", false);
        superAdmins.grantInitial(ADMIN, SECOND, "隔离环境第二位管理员", false);
    }

    @AfterEach
    void tearDown() {
        clearAll();
    }

    @Test
    void revocationCommittedFirstMakesTheAlreadyIssuedGrantFail() throws Exception {
        String admin = login(ADMIN_LOGIN, ADMIN_PASSWORD);
        String second = login(SECOND_LOGIN, SECOND_PASSWORD);
        String pendingGrant = grantReauth(admin, ADMIN_PASSWORD, "op_r1_late", "稍后提交的增设", TARGET);

        revokeSuperAdmin(second, SECOND_PASSWORD, ADMIN, grantRevision(ADMIN), "op_r1_revoke", "先撤销操作者");

        mockMvc.perform(post("/api/v1/admin/super-admins")
                        .header("Authorization", bearer(admin))
                        .header("X-Operation-Id", "op_r1_late")
                        .header("Idempotency-Key", "key_r1_late")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "targetUserId", TARGET,
                                "reason", "稍后提交的增设",
                                "reauthToken", pendingGrant))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        assertThat(activeGrantCount(TARGET)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM admin_operation WHERE operation_id = 'op_r1_late'", Integer.class)).isZero();
        assertThat(usablePlatformRecoveryCount()).isEqualTo(1);
    }

    @Test
    void inFlightGrantKeepsTheConstraintLockSoRevocationCannotOvertakeIt() throws Exception {
        String admin = login(ADMIN_LOGIN, ADMIN_PASSWORD);
        String second = login(SECOND_LOGIN, SECOND_PASSWORD);
        int adminRevision = grantRevision(ADMIN);
        String grantToken = grantReauth(admin, ADMIN_PASSWORD, "op_r1_race_grant", "并发增设", TARGET);
        String revokeToken = revokeReauth(second, SECOND_PASSWORD, "op_r1_race_revoke", "并发撤销", ADMIN,
                adminRevision);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (PreparedStatement lock = holder.prepareStatement(
                    "SELECT revision FROM app_user WHERE id = ? FOR UPDATE")) {
                lock.setString(1, TARGET);
                lock.executeQuery();
            }
            Future<Integer> granting = pool.submit(() -> postGrant(admin, "op_r1_race_grant", "key_r1_race_grant",
                    "并发增设", grantToken));
            awaitLockWait("app_user", Duration.ofSeconds(20));

            Future<Integer> revoking = pool.submit(() -> postRevoke(second, ADMIN, adminRevision,
                    "op_r1_race_revoke", "key_r1_race_revoke", "并发撤销", revokeToken));
            awaitLockWait("platform_admin_lock", Duration.ofSeconds(20));

            holder.rollback();
            assertThat(granting.get(30, TimeUnit.SECONDS)).isEqualTo(200);
            assertThat(revoking.get(30, TimeUnit.SECONDS)).isEqualTo(200);
        } finally {
            pool.shutdownNow();
        }

        assertThat(activeGrantCount(TARGET)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM platform_admin_grant WHERE user_id = ? ORDER BY granted_at LIMIT 1",
                String.class, ADMIN)).isEqualTo("REVOKED");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM user_role WHERE user_id = ? AND role = 'SUPER_ADMIN'",
                Integer.class, ADMIN)).isZero();
        mockMvc.perform(get("/api/v1/admin/dashboard").header("Authorization", bearer(admin)))
                .andExpect(status().isForbidden());
        // setUp 里 SECOND 也是全平台管理员；撤销 ADMIN 后，SECOND 与本次新增的 TARGET 都仍可用。
        assertThat(usablePlatformRecoveryCount()).isEqualTo(2);
    }

    @Test
    void lastPlatformAdminSurvivesWhileOnlyASchoolScopedAdminRemains() throws Exception {
        grantSchoolScopedAdmin(SECOND);
        String admin = login(ADMIN_LOGIN, ADMIN_PASSWORD);
        int revision = grantRevision(ADMIN);

        String token = revokeReauth(admin, ADMIN_PASSWORD, "op_r4_school", "只剩学校范围管理员", ADMIN, revision);
        mockMvc.perform(post("/api/v1/admin/super-admins/{id}/revocations", ADMIN)
                        .header("Authorization", bearer(admin))
                        .header("X-Operation-Id", "op_r4_school")
                        .header("Idempotency-Key", "key_r4_school")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "reason", "只剩学校范围管理员",
                                "expectedRevision", revision,
                                "reauthToken", token))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LAST_ACTIVE_SUPER_ADMIN"));

        assertThat(activeGrantCount(ADMIN)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM user_role WHERE user_id = ? AND role = 'SUPER_ADMIN'",
                Integer.class, ADMIN)).isEqualTo(1);
    }

    @Test
    void grantWithoutTheSuperAdminRoleIsNotCountedAsRecoverable() throws Exception {
        grantPlatformFor(SECOND);
        jdbc.update("DELETE FROM user_role WHERE user_id = ? AND role = 'SUPER_ADMIN'", SECOND);
        assertThat(usablePlatformRecoveryCount()).isEqualTo(1);

        String admin = login(ADMIN_LOGIN, ADMIN_PASSWORD);
        int revision = grantRevision(ADMIN);
        String token = revokeReauth(admin, ADMIN_PASSWORD, "op_r4_role_gap", "角色缺口不算恢复管理员", ADMIN,
                revision);
        mockMvc.perform(post("/api/v1/admin/super-admins/{id}/revocations", ADMIN)
                        .header("Authorization", bearer(admin))
                        .header("X-Operation-Id", "op_r4_role_gap")
                        .header("Idempotency-Key", "key_r4_role_gap")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "reason", "角色缺口不算恢复管理员",
                                "expectedRevision", revision,
                                "reauthToken", token))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LAST_ACTIVE_SUPER_ADMIN"));
    }

    @Test
    void disabledPlatformAdminIsNotCountedAsRecoverable() throws Exception {
        grantPlatformFor(SECOND);
        jdbc.update("""
                UPDATE app_user SET disabled = true, account_status = 'DISABLED', eligibility_source = 'ADMIN_DISABLED'
                WHERE id = ?
                """, SECOND);
        assertThat(usablePlatformRecoveryCount()).isEqualTo(1);

        String admin = login(ADMIN_LOGIN, ADMIN_PASSWORD);
        int revision = grantRevision(ADMIN);
        String token = revokeReauth(admin, ADMIN_PASSWORD, "op_r4_disabled", "停用账号不算恢复管理员", ADMIN,
                revision);
        mockMvc.perform(post("/api/v1/admin/super-admins/{id}/revocations", ADMIN)
                        .header("Authorization", bearer(admin))
                        .header("X-Operation-Id", "op_r4_disabled")
                        .header("Idempotency-Key", "key_r4_disabled")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "reason", "停用账号不算恢复管理员",
                                "expectedRevision", revision,
                                "reauthToken", token))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LAST_ACTIVE_SUPER_ADMIN"));

        String disableToken = statusReauth(admin, ADMIN_PASSWORD, "op_r4_self_disable", "不能停用最后管理员",
                ADMIN, revision);
        mockMvc.perform(post("/api/v1/admin/users/{id}/status-changes", ADMIN)
                        .header("Authorization", bearer(admin))
                        .header("X-Operation-Id", "op_r4_self_disable")
                        .header("Idempotency-Key", "key_r4_self_disable")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "status", "DISABLED",
                                "reason", "不能停用最后管理员",
                                "expectedRevision", revision,
                                "reauthToken", disableToken))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LAST_ACTIVE_SUPER_ADMIN"));

        assertThat(jdbc.queryForObject(
                "SELECT account_status FROM app_user WHERE id = ?", String.class, ADMIN)).isEqualTo("ACTIVE");
    }

    @Test
    void mixedDisableAndRevocationLeaveExactlyOneUsablePlatformAdmin() throws Exception {
        String admin = login(ADMIN_LOGIN, ADMIN_PASSWORD);
        String second = login(SECOND_LOGIN, SECOND_PASSWORD);
        int adminRevision = grantRevision(ADMIN);
        int secondUserRevision = jdbc.queryForObject(
                "SELECT revision FROM app_user WHERE id = ?", Integer.class, SECOND);

        String revokeToken = revokeReauth(second, SECOND_PASSWORD, "op_r4_mixed_revoke", "并发撤销甲", ADMIN,
                adminRevision);
        String disableToken = statusReauth(admin, ADMIN_PASSWORD, "op_r4_mixed_disable", "并发停用乙", SECOND,
                secondUserRevision);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> revoking = pool.submit(() -> postRevoke(second, ADMIN, adminRevision,
                    "op_r4_mixed_revoke", "key_r4_mixed_revoke", "并发撤销甲", revokeToken));
            Future<Integer> disabling = pool.submit(() -> postDisable(admin, SECOND, secondUserRevision,
                    "op_r4_mixed_disable", "key_r4_mixed_disable", "并发停用乙", disableToken));
            int revoked = revoking.get(30, TimeUnit.SECONDS);
            int disabled = disabling.get(30, TimeUnit.SECONDS);
            assertThat(List.of(revoked, disabled)).filteredOn(code -> code == 200).hasSize(1);
            assertThat(List.of(revoked, disabled))
                    .allMatch(code -> code == 200 || code == 401 || code == 403 || code == 409);
        } finally {
            pool.shutdownNow();
        }
        assertThat(usablePlatformRecoveryCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM platform_admin_grant g
                JOIN app_user u ON u.id = g.user_id
                WHERE g.status = 'ACTIVE' AND u.account_status = 'ACTIVE' AND u.disabled = false
                """, Integer.class)).isEqualTo(1);
    }

    /**
     * 第二轮 S5：verifyUnused 通过后，请求可能在目标行锁上等待很久。等待期间时钟越过 TTL 时，
     * 消费必须失败并让业务、审计与操作记录一起回滚；已提交回放不要求原令牌仍有效。
     */
    @Test
    void reauthTokenExpiringWhileWaitingForTheTargetLockLeavesNoTraceAndStillReplays() throws Exception {
        String admin = login(ADMIN_LOGIN, ADMIN_PASSWORD);
        int revision = userRevision(TARGET);
        String reauthToken = statusReauth(admin, ADMIN_PASSWORD, "op_r5_expiry", "锁等待中过期", TARGET, revision);
        int audits = auditCount();
        int operations = operationCount();

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (PreparedStatement lock = holder.prepareStatement(
                    "SELECT id FROM app_user WHERE id = ? FOR UPDATE")) {
                lock.setString(1, TARGET);
                lock.executeQuery();
            }
            Future<Integer> pending = pool.submit(() -> postDisable(admin, TARGET, revision,
                    "op_r5_expiry", "key_r5_expiry", "锁等待中过期", reauthToken));
            awaitLockWait("FROM app_user", Duration.ofSeconds(10));
            // 令牌在 BASE+5 分钟过期；业务行锁尚未释放时把时钟推过 TTL。
            clock.setInstant(BASE.plus(Duration.ofMinutes(6)));
            holder.rollback();
            assertThat(pending.get(30, TimeUnit.SECONDS)).isEqualTo(403);
        } finally {
            pool.shutdownNow();
        }

        assertThat(jdbc.queryForObject("SELECT account_status FROM app_user WHERE id = ?", String.class, TARGET))
                .isEqualTo("ACTIVE");
        assertThat(userRevision(TARGET)).isEqualTo(revision);
        assertThat(auditCount()).isEqualTo(audits);
        assertThat(operationCount()).isEqualTo(operations);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM admin_reauthentication WHERE operation_id = 'op_r5_expiry' "
                        + "AND consumed_at IS NOT NULL", Integer.class)).isZero();

        // 回到令牌有效期内：正常停用另一个目标应成功，证明失败原因确实是等待期间过期。
        clock.setInstant(BASE);
        int otherRevision = userRevision(OTHER_TARGET);
        String normalToken = statusReauth(admin, ADMIN_PASSWORD, "op_r5_ok", "正常停用", OTHER_TARGET, otherRevision);
        assertThat(postDisable(admin, OTHER_TARGET, otherRevision, "op_r5_ok", "key_r5_ok",
                "正常停用", normalToken)).isEqualTo(200);
        int auditsAfter = auditCount();
        int operationsAfter = operationCount();

        // 已提交回放不再要求原令牌未过期或未消费。
        clock.setInstant(BASE.plus(Duration.ofMinutes(10)));
        assertThat(postDisable(admin, OTHER_TARGET, otherRevision, "op_r5_ok", "key_r5_ok",
                "正常停用", normalToken)).isEqualTo(200);
        assertThat(auditCount()).isEqualTo(auditsAfter);
        assertThat(operationCount()).isEqualTo(operationsAfter);
        assertThat(jdbc.queryForObject("SELECT account_status FROM app_user WHERE id = ?", String.class, OTHER_TARGET))
                .isEqualTo("DISABLED");
    }

    private int postGrant(String token, String operationId, String key, String reason, String reauthToken)
            throws Exception {
        return mockMvc.perform(post("/api/v1/admin/super-admins")
                        .header("Authorization", bearer(token))
                        .header("X-Operation-Id", operationId)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "targetUserId", TARGET,
                                "reason", reason,
                                "reauthToken", reauthToken))))
                .andReturn().getResponse().getStatus();
    }

    private int postRevoke(String token, String userId, int revision, String operationId, String key, String reason,
                           String reauthToken) throws Exception {
        return mockMvc.perform(post("/api/v1/admin/super-admins/{id}/revocations", userId)
                        .header("Authorization", bearer(token))
                        .header("X-Operation-Id", operationId)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "reason", reason,
                                "expectedRevision", revision,
                                "reauthToken", reauthToken))))
                .andReturn().getResponse().getStatus();
    }

    private int postDisable(String token, String userId, int revision, String operationId, String key, String reason,
                            String reauthToken) throws Exception {
        return mockMvc.perform(post("/api/v1/admin/users/{id}/status-changes", userId)
                        .header("Authorization", bearer(token))
                        .header("X-Operation-Id", operationId)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "status", "DISABLED",
                                "reason", reason,
                                "expectedRevision", revision,
                                "reauthToken", reauthToken))))
                .andReturn().getResponse().getStatus();
    }

    private void revokeSuperAdmin(String token, String password, String userId, int revision, String operationId,
                                    String reason) throws Exception {
        String reauthToken = revokeReauth(token, password, operationId, reason, userId, revision);
        mockMvc.perform(post("/api/v1/admin/super-admins/{id}/revocations", userId)
                        .header("Authorization", bearer(token))
                        .header("X-Operation-Id", operationId)
                        .header("Idempotency-Key", "key_" + operationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "reason", reason,
                                "expectedRevision", revision,
                                "reauthToken", reauthToken))))
                .andExpect(status().isOk());
    }

    private String grantReauth(String token, String password, String operationId, String reason, String targetUserId)
            throws Exception {
        return reauth(token, password, operationId, "SUPER_ADMIN_GRANT", "USER", targetUserId, null,
                GRANT_PRECONDITION, null, reason, null, null, targetUserId);
    }

    private String revokeReauth(String token, String password, String operationId, String reason, String userId,
                                 int revision) throws Exception {
        return reauth(token, password, operationId, "SUPER_ADMIN_REVOKE", "USER", userId, revision, null, null, reason,
                null, null, null);
    }

    private String statusReauth(String token, String password, String operationId, String reason, String userId,
                                 int revision) throws Exception {
        return reauth(token, password, operationId, "ACCOUNT_STATUS_CHANGE", "USER", userId, revision, null, null,
                reason, null, "DISABLED", null);
    }

    private String reauth(String token, String password, String operationId, String action, String targetType,
                          String targetId, Integer revision, String precondition, String decision, String reason,
                          String note, String statusValue, String targetUserId) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("password", password);
        body.put("operationId", operationId);
        body.put("action", action);
        body.put("targetType", targetType);
        body.put("targetId", targetId);
        if (revision != null) {
            body.put("expectedRevision", revision);
        }
        if (precondition != null) {
            body.put("precondition", precondition);
        }
        if (decision != null) {
            body.put("decision", decision);
        }
        if (reason != null) {
            body.put("reason", reason);
        }
        if (note != null) {
            body.put("note", note);
        }
        if (statusValue != null) {
            body.put("status", statusValue);
        }
        if (targetUserId != null) {
            body.put("targetUserId", targetUserId);
        }
        MvcResult result = mockMvc.perform(post("/api/v1/auth/reauthentications")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk()).andReturn();
        return data(result).get("reauthToken").asText();
    }

    private void awaitLockWait(String needle, Duration timeout) throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            Integer waiting = jdbc.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity
                    WHERE datname = current_database() AND state = 'active' AND wait_event_type = 'Lock'
                      AND query LIKE ?
                    """, Integer.class, "%" + needle + "%");
            if (waiting != null && waiting > 0) {
                return;
            }
            Thread.sleep(25L);
        }
        throw new AssertionError("没有在 " + timeout.toSeconds() + " 秒内观察到等待 " + needle + " 的锁等待");
    }

    private void deleteGrantChain(String userId) {
        jdbc.update("DELETE FROM platform_admin_capability WHERE grant_id IN "
                + "(SELECT id FROM platform_admin_grant WHERE user_id = ?)", userId);
        jdbc.update("DELETE FROM platform_admin_scope WHERE grant_id IN "
                + "(SELECT id FROM platform_admin_grant WHERE user_id = ?)", userId);
        jdbc.update("DELETE FROM platform_admin_grant WHERE user_id = ?", userId);
    }

    private void grantPlatformFor(String userId) {
        // setUp 已用 CLI 路径给 SECOND 建过 ACTIVE 授权；同一用户只能有一行 ACTIVE 授权，
        // 这里先清掉旧授权链，再按用例需要重建。
        deleteGrantChain(userId);
        jdbc.update("""
                INSERT INTO platform_admin_grant (id, user_id, status, granted_by, granted_at)
                VALUES ('grant_platform_second', ?, 'ACTIVE', ?, '2026-11-05T00:00:00Z')
                """, userId, ADMIN);
        for (String capability : List.of(
                "PLATFORM_ACCOUNT_READ", "PLATFORM_ACCOUNT_MANAGE", "PLATFORM_TEACHER_REVIEW",
                "PLATFORM_COURSE_GOVERNANCE", "PLATFORM_ROUTING_MANAGE", "PLATFORM_AUDIT_READ",
                "PLATFORM_SUPER_ADMIN_MANAGE")) {
            jdbc.update("""
                    INSERT INTO platform_admin_capability (grant_id, capability)
                    VALUES ('grant_platform_second', ?)
                    """, capability);
        }
        jdbc.update("""
                INSERT INTO platform_admin_scope (grant_id, scope_type, school_id)
                VALUES ('grant_platform_second', 'PLATFORM', NULL)
                """);
        jdbc.update("INSERT INTO user_role (user_id, role) VALUES (?, 'SUPER_ADMIN') ON CONFLICT DO NOTHING",
                userId);
    }

    private void grantSchoolScopedAdmin(String userId) {
        deleteGrantChain(userId);
        jdbc.update("""
                INSERT INTO platform_admin_grant (id, user_id, status, granted_by, granted_at)
                VALUES ('grant_school_second', ?, 'ACTIVE', ?, '2026-11-05T00:00:00Z')
                """, userId, ADMIN);
        for (String capability : List.of(
                "PLATFORM_ACCOUNT_READ", "PLATFORM_ACCOUNT_MANAGE", "PLATFORM_TEACHER_REVIEW",
                "PLATFORM_COURSE_GOVERNANCE", "PLATFORM_ROUTING_MANAGE", "PLATFORM_AUDIT_READ",
                "PLATFORM_SUPER_ADMIN_MANAGE")) {
            jdbc.update("""
                    INSERT INTO platform_admin_capability (grant_id, capability)
                    VALUES ('grant_school_second', ?)
                    """, capability);
        }
        jdbc.update("""
                INSERT INTO platform_admin_scope (grant_id, scope_type, school_id)
                VALUES ('grant_school_second', 'SCHOOL', 'school_demo_01')
                """);
        jdbc.update("INSERT INTO user_role (user_id, role) VALUES (?, 'SUPER_ADMIN') ON CONFLICT DO NOTHING",
                userId);
    }

    private int activeGrantCount(String userId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM platform_admin_grant WHERE user_id = ? AND status = 'ACTIVE'",
                Integer.class, userId);
        return count == null ? 0 : count;
    }

    /**
     * 与 PlatformRepository 的恢复管理员谓词保持同一口径：角色、账号、能力、全平台范围与凭据齐全。
     */
    private int usablePlatformRecoveryCount() {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM platform_admin_grant g
                JOIN app_user u ON u.id = g.user_id
                WHERE g.status = 'ACTIVE' AND u.account_status = 'ACTIVE' AND u.disabled = false
                  AND u.identity_status = 'VERIFIED' AND u.eligibility_source = 'VERIFIED_ACTIVE'
                  AND u.password_hash IS NOT NULL AND length(u.password_hash) > 0
                  AND EXISTS (SELECT 1 FROM user_role r WHERE r.user_id = u.id AND r.role = 'SUPER_ADMIN')
                  AND EXISTS (SELECT 1 FROM platform_admin_capability c
                              WHERE c.grant_id = g.id AND c.capability = 'PLATFORM_SUPER_ADMIN_MANAGE')
                  AND EXISTS (SELECT 1 FROM platform_admin_capability c
                              WHERE c.grant_id = g.id AND c.capability = 'PLATFORM_ACCOUNT_MANAGE')
                  AND EXISTS (SELECT 1 FROM platform_admin_scope s
                              WHERE s.grant_id = g.id AND s.scope_type = 'PLATFORM')
                """, Integer.class);
        return count == null ? 0 : count;
    }

    private int userRevision(String userId) {
        return jdbc.queryForObject("SELECT revision FROM app_user WHERE id = ?", Integer.class, userId);
    }

    private int auditCount() {
        return jdbc.queryForObject("SELECT count(*) FROM audit_event", Integer.class);
    }

    private int operationCount() {
        return jdbc.queryForObject("SELECT count(*) FROM admin_operation", Integer.class);
    }

    private int grantRevision(String userId) {
        return jdbc.queryForObject("""
                SELECT revision FROM platform_admin_grant WHERE user_id = ? AND status = 'ACTIVE'
                """, Integer.class, userId);
    }

    private String login(String loginId, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/sessions").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("loginId", loginId, "password", password))))
                .andExpect(status().isOk()).andReturn();
        return data(result).get("accessToken").asText();
    }

    private JsonNode data(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private void clearAll() {
        TestSchemaCleaner.deleteAll(jdbc, List.of(
                "operation_receipt", "access_token", "auth_session", "audit_event", "rate_window",
                "import_batch", "registration_receipt", "registration_application", "cursor_snapshot",
                "teacher_transfer", "teacher_claim", "teacher_assignment", "class_grant", "course_code",
                "course_join_request", "assignment", "course_membership", "square", "verified_enrollment",
                "verified_course", "course_name", "period", "term", "user_role", "user_profile", "app_user",
                "school"));
    }

    @TestConfiguration
    static class TestClockConfig {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(BASE, ZoneOffset.UTC);
        }
    }
}
