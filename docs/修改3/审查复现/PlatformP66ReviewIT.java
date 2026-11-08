/*
 * 历史缺陷复现探针（2026-11-06 第一轮审查原文，保留作证据）。
 *
 * 本文件位于 docs/ 下，不参与构建与测试；其中的断言描述的是修复前的行为，
 * 即“漏洞可以发生”。它不能作为安全验收证据，也不要把它放回 src/test。
 *
 * 对应的安全回归已经重写为正式用例：
 *   src/test/java/cn/gulupai/server/platform/PlatformAdminTransactionIT.java   （R1、R4）
 *   src/test/java/cn/gulupai/server/platform/PlatformImportP66IT.java          （R2）
 *   src/test/java/cn/gulupai/server/platform/PlatformReadPrivacyP66IT.java     （R3、R6）
 *   src/test/java/cn/gulupai/server/platform/PlatformReplayScopeP66IT.java     （R5）
 */
package cn.gulupai.server.platform;

import cn.gulupai.server.common.audit.AdminAuditGate;
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

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PlatformP66ReviewIT.TestClockConfig.class)
/** 审查探针：断言当前缺陷可触发；通过不代表安全验收通过。修复后须反转相应断言。 */
class PlatformP66ReviewIT {

    private static final Instant BASE = Instant.parse("2026-11-05T00:00:00Z");
    private static final String ADMIN = "teacher_demo_01";
    private static final String ADMIN_PASSWORD = "demo-password-03";
    private static final String OTHER_ADMIN = "teacher_demo_02";
    private static final String OTHER_PASSWORD = "demo-password-05";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MutableClock clock;
    @Autowired private AdminImportService importService;
    @Autowired private PlatformSuperAdminService superAdmins;
    @Autowired private AdminAuditGate auditGate;

    @BeforeEach
    void setUp() {
        clearAll();
        clock.setInstant(BASE);
        importService.importUsers(Path.of("admin/fixtures/p01-demo-users.json"), false);
        jdbc.update("UPDATE school SET registration_enabled = true WHERE id = 'school_demo_01'");
        superAdmins.grantInitial("user_demo_teacher_01", "user_demo_teacher_01", "隔离环境首位管理员", false);
    }

    @AfterEach
    void tearDown() {
        auditGate.clear();
        clearAll();
    }

    private void grantReadOnly(String userId) {
        jdbc.update("""
                INSERT INTO platform_admin_grant (id, user_id, status, granted_by, granted_at)
                VALUES ('grant_read', ?, 'ACTIVE', 'user_demo_teacher_01', '2026-11-05T00:00:00Z')
                """, userId);
        jdbc.update("""
                INSERT INTO platform_admin_capability (grant_id, capability)
                VALUES ('grant_read', 'PLATFORM_ACCOUNT_READ')
                """);
        jdbc.update("""
                INSERT INTO platform_admin_scope (grant_id, scope_type, school_id)
                VALUES ('grant_read', 'PLATFORM', NULL)
                """);
        jdbc.update("INSERT INTO user_role (user_id, role) VALUES (?, 'SUPER_ADMIN')", userId);
    }

    private void grantSchool(String userId, String schoolId) {
        jdbc.update("""
                INSERT INTO platform_admin_grant (id, user_id, status, granted_by, granted_at)
                VALUES ('grant_school', ?, 'ACTIVE', 'user_demo_teacher_01', '2026-11-05T00:00:00Z')
                """, userId);
        for (String capability : List.of(
                "PLATFORM_ACCOUNT_READ", "PLATFORM_ACCOUNT_MANAGE", "PLATFORM_TEACHER_REVIEW",
                "PLATFORM_COURSE_GOVERNANCE", "PLATFORM_ROUTING_MANAGE", "PLATFORM_AUDIT_READ",
                "PLATFORM_SUPER_ADMIN_MANAGE")) {
            jdbc.update("INSERT INTO platform_admin_capability (grant_id, capability) VALUES ('grant_school', ?)",
                    capability);
        }
        jdbc.update("""
                INSERT INTO platform_admin_scope (grant_id, scope_type, school_id)
                VALUES ('grant_school', 'SCHOOL', ?)
                """, schoolId);
        jdbc.update("INSERT INTO user_role (user_id, role) VALUES (?, 'SUPER_ADMIN') ON CONFLICT DO NOTHING", userId);
    }

    private int recoveryCount() {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM platform_admin_grant g
                JOIN app_user u ON u.id = g.user_id
                WHERE g.status = 'ACTIVE' AND u.account_status = 'ACTIVE' AND u.disabled = false
                  AND EXISTS (SELECT 1 FROM platform_admin_capability c
                              WHERE c.grant_id = g.id AND c.capability = 'PLATFORM_SUPER_ADMIN_MANAGE')
                  AND EXISTS (SELECT 1 FROM platform_admin_capability c
                              WHERE c.grant_id = g.id AND c.capability = 'PLATFORM_ACCOUNT_MANAGE')
                """, Integer.class);
        return count == null ? 0 : count;
    }

    private String recoveryUser() {
        return jdbc.queryForObject("""
                SELECT g.user_id FROM platform_admin_grant g
                JOIN app_user u ON u.id = g.user_id
                WHERE g.status = 'ACTIVE' AND u.account_status = 'ACTIVE' AND u.disabled = false
                ORDER BY g.user_id LIMIT 1
                """, String.class);
    }

    private int grantRevision(String userId) {
        return jdbc.queryForObject("""
                SELECT revision FROM platform_admin_grant WHERE user_id = ? AND status = 'ACTIVE'
                """, Integer.class, userId);
    }

    private int userRevision(String token, String userId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/admin/users/{id}", userId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andReturn();
        return data(result).get("user").get("revision").asInt();
    }

    private String reauth(String token, String operationId, String action, String targetType, String targetId,
                          Integer revision, String precondition, String decision, String reason, String note,
                          String statusValue, String targetUserId) throws Exception {
        return reauthWith(token, ADMIN_PASSWORD, operationId, action, targetType, targetId, revision, precondition,
                decision, reason, note, statusValue, targetUserId);
    }

    private String reauthWith(String token, String password, String operationId, String action, String targetType,
                              String targetId, Integer revision, String precondition, String decision, String reason,
                              String note, String statusValue, String targetUserId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/reauthentications")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reauthBody(operationId, action, targetType, targetId, revision, precondition,
                                decision, reason, note, statusValue, targetUserId, password)))
                .andExpect(status().isOk()).andReturn();
        return data(result).get("reauthToken").asText();
    }

    private String reauthBody(String operationId, String action, String targetType, String targetId,
                              Integer revision, String precondition, String decision, String reason, String note,
                              String statusValue, String targetUserId, String password) throws Exception {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
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
        return objectMapper.writeValueAsString(body);
    }

    private MvcResult write(String token, String path, String operationId, String key, Map<String, Object> body)
            throws Exception {
        return mockMvc.perform(post(path)
                        .header("Authorization", bearer(token))
                        .header("Idempotency-Key", key)
                        .header("X-Operation-Id", operationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk()).andReturn();
    }

    private String registration(String loginId, String role, String teacherApplication) throws Exception {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("loginId", loginId);
        body.put("password", "example-password-123");
        body.put("displayName", "示例同学");
        body.put("schoolId", "school_demo_01");
        body.put("requestedRole", role);
        body.put("teacherApplication", teacherApplication);
        return objectMapper.writeValueAsString(body);
    }

    private String login(String loginId, String password) throws Exception {
        return loginSession(loginId, password).access();
    }

    private Tokens loginSession(String loginId, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/sessions").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("loginId", loginId, "password", password))))
                .andExpect(status().isOk()).andReturn();
        JsonNode data = data(result);
        return new Tokens(data.get("accessToken").asText(), data.get("refreshToken").asText());
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

    private record Tokens(String access, String refresh) {
    }

    @TestConfiguration
    static class TestClockConfig {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(BASE, ZoneOffset.UTC);
        }
    }
    @Test
    void reviewImportRemovesLastAdminRole() throws Exception {
        importService.importUsers(Path.of("admin/fixtures/p01-demo-users.json"), false);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_role WHERE role='SUPER_ADMIN'", Integer.class)).isZero();
        assertThat(recoveryCount()).isEqualTo(1);
        String token = login(ADMIN, ADMIN_PASSWORD);
        mockMvc.perform(get("/api/v1/admin/dashboard").header("Authorization", bearer(token)))
                .andExpect(status().isForbidden());
        assertThat(superAdmins.grantInitial("cli", "user_demo_teacher_01", "恢复测试", false).alreadyActive()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_role WHERE role='SUPER_ADMIN'", Integer.class)).isZero();
    }

    @Test
    void reviewAuditExposesBusinessReasonAndDashboardBypassesAuditCapability() throws Exception {
        jdbc.update("""
                INSERT INTO audit_event (id,actor_id,action,resource_type,resource_id,reason,at,revision)
                VALUES ('review_sensitive','user_demo_student_01','SUPPORT_CLOSE','SUPPORT_TICKET',
                        'private_ticket','PRIVATE_REASON_MARKER','2026-11-05T00:01:00Z',1)
                """);
        String admin = login(ADMIN, ADMIN_PASSWORD);
        MvcResult audit = mockMvc.perform(get("/api/v1/admin/audit-logs").header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andReturn();
        assertThat(audit.getResponse().getContentAsString()).contains("PRIVATE_REASON_MARKER");
        grantReadOnly("user_demo_student_02");
        String reader = login("student_demo_02", "demo-password-02");
        mockMvc.perform(get("/api/v1/admin/audit-logs").header("Authorization", bearer(reader)))
                .andExpect(status().isForbidden());
        MvcResult dashboard = mockMvc.perform(get("/api/v1/admin/dashboard").header("Authorization", bearer(reader)))
                .andExpect(status().isOk()).andReturn();
        assertThat(dashboard.getResponse().getContentAsString()).contains("private_ticket");
    }

    @Test
    void reviewReplayBypassesChangedScope() throws Exception {
        String admin = login(ADMIN, ADMIN_PASSWORD);
        String target = "user_demo_student_01";
        int revision = userRevision(admin, target);
        String token = reauth(admin, "review_scope", "ACCOUNT_STATUS_CHANGE", "USER", target,
                revision, null, null, "scope test", null, "DISABLED", null);
        Map<String,Object> body = Map.of("status","DISABLED","reason","scope test",
                "expectedRevision",revision,"reauthToken",token);
        String path = "/api/v1/admin/users/" + target + "/status-changes";
        write(admin,path,"review_scope","review_key",body);
        jdbc.update("INSERT INTO school (id,name,timezone) VALUES ('review_other','其他学校','Asia/Shanghai')");
        jdbc.update("""
                UPDATE platform_admin_scope SET scope_type='SCHOOL',school_id='review_other'
                WHERE grant_id IN (SELECT id FROM platform_admin_grant WHERE user_id='user_demo_teacher_01')
                """);
        mockMvc.perform(get("/api/v1/admin/users/"+target).header("Authorization",bearer(admin)))
                .andExpect(status().isForbidden());
        assertThat(data(write(admin,path,"review_scope","review_key",body)).get("operation").get("status").asText()).isEqualTo("COMMITTED");
        assertThat(data(write(admin,path,"review_scope","review_key_2",body)).get("operation").get("status").asText()).isEqualTo("COMMITTED");
    }

    @Test
    void reviewLastPlatformAdminCanBeRevokedWithOnlySchoolAdminRemaining() throws Exception {
        grantSchool("user_demo_teacher_02", "school_demo_01");
        String admin = login(ADMIN, ADMIN_PASSWORD);
        String target = "user_demo_teacher_01";
        int revision = grantRevision(target);
        String token = reauth(admin,"review_last","SUPER_ADMIN_REVOKE","USER",target,
                revision,null,null,"last platform test",null,null,null);
        write(admin,"/api/v1/admin/super-admins/"+target+"/revocations","review_last","review_last_key",
                Map.of("reason","last platform test","expectedRevision",revision,"reauthToken",token));
        assertThat(recoveryCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM platform_admin_grant g JOIN platform_admin_scope s ON s.grant_id=g.id
                WHERE g.status='ACTIVE' AND s.scope_type='PLATFORM'
                """,Integer.class)).isZero();
    }
    @Autowired private org.springframework.transaction.support.TransactionTemplate reviewTransactions;

    @Test
    void reviewGrantContinuesAfterActorRevokedWhileWaitingForTargetLock() throws Exception {
        superAdmins.grantInitial("cli", "user_demo_teacher_02", "second admin", false);
        String admin = login(ADMIN, ADMIN_PASSWORD);
        String other = login(OTHER_ADMIN, OTHER_PASSWORD);
        String target = "user_demo_student_01";
        String token = reauth(admin,"review_race","SUPER_ADMIN_GRANT","USER",target,
                null,"NO_ACTIVE_GRANT",null,"race test",null,null,target);
        int revision = grantRevision("user_demo_teacher_01");
        String revokeToken = reauthWith(other,OTHER_PASSWORD,"review_race_revoke","SUPER_ADMIN_REVOKE",
                "USER","user_demo_teacher_01",revision,null,null,"revoke actor",null,null,null);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> blocker = pool.submit(() -> reviewTransactions.execute(s -> {
                jdbc.queryForObject("SELECT id FROM app_user WHERE id=? FOR UPDATE",String.class,target);
                locked.countDown();
                try { if (!release.await(15,TimeUnit.SECONDS)) throw new RuntimeException("timeout"); }
                catch (InterruptedException e) { throw new RuntimeException(e); }
                return null;
            }));
            assertThat(locked.await(5,TimeUnit.SECONDS)).isTrue();
            Future<MvcResult> grant = pool.submit(() -> write(admin,"/api/v1/admin/super-admins",
                    "review_race","review_race_key",Map.of("targetUserId",target,"reason","race test","reauthToken",token)));
            boolean waiting = false;
            for (int i=0;i<100;i++) {
                waiting = Boolean.TRUE.equals(jdbc.queryForObject("""
                        SELECT EXISTS(SELECT 1 FROM pg_stat_activity
                        WHERE wait_event_type='Lock' AND query LIKE '%FROM app_user WHERE id =%FOR UPDATE%')
                        """,Boolean.class));
                if (waiting) break;
                Thread.sleep(30);
            }
            assertThat(waiting).isTrue();
            write(other,"/api/v1/admin/super-admins/user_demo_teacher_01/revocations",
                    "review_race_revoke","review_race_revoke_key",Map.of("reason","revoke actor",
                            "expectedRevision",revision,"reauthToken",revokeToken));
            release.countDown();
            blocker.get(10,TimeUnit.SECONDS);
            assertThat(data(grant.get(10,TimeUnit.SECONDS)).get("operation").get("status").asText()).isEqualTo("COMMITTED");
        } finally { release.countDown(); pool.shutdownNow(); }
    }
}
