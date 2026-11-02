package cn.gulupai.server.identity;

import cn.gulupai.server.common.audit.AuditService;
import cn.gulupai.server.common.clock.MutableClock;
import cn.gulupai.server.common.idempotency.IdempotencyRequest;
import cn.gulupai.server.common.idempotency.IdempotencyService;
import cn.gulupai.server.common.security.AuthenticatedUser;
import cn.gulupai.server.common.security.Hashing;
import cn.gulupai.server.identity.api.SessionView;
import cn.gulupai.server.identity.cli.AdminImportService;
import cn.gulupai.server.identity.domain.Role;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(AuthP01IT.TestClockConfig.class)
class AuthP01IT {

    private static final Instant BASE = Instant.parse("2026-10-31T00:00:00Z");
    private static final String PASSWORD = "demo-password-01";
    private static final String LOGIN = "student_demo_01";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private MutableClock clock;
    @Autowired
    private AdminImportService importService;
    @Autowired
    private IdempotencyService idempotencyService;
    @Autowired
    private AuditService auditService;

    @BeforeEach
    void setUp() {
        clearAll();
        clock.setInstant(BASE);
        importService.importUsers(Path.of("admin/fixtures/p01-demo-users.json"), false);
    }

    @AfterEach
    void tearDown() {
        clearAll();
    }

    private void clearAll() {
        for (String table : List.of("file_reference", "file_authorization", "cleanup_plan", "stored_file",
                "personnel_handoff", "material_request", "personnel_record", "personnel_case_grant", "personnel_case",
                "removal_ballot", "handover_ack", "handover_item", "member_change_event",
                "member_change", "removal_vote",
                "teacher_summary_consent", "teacher_summary", "grade_appeal", "grade_snapshot",
                "grade_publication", "settlement", "ledger_correction", "grade_draft", "grade_rule",
                "contribution_confirmation", "unit_acceptance", "acceptance", "submission",
                "delivery_draft", "proposal_response", "unit_change_proposal",
                "reviewer_invitation", "reviewer_arrangement", "agreement_ack", "unit_share",
                "task_dependency", "criterion", "delivery_unit", "agreement", "task", "user_project_color",
                "announcement_reminder", "announcement_preview", "announcement_receipt",
                "announcement_recipient", "announcement_version", "help_article", "help_feedback", "support_route", "support_ticket",
                "support_reply", "support_history", "operator_grant", "content_report",
                "report_handling", "announcement",
                "notification_preference", "notification", "board_topic", "board_reply", "content_revision", "topic_follow",
                "chat_counter", "chat_message", "chat_change", "chat_read_state",
                "notification_outbox", "dice_round", "dice_roll", "dice_participant", "dice_session", "ai_reference", "peer_review_version", "peer_review",
                "work_version", "work", "project_code", "seat_reservation", "team_invitation", "team_application",
                "role_ack", "role_negotiation", "assignment_team_slot", "membership", "role_slot",
                "project_stage", "project",
                "cursor_snapshot", "teacher_transfer", "teacher_claim",
                "teacher_assignment", "class_grant", "course_code", "course_join_request",
                "assignment", "course_membership", "merge_ballot", "square_merge", "preview_token", "square",
                "verified_enrollment", "verified_course", "course_name", "period", "term",
                "operation_receipt", "access_token", "auth_session", "audit_event",
                "rate_window", "import_batch", "user_role", "user_profile", "app_user", "school")) {
            jdbcTemplate.update("DELETE FROM " + table);
        }
    }

    @Test
    void loginStoresOnlyHashesAndReturnsCapabilities() throws Exception {
        SessionView session = login(LOGIN, PASSWORD);

        assertThat(session.tokenType()).isEqualTo("Bearer");
        assertThat(session.expiresIn()).isEqualTo(1800);
        assertThat(session.refreshExpiresAt()).isEqualTo(BASE.plus(Duration.ofDays(7)));
        assertThat(session.user().roles()).containsExactly("STUDENT");
        assertThat(session.user().skills()).containsExactly("Java", "Android");
        assertThat(session.user().capabilities()).isEmpty();

        String passwordHash = jdbcTemplate.queryForObject(
                "SELECT password_hash FROM app_user WHERE login_id = ?", String.class, LOGIN);
        assertThat(passwordHash).startsWith("$2").doesNotContain(PASSWORD);

        Integer rawAccess = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM access_token WHERE token_hash = ?", Integer.class,
                session.accessToken());
        Integer hashedAccess = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM access_token WHERE token_hash = ?", Integer.class,
                Hashing.sha256Hex(session.accessToken()));
        Integer rawRefresh = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM auth_session WHERE refresh_hash = ?", Integer.class,
                session.refreshToken());
        Integer hashedRefresh = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM auth_session WHERE refresh_hash = ?", Integer.class,
                Hashing.sha256Hex(session.refreshToken()));
        assertThat(rawAccess).isZero();
        assertThat(hashedAccess).isOne();
        assertThat(rawRefresh).isZero();
        assertThat(hashedRefresh).isOne();

        String device = jdbcTemplate.queryForObject(
                "SELECT device_label FROM auth_session WHERE refresh_hash = ?", String.class,
                Hashing.sha256Hex(session.refreshToken()));
        assertThat(device).isEqualTo("Android App");

        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + session.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value("user_demo_student_01"))
                .andExpect(jsonPath("$.data.capabilities").isEmpty());
    }

    @Test
    void wrongPasswordAndUnknownAccountReturnSameCode() throws Exception {
        loginFailure(LOGIN, "wrong-password");
        loginFailure("no_such_login", "wrong-password");
    }

    @Test
    void loginBlocksAfterFiveFailuresAndRecoversAfterWindow() throws Exception {
        for (int i = 0; i < 5; i++) {
            loginFailure(LOGIN, "wrong-password");
        }
        mockMvc.perform(post("/api/v1/auth/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(LOGIN, PASSWORD)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(header().exists("Retry-After"));

        clock.advance(Duration.ofMinutes(16));
        SessionView session = login(LOGIN, PASSWORD);
        assertThat(session.accessToken()).isNotBlank();
    }

    @Test
    void accessExpiresButRefreshKeepsFixedSessionExpiry() throws Exception {
        SessionView original = login(LOGIN, PASSWORD);
        clock.advance(Duration.ofMinutes(29));
        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + original.accessToken()))
                .andExpect(status().isOk());

        clock.advance(Duration.ofMinutes(2));
        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + original.accessToken()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_EXPIRED"));

        SessionView refreshed = refresh(original.refreshToken());
        assertThat(refreshed.refreshExpiresAt()).isEqualTo(original.refreshExpiresAt());
        assertThat(refreshed.accessToken()).isNotEqualTo(original.accessToken());
        assertThat(refreshed.expiresIn()).isEqualTo(1800);
        assertThat(refreshed.user().capabilities()).isEmpty();
        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + refreshed.accessToken()))
                .andExpect(status().isOk());
    }

    @Test
    void refreshAfterFixedSessionExpiryIsRejected() throws Exception {
        SessionView original = login(LOGIN, PASSWORD);
        clock.advance(Duration.ofDays(7).plusMinutes(1));
        mockMvc.perform(post("/api/v1/auth/sessions/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + original.refreshToken() + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_EXPIRED"));
    }

    @Test
    void logoutRevokesSessionAndSupportsSameKeyReplay() throws Exception {
        SessionView first = login(LOGIN, PASSWORD);
        SessionView second = login(LOGIN, PASSWORD);

        mockMvc.perform(delete("/api/v1/auth/sessions/current")
                        .header("Authorization", "Bearer " + first.accessToken())
                        .header("Idempotency-Key", "logout-key-1")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revoked").value(true));

        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + first.accessToken()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + second.accessToken()))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/v1/auth/sessions/current")
                        .header("Authorization", "Bearer " + first.accessToken())
                        .header("Idempotency-Key", "logout-key-1")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revoked").value(true));

        Integer revokedSessions = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM auth_session WHERE refresh_hash = ? AND revoked_at IS NOT NULL",
                Integer.class, Hashing.sha256Hex(first.refreshToken()));
        Integer activeTokens = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM access_token a
                JOIN auth_session s ON s.id = a.session_id
                WHERE s.refresh_hash = ? AND a.revoked_at IS NULL
                """, Integer.class, Hashing.sha256Hex(first.refreshToken()));
        assertThat(revokedSessions).isOne();
        assertThat(activeTokens).isZero();
    }

    @Test
    void accountSwitchDoesNotReplayThePreviousUsersLatePacket() throws Exception {
        SessionView first = login(LOGIN, PASSWORD);
        SessionView second = login("student_demo_02", "demo-password-02");
        String lateKey = "late-packet-key";
        String firstBody = profileBody(0, "学生甲迟到回包", 4);

        mockMvc.perform(put("/api/v1/me/profile")
                        .header("Authorization", "Bearer " + first.accessToken())
                        .header("Idempotency-Key", lateKey)
                        .contentType(MediaType.APPLICATION_JSON).content(firstBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.displayName").value("学生甲迟到回包"));

        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + second.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value("user_demo_student_02"));
        mockMvc.perform(put("/api/v1/me/profile")
                        .header("Authorization", "Bearer " + second.accessToken())
                        .header("Idempotency-Key", lateKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(profileBody(0, "学生乙自己的资料", 3)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.displayName").value("学生乙自己的资料"));

        mockMvc.perform(put("/api/v1/me/profile")
                        .header("Authorization", "Bearer " + first.accessToken())
                        .header("Idempotency-Key", lateKey)
                        .contentType(MediaType.APPLICATION_JSON).content(firstBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.displayName").value("学生甲迟到回包"));

        mockMvc.perform(delete("/api/v1/auth/sessions/current")
                        .header("Authorization", "Bearer " + first.accessToken())
                        .header("Idempotency-Key", "late-packet-logout")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/v1/me/profile")
                        .header("Authorization", "Bearer " + first.accessToken())
                        .header("Idempotency-Key", lateKey)
                        .contentType(MediaType.APPLICATION_JSON).content(firstBody))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_EXPIRED"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT display_name FROM app_user WHERE id = 'user_demo_student_01'", String.class))
                .isEqualTo("学生甲迟到回包");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT display_name FROM app_user WHERE id = 'user_demo_student_02'", String.class))
                .isEqualTo("学生乙自己的资料");
    }

    @Test
    void logoutRequiresIdempotencyKeyAndKeepsSessionAliveOnFailure() throws Exception {
        SessionView session = login(LOGIN, PASSWORD);
        mockMvc.perform(delete("/api/v1/auth/sessions/current")
                        .header("Authorization", "Bearer " + session.accessToken())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + session.accessToken()))
                .andExpect(status().isOk());
    }

    @Test
    void disabledUserIsRejectedImmediately() throws Exception {
        SessionView session = login(LOGIN, PASSWORD);
        jdbcTemplate.update("UPDATE app_user SET disabled = true WHERE login_id = ?", LOGIN);

        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + session.accessToken()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_EXPIRED"));
        mockMvc.perform(post("/api/v1/auth/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(LOGIN, PASSWORD)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_DISABLED"));
        mockMvc.perform(post("/api/v1/auth/sessions/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + session.refreshToken() + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void profileDefaultsRevisionAndIdempotency() throws Exception {
        SessionView session = login(LOGIN, PASSWORD);
        String token = session.accessToken();

        mockMvc.perform(get("/api/v1/me/profile").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revision").value(0))
                .andExpect(jsonPath("$.data.displayName").value("示例学生甲"))
                .andExpect(jsonPath("$.data.weeklyHours").value(0))
                .andExpect(jsonPath("$.data.availability").value("READY"))
                .andExpect(jsonPath("$.data.intro").value(""));

        String body = profileBody(0, "示例学生甲（资料）", 6);
        mockMvc.perform(put("/api/v1/me/profile")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "profile-key-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revision").value(1))
                .andExpect(jsonPath("$.data.weeklyHours").value(6))
                .andExpect(jsonPath("$.data.skills[1]").value("测试"));

        mockMvc.perform(put("/api/v1/me/profile")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "profile-key-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revision").value(1));

        String different = profileBody(0, "另一个名字", 6);
        mockMvc.perform(put("/api/v1/me/profile")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "profile-key-1")
                        .contentType(MediaType.APPLICATION_JSON).content(different))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        mockMvc.perform(put("/api/v1/me/profile")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "profile-key-2")
                        .contentType(MediaType.APPLICATION_JSON).content(profileBody(0, "示例学生甲（资料）", 6)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESOURCE_CHANGED"));

        mockMvc.perform(put("/api/v1/me/profile")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "profile-key-3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(profileBody(1, "示例学生甲（资料已更新）", 8)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revision").value(2));

        Integer receiptCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM operation_receipt WHERE path = '/api/v1/me/profile'",
                Integer.class);
        assertThat(receiptCount).isEqualTo(2);

        String ciphertext = jdbcTemplate.queryForObject("""
                SELECT result_ciphertext FROM operation_receipt
                WHERE path = '/api/v1/me/profile' AND key = 'profile-key-1'
                """, String.class);
        java.time.OffsetDateTime expiresAt = jdbcTemplate.queryForObject("""
                SELECT expires_at FROM operation_receipt
                WHERE path = '/api/v1/me/profile' AND key = 'profile-key-1'
                """, java.time.OffsetDateTime.class);
        assertThat(ciphertext).doesNotContain("示例学生甲").doesNotContain("Java");
        assertThat(expiresAt.toInstant()).isEqualTo(BASE.plus(Duration.ofDays(7)));

        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revision").value(3))
                .andExpect(jsonPath("$.data.capabilities").isEmpty());
    }

    @Test
    void profileUpdateRequiresIdempotencyKeyAndRejectsUnknownFields() throws Exception {
        SessionView session = login(LOGIN, PASSWORD);
        String token = session.accessToken();
        mockMvc.perform(put("/api/v1/me/profile")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(profileBody(0, "名字", 1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));

        String unknown = "{" + profileBody(0, "名字", 1).substring(1, profileBody(0, "名字", 1).length() - 1)
                + ",\"roles\":[\"TEACHER\"]}";
        mockMvc.perform(put("/api/v1/me/profile")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "profile-key-unknown")
                        .contentType(MediaType.APPLICATION_JSON).content(unknown))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("FIELD_NOT_ALLOWED"));
    }

    @Test
    void visibilityDefaultsAndRevisionFlow() throws Exception {
        SessionView session = login(LOGIN, PASSWORD);
        String token = session.accessToken();
        mockMvc.perform(get("/api/v1/me/profile-visibility").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revision").value(0))
                .andExpect(jsonPath("$.data.worksAudience").value("PRIVATE"))
                .andExpect(jsonPath("$.data.teacherSummaryVisible").value(false));

        mockMvc.perform(put("/api/v1/me/profile-visibility")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "visibility-key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":0,\"worksAudience\":\"CLASS\","
                                + "\"teacherSummaryVisible\":true,\"cooperationSummaryVisible\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revision").value(1))
                .andExpect(jsonPath("$.data.worksAudience").value("CLASS"));

        mockMvc.perform(get("/api/v1/me/profile-visibility").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revision").value(1))
                .andExpect(jsonPath("$.data.teacherSummaryVisible").value(true));

        mockMvc.perform(put("/api/v1/me/profile-visibility")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "visibility-key-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":0,\"worksAudience\":\"TEAM\","
                                + "\"teacherSummaryVisible\":false,\"cooperationSummaryVisible\":false}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESOURCE_CHANGED"));
    }

    @Test
    void concurrentSameKeyHasOneEffect() throws Exception {
        SessionView session = login(LOGIN, PASSWORD);
        String token = session.accessToken();
        String body = profileBody(0, "并发资料", 5);
        String key = "concurrent-key-1";

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await(5, TimeUnit.SECONDS);
                    return mockMvc.perform(put("/api/v1/me/profile")
                                    .header("Authorization", "Bearer " + token)
                                    .header("Idempotency-Key", key)
                                    .contentType(MediaType.APPLICATION_JSON).content(body))
                            .andReturn().getResponse().getStatus();
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get(10, TimeUnit.SECONDS));
            }
            assertThat(statuses).allMatch(value -> value == 200 || value == 409);
            assertThat(statuses).contains(200);
        } finally {
            executor.shutdownNow();
        }

        Integer profileRevision = jdbcTemplate.queryForObject(
                "SELECT revision FROM user_profile WHERE user_id = 'user_demo_student_01'", Integer.class);
        Integer receiptCount = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM operation_receipt
                WHERE user_id = 'user_demo_student_01' AND method = 'PUT'
                  AND path = '/api/v1/me/profile' AND key = ?
                """, Integer.class, key);
        assertThat(profileRevision).isEqualTo(1);
        assertThat(receiptCount).isEqualTo(1);
    }

    @Test
    void concurrentRefreshAndLogoutSerializeOnSessionRow() throws Exception {
        SessionView session = login(LOGIN, PASSWORD);
        String token = session.accessToken();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        int refreshStatus;
        int logoutStatus;
        try {
            Future<Integer> refresh = executor.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return mockMvc.perform(post("/api/v1/auth/sessions/refresh")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"refreshToken\":\"" + session.refreshToken() + "\"}"))
                        .andReturn().getResponse().getStatus();
            });
            Future<Integer> logout = executor.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return mockMvc.perform(delete("/api/v1/auth/sessions/current")
                                .header("Authorization", "Bearer " + token)
                                .header("Idempotency-Key", "race-logout-key")
                                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                        .andReturn().getResponse().getStatus();
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            refreshStatus = refresh.get(10, TimeUnit.SECONDS);
            logoutStatus = logout.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
        assertThat(logoutStatus).isEqualTo(200);
        assertThat(refreshStatus).isIn(200, 401);

        Integer activeTokens = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM access_token a
                JOIN auth_session s ON s.id = a.session_id
                WHERE s.refresh_hash = ? AND a.revoked_at IS NULL
                """, Integer.class, Hashing.sha256Hex(session.refreshToken()));
        Integer revokedSessions = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM auth_session WHERE refresh_hash = ? AND revoked_at IS NOT NULL",
                Integer.class, Hashing.sha256Hex(session.refreshToken()));
        assertThat(activeTokens).isZero();
        assertThat(revokedSessions).isOne();
    }

    @Test
    void failedTransactionLeavesNoReceiptOrAudit() {
        AuthenticatedUser principal = new AuthenticatedUser(
                "user_fail", "session_fail", "login_fail", "school_fail",
                "Fail", "VERIFIED", List.of(Role.STUDENT), false);
        IdempotencyRequest request = new IdempotencyRequest(principal, "PUT",
                "/api/v1/test/fail", "fail-key", Map.of("x", 1), List.of());

        assertThatThrownBy(() -> idempotencyService.execute(request, String.class, () -> {
        }, () -> {
            auditService.record(principal.userId(), "TEST_FAIL", "TEST", "x", null, null, null);
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);

        Integer receipts = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM operation_receipt WHERE path = '/api/v1/test/fail'", Integer.class);
        Integer audits = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_event WHERE action = 'TEST_FAIL'", Integer.class);
        assertThat(receipts).isZero();
        assertThat(audits).isZero();
    }

    @Test
    void loginRejectsClientSuppliedCapabilitiesAndUnknownFields() throws Exception {
        String body = "{\"loginId\":\"" + LOGIN + "\",\"password\":\"" + PASSWORD
                + "\",\"capabilities\":[\"HELP_CONTENT_WRITE\"]}";
        mockMvc.perform(post("/api/v1/auth/sessions")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("FIELD_NOT_ALLOWED"));
    }

    @Test
    void publicHelpPathsAreNotForcedThroughAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/help/articles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.items").isArray());
    }

    private SessionView login(String loginId, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(loginId, password)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andReturn();
        return data(result, SessionView.class);
    }

    private void loginFailure(String loginId, String password) throws Exception {
        mockMvc.perform(post("/api/v1/auth/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(loginId, password)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    private SessionView refresh(String refreshToken) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/sessions/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return data(result, SessionView.class);
    }

    private String loginBody(String loginId, String password) throws Exception {
        return objectMapper.writeValueAsString(Map.of("loginId", loginId, "password", password));
    }

    private String profileBody(int expectedRevision, String displayName, int weeklyHours) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "expectedRevision", expectedRevision,
                "displayName", displayName,
                "skills", List.of("Java", "测试"),
                "weeklyHours", weeklyHours,
                "availability", "周三晚",
                "collaborationGoal", "完成课程设计",
                "intro", "负责客户端"));
    }

    private <T> T data(MvcResult result, Class<T> type) throws Exception {
        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        return objectMapper.treeToValue(root.get("data"), type);
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
