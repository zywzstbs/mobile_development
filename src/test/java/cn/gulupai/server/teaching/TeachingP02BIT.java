package cn.gulupai.server.teaching;

import cn.gulupai.server.common.clock.MutableClock;
import cn.gulupai.server.identity.cli.AcademicImportService;
import cn.gulupai.server.identity.cli.AdminImportService;
import cn.gulupai.server.teaching.application.TeacherClaimReviewService;
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
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TeachingP02BIT.TestClockConfig.class)
class TeachingP02BIT {

    private static final Instant BASE = Instant.parse("2026-10-31T00:00:00Z");
    private static final String SCHOOL = "school_demo_01";
    private static final String TERM = "term_demo_2026a";
    private static final String MOBILE = "course_name_demo_mobile";
    private static final String DB = "course_name_demo_db";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private MutableClock clock;
    @Autowired
    private AdminImportService userImportService;
    @Autowired
    private AcademicImportService academicImportService;
    @Autowired
    private TeacherClaimReviewService claimReviewService;

    @BeforeEach
    void setUp() {
        clearAll();
        clock.setInstant(BASE);
        userImportService.importUsers(Path.of("admin/fixtures/p01-demo-users.json"), false);
        academicImportService.importAcademic(Path.of("admin/fixtures/p02-demo-academic.json"), false);
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
    void grantCodeResolveConfirmAndRevoke() throws Exception {
        String student = login("student_demo_01", "demo-password-01");
        String student2 = login("student_demo_02", "demo-password-02");
        String teacher = login("teacher_demo_01", "demo-password-03");
        String squareId = createSquare(student, "t-grant-square").get("id").asText();

        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM teacher_assignment
                WHERE square_id = ? AND user_id = 'user_demo_teacher_01' AND active
                """, Integer.class, squareId)).isEqualTo(1);

        mockMvc.perform(get("/api/v1/me/teaching-workspaces").header("Authorization", bearer(teacher)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].claimStatus").value("ACTIVE"));

        JsonNode grant = grant(teacher, squareId, "user_demo_student_02",
                List.of("ANNOUNCEMENT_WRITE", "COURSE_JOIN_CODE_MANAGE"), "t-grant-1");
        assertThat(grant.get("expiresAt").asText()).isEqualTo("2027-01-31T15:59:59Z");
        JsonNode replay = grant(teacher, squareId, "user_demo_student_02",
                List.of("ANNOUNCEMENT_WRITE", "COURSE_JOIN_CODE_MANAGE"), "t-grant-1");
        assertThat(replay.get("id").asText()).isEqualTo(grant.get("id").asText());
        mockMvc.perform(post("/api/v1/squares/{id}/admin-grants", squareId)
                        .header("Authorization", bearer(teacher))
                        .header("Idempotency-Key", "t-grant-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "studentId", "user_demo_student_02",
                                "capabilities", List.of("TEAM_SUMMARY_READ")))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        mockMvc.perform(get("/api/v1/me/admin-workspaces").header("Authorization", bearer(student2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].myMembershipStatus").value("NONE"));
        mockMvc.perform(get("/api/v1/squares/{id}/admin-grants", squareId)
                        .header("Authorization", bearer(student2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value(grant.get("id").asText()));
        mockMvc.perform(get("/api/v1/squares/{id}/admin-grants", squareId)
                        .header("Authorization", bearer(student)))
                .andExpect(status().isForbidden());
        JsonNode revoked = revokeGrant(teacher, grant.get("id").asText(), 1, "结束助理工作");
        assertThat(revoked.get("active").asBoolean()).isFalse();
        mockMvc.perform(get("/api/v1/me/admin-workspaces").header("Authorization", bearer(student2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));

        JsonNode code = issueCode(teacher, squareId, squareRevision(teacher, squareId), 3600, "t-code-1");
        assertThat(code.get("code").asText()).isNotBlank();
        mockMvc.perform(get("/api/v1/squares/{id}/join-codes/current", squareId)
                        .header("Authorization", bearer(teacher)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.code").value(code.get("code").asText()));

        JsonNode resolution = resolve(student2, code.get("code").asText());
        assertThat(resolution.get("verificationStatus").asText()).isEqualTo("VERIFIED");
        assertThat(resolution.get("canConfirm").asBoolean()).isTrue();
        JsonNode confirmed = confirm(student2, squareId,
                resolution.get("confirmationToken").asText(), "t-confirm-1");
        assertThat(confirmed.get("membershipStatus").asText()).isEqualTo("ACTIVE");
        JsonNode after = resolve(student2, code.get("code").asText());
        assertThat(after.get("membershipStatus").asText()).isEqualTo("ACTIVE");
        assertThat(after.get("canConfirm").asBoolean()).isFalse();

        JsonNode newCode = issueCode(teacher, squareId, squareRevision(teacher, squareId), 3600, "t-code-2");
        mockMvc.perform(post("/api/v1/square-code-resolutions")
                        .header("Authorization", bearer(student2))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code.get("code").asText() + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CODE_REVOKED"));
        mockMvc.perform(post("/api/v1/square-join-codes/{id}/revocations", newCode.get("id").asText())
                        .header("Authorization", bearer(teacher))
                        .header("Idempotency-Key", "t-code-revoke")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"reason\":\"分享结束\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.code").value(nullValue()))
                .andExpect(jsonPath("$.data.status").value("REVOKED"));
        mockMvc.perform(get("/api/v1/squares/{id}/join-codes/current", squareId)
                        .header("Authorization", bearer(teacher)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(nullValue()));

        mockMvc.perform(post("/api/v1/squares/{id}/join-codes", squareId)
                        .header("Authorization", bearer(teacher))
                        .header("Idempotency-Key", "t-code-bad")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":" + squareRevision(teacher, squareId)
                                + ",\"expiresInSeconds\":299}"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(post("/api/v1/squares/{id}/join-codes", squareId)
                        .header("Authorization", bearer(student2))
                        .header("Idempotency-Key", "t-code-student")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":" + squareRevision(teacher, squareId)
                                + ",\"expiresInSeconds\":300}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void joinRequestsApprovalSelfReviewAndCursor() throws Exception {
        String student = login("student_demo_01", "demo-password-01");
        String student2 = login("student_demo_02", "demo-password-02");
        String assistant = login("assistant_demo_01", "demo-password-04");
        String teacher = login("teacher_demo_01", "demo-password-03");
        String squareId = createSquare(student, "t-join-square").get("id").asText();

        String requestId = createJoinRequest(student2, squareId, "t-join-1").get("id").asText();
        mockMvc.perform(get("/api/v1/squares/{id}/join-requests", squareId)
                        .header("Authorization", bearer(teacher)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1));
        mockMvc.perform(get("/api/v1/me/square-membership-requests")
                        .header("Authorization", bearer(student2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value(requestId));
        mockMvc.perform(get("/api/v1/square-membership-requests/{id}", requestId)
                        .header("Authorization", bearer(student2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"));

        mockMvc.perform(post("/api/v1/square-membership-requests/{id}/decisions", requestId)
                        .header("Authorization", bearer(teacher))
                        .header("Idempotency-Key", "t-join-approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"decision\":\"APPROVE\",\"reason\":\"名单匹配\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.request.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.membership.status").value("ACTIVE"));

        String dbSquare = createSquare(student, "t-join-db", DB, "period_demo_3", 5).get("id").asText();
        createJoinRequest(student2, dbSquare, "t-join-2");
        createJoinRequest(assistant, dbSquare, "t-join-3");
        JsonNode page = data(mockMvc.perform(get("/api/v1/squares/{id}/join-requests", dbSquare)
                        .param("limit", "1").param("status", "PENDING")
                        .header("Authorization", bearer(teacher)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.hasMore").value(true))
                .andExpect(jsonPath("$.data.nextCursor").isString()).andReturn());
        mockMvc.perform(get("/api/v1/squares/{id}/join-requests", dbSquare)
                        .param("limit", "1").param("status", "APPROVED")
                        .param("cursor", page.path("nextCursor").asText())
                        .header("Authorization", bearer(teacher)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CURSOR_EXPIRED"));

        grant(teacher, squareId, "user_demo_assistant_01",
                List.of("COURSE_MEMBERSHIP_REVIEW"), "t-assistant-review");
        String assistantRequest = createJoinRequest(assistant, squareId, "t-assistant-request").get("id").asText();
        mockMvc.perform(post("/api/v1/square-membership-requests/{id}/decisions", assistantRequest)
                        .header("Authorization", bearer(assistant))
                        .header("Idempotency-Key", "t-self-review")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"decision\":\"REJECT\",\"reason\":\"自审\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SELF_REVIEW_FORBIDDEN"));

        String customSquare = createCustomSquare(student, "t-custom-square").get("id").asText();
        mockMvc.perform(post("/api/v1/squares/{id}/join-requests", customSquare)
                        .header("Authorization", bearer(student2))
                        .header("Idempotency-Key", "t-no-reviewer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"请求入课\",\"proofRefs\":[]}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("COURSE_REVIEW_UNAVAILABLE"));
    }

    @Test
    void transferRevokesCodeAndStaleSourceIsRejected() throws Exception {
        String student = login("student_demo_01", "demo-password-01");
        String teacher1 = login("teacher_demo_01", "demo-password-03");
        String teacher2 = login("teacher_demo_02", "demo-password-05");
        String squareId = createSquare(student, "t-transfer-square").get("id").asText();
        JsonNode code = issueCode(teacher1, squareId, 1, 3600, "t-transfer-code");
        JsonNode transfer = createTransfer(teacher1, squareId, squareRevision(teacher1, squareId), teacher2);
        assertThat(transfer.get("status").asText()).isEqualTo("PENDING");

        mockMvc.perform(get("/api/v1/me/teaching-workspaces").header("Authorization", bearer(teacher2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pendingTransfers[0].id").value(transfer.get("id").asText()));
        mockMvc.perform(post("/api/v1/teacher-transfers/{id}/responses", transfer.get("id").asText())
                        .header("Authorization", bearer(teacher2))
                        .header("Idempotency-Key", "t-transfer-accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"decision\":\"ACCEPT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACCEPTED"));
        mockMvc.perform(post("/api/v1/square-code-resolutions")
                        .header("Authorization", bearer(student))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code.get("code").asText() + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CODE_REVOKED"));
        mockMvc.perform(get("/api/v1/me/teaching-workspaces").header("Authorization", bearer(teacher2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].claimStatus").value("ACTIVE"));
        mockMvc.perform(post("/api/v1/squares/{id}/join-codes", squareId)
                        .header("Authorization", bearer(teacher1))
                        .header("Idempotency-Key", "t-old-teacher")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":3,\"expiresInSeconds\":300}"))
                .andExpect(status().isForbidden());

        String staleSquare = createSquare(student, "t-transfer-stale", DB, "period_demo_3", 5).get("id").asText();
        issueCode(teacher1, staleSquare, 1, 3600, "t-stale-code");
        JsonNode stale = createTransfer(teacher1, staleSquare,
                squareRevision(teacher1, staleSquare), teacher2);
        grant(teacher1, staleSquare, "user_demo_student_01", List.of("ANNOUNCEMENT_WRITE"), "t-stale-grant");
        mockMvc.perform(post("/api/v1/teacher-transfers/{id}/responses", stale.get("id").asText())
                        .header("Authorization", bearer(teacher2))
                        .header("Idempotency-Key", "t-stale-accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"decision\":\"ACCEPT\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESOURCE_CHANGED"));
        mockMvc.perform(post("/api/v1/teacher-transfers/{id}/responses", stale.get("id").asText())
                        .header("Authorization", bearer(teacher2))
                        .header("Idempotency-Key", "t-stale-decline")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"decision\":\"DECLINE\",\"reason\":\"来源已变化\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DECLINED"));

        mockMvc.perform(post("/api/v1/squares/{id}/teacher-transfers", squareId)
                        .header("Authorization", bearer(teacher2))
                        .header("Idempotency-Key", "t-student-recipient")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "expectedSquareRevision", squareRevision(teacher2, squareId),
                                "reason", "非法接任", "recipientTeacherId", "user_demo_student_02"))))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void relinquishBlocksWithGrantsAndClaimReviewRestoresAuthority() throws Exception {
        String student = login("student_demo_01", "demo-password-01");
        String teacher = login("teacher_demo_01", "demo-password-03");
        String teacher2 = login("teacher_demo_02", "demo-password-05");
        String squareId = createSquare(student, "t-relinquish-square").get("id").asText();
        grant(teacher, squareId, "user_demo_student_02", List.of("ANNOUNCEMENT_WRITE"), "t-relinquish-grant");
        mockMvc.perform(post("/api/v1/squares/{id}/teacher-relinquishments", squareId)
                        .header("Authorization", bearer(teacher))
                        .header("Idempotency-Key", "t-relinquish-blocked")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedSquareRevision\":" + squareRevision(teacher, squareId)
                                + ",\"reason\":\"退出\"}"))
                .andExpect(status().isConflict());

        String grantId = jdbcTemplate.queryForObject(
                "SELECT id FROM class_grant WHERE square_id = ?", String.class, squareId);
        revokeGrant(teacher, grantId, 1, "撤销后退出");
        mockMvc.perform(post("/api/v1/squares/{id}/teacher-relinquishments", squareId)
                        .header("Authorization", bearer(teacher))
                        .header("Idempotency-Key", "t-relinquish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedSquareRevision\":" + squareRevision(teacher, squareId)
                                + ",\"reason\":\"退出\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RELINQUISHED"));
        mockMvc.perform(get("/api/v1/me/teaching-workspaces").header("Authorization", bearer(teacher)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty());

        mockMvc.perform(post("/api/v1/squares/{id}/teacher-claims", squareId)
                        .header("Authorization", bearer(teacher2))
                        .header("Idempotency-Key", "t-wrong-claim")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"接管\",\"proofRefs\":[]}"))
                .andExpect(status().isForbidden());

        String customSquare = createCustomSquare(student, "t-claim-custom").get("id").asText();
        JsonNode claim = claim(teacher2, customSquare, "t-custom-claim");
        assertThat(claim.get("status").asText()).isEqualTo("PENDING");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM teacher_claim WHERE id = ?", String.class,
                claim.get("id").asText())).isEqualTo("PENDING");
        claimReviewService.review(claim.get("id").asText(), "APPROVE", "管理员核对");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM teacher_assignment
                WHERE square_id = ? AND user_id = 'user_demo_teacher_02' AND active
                """, Integer.class, customSquare)).isEqualTo(1);
    }

    @Test
    void teachingProgressPermissionsAndExpiredCode() throws Exception {
        String student = login("student_demo_01", "demo-password-01");
        String assistant = login("assistant_demo_01", "demo-password-04");
        String teacher = login("teacher_demo_01", "demo-password-03");
        String squareId = createSquare(student, "t-progress-square").get("id").asText();
        mockMvc.perform(get("/api/v1/squares/{id}/teaching-progress", squareId)
                        .header("Authorization", bearer(student)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/squares/{id}/teaching-progress", squareId)
                        .header("Authorization", bearer(teacher)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
        grant(teacher, squareId, "user_demo_assistant_01", List.of("TEAM_SUMMARY_READ"), "t-progress-grant");
        mockMvc.perform(get("/api/v1/squares/{id}/teaching-progress", squareId)
                        .header("Authorization", bearer(assistant)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));

        JsonNode shortCode = issueCode(teacher, squareId, squareRevision(teacher, squareId), 300, "t-short-code");
        clock.advance(Duration.ofSeconds(301));
        mockMvc.perform(post("/api/v1/square-code-resolutions")
                        .header("Authorization", bearer(student))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + shortCode.get("code").asText() + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CODE_EXPIRED"));
        mockMvc.perform(post("/api/v1/square-code-resolutions")
                        .header("Authorization", bearer(student))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"not-real\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CODE_INVALID"));
    }

    private JsonNode createSquare(String token, String key) throws Exception {
        return createSquare(token, key, MOBILE, "period_demo_2", 3);
    }

    private JsonNode createSquare(String token, String key, String courseNameId,
                                  String periodId, int weekday) throws Exception {
        JsonNode match = match(token, courseNameId, periodId, weekday, "示例教师", null);
        String body = objectMapper.writeValueAsString(Map.of(
                "schoolId", SCHOOL, "termId", TERM, "teacherName", "示例教师",
                "slots", List.of(slot(periodId, weekday)), "courseNameId", courseNameId,
                "candidateCheckToken", match.get("candidateCheckToken").asText()));
        return data(postJson(token, "/api/v1/squares", key, body));
    }

    private JsonNode createCustomSquare(String token, String key) throws Exception {
        JsonNode match = match(token, null, "period_demo_1", 3, "自定义教师", "自选课程");
        String body = objectMapper.writeValueAsString(Map.of(
                "schoolId", SCHOOL, "termId", TERM, "teacherName", "自定义教师",
                "slots", List.of(slot("period_demo_1", 3)), "customCourseName", "自选课程",
                "candidateCheckToken", match.get("candidateCheckToken").asText()));
        return data(postJson(token, "/api/v1/squares", key, body));
    }

    private JsonNode match(String token, String courseNameId, String periodId, int weekday,
                           String teacherName, String customName) throws Exception {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("schoolId", SCHOOL);
        body.put("termId", TERM);
        body.put("teacherName", teacherName);
        body.put("slots", List.of(slot(periodId, weekday)));
        if (courseNameId != null) {
            body.put("courseNameId", courseNameId);
        }
        if (customName != null) {
            body.put("customCourseName", customName);
        }
        MvcResult result = mockMvc.perform(post("/api/v1/squares/matches")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk()).andReturn();
        return data(result);
    }

    private MvcResult postJson(String token, String path, String key, String body) throws Exception {
        return mockMvc.perform(post(path)
                        .header("Authorization", bearer(token))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
    }

    private Map<String, Object> slot(String periodId, int weekday) {
        return Map.of("weekday", weekday, "periodId", periodId, "weekPattern", "ALL",
                "startWeek", 1, "endWeek", 16, "customWeeks", List.of());
    }

    private JsonNode grant(String token, String squareId, String studentId,
                           List<String> capabilities, String key) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "studentId", studentId, "capabilities", capabilities));
        return data(postJson(token, "/api/v1/squares/" + squareId + "/admin-grants", key, body));
    }

    private JsonNode revokeGrant(String token, String grantId, int revision, String reason) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "expectedRevision", revision, "reason", reason));
        return data(postJson(token, "/api/v1/admin-grants/" + grantId + "/revocations",
                "revoke-" + grantId, body));
    }

    private JsonNode issueCode(String token, String squareId, int revision, int expiresIn,
                               String key) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "expectedRevision", revision, "expiresInSeconds", expiresIn));
        return data(postJson(token, "/api/v1/squares/" + squareId + "/join-codes", key, body));
    }

    private JsonNode resolve(String token, String code) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/square-code-resolutions")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return data(result);
    }

    private JsonNode confirm(String token, String squareId, String confirmationToken, String key)
            throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "confirmationToken", confirmationToken, "confirm", true));
        return data(postJson(token, "/api/v1/squares/" + squareId + "/membership-confirmations",
                key, body));
    }

    private JsonNode createJoinRequest(String token, String squareId, String key) throws Exception {
        String body = "{\"message\":\"请核对选课\",\"proofRefs\":[]}";
        return data(postJson(token, "/api/v1/squares/" + squareId + "/join-requests", key, body));
    }

    private JsonNode createTransfer(String token, String squareId, int revision, String recipient)
            throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "expectedSquareRevision", revision, "reason", "课程交接",
                "recipientTeacherId", "user_demo_teacher_02"));
        return data(postJson(token, "/api/v1/squares/" + squareId + "/teacher-transfers",
                "transfer-" + squareId, body));
    }

    private JsonNode claim(String token, String squareId, String key) throws Exception {
        String body = "{\"message\":\"申请负责课程\",\"proofRefs\":[]}";
        return data(postJson(token, "/api/v1/squares/" + squareId + "/teacher-claims", key, body));
    }

    private int squareRevision(String token, String squareId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/squares/{id}", squareId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andReturn();
        return data(result).get("revision").asInt();
    }

    private String login(String loginId, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "loginId", loginId, "password", password))))
                .andExpect(status().isOk()).andReturn();
        return data(result).get("accessToken").asText();
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private JsonNode data(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
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
