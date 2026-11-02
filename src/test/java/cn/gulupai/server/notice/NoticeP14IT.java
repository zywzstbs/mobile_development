package cn.gulupai.server.notice;

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
import java.util.UUID;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(NoticeP14IT.TestClockConfig.class)
class NoticeP14IT {

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
    }

    @AfterEach
    void tearDown() {
        clearAll();
    }

    private void clearAll() {
        for (String table : List.of("announcement_reminder", "announcement_preview",
                "announcement_receipt", "announcement_recipient", "announcement_version",
                "help_article", "help_feedback", "support_route", "support_ticket",
                "support_reply", "support_history", "operator_grant", "content_report",
                "report_handling", "announcement", "notification_preference", "notification", "notification_outbox",
                "chat_change", "chat_message", "chat_read_state", "chat_counter", "topic_follow",
                "content_revision", "board_reply", "board_topic", "dice_round", "dice_roll",
                "dice_participant", "dice_session", "report_snapshot", "delivery_release",
                "delivery_release_preview", "delivery_release_draft", "overall_reflection",
                "project_reflection", "file_reference", "file_authorization", "cleanup_plan",
                "stored_file", "personnel_handoff", "material_request", "personnel_record",
                "personnel_case_grant", "personnel_case", "removal_ballot", "handover_ack", "handover_item",
                "member_change_event", "member_change", "removal_vote", "teacher_summary_consent",
                "teacher_summary", "grade_appeal", "grade_snapshot", "grade_publication",
                "settlement", "ledger_correction", "grade_draft", "grade_rule",
                "contribution_confirmation", "unit_acceptance", "acceptance", "submission",
                "delivery_draft", "proposal_response", "unit_change_proposal", "reviewer_invitation",
                "reviewer_arrangement", "agreement_ack", "unit_share", "task_dependency", "criterion",
                "delivery_unit", "agreement", "task", "user_project_color", "role_ack",
                "role_negotiation", "ai_reference", "peer_review_version", "peer_review",
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
    void previewPublishReadAckReceiptRevisionReminderAndPreferences() throws Exception {
        String teacher = login("teacher_demo_01", "demo-password-03");
        String student = login("student_demo_01", "demo-password-01");
        String squareId = square(student, "p14-square");
        Map<String, Object> previewBody = new java.util.LinkedHashMap<>();
        previewBody.put("expectedRevision", 1);
        previewBody.put("publisherRole", "TEACHER");
        previewBody.put("assignmentId", null);
        previewBody.put("title", "联调安排");
        previewBody.put("content", "周五完成联调");
        previewBody.put("category", "COURSE_INFO");
        previewBody.put("importance", "IMPORTANT");
        previewBody.put("requiresAcknowledgement", true);
        previewBody.put("acknowledgeDueAt", "2026-11-05T15:59:59Z");
        previewBody.put("expiresAt", "2026-12-01T15:59:59Z");
        previewBody.put("refs", List.of());
        JsonNode preview = data(mockMvc.perform(post("/api/v1/squares/{id}/announcement-previews", squareId)
                        .header("Authorization", bearer(teacher)).header("Idempotency-Key", "p14-prev")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(previewBody)))
                .andExpect(status().isOk()).andReturn());
        JsonNode announcement = data(mockMvc.perform(post("/api/v1/squares/{id}/announcements", squareId)
                        .header("Authorization", bearer(teacher)).header("Idempotency-Key", "p14-publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("title", "联调安排",
                                "content", "周五完成联调", "publisherRole", "TEACHER",
                                "previewToken", preview.path("previewToken").asText()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.requiresAcknowledgement").value(true))
                .andExpect(jsonPath("$.data.contentVersion").value(1))
                .andReturn());
        String id = announcement.path("id").asText();
        mockMvc.perform(get("/api/v1/squares/{id}/announcements", squareId)
                        .header("Authorization", bearer(student)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1));
        mockMvc.perform(get("/api/v1/announcements/{id}", id).header("Authorization", bearer(student)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.myReceipt.readAt").value(nullValue()));
        mockMvc.perform(post("/api/v1/announcements/{id}/reads", id)
                        .header("Authorization", bearer(student))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"contentVersion\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.contentVersion").value(1));
        mockMvc.perform(post("/api/v1/announcements/{id}/acknowledgements", id)
                        .header("Authorization", bearer(student))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"contentVersion\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.acknowledgedAt").isString());
        mockMvc.perform(get("/api/v1/announcements/{id}/receipts", id)
                        .param("contentVersion", "1")
                        .header("Authorization", bearer(teacher)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.acknowledgedCount").value(1));
        mockMvc.perform(get("/api/v1/me/notifications").header("Authorization", bearer(student)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1));
        mockMvc.perform(get("/api/v1/me/notification-preferences").header("Authorization", bearer(student)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.channel").value("IN_APP"))
                .andExpect(jsonPath("$.data.quietStart").value("22:00"));
        mockMvc.perform(put("/api/v1/me/notification-preferences")
                        .header("Authorization", bearer(student))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":0,\"channel\":\"SYSTEM\",\"chatDefault\":\"ALL\"," +
                                "\"quietStart\":\"23:00\",\"quietEnd\":\"07:00\",\"timezone\":\"Asia/Shanghai\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.channel").value("SYSTEM"));

        mockMvc.perform(post("/api/v1/announcements/{id}/revisions", id)
                        .header("Authorization", bearer(teacher)).header("Idempotency-Key", "p14-format")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"changeKind\":\"FORMAT_ONLY\"," +
                                "\"title\":\"联调安排\",\"content\":\"周五完成联调\\n\",\"changeSummary\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.displayRevision").value(2))
                .andExpect(jsonPath("$.data.contentVersion").value(1));
        mockMvc.perform(post("/api/v1/announcements/{id}/revisions", id)
                        .header("Authorization", bearer(teacher)).header("Idempotency-Key", "p14-sub")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":2,\"changeKind\":\"SUBSTANTIVE\"," +
                                "\"title\":\"联调安排更新\",\"content\":\"改为周六\",\"changeSummary\":\"改期\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.contentVersion").value(2));
        mockMvc.perform(post("/api/v1/announcements/{id}/acknowledgements", id)
                        .header("Authorization", bearer(student))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"contentVersion\":1}"))
                .andExpect(status().isConflict());
        JsonNode reminderPreview = data(mockMvc.perform(post("/api/v1/announcements/{id}/reminder-previews", id)
                        .header("Authorization", bearer(teacher)).header("Idempotency-Key", "p14-remprev")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":3,\"contentVersion\":2}"))
                .andExpect(status().isOk()).andReturn());
        mockMvc.perform(post("/api/v1/announcements/{id}/reminders", id)
                        .header("Authorization", bearer(teacher)).header("Idempotency-Key", "p14-remind")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("contentVersion", 2,
                                "previewToken", reminderPreview.path("previewToken").asText()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.recordedCount").isNumber());
        mockMvc.perform(post("/api/v1/announcements/{id}/reminders", id)
                        .header("Authorization", bearer(teacher)).header("Idempotency-Key", "p14-remind2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("contentVersion", 2,
                                "previewToken", reminderPreview.path("previewToken").asText()))))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/api/v1/announcements/{id}/revocations", id)
                        .header("Authorization", bearer(teacher)).header("Idempotency-Key", "p14-revoke")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":3,\"reason\":\"课程调整\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REVOKED"));
    }

    @Test
    void newCourseMemberReadsButDoesNotEnterOldDenominator() throws Exception {
        String teacher = login("teacher_demo_01", "demo-password-03");
        String student = login("student_demo_01", "demo-password-01");
        String newcomer = login("teacher_demo_02", "demo-password-05");
        String squareId = square(student, "p14-denominator");
        JsonNode ann = data(mockMvc.perform(post("/api/v1/squares/{id}/announcements", squareId)
                        .header("Authorization", bearer(teacher)).header("Idempotency-Key", "p14-den-ann")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"分母公告\",\"content\":\"正文\",\"publisherRole\":\"TEACHER\"}"))
                .andExpect(status().isOk()).andReturn());
        String id = ann.path("id").asText();
        int count = ann.path("recipientCount").asInt();
        jdbc.update("INSERT INTO course_membership(id, square_id, user_id, status, joined_at, revision) " +
                        "VALUES (?, ?, ?, 'ACTIVE', ?, 1) ON CONFLICT DO NOTHING",
                UUID.randomUUID().toString(), squareId, "user_demo_teacher_02",
                java.time.OffsetDateTime.ofInstant(BASE, ZoneOffset.UTC));
        mockMvc.perform(get("/api/v1/announcements/{id}", id).header("Authorization", bearer(newcomer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.recipientCount").value(count));
        mockMvc.perform(get("/api/v1/announcements/{id}/receipts", id)
                        .param("contentVersion", "1").header("Authorization", bearer(teacher)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.recipientCount").value(count));
    }

    @Test
    void clearingNotificationDoesNotMeanReadOrAcknowledged() throws Exception {
        String teacher = login("teacher_demo_01", "demo-password-03");
        String student = login("student_demo_01", "demo-password-01");
        String squareId = square(student, "p14-clear");
        Map<String, Object> previewBody = new java.util.LinkedHashMap<>();
        previewBody.put("expectedRevision", 1);
        previewBody.put("publisherRole", "TEACHER");
        previewBody.put("assignmentId", null);
        previewBody.put("title", "需知悉公告");
        previewBody.put("content", "正文");
        previewBody.put("category", "COURSE_INFO");
        previewBody.put("importance", "IMPORTANT");
        previewBody.put("requiresAcknowledgement", true);
        previewBody.put("acknowledgeDueAt", "2026-11-05T15:59:59Z");
        previewBody.put("expiresAt", null);
        previewBody.put("refs", List.of());
        JsonNode preview = data(mockMvc.perform(post("/api/v1/squares/{id}/announcement-previews", squareId)
                        .header("Authorization", bearer(teacher)).header("Idempotency-Key", "p14-clear-prev")
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(previewBody)))
                .andExpect(status().isOk()).andReturn());
        JsonNode ann = data(mockMvc.perform(post("/api/v1/squares/{id}/announcements", squareId)
                        .header("Authorization", bearer(teacher)).header("Idempotency-Key", "p14-clear-pub")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("title", "需知悉公告",
                                "content", "正文", "publisherRole", "TEACHER",
                                "previewToken", preview.path("previewToken").asText()))))
                .andExpect(status().isOk()).andReturn());
        JsonNode notification = data(mockMvc.perform(get("/api/v1/me/notifications")
                        .header("Authorization", bearer(student))).andExpect(status().isOk()).andReturn())
                .path("items").get(0);
        mockMvc.perform(post("/api/v1/me/notifications/read-actions")
                        .header("Authorization", bearer(student))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("notificationIds",
                                List.of(notification.path("id").asText())))))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/announcements/{id}", ann.path("id").asText())
                        .header("Authorization", bearer(student)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.myReceipt.readAt").value(nullValue()))
                .andExpect(jsonPath("$.data.myReceipt.acknowledgedAt").value(nullValue()));
        mockMvc.perform(get("/api/v1/me/notifications").param("unreadOnly", "true")
                        .header("Authorization", bearer(student)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    void assistantPublishLosesAuthorityAfterGrantRevoked() throws Exception {
        String teacher = login("teacher_demo_01", "demo-password-03");
        String assistant = login("assistant_demo_01", "demo-password-04");
        String student = login("student_demo_01", "demo-password-01");
        String squareId = square(student, "p14-assistant");
        JsonNode grant = data(mockMvc.perform(post("/api/v1/squares/{id}/admin-grants", squareId)
                        .header("Authorization", bearer(teacher)).header("Idempotency-Key", "p14-grant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentId\":\"user_demo_assistant_01\",\"capabilities\":[\"ANNOUNCEMENT_WRITE\"]}"))
                .andExpect(status().isOk()).andReturn());
        JsonNode announcement = data(mockMvc.perform(post("/api/v1/squares/{id}/announcements", squareId)
                        .header("Authorization", bearer(assistant)).header("Idempotency-Key", "p14-a-publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"助理公告\",\"content\":\"助理发布\",\"publisherRole\":\"CLASS_ASSISTANT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.publisherRole").value("CLASS_ASSISTANT"))
                .andReturn());
        mockMvc.perform(post("/api/v1/admin-grants/{id}/revocations", grant.path("id").asText())
                        .header("Authorization", bearer(teacher)).header("Idempotency-Key", "p14-revoke-grant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"reason\":\"结束\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/squares/{id}/announcements", squareId)
                        .header("Authorization", bearer(assistant)).header("Idempotency-Key", "p14-a-publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"助理公告\",\"content\":\"助理发布\",\"publisherRole\":\"CLASS_ASSISTANT\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/announcements/{id}/revisions", announcement.path("id").asText())
                        .header("Authorization", bearer(assistant)).header("Idempotency-Key", "p14-a-revise")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"changeKind\":\"FORMAT_ONLY\"," +
                                "\"title\":\"助理公告\",\"content\":\"助理发布\\n\",\"changeSummary\":null}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void nonReaderCannotReadAndReadActionsDoNotCrossLaterEvents() throws Exception {
        String teacher = login("teacher_demo_01", "demo-password-03");
        String teacher2 = login("teacher_demo_02", "demo-password-05");
        String student = login("student_demo_01", "demo-password-01");
        String squareId = square(student, "p14-reader");
        JsonNode ann = data(mockMvc.perform(post("/api/v1/squares/{id}/announcements", squareId)
                        .header("Authorization", bearer(teacher)).header("Idempotency-Key", "p14-r-ann")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"读者公告\",\"content\":\"正文\",\"publisherRole\":\"TEACHER\"}"))
                .andExpect(status().isOk()).andReturn());
        mockMvc.perform(get("/api/v1/announcements/{id}", ann.path("id").asText())
                        .header("Authorization", bearer(teacher2)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/squares/{id}/announcements", squareId)
                        .header("Authorization", bearer(teacher2)))
                .andExpect(status().isForbidden());
        JsonNode notification = data(mockMvc.perform(get("/api/v1/me/notifications")
                        .header("Authorization", bearer(student))).andExpect(status().isOk()).andReturn())
                .path("items").get(0);
        mockMvc.perform(post("/api/v1/me/notifications/read-actions")
                        .header("Authorization", bearer(student))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("notificationIds",
                                List.of(notification.path("id").asText())))))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/me/notifications").param("unreadOnly", "true")
                        .header("Authorization", bearer(student)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
    }

    private String square(String studentToken, String key) throws Exception {
        JsonNode match = data(mockMvc.perform(post("/api/v1/squares/matches")
                        .header("Authorization", bearer(studentToken)).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("schoolId", SCHOOL, "termId", TERM,
                                "teacherName", "示例教师", "slots", slots(),
                                "courseNameId", "course_name_demo_mobile"))))
                .andExpect(status().isOk()).andReturn());
        return data(mockMvc.perform(post("/api/v1/squares")
                        .header("Authorization", bearer(studentToken)).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("schoolId", SCHOOL, "termId", TERM,
                                "teacherName", "示例教师", "slots", slots(),
                                "courseNameId", "course_name_demo_mobile",
                                "candidateCheckToken", match.path("candidateCheckToken").asText()))))
                .andExpect(status().isOk()).andReturn()).path("id").asText();
    }

    private List<Map<String, Object>> slots() {
        return List.of(Map.of("weekday", 3, "periodId", "period_demo_2", "weekPattern", "ALL",
                "startWeek", 1, "endWeek", 16, "customWeeks", List.of()));
    }

    private String login(String loginId, String password) throws Exception {
        return data(mockMvc.perform(post("/api/v1/auth/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("loginId", loginId, "password", password))))
                .andExpect(status().isOk()).andReturn()).path("accessToken").asText();
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private JsonNode data(MvcResult result) throws Exception {
        return mapper.readTree(result.getResponse().getContentAsString()).path("data");
    }

    @TestConfiguration
    static class TestClockConfig {
        @Bean @Primary
        MutableClock testClock() {
            return new MutableClock(BASE, ZoneOffset.UTC);
        }
    }
}
