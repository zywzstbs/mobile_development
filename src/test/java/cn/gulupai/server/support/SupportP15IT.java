package cn.gulupai.server.support;

import cn.gulupai.server.common.clock.MutableClock;
import cn.gulupai.server.identity.cli.AcademicImportService;
import cn.gulupai.server.identity.cli.AdminImportService;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(SupportP15IT.TestClockConfig.class)
class SupportP15IT {

    private static final Instant BASE = Instant.parse("2026-10-31T00:00:00Z");
    private static final String SCHOOL = "school_demo_01";
    private static final String TERM = "term_demo_2026a";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MutableClock clock;
    @Autowired private AdminImportService users;
    @Autowired private AcademicImportService academics;

    @BeforeEach
    void setUp() throws Exception {
        clearAll();
        clock.setInstant(BASE);
        users.importUsers(Path.of("admin/fixtures/p01-demo-users.json"), false);
        academics.importAcademic(Path.of("admin/fixtures/p02-demo-academic.json"), false);
        jdbc.update("""
                INSERT INTO support_route(id, type, name, available) VALUES
                ('route_support','SUPPORT','平台使用求助',true),
                ('route_course','COURSE','课程与教学求助',true),
                ('route_grades','GRADES','成绩与评价求助',true)
                ON CONFLICT (id) DO NOTHING
                """);
        jdbc.update("""
                INSERT INTO help_article(id, audience, title, summary, body, category, app_version_range, published, revision, updated_at)
                VALUES ('help_general_1','GENERAL','如何提交任务','提交说明','在任务详情提交文件','GENERAL','1.0.*',true,1,?)
                """, java.time.OffsetDateTime.ofInstant(BASE, ZoneOffset.UTC));
    }

    @AfterEach
    void tearDown() {
        clearAll();
    }

    private void clearAll() {
        for (String table : List.of("help_article", "help_feedback", "support_history", "support_reply",
                "support_ticket", "support_route", "operator_grant", "report_handling", "content_report",
                "announcement_reminder", "announcement_preview", "announcement_receipt",
                "announcement_recipient", "announcement_version", "announcement",
                "notification_preference", "notification", "notification_outbox", "chat_change",
                "chat_message", "chat_read_state", "chat_counter", "topic_follow", "content_revision",
                "board_reply", "board_topic", "dice_round", "dice_roll", "dice_participant",
                "dice_session", "report_snapshot", "delivery_release", "delivery_release_preview",
                "delivery_release_draft", "overall_reflection", "project_reflection", "file_reference",
                "file_authorization", "cleanup_plan", "stored_file", "personnel_handoff",
                "material_request", "personnel_record", "personnel_case_grant", "personnel_case", "removal_ballot",
                "handover_ack", "handover_item", "member_change_event", "member_change",
                "removal_vote", "teacher_summary_consent", "teacher_summary", "grade_appeal",
                "grade_snapshot", "grade_publication", "settlement", "ledger_correction", "grade_draft",
                "grade_rule", "contribution_confirmation", "unit_acceptance", "acceptance",
                "submission", "delivery_draft", "proposal_response", "unit_change_proposal",
                "reviewer_invitation", "reviewer_arrangement", "agreement_ack", "unit_share",
                "task_dependency", "criterion", "delivery_unit", "agreement", "task", "user_project_color",
                "role_ack", "role_negotiation", "ai_reference", "peer_review_version", "peer_review",
                "work_version", "work", "project_code", "seat_reservation", "team_invitation",
                "team_application", "assignment_team_slot", "membership", "role_slot", "project_stage",
                "project", "teacher_transfer", "teacher_claim", "teacher_assignment", "class_grant",
                "course_code", "course_join_request", "cursor_snapshot", "assignment",
                "course_membership", "merge_ballot", "square_merge", "preview_token", "square",
                "verified_enrollment", "verified_course", "course_name", "period", "term",
                "operation_receipt", "access_token", "auth_session", "audit_event", "rate_window",
                "import_batch", "user_role", "user_profile", "app_user", "school")) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    @Test
    void helpPackTicketAcceptReplyResolveReopenAndTransfer() throws Exception {
        String student = login("student_demo_01", "demo-password-01");
        String helper = login("teacher_demo_02", "demo-password-05");
        String teacher1 = login("teacher_demo_01", "demo-password-03");
        mockMvc.perform(get("/api/v1/help/content-pack"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.articles[0].id").value("help_general_1"));
        mockMvc.perform(get("/api/v1/help/articles").header("Authorization", bearer(student)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1));
        mockMvc.perform(get("/api/v1/support/routes").header("Authorization", bearer(student)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(3));
        grant("user_demo_teacher_02", "SUPPORT_HANDLE");
        JsonNode ticket = data(mockMvc.perform(post("/api/v1/me/support-tickets")
                        .header("Authorization", bearer(student)).header("Idempotency-Key", "p15-t1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"routeId\":\"route_support\",\"clientRequestId\":\"c1\"," +
                                "\"subject\":\"无法提交\",\"body\":\"点击提交无响应\",\"attachments\":[]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("OPEN")).andReturn());
        String ticketId = ticket.path("id").asText();
        mockMvc.perform(post("/api/v1/support-tickets/{id}/acceptances", ticketId)
                        .header("Authorization", bearer(helper)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("IN_PROGRESS"));
        mockMvc.perform(post("/api/v1/support-tickets/{id}/replies", ticketId)
                        .header("Authorization", bearer(helper))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"请补充截图\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("ANSWERED"));
        mockMvc.perform(post("/api/v1/support-tickets/{id}/resolution-decisions", ticketId)
                        .header("Authorization", bearer(student))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resolved\":true,\"resolution\":\"已自行解决\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("CLOSED"));
        mockMvc.perform(post("/api/v1/support-tickets/{id}/status-actions", ticketId)
                        .header("Authorization", bearer(student))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"REOPEN\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("OPEN"));
        mockMvc.perform(post("/api/v1/support-tickets/{id}/transfers", ticketId)
                        .header("Authorization", bearer(helper))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetUserId\":\"user_demo_teacher_01\"}"))
                .andExpect(status().isUnprocessableEntity());
        grant("user_demo_teacher_01", "SUPPORT_HANDLE");
        mockMvc.perform(post("/api/v1/support-tickets/{id}/transfers", ticketId)
                        .header("Authorization", bearer(helper))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetUserId\":\"user_demo_teacher_01\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.assigneeId").value("user_demo_teacher_01"));
        jdbc.update("UPDATE support_route SET available=false WHERE id='route_support'");
        mockMvc.perform(post("/api/v1/me/support-tickets")
                        .header("Authorization", bearer(student)).header("Idempotency-Key", "p15-t2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"routeId\":\"route_support\",\"clientRequestId\":\"c2\"," +
                                "\"subject\":\"无路由\",\"body\":\"x\",\"attachments\":[]}"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void handlerClosureDoesNotImpersonateRequesterResolution() throws Exception {
        String student = login("student_demo_01", "demo-password-01");
        String helper = login("teacher_demo_02", "demo-password-05");
        grant("user_demo_teacher_02", "SUPPORT_HANDLE");
        JsonNode ticket = data(mockMvc.perform(post("/api/v1/me/support-tickets")
                        .header("Authorization", bearer(student)).header("Idempotency-Key", "p15-close-t")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"routeId\":\"route_support\",\"clientRequestId\":\"close-1\"," +
                                "\"subject\":\"处理人关闭\",\"body\":\"x\",\"attachments\":[]}"))
                .andExpect(status().isOk()).andReturn());
        String id = ticket.path("id").asText();
        mockMvc.perform(post("/api/v1/support-tickets/{id}/acceptances", id)
                        .header("Authorization", bearer(helper))).andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/support-tickets/{id}/closures", id)
                        .header("Authorization", bearer(helper)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("CLOSED"));
        assertThat(jdbc.queryForObject("SELECT resolved_by FROM support_ticket WHERE id=?", String.class, id))
                .isNull();
        assertThat(jdbc.queryForObject("SELECT status FROM support_ticket WHERE id=?", String.class, id))
                .isEqualTo("CLOSED");
    }

    @Test
    void reportExcerptOperatorHandlingDoesNotDeleteContent() throws Exception {
        String student = login("student_demo_01", "demo-password-01");
        String operator = login("teacher_demo_02", "demo-password-05");
        Project project = project(student);
        JsonNode topic = data(mockMvc.perform(post("/api/v1/projects/{id}/board-topics", project.id())
                        .header("Authorization", bearer(student)).header("Idempotency-Key", "p15-topic")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"举报目标\",\"content\":\"这段内容需要被举报\",\"tag\":\"QUESTION\"," +
                                "\"refs\":[],\"mentions\":[]}"))
                .andExpect(status().isOk()).andReturn());
        String topicId = topic.path("id").asText();
        grant("user_demo_teacher_02", "CONTENT_REVIEW");
        mockMvc.perform(post("/api/v1/me/content-reports")
                        .header("Authorization", bearer(student)).header("Idempotency-Key", "p15-report")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("clientRequestId", "r1",
                                "contentType", "TOPIC", "contentId", topicId,
                                "excerpt", "这段内容需要被举报", "reason", "不当内容"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("OPEN"))
                .andExpect(jsonPath("$.data.sourceVersion").value(1));
        mockMvc.perform(post("/api/v1/me/content-reports")
                        .header("Authorization", bearer(student)).header("Idempotency-Key", "p15-report-bad")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("clientRequestId", "r2",
                                "contentType", "TOPIC", "contentId", topicId,
                                "excerpt", "编造片段", "reason", "x"))))
                .andExpect(status().isUnprocessableEntity());
        JsonNode report = data(mockMvc.perform(get("/api/v1/me/content-reports")
                        .header("Authorization", bearer(student))).andExpect(status().isOk()).andReturn())
                .path("items").get(0);
        String reportId = report.path("id").asText();
        mockMvc.perform(post("/api/v1/content-reports/{id}/handling-actions", reportId)
                        .header("Authorization", bearer(operator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"ACCEPT\",\"note\":\"受理\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("ACCEPTED"));
        mockMvc.perform(post("/api/v1/content-reports/{id}/handling-actions", reportId)
                        .header("Authorization", bearer(operator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"RECORD_RESULT\",\"note\":\"已提醒\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("RECORDED"));
        mockMvc.perform(get("/api/v1/board-topics/{id}", topicId).header("Authorization", bearer(student)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.title").value("举报目标"));
    }

    private void grant(String userId, String capability) throws Exception {
        jdbc.update("""
                INSERT INTO operator_grant(id, user_id, scope_type, capability, active, created_at)
                VALUES (?, ?, 'GLOBAL', ?, true, ?)
                """, UUID.randomUUID().toString(), userId, capability,
                java.time.OffsetDateTime.ofInstant(BASE, ZoneOffset.UTC));
    }

    private String login(String loginId, String password) throws Exception {
        return data(mockMvc.perform(post("/api/v1/auth/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("loginId", loginId, "password", password))))
                .andExpect(status().isOk()).andReturn()).path("accessToken").asText();
    }

    private Project project(String studentToken) throws Exception {
        JsonNode match = data(mockMvc.perform(post("/api/v1/squares/matches")
                        .header("Authorization", bearer(studentToken)).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("schoolId", SCHOOL, "termId", TERM,
                                "teacherName", "示例教师", "slots", slots(),
                                "courseNameId", "course_name_demo_mobile"))))
                .andExpect(status().isOk()).andReturn());
        JsonNode square = data(mockMvc.perform(post("/api/v1/squares")
                        .header("Authorization", bearer(studentToken)).header("Idempotency-Key", "p15-sq")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("schoolId", SCHOOL, "termId", TERM,
                                "teacherName", "示例教师", "slots", slots(),
                                "courseNameId", "course_name_demo_mobile",
                                "candidateCheckToken", match.path("candidateCheckToken").asText()))))
                .andExpect(status().isOk()).andReturn());
        JsonNode assignment = data(mockMvc.perform(post("/api/v1/squares/{id}/assignments",
                                square.path("id").asText())
                        .header("Authorization", bearer(studentToken)).header("Idempotency-Key", "p15-asg")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("name", "举报作业", "requirements", "演示",
                                "hardDueAt", "2026-12-15T15:59:59Z", "minMembers", 2, "maxMembers", 6,
                                "sourceReferences", List.of()))))
                .andExpect(status().isOk()).andReturn());
        String teacher = login("teacher_demo_01", "demo-password-03");
        mockMvc.perform(post("/api/v1/assignments/{id}/confirmations", assignment.path("id").asText())
                        .header("Authorization", bearer(teacher)).header("Idempotency-Key", "p15-conf")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"decision\":\"CONFIRM_NEW\",\"reason\":\"ok\"}"))
                .andExpect(status().isOk());
        JsonNode project = data(mockMvc.perform(post("/api/v1/projects")
                        .header("Authorization", bearer(studentToken)).header("Idempotency-Key", "p15-project")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("squareId", square.path("id").asText(),
                                "assignmentId", assignment.path("id").asText(), "mode", "QUICK",
                                "name", "举报组", "capacity", 3, "discussionTime", "周三", "roles", List.of()))))
                .andExpect(status().isOk()).andReturn());
        return new Project(project.path("id").asText());
    }

    private List<Map<String, Object>> slots() {
        return List.of(Map.of("weekday", 3, "periodId", "period_demo_2", "weekPattern", "ALL",
                "startWeek", 1, "endWeek", 16, "customWeeks", List.of()));
    }

    private static String bearer(String token) { return "Bearer " + token; }

    private JsonNode data(MvcResult result) throws Exception {
        return mapper.readTree(result.getResponse().getContentAsString()).path("data");
    }

    private record Project(String id) {}

    @TestConfiguration
    static class TestClockConfig {
        @Bean @Primary
        MutableClock testClock() { return new MutableClock(BASE, ZoneOffset.UTC); }
    }
}
