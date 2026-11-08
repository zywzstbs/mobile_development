package cn.gulupai.server.identity;

import cn.gulupai.server.common.api.ApiCode;
import cn.gulupai.server.common.api.ApiException;
import cn.gulupai.server.common.audit.AuditService;
import cn.gulupai.server.identity.application.RegistrationReviewService;
import cn.gulupai.server.identity.cli.AdminCliRunner;
import cn.gulupai.server.identity.cli.AdminImportService;
import cn.gulupai.server.identity.cli.CliOperatorDirectory;
import cn.gulupai.server.identity.persistence.RegistrationRepository;
import cn.gulupai.server.it.TestSchemaCleaner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import org.springframework.boot.DefaultApplicationArguments;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RegistrationP62IT {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private AdminImportService importService;
    @Autowired
    private RegistrationReviewService reviewService;
    @Autowired
    private CliOperatorDirectory operatorDirectory;
    @Autowired
    private RegistrationRepository registrationRepository;
    @Autowired
    private AuditService auditService;
    @Autowired
    private Clock clock;

    @BeforeEach
    void setUp() {
        clearAll();
        importService.importUsers(Path.of("admin/fixtures/p01-demo-users.json"), false);
        jdbcTemplate.update("UPDATE school SET registration_enabled = true WHERE id = 'school_demo_01'");
    }

    @AfterEach
    void tearDown() {
        clearAll();
    }

    @Test
    void anonymousOptionsAndStudentRegistrationThenProfileDefaults() throws Exception {
        mockMvc.perform(get("/api/v1/auth/registration-options"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andExpect(jsonPath("$.data.teacherApplicationEnabled").value(true))
                .andExpect(jsonPath("$.data.schools[0].id").value("school_demo_01"));
        mockMvc.perform(get("/api/v1/schools/school_demo_01/terms"))
                .andExpect(status().isUnauthorized());

        String body = registration("student_new_01", "STUDENT", null);
        MvcResult created = mockMvc.perform(post("/api/v1/auth/registrations")
                        .header("Idempotency-Key", "p62-student-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.requestedRole").value("STUDENT"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andReturn();
        assertThat(created.getResponse().getContentAsString()).doesNotContain("example-password-123");
        mockMvc.perform(post("/api/v1/auth/registrations")
                        .header("Idempotency-Key", "p62-student-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(data(created).get("id").asText()));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM app_user WHERE login_id = 'student_new_01'", Integer.class)).isEqualTo(1);

        String token = login("student_new_01", "example-password-123");
        mockMvc.perform(get("/api/v1/me/profile").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revision").value(0))
                .andExpect(jsonPath("$.data.collaborationGoal").value(""));
        mockMvc.perform(put("/api/v1/me/profile")
                        .header("Authorization", bearer(token))
                        .header("Idempotency-Key", "p62-profile-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedRevision":0,"displayName":"示例同学","skills":[],"weeklyHours":0,
                                 "availability":"","collaborationGoal":"","intro":""}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revision").value(1))
                .andExpect(jsonPath("$.data.collaborationGoal").value(""));
        mockMvc.perform(put("/api/v1/me/profile")
                        .header("Authorization", bearer(token))
                        .header("Idempotency-Key", "p62-profile-bad")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedRevision":1,"displayName":"示例同学","skills":["Java"],"weeklyHours":61,
                                 "availability":"","collaborationGoal":"","intro":""}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.data.fieldErrors[0].field").value("weeklyHours"))
                .andExpect(jsonPath("$.data.fieldErrors[0].code").value("OUT_OF_RANGE"))
                .andExpect(jsonPath("$.message").value("请检查填写内容"));
    }

    @Test
    void teacherStaysPendingUntilControlledReview() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/auth/registrations")
                        .header("Idempotency-Key", "p62-teacher-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registration("teacher_new_01", "TEACHER", "计算机学院工号 T10086")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING_REVIEW"))
                .andReturn();
        assertThat(created.getResponse().getContentAsString()).doesNotContain("T10086");
        mockMvc.perform(post("/api/v1/auth/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"teacher_new_01\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        mockMvc.perform(post("/api/v1/auth/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"teacher_new_01\",\"password\":\"example-password-123\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_PENDING_REVIEW"));
        String userId = jdbcTemplate.queryForObject(
                "SELECT id FROM app_user WHERE login_id = 'teacher_new_01'", String.class);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM user_role WHERE user_id = ? AND role = 'TEACHER'", Integer.class, userId))
                .isZero();

        String applicationId = data(created).get("id").asText();
        CliOperatorDirectory.Reviewer reviewer = operatorDirectory.requireCurrent();
        Output approved = runCli(reviewCli(),
                "--app.cli.application-id=" + applicationId,
                "--app.cli.decision=APPROVE",
                "--app.cli.expected-revision=1",
                "--app.cli.reason=学校已核验");
        assertThat(approved.code()).isZero();
        assertThat(approved.out()).contains(reviewer.displayLabel());
        assertThat(approved.out()).doesNotContain("cli-test");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT reviewed_by FROM registration_application WHERE id = ?", String.class, applicationId))
                .isEqualTo(reviewer.subjectId())
                .isNotEqualTo(reviewer.displayLabel());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT actor_id FROM audit_event WHERE action = 'REGISTRATION_APPROVE' AND resource_id = ?",
                String.class, applicationId))
                .isEqualTo(reviewer.subjectId());
        String token = login("teacher_new_01", "example-password-123");
        mockMvc.perform(get("/api/v1/me").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roles[0]").value("TEACHER"))
                .andExpect(jsonPath("$.data.capabilities").isEmpty());
    }

    @Test
    void reviewRejectsUnauthorizedSelfReviewAndImpersonatedActor() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/auth/registrations")
                        .header("Idempotency-Key", "p62-teacher-guard")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registration("teacher_guard_01", "TEACHER", "计算机学院工号 T20000")))
                .andExpect(status().isOk())
                .andReturn();
        String applicationId = data(created).get("id").asText();
        String applicantId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM registration_application WHERE id = ?", String.class, applicationId);

        CliOperatorDirectory denied = new CliOperatorDirectory();
        RegistrationReviewService blocked = new RegistrationReviewService(
                registrationRepository, auditService, clock, denied);
        assertThatThrownBy(() -> blocked.review(applicationId, 1, "APPROVE", "未授权"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未获注册审核授权");
        assertPending(applicationId);

        jdbcTemplate.update("UPDATE registration_application SET user_id = ? WHERE id = ?",
                operatorDirectory.requireCurrent().subjectId(), applicationId);
        assertThatThrownBy(() -> reviewService.review(applicationId, 1, "APPROVE", "自己审自己"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ApiCode.SELF_REVIEW_FORBIDDEN);
        assertPending(applicationId);

        jdbcTemplate.update("UPDATE registration_application SET user_id = ? WHERE id = ?",
                applicantId, applicationId);
        Output impersonated = runCli(reviewCli(),
                "--app.cli.application-id=" + applicationId,
                "--app.cli.decision=APPROVE",
                "--app.cli.expected-revision=1",
                "--app.cli.reason=冒充审核人",
                "--app.cli.actor=" + applicantId);
        assertThat(impersonated.code()).isEqualTo(1);
        assertThat(impersonated.err()).contains("不能通过 app.cli.actor 指定审核人");
        assertThat(impersonated.out()).doesNotContain("ACTIVE");
        assertPending(applicationId);
    }

    @Test
    void rejectsUnknownAuthorityFieldsAndStudentVerificationText() throws Exception {
        mockMvc.perform(post("/api/v1/auth/registrations")
                        .header("Idempotency-Key", "p62-unknown")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"loginId":"student_new_02","password":"example-password-123",
                                 "displayName":"示例同学","schoolId":"school_demo_01",
                                 "requestedRole":"STUDENT","roles":["TEACHER"]}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("FIELD_NOT_ALLOWED"));
        mockMvc.perform(post("/api/v1/auth/registrations")
                        .header("Idempotency-Key", "p62-student-text")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registration("student_new_03", "STUDENT", "不该出现")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.data.fieldErrors[0].field").value("teacherApplication"));
        mockMvc.perform(post("/api/v1/auth/registrations")
                        .header("Idempotency-Key", "p62-dup-account")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registration("student_demo_01", "STUDENT", null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACCOUNT_EXISTS"));
    }

    private String registration(String loginId, String role, String teacherApplication) throws Exception {
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("loginId", loginId);
        body.put("password", "example-password-123");
        body.put("displayName", "示例同学");
        body.put("schoolId", "school_demo_01");
        body.put("requestedRole", role);
        if (teacherApplication != null) {
            body.put("teacherApplication", teacherApplication);
        }
        return objectMapper.writeValueAsString(body);
    }

    private String login(String loginId, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "loginId", loginId, "password", password))))
                .andExpect(status().isOk())
                .andReturn();
        return data(result).get("accessToken").asText();
    }

    private JsonNode data(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private void clearAll() {
        TestSchemaCleaner.deleteAll(jdbcTemplate, List.of("file_reference", "file_authorization", "cleanup_plan", "stored_file",
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
                "rate_window", "import_batch", "user_role", "user_profile",
                "registration_receipt", "registration_application", "app_user", "school"));
    }

    private AdminCliRunner reviewCli() {
        return new AdminCliRunner(null, null, null, null, null, null, null, null, null,
                null, null, reviewService, operatorDirectory, "review-registration", "", false, "cli-test");
    }

    private static Output runCli(AdminCliRunner runner, String... args) {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            int code = runner.execute(new DefaultApplicationArguments(args));
            return new Output(code, out.toString(StandardCharsets.UTF_8),
                    err.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    private void assertPending(String applicationId) {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM registration_application WHERE id = ?", String.class, applicationId))
                .isEqualTo("PENDING_REVIEW");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT reviewed_by FROM registration_application WHERE id = ?", String.class, applicationId))
                .isNull();
    }

    private record Output(int code, String out, String err) {
    }
}
