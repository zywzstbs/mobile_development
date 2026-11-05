package cn.gulupai.server.course;

import cn.gulupai.server.it.TestSchemaCleaner;
import cn.gulupai.server.common.clock.MutableClock;
import cn.gulupai.server.identity.cli.AcademicImportService;
import cn.gulupai.server.identity.cli.AdminImportService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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

import java.nio.file.Files;
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
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(CourseP02IT.TestClockConfig.class)
class CourseP02IT {

    private static final Instant BASE = Instant.parse("2026-10-31T00:00:00Z");
    private static final String SCHOOL = "school_demo_01";
    private static final String TERM = "term_demo_2026a";
    private static final String MOBILE_COURSE = "course_name_demo_mobile";
    private static final String DB_COURSE = "course_name_demo_db";
    private static final String TEACHER = "示例教师";

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
                "rate_window", "import_batch", "user_role", "user_profile", "app_user", "school"));
    }

    @Test
    void dictionaryEndpointsReturnImportedOrderedData() throws Exception {
        String token = login("student_demo_01", "demo-password-01");

        mockMvc.perform(get("/api/v1/schools/{schoolId}/terms", SCHOOL)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value(TERM))
                .andExpect(jsonPath("$.data.items[0].timezone").value("Asia/Shanghai"))
                .andExpect(jsonPath("$.data.items[0].startDate").value("2026-09-01"))
                .andExpect(jsonPath("$.data.hasMore").value(false))
                .andExpect(jsonPath("$.data.total").value(1));

        mockMvc.perform(get("/api/v1/schools/{schoolId}/periods", SCHOOL)
                        .param("termId", TERM)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value("period_demo_1"))
                .andExpect(jsonPath("$.data.items[0].startTime").value("08:00:00"))
                .andExpect(jsonPath("$.data.items[0].endTime").value("09:40:00"))
                .andExpect(jsonPath("$.data.items.length()").value(3));

        mockMvc.perform(get("/api/v1/course-names")
                        .param("schoolId", SCHOOL)
                        .param("q", "移动")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value(MOBILE_COURSE))
                .andExpect(jsonPath("$.data.items[0].aliases[0]").value("移动应用开发"))
                .andExpect(jsonPath("$.data.total").value(1));

        mockMvc.perform(get("/api/v1/course-names")
                        .param("schoolId", SCHOOL)
                        .param("q", "%")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));

        mockMvc.perform(get("/api/v1/course-names")
                        .param("schoolId", SCHOOL)
                        .param("q", "x".repeat(81))
                        .header("Authorization", bearer(token)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void crossSchoolReadsAreRejected() throws Exception {
        String token = login("student_demo_01", "demo-password-01");
        mockMvc.perform(get("/api/v1/schools/{schoolId}/terms", "school_other")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(get("/api/v1/squares")
                        .param("schoolId", "school_other").param("termId", TERM)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/course-names")
                        .param("schoolId", "school_other")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isForbidden());
    }

    @Test
    void matchIsReadOnlyAndIssuesFiveMinuteToken() throws Exception {
        String token = login("student_demo_01", "demo-password-01");
        MvcResult result = mockMvc.perform(post("/api/v1/squares/matches")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(matchBody("period_demo_2", MOBILE_COURSE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.canCreate").value(true))
                .andExpect(jsonPath("$.data.exactMatch").value(nullValue()))
                .andExpect(jsonPath("$.data.normalizedInput.courseNameId").value(MOBILE_COURSE))
                .andExpect(jsonPath("$.data.normalizedInput.teacherName").value(TEACHER))
                .andExpect(jsonPath("$.data.candidateCheckToken").isString())
                .andReturn();
        JsonNode data = data(result);
        assertThat(objectMapper.treeToValue(data.get("expiresAt"), Instant.class))
                .isEqualTo(BASE.plus(Duration.ofMinutes(5)));
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM preview_token", Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM square", Integer.class))
                .isZero();

        mockMvc.perform(post("/api/v1/squares/matches")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(matchBody("period_demo_2", MOBILE_COURSE) + "x"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createSquareFromTokenCreatesVerifiedMembershipAndStableNumber() throws Exception {
        String token = login("student_demo_01", "demo-password-01");
        JsonNode match = match(token, "period_demo_2", MOBILE_COURSE);
        String checkToken = match.get("candidateCheckToken").asText();

        JsonNode square = createSquare(token, "period_demo_2", MOBILE_COURSE,
                checkToken, "p02-square-key-1");
        assertThat(square.get("id").asText()).isNotBlank();
        assertThat(square.get("squareNo").asText()).startsWith("SQ-2026-");
        assertThat(square.get("fullCourseName").asText()).isEqualTo("移动应用开发实践");
        assertThat(square.get("capabilities").get(0).asText()).isEqualTo("COURSE_READ");
        assertThat(square.get("myMembershipStatus").asText()).isEqualTo("ACTIVE");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT verified_course_id FROM square WHERE id = ?", String.class,
                square.get("id").asText())).isEqualTo("vc_demo_mobile_01");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM course_membership WHERE square_id = ? AND user_id = ? AND status = 'ACTIVE'",
                Integer.class, square.get("id").asText(), "user_demo_student_01")).isEqualTo(1);

        JsonNode secondMatch = match(token, "period_demo_2", MOBILE_COURSE);
        assertThat(secondMatch.get("canCreate").asBoolean()).isFalse();
        assertThat(secondMatch.get("exactMatch").get("id").asText())
                .isEqualTo(square.get("id").asText());

        JsonNode existing = createSquare(token, "period_demo_2", MOBILE_COURSE,
                secondMatch.get("candidateCheckToken").asText(), "p02-square-key-2");
        assertThat(existing.get("id").asText()).isEqualTo(square.get("id").asText());
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM square", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void expiredOrForeignMatchTokenIsRejected() throws Exception {
        String student1 = login("student_demo_01", "demo-password-01");
        String student2 = login("student_demo_02", "demo-password-02");
        JsonNode match = match(student1, "period_demo_2", MOBILE_COURSE);
        String checkToken = match.get("candidateCheckToken").asText();

        mockMvc.perform(post("/api/v1/squares")
                        .header("Authorization", bearer(student2))
                        .header("Idempotency-Key", "p02-foreign-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("period_demo_2", MOBILE_COURSE, checkToken)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM square", Integer.class))
                .isZero();

        clock.advance(Duration.ofMinutes(6));
        mockMvc.perform(post("/api/v1/squares")
                        .header("Authorization", bearer(student1))
                        .header("Idempotency-Key", "p02-expired-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("period_demo_2", MOBILE_COURSE, checkToken)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM square", Integer.class))
                .isZero();
    }

    @Test
    void sameCourseNameWithDifferentSlotsCreatesDifferentSquares() throws Exception {
        String token = login("student_demo_01", "demo-password-01");
        JsonNode first = createSquare(token, "period_demo_2", MOBILE_COURSE,
                match(token, "period_demo_2", MOBILE_COURSE).get("candidateCheckToken").asText(),
                "p02-slot-key-1");
        JsonNode second = createSquare(token, "period_demo_1", MOBILE_COURSE,
                match(token, "period_demo_1", MOBILE_COURSE).get("candidateCheckToken").asText(),
                "p02-slot-key-2");
        assertThat(first.get("id").asText()).isNotEqualTo(second.get("id").asText());
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM square", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void concurrentSameCanonicalCreatesOneSquare() throws Exception {
        String token = login("student_demo_01", "demo-password-01");
        JsonNode match = match(token, "period_demo_2", MOBILE_COURSE);
        String checkToken = match.get("candidateCheckToken").asText();
        String body = createBody("period_demo_2", MOBILE_COURSE, checkToken);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<Future<MvcResult>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < 2; i++) {
                String key = "p02-concurrent-square-" + i;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await(5, TimeUnit.SECONDS);
                    return mockMvc.perform(post("/api/v1/squares")
                                    .header("Authorization", bearer(token))
                                    .header("Idempotency-Key", key)
                                    .contentType(MediaType.APPLICATION_JSON).content(body))
                            .andReturn();
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<String> ids = new ArrayList<>();
            for (Future<MvcResult> future : futures) {
                MvcResult result = future.get(10, TimeUnit.SECONDS);
                assertThat(result.getResponse().getStatus()).isEqualTo(200);
                ids.add(data(result).get("id").asText());
            }
            assertThat(ids.get(0)).isEqualTo(ids.get(1));
        } finally {
            executor.shutdownNow();
        }
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM square", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void customCourseNameCreatesUnverifiedSquareWithoutMembership() throws Exception {
        String student = login("student_demo_01", "demo-password-01");
        String customMatchBody = objectMapper.writeValueAsString(Map.of(
                "schoolId", SCHOOL, "termId", TERM, "teacherName", "自定义教师",
                "slots", List.of(slot("period_demo_1")),
                "customCourseName", "自选课程设计"));
        MvcResult matchResult = mockMvc.perform(post("/api/v1/squares/matches")
                        .header("Authorization", bearer(student))
                        .contentType(MediaType.APPLICATION_JSON).content(customMatchBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.canCreate").value(true))
                .andExpect(jsonPath("$.data.normalizedInput.courseNameId").value(nullValue()))
                .andReturn();
        JsonNode match = data(matchResult);

        String createBody = objectMapper.writeValueAsString(Map.of(
                "schoolId", SCHOOL, "termId", TERM, "teacherName", "自定义教师",
                "slots", List.of(slot("period_demo_1")),
                "customCourseName", "自选课程设计",
                "candidateCheckToken", match.get("candidateCheckToken").asText()));
        JsonNode square = data(mockMvc.perform(post("/api/v1/squares")
                        .header("Authorization", bearer(student))
                        .header("Idempotency-Key", "p02-custom-square")
                        .contentType(MediaType.APPLICATION_JSON).content(createBody))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(square.get("fullCourseName").asText()).isEqualTo("自选课程设计");
        assertThat(square.get("myMembershipStatus").asText()).isEqualTo("NONE");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT verified_course_id FROM square WHERE id = ?", String.class,
                square.get("id").asText())).isNull();

        mockMvc.perform(post("/api/v1/squares/matches")
                        .header("Authorization", bearer(student))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "schoolId", SCHOOL, "termId", TERM, "teacherName", "自定义教师",
                                "slots", List.of(slot("period_demo_1")),
                                "courseNameId", MOBILE_COURSE,
                                "customCourseName", "自选课程设计"))))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void squareListDetailAndNumberResolutionWork() throws Exception {
        String token = login("student_demo_01", "demo-password-01");
        JsonNode square = createSquare(token, "period_demo_2", MOBILE_COURSE,
                match(token, "period_demo_2", MOBILE_COURSE).get("candidateCheckToken").asText(),
                "p02-list-key-1");

        mockMvc.perform(get("/api/v1/squares")
                        .param("schoolId", SCHOOL).param("termId", TERM)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(square.get("id").asText()));

        mockMvc.perform(get("/api/v1/squares")
                        .param("schoolId", SCHOOL).param("termId", TERM)
                        .param("q", "不存在")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));

        mockMvc.perform(get("/api/v1/squares/{id}", square.get("id").asText())
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.squareNo").value(square.get("squareNo").asText()));

        mockMvc.perform(post("/api/v1/square-number-resolutions")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"squareNo\":\"" + square.get("squareNo").asText() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(square.get("id").asText()));

        mockMvc.perform(post("/api/v1/square-number-resolutions")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"squareNo\":\"SQ-2026-999999\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void studentAssignmentStartsPendingAndTeacherConfirms() throws Exception {
        String student = login("student_demo_01", "demo-password-01");
        String teacher = login("teacher_demo_01", "demo-password-03");
        JsonNode square = createSquare(student, "period_demo_2", MOBILE_COURSE,
                match(student, "period_demo_2", MOBILE_COURSE).get("candidateCheckToken").asText(),
                "p02-assignment-square-1");

        JsonNode assignment = createAssignment(student, square.get("id").asText(),
                assignmentBody(), "p02-assignment-key-1");
        assertThat(assignment.get("origin").asText()).isEqualTo("STUDENT");
        assertThat(assignment.get("createdBy").asText()).isEqualTo("user_demo_student_01");
        assertThat(assignment.get("canEdit").asBoolean()).isTrue();
        assertThat(assignment.get("confirmationStatus").asText()).isEqualTo("PENDING_CONFIRMATION");
        assertThat(assignment.get("gradingEnabled").asBoolean()).isFalse();
        assertThat(assignment.get("revision").asInt()).isEqualTo(1);

        mockMvc.perform(get("/api/v1/squares/{id}/assignments", square.get("id").asText())
                        .header("Authorization", bearer(student)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(assignment.get("id").asText()));

        mockMvc.perform(post("/api/v1/assignments/{id}/confirmations", assignment.get("id").asText())
                        .header("Authorization", bearer(student))
                        .header("Idempotency-Key", "p02-student-confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"decision\":\"CONFIRM_NEW\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/assignments/{id}/confirmations", assignment.get("id").asText())
                        .header("Authorization", bearer(teacher))
                        .header("Idempotency-Key", "p02-teacher-confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"decision\":\"CONFIRM_NEW\",\"reason\":\"任务要求明确\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.confirmationStatus").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.revision").value(2));

        mockMvc.perform(post("/api/v1/assignments/{id}/confirmations", assignment.get("id").asText())
                        .header("Authorization", bearer(teacher))
                        .header("Idempotency-Key", "p02-teacher-confirm-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":2,\"decision\":\"CONFIRM_NEW\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESOURCE_CHANGED"));

        mockMvc.perform(get("/api/v1/assignments/{id}", assignment.get("id").asText())
                        .header("Authorization", bearer(student)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.confirmationStatus").value("CONFIRMED"));
    }

    @Test
    void studentCanEditPendingAssignmentUntilConfirmedOrUsed() throws Exception {
        String student = login("student_demo_01", "demo-password-01");
        String other = login("student_demo_02", "demo-password-02");
        JsonNode square = createSquare(student, "period_demo_2", MOBILE_COURSE,
                match(student, "period_demo_2", MOBILE_COURSE).get("candidateCheckToken").asText(),
                "p62-edit-square");
        JsonNode assignment = createAssignment(student, square.get("id").asText(),
                assignmentBody(), "p62-edit-create");
        String id = assignment.get("id").asText();
        String body = objectMapper.writeValueAsString(Map.of(
                "expectedRevision", 1,
                "name", "修正后的作业名称",
                "requirements", "修正后的任务书要求",
                "hardDueAt", "2026-12-30T15:59:59Z",
                "minMembers", 2,
                "maxMembers", 5));
        mockMvc.perform(patch("/api/v1/assignments/{id}", id)
                        .header("Authorization", bearer(other))
                        .header("Idempotency-Key", "p62-edit-other")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mockMvc.perform(patch("/api/v1/assignments/{id}", id)
                        .header("Authorization", bearer(student))
                        .header("Idempotency-Key", "p62-edit-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(id))
                .andExpect(jsonPath("$.data.name").value("修正后的作业名称"))
                .andExpect(jsonPath("$.data.revision").value(2))
                .andExpect(jsonPath("$.data.canEdit").value(true))
                .andExpect(jsonPath("$.data.createdBy").value("user_demo_student_01"));
        mockMvc.perform(patch("/api/v1/assignments/{id}", id)
                        .header("Authorization", bearer(student))
                        .header("Idempotency-Key", "p62-edit-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revision").value(2))
                .andExpect(jsonPath("$.data.name").value("修正后的作业名称"));

        String project = objectMapper.writeValueAsString(Map.of(
                "squareId", square.get("id").asText(), "assignmentId", id,
                "mode", "QUICK", "name", "占用组", "capacity", 3, "roles", List.of()));
        mockMvc.perform(post("/api/v1/projects")
                        .header("Authorization", bearer(student))
                        .header("Idempotency-Key", "p62-edit-project")
                        .contentType(MediaType.APPLICATION_JSON).content(project))
                .andExpect(status().isOk());
        String constrained = objectMapper.writeValueAsString(Map.of(
                "expectedRevision", 2,
                "name", "只改文字",
                "requirements", "人数不变",
                "hardDueAt", "2026-12-30T16:59:59Z",
                "minMembers", 2,
                "maxMembers", 4));
        mockMvc.perform(patch("/api/v1/assignments/{id}", id)
                        .header("Authorization", bearer(student))
                        .header("Idempotency-Key", "p62-edit-constraint")
                        .contentType(MediaType.APPLICATION_JSON).content(constrained))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ASSIGNMENT_IN_USE"));
        String textOnly = objectMapper.writeValueAsString(Map.of(
                "expectedRevision", 2,
                "name", "只改文字",
                "requirements", "人数不变",
                "hardDueAt", "2026-12-30T15:59:59Z",
                "minMembers", 2,
                "maxMembers", 5));
        mockMvc.perform(patch("/api/v1/assignments/{id}", id)
                        .header("Authorization", bearer(student))
                        .header("Idempotency-Key", "p62-edit-text")
                        .contentType(MediaType.APPLICATION_JSON).content(textOnly))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("只改文字"))
                .andExpect(jsonPath("$.data.revision").value(3));
    }

    @Test
    void teacherAssignmentIsConfirmedImmediatelyAndUnrelatedStudentIsRejected() throws Exception {
        String student1 = login("student_demo_01", "demo-password-01");
        String student2 = login("student_demo_02", "demo-password-02");
        String teacher = login("teacher_demo_01", "demo-password-03");

        JsonNode square = createSquare(teacher, "period_demo_2", MOBILE_COURSE,
                match(teacher, "period_demo_2", MOBILE_COURSE).get("candidateCheckToken").asText(),
                "p02-teacher-square-1");
        JsonNode assignment = createAssignment(teacher, square.get("id").asText(),
                assignmentBody(), "p02-teacher-assignment-1");
        assertThat(assignment.get("origin").asText()).isEqualTo("TEACHER");
        assertThat(assignment.get("confirmationStatus").asText()).isEqualTo("CONFIRMED");

        JsonNode dbSquare = createSquare(student1, "period_demo_3", DB_COURSE,
                match(student1, "period_demo_3", DB_COURSE).get("candidateCheckToken").asText(),
                "p02-db-square-1");
        mockMvc.perform(post("/api/v1/squares/{id}/assignments", dbSquare.get("id").asText())
                        .header("Authorization", bearer(student2))
                        .header("Idempotency-Key", "p02-unrelated-assignment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(assignmentBody()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void assignmentValidationRejectsBadInputs() throws Exception {
        String student = login("student_demo_01", "demo-password-01");
        JsonNode square = createSquare(student, "period_demo_2", MOBILE_COURSE,
                match(student, "period_demo_2", MOBILE_COURSE).get("candidateCheckToken").asText(),
                "p02-invalid-square-1");
        String squareId = square.get("id").asText();

        mockMvc.perform(post("/api/v1/squares/{id}/assignments", squareId)
                        .header("Authorization", bearer(student))
                        .contentType(MediaType.APPLICATION_JSON).content(assignmentBody()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));

        mockMvc.perform(post("/api/v1/squares/{id}/assignments", squareId)
                        .header("Authorization", bearer(student))
                        .header("Idempotency-Key", "p02-invalid-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(assignmentBody(Map.of("minMembers", 5, "maxMembers", 2))))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(post("/api/v1/squares/{id}/assignments", squareId)
                        .header("Authorization", bearer(student))
                        .header("Idempotency-Key", "p02-invalid-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(assignmentBody(Map.of("hardDueAt", "2020-01-01T00:00:00Z"))))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(post("/api/v1/squares/{id}/assignments", squareId)
                        .header("Authorization", bearer(student))
                        .header("Idempotency-Key", "p02-invalid-3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(assignmentBody(Map.of("sourceReferences",
                                List.of(Map.of("kind", "FILE", "url", "https://example.test/a.pdf",
                                        "title", "文件"))))))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(post("/api/v1/squares/{id}/assignments", squareId)
                        .header("Authorization", bearer(student))
                        .header("Idempotency-Key", "p02-invalid-4")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(assignmentBody(Map.of("unknown", true))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("FIELD_NOT_ALLOWED"));
    }

    @Test
    void squareIdempotencyReplaysAndDifferentBodyConflicts() throws Exception {
        String student = login("student_demo_01", "demo-password-01");
        JsonNode match = match(student, "period_demo_2", MOBILE_COURSE);
        String check = match.get("candidateCheckToken").asText();
        String body = createBody("period_demo_2", MOBILE_COURSE, check);

        JsonNode first = data(mockMvc.perform(post("/api/v1/squares")
                        .header("Authorization", bearer(student))
                        .header("Idempotency-Key", "p02-idem-square")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn());
        JsonNode replay = data(mockMvc.perform(post("/api/v1/squares")
                        .header("Authorization", bearer(student))
                        .header("Idempotency-Key", "p02-idem-square")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn());
        assertThat(replay.get("id").asText()).isEqualTo(first.get("id").asText());

        String different = createBody("period_demo_1", MOBILE_COURSE, check);
        mockMvc.perform(post("/api/v1/squares")
                        .header("Authorization", bearer(student))
                        .header("Idempotency-Key", "p02-idem-square")
                        .contentType(MediaType.APPLICATION_JSON).content(different))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void academicImportDryRunAndBadForeignKeyLeaveNoData(@TempDir Path tempDir) throws Exception {
        AcademicImportService.ImportSummary reused = academicImportService.importAcademic(
                Path.of("admin/fixtures/p02-demo-academic.json"), false);
        assertThat(reused.reused()).isTrue();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM import_batch WHERE kind = 'ACADEMIC'",
                Integer.class)).isEqualTo(1);

        AcademicImportService.ImportSummary dryRun = academicImportService.importAcademic(
                Path.of("admin/fixtures/p02-demo-academic.json"), true);
        assertThat(dryRun.terms()).isEqualTo(1);
        assertThat(dryRun.verifiedCourses()).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM term", Integer.class)).isEqualTo(1);

        Path bad = tempDir.resolve("bad-academic.json");
        Files.writeString(bad, """
                {"terms":[{"id":"term_bad","schoolId":"school_missing","name":"坏学期",
                  "startDate":"2026-09-01","endDate":"2027-01-31","ordinal":9}]}
                """);
        assertThatThrownBy(() -> academicImportService.importAcademic(bad, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM term WHERE id = 'term_bad'", Integer.class)).isZero();
    }

    private JsonNode match(String token, String periodId, String courseNameId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/squares/matches")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(matchBody(periodId, courseNameId)))
                .andExpect(status().isOk())
                .andReturn();
        return data(result);
    }

    private JsonNode createSquare(String token, String periodId, String courseNameId,
                                  String checkToken, String key) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/squares")
                        .header("Authorization", bearer(token))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(periodId, courseNameId, checkToken)))
                .andExpect(status().isOk())
                .andReturn();
        return data(result);
    }

    private JsonNode createAssignment(String token, String squareId, String body, String key)
            throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/squares/{id}/assignments", squareId)
                        .header("Authorization", bearer(token))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn();
        return data(result);
    }

    private String matchBody(String periodId, String courseNameId) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "schoolId", SCHOOL,
                "termId", TERM,
                "teacherName", TEACHER,
                "slots", List.of(slot(periodId)),
                "courseNameId", courseNameId));
    }

    private String createBody(String periodId, String courseNameId, String checkToken)
            throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "schoolId", SCHOOL,
                "termId", TERM,
                "teacherName", TEACHER,
                "slots", List.of(slot(periodId)),
                "courseNameId", courseNameId,
                "candidateCheckToken", checkToken));
    }

    private Map<String, Object> slot(String periodId) {
        return Map.of(
                "weekday", periodId.equals("period_demo_3") ? 5 : 3,
                "periodId", periodId,
                "weekPattern", "ALL",
                "startWeek", 1,
                "endWeek", 16,
                "customWeeks", List.of());
    }

    private String assignmentBody() throws Exception {
        return assignmentBody(Map.of());
    }

    private String assignmentBody(Map<String, Object> overrides) throws Exception {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("name", "课程设计");
        body.put("requirements", "完成可演示应用");
        body.put("hardDueAt", "2026-12-15T15:59:59Z");
        body.put("minMembers", 2);
        body.put("maxMembers", 6);
        body.put("sourceReferences", List.of());
        body.putAll(overrides);
        return objectMapper.writeValueAsString(body);
    }

    private String login(String loginId, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "loginId", loginId, "password", password))))
                .andExpect(status().isOk())
                .andReturn();
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
