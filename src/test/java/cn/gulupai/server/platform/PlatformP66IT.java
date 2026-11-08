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
@Import(PlatformP66IT.TestClockConfig.class)
class PlatformP66IT {

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

    @Test
    void meShowsAccountStatusSeparatelyAndReadsMatchContract() throws Exception {
        String token = login(ADMIN, ADMIN_PASSWORD);
        mockMvc.perform(get("/api/v1/me").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accountStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.data.identityStatus").value("VERIFIED"))
                .andExpect(jsonPath("$.data.roles[?(@ == 'SUPER_ADMIN')]").exists())
                .andExpect(jsonPath("$.data.capabilities[?(@ == 'PLATFORM_ACCOUNT_READ')]").exists())
                .andExpect(jsonPath("$.data.capabilities[?(@ == 'SUPPORT_HANDLE')]").doesNotExist());

        mockMvc.perform(get("/api/v1/admin/dashboard").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.counts.teacherApplicationsPending").value(0))
                .andExpect(jsonPath("$.data.counts.courseClaimsPending").value(0))
                .andExpect(jsonPath("$.data.counts.exceptionsPending").value(0))
                .andExpect(jsonPath("$.data.recentOperations").isArray());

        mockMvc.perform(get("/api/v1/admin/users").param("limit", "1").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(5))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.hasMore").value(true))
                .andExpect(jsonPath("$.data.nextCursor").isNotEmpty());

        Set<String> seen = new LinkedHashSet<>();
        String cursor = "";
        boolean more = true;
        int pages = 0;
        while (more && pages++ < 10) {
            var request = get("/api/v1/admin/users").param("limit", "2").header("Authorization", bearer(token));
            if (!cursor.isEmpty()) {
                request.param("cursor", cursor);
            }
            MvcResult page = mockMvc.perform(request).andExpect(status().isOk()).andReturn();
            JsonNode data = data(page);
            assertThat(data.get("total").asInt()).isEqualTo(5);
            data.get("items").forEach(item -> seen.add(item.get("id").asText()));
            more = data.get("hasMore").asBoolean();
            cursor = data.get("nextCursor").asText();
            if (more) {
                assertThat(cursor).matches("[0-9a-f]{2,300}");
            } else {
                assertThat(cursor).isEmpty();
            }
        }
        assertThat(seen).hasSize(5);

        mockMvc.perform(get("/api/v1/admin/users/{id}", "user_demo_student_01")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user.id").value("user_demo_student_01"))
                .andExpect(jsonPath("$.data.user.accountStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.data.user.revision").isNumber())
                .andExpect(jsonPath("$.data.grants").isArray())
                .andExpect(jsonPath("$.data.teachingCourses").isArray())
                .andExpect(jsonPath("$.data.managementRecords").isArray());

        mockMvc.perform(get("/api/v1/admin/users").param("status", "DISABLED")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.hasMore").value(false))
                .andExpect(jsonPath("$.data.nextCursor").value(""));

        mockMvc.perform(get("/api/v1/admin/super-admins").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.items[0].userId").value("user_demo_teacher_01"));
    }

    @Test
    void unauthorizedRolesMissingResourceAndClosedResetAreDistinct() throws Exception {
        String admin = login(ADMIN, ADMIN_PASSWORD);
        String student = login("student_demo_01", "demo-password-01");
        mockMvc.perform(get("/api/v1/admin/dashboard")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/admin/dashboard").header("Authorization", bearer(student)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        jdbc.update("INSERT INTO user_role (user_id, role) VALUES ('user_demo_assistant_01', 'SUPER_ADMIN')");
        String assistant = login("assistant_demo_01", "demo-password-04");
        mockMvc.perform(get("/api/v1/admin/dashboard").header("Authorization", bearer(assistant)))
                .andExpect(status().isForbidden());

        grantReadOnly("user_demo_student_02");
        String reader = login("student_demo_02", "demo-password-02");
        mockMvc.perform(get("/api/v1/admin/users").header("Authorization", bearer(reader)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/admin/users/{id}/status-changes", "user_demo_student_01")
                        .header("Authorization", bearer(reader))
                        .header("Idempotency-Key", "p66-readonly")
                        .header("X-Operation-Id", "op_readonly")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DISABLED\",\"reason\":\"只读不能写\",\"expectedRevision\":1,\"reauthToken\":\"token-not-real\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/admin/users/{id}/status-changes", "user_demo_student_01")
                        .header("Authorization", bearer(admin))
                        .header("Idempotency-Key", "p66-inject")
                        .header("X-Operation-Id", "op_inject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DISABLED\",\"reason\":\"注入\",\"expectedRevision\":1,\"reauthToken\":\"x\",\"roles\":[\"SUPER_ADMIN\"]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("FIELD_NOT_ALLOWED"));

        mockMvc.perform(get("/api/v1/admin/users/{id}", "missing_user").header("Authorization", bearer(admin)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        mockMvc.perform(get("/api/v1/admin/no-such-route").header("Authorization", bearer(admin)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PATH_NOT_FOUND"));

        String hashBefore = jdbc.queryForObject(
                "SELECT password_hash FROM app_user WHERE id = 'user_demo_student_01'", String.class);
        MvcResult reset = mockMvc.perform(post("/api/v1/admin/users/{id}/password-reset-requests", "user_demo_student_01")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"没有安全渠道\",\"reauthToken\":\"unused-token\"}"))
                .andExpect(status().isNotImplemented())
                .andExpect(jsonPath("$.code").value("ENDPOINT_NOT_IMPLEMENTED"))
                .andReturn();
        assertThat(reset.getResponse().getContentAsString()).doesNotContain("APPROVED_SECURE_CHANNEL");
        assertThat(jdbc.queryForObject(
                "SELECT password_hash FROM app_user WHERE id = 'user_demo_student_01'", String.class))
                .isEqualTo(hashBefore);
    }

    @Test
    void statusChangeRevokesSessionsAndReplaysDoNotRepeat() throws Exception {
        String admin = login(ADMIN, ADMIN_PASSWORD);
        var student = loginSession("student_demo_01", "demo-password-01");
        int revision = userRevision(admin, "user_demo_student_01");
        String reauth = reauth(admin, "op_disable", "ACCOUNT_STATUS_CHANGE", "USER", "user_demo_student_01",
                revision, null, null, "停用隔离账号", null, "DISABLED", null);
        String secondToken = reauth(admin, "op_disable", "ACCOUNT_STATUS_CHANGE", "USER", "user_demo_student_01",
                revision, null, null, "停用隔离账号", null, "DISABLED", null);
        MvcResult disabled = write(admin, "/api/v1/admin/users/user_demo_student_01/status-changes",
                "op_disable", "key_disable", Map.of(
                        "status", "DISABLED", "reason", "停用隔离账号", "expectedRevision", revision, "reauthToken", reauth));
        assertThat(data(disabled).get("operation").get("status").asText()).isEqualTo("COMMITTED");
        assertThat(data(disabled).get("resource").get("status").asText()).isEqualTo("DISABLED");
        String auditId = data(disabled).get("operation").get("auditId").asText();
        mockMvc.perform(get("/api/v1/me").header("Authorization", bearer(student.access())))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/auth/sessions/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + student.refresh() + "\"}"))
                .andExpect(status().isUnauthorized());

        MvcResult replay = write(admin, "/api/v1/admin/users/user_demo_student_01/status-changes",
                "op_disable", "key_disable", Map.of(
                        "status", "DISABLED", "reason", "停用隔离账号", "expectedRevision", revision, "reauthToken", "stale-token"));
        assertThat(data(replay).get("operation").get("auditId").asText()).isEqualTo(auditId);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM audit_event WHERE action = 'ACCOUNT_DISABLED' AND resource_id = 'user_demo_student_01'",
                Integer.class)).isEqualTo(1);

        MvcResult otherKey = write(admin, "/api/v1/admin/users/user_demo_student_01/status-changes",
                "op_disable", "key_disable_2", Map.of(
                        "status", "DISABLED", "reason", "停用隔离账号", "expectedRevision", revision,
                        "reauthToken", secondToken));
        assertThat(data(otherKey).get("operation").get("auditId").asText()).isEqualTo(auditId);

        mockMvc.perform(post("/api/v1/admin/users/user_demo_student_01/status-changes")
                        .header("Authorization", bearer(admin))
                        .header("Idempotency-Key", "key_disable")
                        .header("X-Operation-Id", "op_disable")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "status", "DISABLED", "reason", "另一条理由", "expectedRevision", revision,
                                "reauthToken", secondToken))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        int disabledRevision = userRevision(admin, "user_demo_student_01");
        String restoreToken = reauth(admin, "op_restore", "ACCOUNT_STATUS_CHANGE", "USER", "user_demo_student_01",
                disabledRevision, null, null, "恢复隔离账号", null, "ACTIVE", null);
        write(admin, "/api/v1/admin/users/user_demo_student_01/status-changes", "op_restore", "key_restore", Map.of(
                "status", "ACTIVE", "reason", "恢复隔离账号", "expectedRevision", disabledRevision,
                "reauthToken", restoreToken));
        mockMvc.perform(post("/api/v1/auth/sessions/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + student.refresh() + "\"}"))
                .andExpect(status().isUnauthorized());
        assertThat(login("student_demo_01", "demo-password-01")).isNotBlank();
        assertThat(jdbc.queryForObject(
                "SELECT identity_status FROM app_user WHERE id = 'user_demo_student_01'", String.class))
                .isEqualTo("VERIFIED");
    }

    /**
     * S1：A03 的 restorationEligible 与 A04 的事务内判定共用同一方法。
     * 管理停用后读侧为 true；来源改成导入停用后读侧为 false，写侧同版本恢复返回 409；
     * 来源恢复为 ADMIN_DISABLED 后同一账号可以真正恢复，恢复后读侧回到 false。
     */
    @Test
    void restorationEligibleReadAndWriteShareOnePredicate() throws Exception {
        String admin = login(ADMIN, ADMIN_PASSWORD);
        String path = "/api/v1/admin/users/user_demo_student_01/status-changes";
        int activeRevision = userRevision(admin, "user_demo_student_01");
        mockMvc.perform(get("/api/v1/admin/users/{id}", "user_demo_student_01")
                        .header("Authorization", bearer(admin)))
                .andExpect(jsonPath("$.data.user.restorationEligible").value(false));

        String disableToken = reauth(admin, "op_elig_disable", "ACCOUNT_STATUS_CHANGE", "USER",
                "user_demo_student_01", activeRevision, null, null, "资格回归停用", null, "DISABLED", null);
        write(admin, path, "op_elig_disable", "key_elig_disable", Map.of(
                "status", "DISABLED", "reason", "资格回归停用", "expectedRevision", activeRevision,
                "reauthToken", disableToken));
        mockMvc.perform(get("/api/v1/admin/users/{id}", "user_demo_student_01")
                        .header("Authorization", bearer(admin)))
                .andExpect(jsonPath("$.data.user.accountStatus").value("DISABLED"))
                .andExpect(jsonPath("$.data.user.restorationEligible").value(true));

        jdbc.update("UPDATE app_user SET eligibility_source = 'IMPORT_DISABLED' WHERE id = ?",
                "user_demo_student_01");
        mockMvc.perform(get("/api/v1/admin/users/{id}", "user_demo_student_01")
                        .header("Authorization", bearer(admin)))
                .andExpect(jsonPath("$.data.user.restorationEligible").value(false));

        int disabledRevision = activeRevision + 1;
        String refusedToken = reauth(admin, "op_elig_restore_refused", "ACCOUNT_STATUS_CHANGE", "USER",
                "user_demo_student_01", disabledRevision, null, null, "来源不明不能恢复", null, "ACTIVE", null);
        mockMvc.perform(post(path)
                        .header("Authorization", bearer(admin))
                        .header("Idempotency-Key", "key_elig_restore_refused")
                        .header("X-Operation-Id", "op_elig_restore_refused")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "status", "ACTIVE", "reason", "来源不明不能恢复",
                                "expectedRevision", disabledRevision, "reauthToken", refusedToken))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
        assertThat(jdbc.queryForObject(
                "SELECT account_status FROM app_user WHERE id = 'user_demo_student_01'", String.class))
                .isEqualTo("DISABLED");
        assertThat(jdbc.queryForObject(
                "SELECT eligibility_source FROM app_user WHERE id = 'user_demo_student_01'", String.class))
                .isEqualTo("IMPORT_DISABLED");

        jdbc.update("UPDATE app_user SET eligibility_source = 'ADMIN_DISABLED' WHERE id = ?",
                "user_demo_student_01");
        String restoreToken = reauth(admin, "op_elig_restore", "ACCOUNT_STATUS_CHANGE", "USER",
                "user_demo_student_01", disabledRevision, null, null, "恢复隔离账号", null, "ACTIVE", null);
        write(admin, path, "op_elig_restore", "key_elig_restore", Map.of(
                "status", "ACTIVE", "reason", "恢复隔离账号", "expectedRevision", disabledRevision,
                "reauthToken", restoreToken));
        mockMvc.perform(get("/api/v1/admin/users/{id}", "user_demo_student_01")
                        .header("Authorization", bearer(admin)))
                .andExpect(jsonPath("$.data.user.accountStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.data.user.restorationEligible").value(false));
    }

    @Test
    void reauthBindsOperationAndRateLimits() throws Exception {
        String admin = login(ADMIN, ADMIN_PASSWORD);
        mockMvc.perform(post("/api/v1/auth/reauthentications")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"demo-password-03\"}"))
                .andExpect(status().isUnprocessableEntity());
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/v1/auth/reauthentications").header("Authorization", bearer(admin))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(reauthBody("op_bad_" + i, "ACCOUNT_STATUS_CHANGE", "USER", "user_demo_student_01",
                                    1, null, null, "错误口令尝试", null, "DISABLED", null, "wrong-password")))
                    .andExpect(status().isUnauthorized());
        }
        mockMvc.perform(post("/api/v1/auth/reauthentications").header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reauthBody("op_bad_block", "ACCOUNT_STATUS_CHANGE", "USER", "user_demo_student_01",
                                1, null, null, "错误口令尝试", null, "DISABLED", null, "wrong-password")))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));

        clock.setInstant(BASE.plusSeconds(16 * 60));
        String adminAgain = login(ADMIN, ADMIN_PASSWORD);
        int revision = userRevision(adminAgain, "user_demo_student_01");
        String token = reauth(adminAgain, "op_expire", "ACCOUNT_STATUS_CHANGE", "USER", "user_demo_student_01",
                revision, null, null, "过期试验", null, "DISABLED", null);
        clock.setInstant(BASE.plusSeconds(16 * 60 + 6 * 60));
        mockMvc.perform(post("/api/v1/admin/users/user_demo_student_01/status-changes")
                        .header("Authorization", bearer(adminAgain))
                        .header("Idempotency-Key", "key_expire")
                        .header("X-Operation-Id", "op_expire")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "status", "DISABLED", "reason", "过期试验", "expectedRevision", revision,
                                "reauthToken", token))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("REAUTH_EXPIRED"));
    }

    @Test
    void teacherDecisionMapsApprovedAndDoesNotGrantSupport() throws Exception {
        String admin = login(ADMIN, ADMIN_PASSWORD);
        MvcResult created = mockMvc.perform(post("/api/v1/auth/registrations")
                        .header("Idempotency-Key", "p66-teacher")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registration("teacher_p66_01", "TEACHER", "计算机学院工号 T20000")))
                .andExpect(status().isOk()).andReturn();
        String applicationId = data(created).get("id").asText();
        mockMvc.perform(get("/api/v1/admin/dashboard").header("Authorization", bearer(admin)))
                .andExpect(jsonPath("$.data.counts.teacherApplicationsPending").value(1));
        mockMvc.perform(get("/api/v1/admin/teacher-applications").header("Authorization", bearer(admin)))
                .andExpect(jsonPath("$.data.items[0].status").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.data.items[0].applicantUserId").isNotEmpty());
        mockMvc.perform(get("/api/v1/admin/teacher-applications/{id}", applicationId)
                        .header("Authorization", bearer(admin)))
                .andExpect(jsonPath("$.data.teacherApplication").value("计算机学院工号 T20000"))
                .andExpect(jsonPath("$.data.history").isEmpty());

        jdbc.update("UPDATE registration_application SET user_id = 'user_demo_teacher_01' WHERE id = ?", applicationId);
        mockMvc.perform(post("/api/v1/auth/reauthentications")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reauthBody("op_self", "TEACHER_APPLICATION_DECIDE", "TEACHER_APPLICATION",
                                applicationId, 1, null, "APPROVE", null, "自己审自己", null, null, ADMIN_PASSWORD)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SELF_REVIEW_FORBIDDEN"));
        assertThat(jdbc.queryForObject(
                "SELECT status FROM registration_application WHERE id = ?", String.class, applicationId))
                .isEqualTo("PENDING_REVIEW");
    }

    @Test
    void teacherApprovalAndCourseGovernanceCommitOnce() throws Exception {
        String admin = login(ADMIN, ADMIN_PASSWORD);
        MvcResult created = mockMvc.perform(post("/api/v1/auth/registrations")
                        .header("Idempotency-Key", "p66-approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registration("teacher_p66_02", "TEACHER", "计算机学院工号 T20001")))
                .andExpect(status().isOk()).andReturn();
        String applicationId = data(created).get("id").asText();
        String applicant = jdbc.queryForObject(
                "SELECT user_id FROM registration_application WHERE id = ?", String.class, applicationId);
        String token = reauth(admin, "op_approve", "TEACHER_APPLICATION_DECIDE", "TEACHER_APPLICATION",
                applicationId, 1, null, "APPROVE", null, "学校已核验", null, null);
        MvcResult approved = write(admin, "/api/v1/admin/teacher-applications/" + applicationId + "/decisions",
                "op_approve", "key_approve", Map.of(
                        "decision", "APPROVE", "note", "学校已核验", "expectedRevision", 1, "reauthToken", token));
        assertThat(data(approved).get("operation").get("status").asText()).isEqualTo("COMMITTED");
        assertThat(data(approved).get("resource").get("status").asText()).isEqualTo("APPROVED");
        assertThat(data(approved).get("resource").get("id").asText()).isEqualTo(applicationId);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM registration_application WHERE id = ?", String.class, applicationId))
                .isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM user_role WHERE user_id = ? AND role = 'TEACHER'", Integer.class, applicant))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM operator_grant WHERE user_id = ? AND capability = 'SUPPORT_HANDLE'",
                Integer.class, applicant)).isZero();
        mockMvc.perform(get("/api/v1/admin/teacher-applications/{id}", applicationId)
                        .header("Authorization", bearer(admin)))
                .andExpect(jsonPath("$.data.status").value("APPROVED"));
        mockMvc.perform(post("/api/v1/auth/reauthentications")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reauthBody("op_approve_again", "TEACHER_APPLICATION_DECIDE", "TEACHER_APPLICATION",
                                applicationId, 1, null, "APPROVE", null, "重复决定", null, null, ADMIN_PASSWORD)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REVISION_CONFLICT"));
        assertThat(jdbc.queryForObject(
                "SELECT status FROM registration_application WHERE id = ?", String.class, applicationId))
                .isEqualTo("ACTIVE");

        jdbc.update("""
                INSERT INTO term (id, school_id, name, start_date, end_date, ordinal)
                VALUES ('term_p66', 'school_demo_01', '秋季', '2026-09-01', '2027-01-15', 66)
                """);
        jdbc.update("""
                INSERT INTO square (id, school_id, term_id, name, teacher_name, slots_json, canonical_key,
                    square_no, status)
                VALUES ('square_p66', 'school_demo_01', 'term_p66', '平台治理课', '教师', '[]'::jsonb, ?, 'P66', 'ACTIVE')
                """, "b".repeat(64));
        jdbc.update("""
                INSERT INTO teacher_claim (id, square_id, user_id, status, message)
                VALUES ('claim_p66', 'square_p66', 'user_demo_teacher_02', 'PENDING', '申请认领')
                """);
        String claimToken = reauth(admin, "op_claim", "COURSE_CLAIM_DECIDE", "TEACHER_CLAIM", "claim_p66",
                1, null, "APPROVE", null, "核验通过", null, null);
        MvcResult claim = write(admin, "/api/v1/admin/course-claims/claim_p66/decisions", "op_claim", "key_claim",
                Map.of("decision", "APPROVE", "note", "核验通过", "expectedRevision", 1, "reauthToken", claimToken));
        assertThat(data(claim).get("resource").get("status").asText()).isEqualTo("APPROVED");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM teacher_assignment WHERE square_id = 'square_p66' AND active", Integer.class))
                .isEqualTo(1);
        mockMvc.perform(get("/api/v1/admin/courses/{id}", "square_p66").header("Authorization", bearer(admin)))
                .andExpect(jsonPath("$.data.course.squareId").value("square_p66"))
                .andExpect(jsonPath("$.data.claims[0].status").value("APPROVED"));

        jdbc.update("""
                INSERT INTO class_grant (id, square_id, user_id, capabilities_json, status, expires_at, granted_by)
                VALUES ('grant_p66', 'square_p66', 'user_demo_assistant_01', '["ANNOUNCEMENT_WRITE"]'::jsonb,
                        'ACTIVE', '2027-01-01T00:00:00Z', 'user_demo_teacher_02')
                """);
        String revoke = reauth(admin, "op_grant", "ADMIN_GRANT_REVOKE", "CLASS_GRANT", "grant_p66",
                1, null, null, "紧急撤销", null, null, null);
        MvcResult revoked = write(admin, "/api/v1/admin/admin-grants/grant_p66/revocations", "op_grant", "key_grant",
                Map.of("reason", "紧急撤销", "expectedRevision", 1, "reauthToken", revoke));
        assertThat(data(revoked).get("resource").get("status").asText()).isEqualTo("REVOKED");
        assertThat(jdbc.queryForObject("SELECT status FROM class_grant WHERE id = 'grant_p66'", String.class))
                .isEqualTo("REVOKED");
        String assistant = login("assistant_demo_01", "demo-password-04");
        mockMvc.perform(get("/api/v1/me").header("Authorization", bearer(assistant))).andExpect(status().isOk());
    }

    /**
     * 第二轮 S2：HTTP 审核实际写入的新动作名必须出现在 A13 审计日志与 A01 首页，
     * 且敏感理由仍被隐藏、范围按真实目标学校解析。
     */
    @Test
    void httpReviewDecisionsAreVisibleInAuditLogsWithoutTheRawNote() throws Exception {
        String admin = login(ADMIN, ADMIN_PASSWORD);
        MvcResult created = mockMvc.perform(post("/api/v1/auth/registrations")
                        .header("Idempotency-Key", "p66-audit-approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registration("teacher_p66_audit", "TEACHER", "审计可见性 T30001")))
                .andExpect(status().isOk()).andReturn();
        String applicationId = data(created).get("id").asText();
        String approveToken = reauth(admin, "op_audit_approve", "TEACHER_APPLICATION_DECIDE",
                "TEACHER_APPLICATION", applicationId, 1, null, "APPROVE", null, "审核理由不应外泄", null, null);
        MvcResult approved = write(admin, "/api/v1/admin/teacher-applications/" + applicationId + "/decisions",
                "op_audit_approve", "key_audit_approve", Map.of("decision", "APPROVE",
                        "note", "审核理由不应外泄", "expectedRevision", 1, "reauthToken", approveToken));
        String approveAuditId = data(approved).get("operation").get("auditId").asText();

        JsonNode approve = auditLogItem(admin, approveAuditId);
        assertThat(approve.path("action").asText()).isEqualTo("TEACHER_APPLICATION_APPROVED");
        assertThat(approve.path("result").asText()).isEqualTo("SUCCEEDED");
        assertThat(approve.path("targetType").asText()).isEqualTo("TEACHER_APPLICATION");
        assertThat(approve.path("targetId").asText()).isEqualTo(applicationId);
        assertThat(approve.path("scope").asText()).isEqualTo("school_demo_01");
        assertThat(approve.path("reason").asText()).isEmpty();
        assertThat(approve.toString()).doesNotContain("审核理由不应外泄");

        jdbc.update("""
                INSERT INTO term (id, school_id, name, start_date, end_date, ordinal)
                VALUES ('term_p66_audit', 'school_demo_01', '秋季', '2026-09-01', '2027-01-15', 67)
                """);
        jdbc.update("""
                INSERT INTO square (id, school_id, term_id, name, teacher_name, slots_json, canonical_key,
                    square_no, status)
                VALUES ('square_p66_audit', 'school_demo_01', 'term_p66_audit', '审计可见性课', '教师',
                    '[]'::jsonb, ?, 'P66A', 'ACTIVE')
                """, "c".repeat(64));
        jdbc.update("""
                INSERT INTO teacher_claim (id, square_id, user_id, status, message)
                VALUES ('claim_p66_audit', 'square_p66_audit', 'user_demo_teacher_02', 'PENDING', '申请认领')
                """);
        String claimToken = reauth(admin, "op_audit_claim", "COURSE_CLAIM_DECIDE", "TEACHER_CLAIM",
                "claim_p66_audit", 1, null, "APPROVE", null, "认领理由不应外泄", null, null);
        MvcResult claim = write(admin, "/api/v1/admin/course-claims/claim_p66_audit/decisions",
                "op_audit_claim", "key_audit_claim", Map.of("decision", "APPROVE",
                        "note", "认领理由不应外泄", "expectedRevision", 1, "reauthToken", claimToken));
        String claimAuditId = data(claim).get("operation").get("auditId").asText();

        JsonNode claimAudit = auditLogItem(admin, claimAuditId);
        assertThat(claimAudit.path("action").asText()).isEqualTo("COURSE_CLAIM_APPROVED");
        assertThat(claimAudit.path("result").asText()).isEqualTo("SUCCEEDED");
        assertThat(claimAudit.path("targetType").asText()).isEqualTo("COURSE_CLAIM");
        assertThat(claimAudit.path("targetId").asText()).isEqualTo("claim_p66_audit");
        assertThat(claimAudit.path("scope").asText()).isEqualTo("school_demo_01");
        assertThat(claimAudit.path("reason").asText()).isEmpty();
        assertThat(claimAudit.toString()).doesNotContain("认领理由不应外泄");

        // A01 首页 recentOperations 与 A13 使用同一白名单与结果映射。
        MvcResult dashboard = mockMvc.perform(get("/api/v1/admin/dashboard")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andReturn();
        JsonNode recent = data(dashboard).get("recentOperations");
        JsonNode recentApprove = findById(recent, approveAuditId);
        assertThat(recentApprove).isNotNull();
        assertThat(recentApprove.path("action").asText()).isEqualTo("TEACHER_APPLICATION_APPROVED");
        JsonNode recentClaim = findById(recent, claimAuditId);
        assertThat(recentClaim).isNotNull();
        assertThat(recentClaim.path("action").asText()).isEqualTo("COURSE_CLAIM_APPROVED");
        assertThat(recent.toString()).doesNotContain("审核理由不应外泄");
        assertThat(recent.toString()).doesNotContain("认领理由不应外泄");
    }

    @Test
    void auditFailureRollsBackAndLastAdminSurvivesConcurrency() throws Exception {
        String admin = login(ADMIN, ADMIN_PASSWORD);
        int revision = userRevision(admin, "user_demo_student_01");
        String reauth = reauth(admin, "op_audit_fail", "ACCOUNT_STATUS_CHANGE", "USER", "user_demo_student_01",
                revision, null, null, "审计失败回滚", null, "DISABLED", null);
        auditGate.failNext(new IllegalStateException("audit-down"));
        mockMvc.perform(post("/api/v1/admin/users/user_demo_student_01/status-changes")
                        .header("Authorization", bearer(admin))
                        .header("Idempotency-Key", "key_audit_fail")
                        .header("X-Operation-Id", "op_audit_fail")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "status", "DISABLED", "reason", "审计失败回滚", "expectedRevision", revision,
                                "reauthToken", reauth))))
                .andExpect(status().isInternalServerError());
        assertThat(jdbc.queryForObject(
                "SELECT account_status FROM app_user WHERE id = 'user_demo_student_01'", String.class))
                .isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM admin_operation", Integer.class)).isZero();

        superAdmins.grantInitial("user_demo_teacher_01", "user_demo_teacher_02", "第二位隔离管理员", false);
        String first = login(ADMIN, ADMIN_PASSWORD);
        String second = login(OTHER_ADMIN, OTHER_PASSWORD);
        int firstRevision = grantRevision("user_demo_teacher_01");
        int secondRevision = grantRevision("user_demo_teacher_02");
        String revokeSecond = reauth(first, "op_race_a", "SUPER_ADMIN_REVOKE", "USER", "user_demo_teacher_02",
                secondRevision, null, null, "并发撤销乙", null, null, null);
        String revokeFirst = reauthWith(second, OTHER_PASSWORD, "op_race_b", "SUPER_ADMIN_REVOKE", "USER",
                "user_demo_teacher_01", firstRevision, null, null, "并发撤销甲", null, null, null);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> left = pool.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return mockMvc.perform(post("/api/v1/admin/super-admins/user_demo_teacher_02/revocations")
                                .header("Authorization", bearer(first))
                                .header("Idempotency-Key", "key_race_a")
                                .header("X-Operation-Id", "op_race_a")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(Map.of(
                                        "reason", "并发撤销乙", "expectedRevision", secondRevision,
                                        "reauthToken", revokeSecond))))
                        .andReturn().getResponse().getStatus();
            });
            Future<Integer> right = pool.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return mockMvc.perform(post("/api/v1/admin/super-admins/user_demo_teacher_01/revocations")
                                .header("Authorization", bearer(second))
                                .header("Idempotency-Key", "key_race_b")
                                .header("X-Operation-Id", "op_race_b")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(Map.of(
                                        "reason", "并发撤销甲", "expectedRevision", firstRevision,
                                        "reauthToken", revokeFirst))))
                        .andReturn().getResponse().getStatus();
            });
            start.countDown();
            List<Integer> statuses = List.of(left.get(20, TimeUnit.SECONDS), right.get(20, TimeUnit.SECONDS));
            assertThat(statuses.stream().filter(code -> code == 200).count()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(recoveryCount()).isEqualTo(1);

        String survivor = recoveryUser();
        String survivorPassword = "user_demo_teacher_01".equals(survivor) ? ADMIN_PASSWORD : OTHER_PASSWORD;
        String survivorToken = login("user_demo_teacher_01".equals(survivor) ? ADMIN : OTHER_ADMIN, survivorPassword);
        int survivorRevision = grantRevision(survivor);
        mockMvc.perform(post("/api/v1/admin/super-admins/{id}/revocations", survivor)
                        .header("Authorization", bearer(survivorToken))
                        .header("Idempotency-Key", "key_last")
                        .header("X-Operation-Id", "op_last")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "reason", "不能撤销最后一位", "expectedRevision", survivorRevision,
                                "reauthToken", reauthWith(survivorToken, survivorPassword, "op_last",
                                        "SUPER_ADMIN_REVOKE", "USER", survivor, survivorRevision, null, null,
                                        "不能撤销最后一位", null, null, null)))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LAST_ACTIVE_SUPER_ADMIN"));
        assertThat(recoveryCount()).isEqualTo(1);
    }

    @Test
    void schoolScopeDoesNotUseTheCallerSchoolAsEveryScope() throws Exception {
        jdbc.update("INSERT INTO school (id, name, timezone) VALUES ('school_other', '其他学校', 'Asia/Shanghai')");
        jdbc.update("""
                INSERT INTO app_user (id, school_id, login_id, display_name, password_hash, identity_status,
                    disabled, account_status, eligibility_source)
                VALUES ('user_other', 'school_other', 'other_user', '其他用户', 'x', 'VERIFIED', false,
                        'ACTIVE', 'VERIFIED_ACTIVE')
                """);
        String platform = login(ADMIN, ADMIN_PASSWORD);
        mockMvc.perform(get("/api/v1/admin/users/{id}", "user_other").header("Authorization", bearer(platform)))
                .andExpect(status().isOk());
        grantSchool("user_demo_teacher_02", "school_demo_01");
        String scoped = login(OTHER_ADMIN, OTHER_PASSWORD);
        mockMvc.perform(get("/api/v1/admin/users/{id}", "user_other").header("Authorization", bearer(scoped)))
                .andExpect(status().isForbidden());
        MvcResult page = mockMvc.perform(get("/api/v1/admin/users").param("limit", "50")
                        .header("Authorization", bearer(scoped)))
                .andExpect(status().isOk()).andReturn();
        List<String> ids = new ArrayList<>();
        data(page).get("items").forEach(item -> ids.add(item.get("id").asText()));
        assertThat(ids).doesNotContain("user_other");
        assertThat(data(page).get("total").asInt()).isEqualTo(5);
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

    private JsonNode auditLogItem(String token, String auditId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/admin/audit-logs").param("limit", "50")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andReturn();
        JsonNode item = findById(data(result).get("items"), auditId);
        assertThat(item).as("A13 审计日志应返回记录 %s", auditId).isNotNull();
        return item;
    }

    private static JsonNode findById(JsonNode items, String id) {
        for (JsonNode item : items) {
            if (id.equals(item.path("id").asText())) {
                return item;
            }
        }
        return null;
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
}
