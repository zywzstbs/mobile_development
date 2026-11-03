package cn.gulupai.server.support;

import cn.gulupai.server.it.TestSchemaCleaner;
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
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
                INSERT INTO support_route(id, type, name, available, category) VALUES
                ('route_support','SUPPORT','平台使用求助',true,'OPERATION'),
                ('route_course','COURSE','课程与教学求助',true,'COURSE_REQUIREMENT'),
                ('route_grades','GRADES','成绩与评价求助',true,'GRADES')
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
        TestSchemaCleaner.deleteAll(jdbc, List.of("help_article", "help_feedback", "support_history", "support_reply",
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
                "import_batch", "user_role", "user_profile", "app_user", "school"));
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
        grant("user_demo_teacher_02", "SUPPORT_HANDLE");
        jdbc.update("UPDATE support_route SET receiver_user_id='user_demo_teacher_02' WHERE id='route_support'");
        mockMvc.perform(get("/api/v1/support/routes").param("category", "OPERATION")
                        .header("Authorization", bearer(student)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.available").value(true))
                .andExpect(jsonPath("$.data.routeId").value("route_support"));
        JsonNode ticket = data(mockMvc.perform(post("/api/v1/me/support-tickets")
                        .header("Authorization", bearer(student)).header("Idempotency-Key", "p15-t1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ticketBody("c1", "无法提交", "点击提交无响应")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.data.assigneeId").value("user_demo_teacher_02")).andReturn());
        String ticketId = ticket.path("id").asText();
        mockMvc.perform(post("/api/v1/support-tickets/{id}/acceptances", ticketId)
                        .header("Authorization", bearer(helper)).header("Idempotency-Key", "p15-accept")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedRevision\":1}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("IN_PROGRESS"));
        mockMvc.perform(post("/api/v1/support-tickets/{id}/replies", ticketId)
                        .header("Authorization", bearer(helper)).header("Idempotency-Key", "p15-reply")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":2,\"content\":\"请补充截图\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.ticketStatus").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.data.reply.content").value("请补充截图"));
        mockMvc.perform(post("/api/v1/support-tickets/{id}/status-actions", ticketId)
                        .header("Authorization", bearer(helper)).header("Idempotency-Key", "p15-answered")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":3,\"action\":\"ANSWERED\",\"reason\":\"已答复\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("ANSWERED"));
        mockMvc.perform(post("/api/v1/support-tickets/{id}/resolution-decisions", ticketId)
                        .header("Authorization", bearer(student)).header("Idempotency-Key", "p15-resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":4,\"resolved\":true,\"note\":\"已自行解决\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("CLOSED"))
                .andExpect(jsonPath("$.data.resolvedByRequester").value(true));
        mockMvc.perform(post("/api/v1/support-tickets/{id}/resolution-decisions", ticketId)
                        .header("Authorization", bearer(student)).header("Idempotency-Key", "p15-reopen")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":5,\"resolved\":false,\"note\":\"仍未解决\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("SUBMITTED"));
        grant("user_demo_teacher_01", "SUPPORT_HANDLE");
        jdbc.update("""
                INSERT INTO support_route(id, type, name, available, category, receiver_user_id)
                VALUES ('route_support_2','SUPPORT','另一受理',true,'OPERATION','user_demo_teacher_01')
                """);
        mockMvc.perform(post("/api/v1/support-tickets/{id}/transfers", ticketId)
                        .header("Authorization", bearer(helper)).header("Idempotency-Key", "p15-transfer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":6,\"targetRouteId\":\"route_support_2\",\"reason\":\"交给另一受理人\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.assigneeId").value("user_demo_teacher_01"))
                .andExpect(jsonPath("$.data.transferTargetRouteId").value("route_support_2"))
                .andExpect(jsonPath("$.data.status").value("SUBMITTED"));
        mockMvc.perform(get("/api/v1/support-tickets/{id}", ticketId).header("Authorization", bearer(helper)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/support-tickets/{id}", ticketId).header("Authorization", bearer(teacher1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.assigneeId").value("user_demo_teacher_01"));
        jdbc.update("UPDATE support_route SET available=false WHERE id='route_support'");
        mockMvc.perform(post("/api/v1/me/support-tickets")
                        .header("Authorization", bearer(student)).header("Idempotency-Key", "p15-t2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ticketBody("c2", "无路由", "仍然失败")))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void handlerClosureDoesNotImpersonateRequesterResolution() throws Exception {
        String student = login("student_demo_01", "demo-password-01");
        String helper = login("teacher_demo_02", "demo-password-05");
        grant("user_demo_teacher_02", "SUPPORT_HANDLE");
        jdbc.update("UPDATE support_route SET receiver_user_id='user_demo_teacher_02' WHERE id='route_support'");
        JsonNode ticket = data(mockMvc.perform(post("/api/v1/me/support-tickets")
                        .header("Authorization", bearer(student)).header("Idempotency-Key", "p15-close-t")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ticketBody("close-1", "处理人关闭", "仍然失败")))
                .andExpect(status().isOk()).andReturn());
        String id = ticket.path("id").asText();
        mockMvc.perform(post("/api/v1/support-tickets/{id}/acceptances", id)
                        .header("Authorization", bearer(helper)).header("Idempotency-Key", "p15-close-accept")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedRevision\":1}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/support-tickets/{id}/closures", id)
                        .header("Authorization", bearer(helper)).header("Idempotency-Key", "p15-close")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":2,\"reason\":\"重复求助\",\"nextAction\":\"查看原问题\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("CLOSED"))
                .andExpect(jsonPath("$.data.resolvedByRequester").value(false));
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
                        .content(reportBody("r1", topicId, "这段内容需要被举报", "不当内容")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.data.source.version").value(1));
        mockMvc.perform(post("/api/v1/me/content-reports")
                        .header("Authorization", bearer(student)).header("Idempotency-Key", "p15-report-bad")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reportBody("r2", topicId, "编造片段", "不当内容")))
                .andExpect(status().isUnprocessableEntity());
        JsonNode report = data(mockMvc.perform(get("/api/v1/me/content-reports")
                        .header("Authorization", bearer(student))).andExpect(status().isOk()).andReturn())
                .path("items").get(0);
        String reportId = report.path("id").asText();
        mockMvc.perform(get("/api/v1/content-reports/{id}", reportId).header("Authorization", bearer(operator)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/content-reports/{id}/handling-actions", reportId)
                        .header("Authorization", bearer(operator)).header("Idempotency-Key", "p15-accept-report")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"action\":\"ACCEPT\",\"note\":\"受理\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("IN_PROGRESS"));
        mockMvc.perform(post("/api/v1/content-reports/{id}/handling-actions", reportId)
                        .header("Authorization", bearer(operator)).header("Idempotency-Key", "p15-record-report")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":2,\"action\":\"RECORD_RESULT\",\"note\":\"已提醒\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("HANDLED"));
        mockMvc.perform(get("/api/v1/board-topics/{id}", topicId).header("Authorization", bearer(student)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.title").value("举报目标"));
    }

    @Test
    void rejectedInputsDoNotCreateTicketsAndConflictsStayOnTheOriginal() throws Exception {
        String student = login("student_demo_01", "demo-password-01");
        String stranger = login("student_demo_02", "demo-password-02");
        grant("user_demo_teacher_02", "SUPPORT_HANDLE");
        jdbc.update("UPDATE support_route SET receiver_user_id='user_demo_teacher_02' WHERE id='route_support'");
        mockMvc.perform(post("/api/v1/me/support-tickets")
                        .header("Authorization", bearer(student)).header("Idempotency-Key", "p58-old")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"routeId\":\"route_support\",\"clientRequestId\":\"old\"," +
                                "\"subject\":\"无法提交\",\"body\":\"旧字段\"}"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(post("/api/v1/me/support-tickets")
                        .header("Authorization", bearer(student)).header("Idempotency-Key", "p58-extra")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(withField(ticketBody("extra", "标题", "正文"), "assigneeId", "user_demo_teacher_02")))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(post("/api/v1/support-tickets/missing/resolution-decisions")
                        .header("Authorization", bearer(student)).header("Idempotency-Key", "p58-type")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":\"1\",\"resolved\":\"true\",\"note\":\"x\"}"))
                .andExpect(status().isUnprocessableEntity());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM support_ticket", Integer.class)).isZero();
        JsonNode created = data(mockMvc.perform(post("/api/v1/me/support-tickets")
                        .header("Authorization", bearer(student)).header("Idempotency-Key", "p58-create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ticketBody("same", "原标题", "原正文")))
                .andExpect(status().isOk()).andReturn());
        mockMvc.perform(post("/api/v1/me/support-tickets")
                        .header("Authorization", bearer(student)).header("Idempotency-Key", "p58-replay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ticketBody("same", "原标题", "原正文")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(created.path("id").asText()));
        mockMvc.perform(post("/api/v1/me/support-tickets")
                        .header("Authorization", bearer(student)).header("Idempotency-Key", "p58-conflict")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ticketBody("same", "另一标题", "原正文")))
                .andExpect(status().isConflict());
        mockMvc.perform(get("/api/v1/support-tickets/{id}", created.path("id").asText())
                        .header("Authorization", bearer(stranger)))
                .andExpect(status().isForbidden());
        int revision = jdbc.queryForObject("SELECT revision FROM support_ticket WHERE id=?", Integer.class,
                created.path("id").asText());
        mockMvc.perform(post("/api/v1/support-tickets/{id}/replies", created.path("id").asText())
                        .header("Authorization", bearer(student)).header("Idempotency-Key", "p58-stale")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":" + (revision + 9) + ",\"content\":\"过期\"}"))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT revision FROM support_ticket WHERE id=?", Integer.class,
                created.path("id").asText())).isEqualTo(revision);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM support_reply", Integer.class)).isZero();
    }

    @Test
    void revokedHandlerLosesTicketFileAndTransferReplay() throws Exception {
        String student = login("student_demo_01", "demo-password-01");
        String helper = login("teacher_demo_02", "demo-password-05");
        String other = login("teacher_demo_01", "demo-password-03");
        grant("user_demo_teacher_02", "SUPPORT_HANDLE");
        grant("user_demo_teacher_01", "SUPPORT_HANDLE");
        jdbc.update("UPDATE support_route SET receiver_user_id='user_demo_teacher_02' WHERE id='route_support'");
        jdbc.update("""
                INSERT INTO support_route(id, type, name, available, category, receiver_user_id)
                VALUES ('route_support_b','SUPPORT','另一受理',true,'OPERATION','user_demo_teacher_01')
                """);
        JsonNode ticket = data(mockMvc.perform(post("/api/v1/me/support-tickets")
                        .header("Authorization", bearer(student)).header("Idempotency-Key", "p58-revoke-create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ticketBody("revoke-1", "撤权后不可读", "仍然失败")))
                .andExpect(status().isOk()).andReturn());
        String ticketId = ticket.path("id").asText();
        byte[] png = Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");
        JsonNode intent = data(mockMvc.perform(post("/api/v1/files/upload-intents")
                        .header("Authorization", bearer(helper)).header("Idempotency-Key", "p58-revoke-intent")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "purpose", "SUPPORT_ATTACHMENT", "fileName", "shot.png",
                                "contentType", "image/png", "sizeBytes", png.length, "ticketId", ticketId))))
                .andExpect(status().isOk()).andReturn());
        String fileId = intent.path("file").path("id").asText();
        String uploadUrl = intent.path("upload").path("url").asText();
        mockMvc.perform(put(uploadUrl.substring(uploadUrl.indexOf("/internal-file-transfer/")))
                        .content(png).contentType("image/png"))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/v1/files/{id}/completions", fileId)
                        .header("Authorization", bearer(helper)).header("Idempotency-Key", "p58-revoke-complete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"uploadToken\":\"" + intent.path("uploadToken").asText() + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("READY"));
        mockMvc.perform(get("/api/v1/files/{id}", fileId).header("Authorization", bearer(helper)))
                .andExpect(status().isOk());
        JsonNode download = data(mockMvc.perform(post("/api/v1/files/{id}/download-authorizations", fileId)
                        .header("Authorization", bearer(helper)).header("Idempotency-Key", "p58-revoke-download")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk()).andReturn());
        String downloadPath = download.path("url").asText();
        downloadPath = downloadPath.substring(downloadPath.indexOf("/internal-file-transfer/"));
        mockMvc.perform(get(downloadPath)).andExpect(status().isOk());

        jdbc.update("UPDATE operator_grant SET active=false WHERE user_id='user_demo_teacher_02'");
        mockMvc.perform(get("/api/v1/support-tickets/{id}", ticketId).header("Authorization", bearer(helper)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/support-workspace/tickets").header("Authorization", bearer(helper)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/files/{id}", fileId).header("Authorization", bearer(helper)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/files/{id}/download-authorizations", fileId)
                        .header("Authorization", bearer(helper)).header("Idempotency-Key", "p58-revoke-download")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(downloadPath)).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/support-tickets/{id}/replies", ticketId)
                        .header("Authorization", bearer(helper)).header("Idempotency-Key", "p58-revoke-reply")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"content\":\"撤权后不能回复\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/support-tickets/{id}/acceptances", ticketId)
                        .header("Authorization", bearer(helper)).header("Idempotency-Key", "p58-revoke-accept")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedRevision\":1}"))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM support_reply WHERE ticket_id=?", Integer.class, ticketId))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM support_history WHERE ticket_id=? AND action<>'CREATE'",
                Integer.class, ticketId)).isZero();
        mockMvc.perform(get("/api/v1/support-tickets/{id}", ticketId).header("Authorization", bearer(student)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.requesterId").value("user_demo_student_01"));
        mockMvc.perform(get("/api/v1/files/{id}", fileId).header("Authorization", bearer(student)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/support-tickets/{id}", ticketId).header("Authorization", bearer(other)))
                .andExpect(status().isForbidden());

        String transferBody = "{\"expectedRevision\":1,\"targetRouteId\":\"route_support_b\",\"reason\":\"交给另一受理人\"}";
        jdbc.update("UPDATE operator_grant SET active=true WHERE user_id='user_demo_teacher_02'");
        mockMvc.perform(post("/api/v1/support-tickets/{id}/transfers", ticketId)
                        .header("Authorization", bearer(helper)).header("Idempotency-Key", "p58-transfer-replay")
                        .contentType(MediaType.APPLICATION_JSON).content(transferBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.assigneeId").value("user_demo_teacher_01"))
                .andExpect(jsonPath("$.data.transferTargetRouteId").value("route_support_b"));
        int revision = jdbc.queryForObject("SELECT revision FROM support_ticket WHERE id=?", Integer.class, ticketId);
        int history = jdbc.queryForObject("SELECT count(*) FROM support_history WHERE ticket_id=?", Integer.class, ticketId);
        mockMvc.perform(get("/api/v1/support-tickets/{id}", ticketId).header("Authorization", bearer(helper)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/support-tickets/{id}/transfers", ticketId)
                        .header("Authorization", bearer(helper)).header("Idempotency-Key", "p58-transfer-replay")
                        .contentType(MediaType.APPLICATION_JSON).content(transferBody))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT assignee_id FROM support_ticket WHERE id=?", String.class, ticketId))
                .isEqualTo("user_demo_teacher_01");
        assertThat(jdbc.queryForObject("SELECT revision FROM support_ticket WHERE id=?", Integer.class, ticketId))
                .isEqualTo(revision);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM support_history WHERE ticket_id=?", Integer.class, ticketId))
                .isEqualTo(history);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM operation_receipt WHERE key='p58-transfer-replay'",
                Integer.class)).isEqualTo(1);
        mockMvc.perform(get("/api/v1/support-tickets/{id}", ticketId).header("Authorization", bearer(other)))
                .andExpect(status().isOk());
    }

    @Test
    void courseSupportRequiresCurrentScopeAndRecusal() throws Exception {
        String student = login("student_demo_01", "demo-password-01");
        String teacher = login("teacher_demo_01", "demo-password-03");
        String assistant = login("assistant_demo_01", "demo-password-04");
        Project project = project(student);
        String squareId = jdbc.queryForObject("SELECT square_id FROM project WHERE id=?", String.class, project.id());
        grant("user_demo_teacher_01", "SUPPORT_HANDLE");
        jdbc.update("""
                INSERT INTO support_route(id, type, name, available, category, square_id, receiver_user_id)
                VALUES ('route_course_live','COURSE','本课求助',true,'COURSE_REQUIREMENT',?,'user_demo_teacher_01')
                """, squareId);
        String body = "{\"clientRequestId\":\"course-1\",\"routeId\":\"route_course_live\","
                + "\"category\":\"COURSE_REQUIREMENT\",\"title\":\"课程问题\",\"content\":\"作业要求不清\","
                + "\"squareId\":\"" + squareId + "\",\"context\":{\"pageType\":\"APPLICATION\",\"resourceId\":null,"
                + "\"errorCode\":\"NETWORK_ERROR\",\"appVersion\":\"0.59.0\"}}";
        JsonNode ticket = data(mockMvc.perform(post("/api/v1/me/support-tickets")
                        .header("Authorization", bearer(student)).header("Idempotency-Key", "p58-course-create")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn());
        String ticketId = ticket.path("id").asText();
        mockMvc.perform(get("/api/v1/support-tickets/{id}", ticketId).header("Authorization", bearer(teacher)))
                .andExpect(status().isOk());
        jdbc.update("UPDATE teacher_assignment SET active=false WHERE square_id=? AND user_id='user_demo_teacher_01'",
                squareId);
        mockMvc.perform(get("/api/v1/support-tickets/{id}", ticketId).header("Authorization", bearer(teacher)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/support-tickets/{id}", ticketId).header("Authorization", bearer(student)))
                .andExpect(status().isOk());

        jdbc.update("""
                INSERT INTO operator_grant(id, user_id, scope_type, scope_id, capability, active, created_at)
                VALUES (?, 'user_demo_assistant_01', 'SQUARE', 'square_other', 'SUPPORT_HANDLE', true, ?)
                """, UUID.randomUUID().toString(), java.time.OffsetDateTime.ofInstant(BASE, ZoneOffset.UTC));
        jdbc.update("UPDATE support_ticket SET assignee_id='user_demo_assistant_01' WHERE id=?", ticketId);
        mockMvc.perform(get("/api/v1/support-tickets/{id}", ticketId).header("Authorization", bearer(assistant)))
                .andExpect(status().isForbidden());
        jdbc.update("UPDATE operator_grant SET scope_id=? WHERE user_id='user_demo_assistant_01'", squareId);
        jdbc.update("""
                INSERT INTO class_grant(id, square_id, user_id, capabilities_json, status, expires_at, granted_by, revision)
                VALUES (?, ?, 'user_demo_assistant_01', '["ANNOUNCEMENT_WRITE"]', 'ACTIVE', ?, 'user_demo_teacher_01', 1)
                """, UUID.randomUUID().toString(), squareId,
                java.time.OffsetDateTime.ofInstant(BASE.minusSeconds(60), ZoneOffset.UTC));
        mockMvc.perform(get("/api/v1/support-tickets/{id}", ticketId).header("Authorization", bearer(assistant)))
                .andExpect(status().isForbidden());
        jdbc.update("UPDATE class_grant SET expires_at=? WHERE user_id='user_demo_assistant_01'",
                java.time.OffsetDateTime.ofInstant(BASE.plusSeconds(86400), ZoneOffset.UTC));
        jdbc.update("""
                INSERT INTO membership(id, project_id, user_id, project_role, status, color, joined_at, revision)
                VALUES (?, ?, 'user_demo_assistant_01', 'MEMBER', 'ACTIVE', '#336699', ?, 1)
                """, UUID.randomUUID().toString(), project.id(),
                java.time.OffsetDateTime.ofInstant(BASE, ZoneOffset.UTC));
        mockMvc.perform(get("/api/v1/support-tickets/{id}", ticketId).header("Authorization", bearer(assistant)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("RECUSAL_REQUIRED"));
        jdbc.update("UPDATE membership SET status='LEFT' WHERE user_id='user_demo_assistant_01'");
        mockMvc.perform(get("/api/v1/support-tickets/{id}", ticketId).header("Authorization", bearer(assistant)))
                .andExpect(status().isOk());
    }

    @Test
    void reopenReadsPostgresTimestampAndKeepsTheExactDeadline() throws Exception {
        String student = login("student_demo_01", "demo-password-01");
        grant("user_demo_teacher_02", "SUPPORT_HANDLE");
        jdbc.update("UPDATE support_route SET receiver_user_id='user_demo_teacher_02' WHERE id='route_support'");

        String openId = createTicket(student, "reopen-open", "p58-reopen-open");
        resolve(student, openId, 1, "p58-reopen-open-close");
        mockMvc.perform(post("/api/v1/support-tickets/{id}/resolution-decisions", openId)
                        .header("Authorization", bearer(student)).header("Idempotency-Key", "p58-reopen-open-again")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":2,\"resolved\":false,\"note\":\"刚关闭仍可重开\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUBMITTED"));

        String edgeId = createTicket(student, "reopen-edge", "p58-reopen-edge");
        resolve(student, edgeId, 1, "p58-reopen-edge-close");
        Instant until = reopenUntil(edgeId);
        clock.setInstant(until.plusSeconds(1));
        String lateStudent = login("student_demo_01", "demo-password-01");
        mockMvc.perform(post("/api/v1/support-tickets/{id}/resolution-decisions", edgeId)
                        .header("Authorization", bearer(lateStudent)).header("Idempotency-Key", "p58-reopen-edge-late")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":2,\"resolved\":false,\"note\":\"过期\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TICKET_REOPEN_WINDOW_CLOSED"));
        assertThat(jdbc.queryForObject("SELECT status FROM support_ticket WHERE id=?", String.class, edgeId))
                .isEqualTo("CLOSED");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM support_history WHERE ticket_id=? AND action='REOPEN'", Integer.class, edgeId))
                .isZero();
        clock.setInstant(until);
        String edgeStudent = login("student_demo_01", "demo-password-01");
        mockMvc.perform(post("/api/v1/support-tickets/{id}/resolution-decisions", edgeId)
                        .header("Authorization", bearer(edgeStudent)).header("Idempotency-Key", "p58-reopen-edge-exact")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":2,\"resolved\":false,\"note\":\"截止瞬间仍可重开\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUBMITTED"));

        clock.setInstant(BASE);
        String missingStudent = login("student_demo_01", "demo-password-01");
        String missingId = createTicket(missingStudent, "reopen-missing", "p58-reopen-missing");
        resolve(missingStudent, missingId, 1, "p58-reopen-missing-close");
        jdbc.update("UPDATE support_ticket SET reopen_until=NULL WHERE id=?", missingId);
        mockMvc.perform(post("/api/v1/support-tickets/{id}/resolution-decisions", missingId)
                        .header("Authorization", bearer(missingStudent)).header("Idempotency-Key", "p58-reopen-missing-try")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":2,\"resolved\":false,\"note\":\"没有期限\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TICKET_REOPEN_WINDOW_CLOSED"));
        assertThat(jdbc.queryForObject("SELECT status FROM support_ticket WHERE id=?", String.class, missingId))
                .isEqualTo("CLOSED");
    }

    private String createTicket(String student, String clientId, String key) throws Exception {
        return data(mockMvc.perform(post("/api/v1/me/support-tickets")
                        .header("Authorization", bearer(student)).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ticketBody(clientId, "重开时限", "请确认时间")))
                .andExpect(status().isOk()).andReturn()).path("id").asText();
    }

    private void resolve(String student, String ticketId, int revision, String key) throws Exception {
        mockMvc.perform(post("/api/v1/support-tickets/{id}/resolution-decisions", ticketId)
                        .header("Authorization", bearer(student)).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":" + revision + ",\"resolved\":true,\"note\":\"先关闭\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CLOSED"));
    }

    private Instant reopenUntil(String ticketId) {
        Object raw = jdbc.queryForObject("SELECT reopen_until FROM support_ticket WHERE id=?", Object.class, ticketId);
        if (raw instanceof java.time.OffsetDateTime odt) {
            return odt.toInstant();
        }
        if (raw instanceof java.sql.Timestamp timestamp) {
            return timestamp.toInstant();
        }
        if (raw instanceof Instant instant) {
            return instant;
        }
        throw new IllegalStateException("无法读取 reopen_until: " + (raw == null ? "null" : raw.getClass().getName()));
    }

    private String withField(String json, String field, String value) {
        return json.substring(0, json.length() - 1) + ",\"" + field + "\":\"" + value + "\"}";
    }

    private String ticketBody(String clientId, String title, String content) {
        return "{\"clientRequestId\":\"" + clientId + "\",\"routeId\":\"route_support\",\"category\":\"OPERATION\","
                + "\"title\":\"" + title + "\",\"content\":\"" + content + "\",\"squareId\":null,"
                + "\"context\":{\"pageType\":\"APPLICATION\",\"resourceId\":null,\"errorCode\":\"NETWORK_ERROR\","
                + "\"appVersion\":\"0.59.0\"}}";
    }

    private String reportBody(String clientId, String topicId, String excerpt, String reason) {
        return "{\"clientRequestId\":\"" + clientId + "\",\"source\":{\"type\":\"BOARD_TOPIC\",\"id\":\""
                + topicId + "\",\"version\":1},\"selectedExcerpt\":\"" + excerpt + "\",\"reason\":\"" + reason + "\"}";
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
