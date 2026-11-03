package cn.gulupai.server.community;

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
import java.util.List;
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
@Import(CommunityP13IT.TestClockConfig.class)
class CommunityP13IT {

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
        TestSchemaCleaner.deleteAll(jdbc, List.of("chat_sync_cursor", "chat_delivery_range",
                "notification_outbox", "chat_change", "chat_message",
                "chat_read_state", "chat_counter", "topic_follow", "content_revision",
                "board_reply", "announcement_reminder", "announcement_preview", "announcement_receipt",
                "announcement_recipient", "announcement_version", "help_article", "help_feedback", "support_route", "support_ticket",
                "support_reply", "support_history", "operator_grant", "content_report",
                "report_handling", "announcement",
                "notification_preference", "notification", "board_topic", "dice_round", "dice_roll", "dice_participant",
                "dice_session", "report_snapshot", "delivery_release", "delivery_release_preview",
                "delivery_release_draft", "overall_reflection", "project_reflection",
                "file_reference", "file_authorization", "cleanup_plan", "stored_file",
                "personnel_handoff", "material_request", "personnel_record", "personnel_case_grant", "personnel_case",
                "removal_ballot", "handover_ack", "handover_item", "member_change_event",
                "member_change", "removal_vote", "teacher_summary_consent", "teacher_summary",
                "grade_appeal", "grade_snapshot", "grade_publication", "settlement", "ledger_correction",
                "grade_draft", "grade_rule", "contribution_confirmation", "unit_acceptance", "acceptance",
                "submission", "delivery_draft", "proposal_response", "unit_change_proposal",
                "reviewer_invitation", "reviewer_arrangement", "agreement_ack", "unit_share",
                "task_dependency", "criterion", "delivery_unit", "agreement", "task", "user_project_color",
                "role_ack", "role_negotiation", "ai_reference", "peer_review_version", "peer_review",
                "work_version", "work", "project_code", "seat_reservation", "team_invitation",
                "team_application", "assignment_team_slot", "membership", "role_slot", "project_stage",
                "project", "teacher_transfer", "teacher_claim", "teacher_assignment", "class_grant",
                "course_code", "course_join_request", "cursor_snapshot", "assignment", "course_membership",
                "merge_ballot", "square_merge", "preview_token", "square", "verified_enrollment",
                "verified_course", "course_name", "period", "term", "operation_receipt", "access_token",
                "auth_session", "audit_event", "rate_window", "import_batch", "user_role", "user_profile",
                "app_user", "school"));
    }

    @Test
    void boardTopicReplyRevisionWithdrawAndPermissions() throws Exception {
        String leader = login("student_demo_01", "demo-password-01");
        String member = login("student_demo_02", "demo-password-02");
        String projectId = project(leader, member, "p13-board");
        JsonNode topic = data(mockMvc.perform(post("/api/v1/projects/{id}/board-topics", projectId)
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "p13-t1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"接口核对\",\"content\":\"请核对字段\",\"tag\":\"QUESTION\",\"refs\":[],\"mentions\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("OPEN"))
                .andExpect(jsonPath("$.data.following").value(false))
                .andReturn());
        String topicId = topic.path("id").asText();
        mockMvc.perform(get("/api/v1/projects/{id}/board-topics", projectId)
                        .header("Authorization", bearer(member)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1));
        JsonNode reply = data(mockMvc.perform(post("/api/v1/board-topics/{id}/replies", topicId)
                        .header("Authorization", bearer(member)).header("Idempotency-Key", "p13-r1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedTopicRevision\":1,\"content\":\"建议先核对请求\",\"replyToId\":null,\"mentions\":[]}"))
                .andExpect(status().isOk()).andReturn());
        String replyId = reply.path("id").asText();
        mockMvc.perform(post("/api/v1/board-topics/{id}/replies", topicId)
                        .header("Authorization", bearer(member)).header("Idempotency-Key", "p13-r-bad")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedTopicRevision\":1,\"content\":\"错误父回复\",\"replyToId\":\"not-exist\"}"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(get("/api/v1/board-topics/{id}/replies", topicId)
                        .header("Authorization", bearer(leader)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1));

        mockMvc.perform(post("/api/v1/board-topics/{id}/revisions", topicId)
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "p13-t-rev")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"title\":\"接口核对（更新）\",\"content\":\"更新内容\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.revision").value(2));
        mockMvc.perform(get("/api/v1/board-topics/{id}/revisions", topicId)
                        .header("Authorization", bearer(leader)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(2));
        mockMvc.perform(post("/api/v1/board-replies/{id}/revisions", replyId)
                        .header("Authorization", bearer(member)).header("Idempotency-Key", "p13-r-rev")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"content\":\"已更新回复\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.revision").value(2));
        mockMvc.perform(post("/api/v1/board-topics/{id}/status-actions", topicId)
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "p13-resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("expectedRevision", 2,
                                "action", "RESOLVE", "helpfulReplyId", replyId, "note", "已核对"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("RESOLVED"));
        mockMvc.perform(put("/api/v1/board-topics/{id}/pin", topicId)
                        .header("Authorization", bearer(member)).header("Idempotency-Key", "p13-pin-bad")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedRevision\":3,\"pinned\":true}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/v1/board-topics/{id}/me/following", topicId)
                        .header("Authorization", bearer(member)).header("Idempotency-Key", "p13-follow")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedRevision\":3,\"following\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.following").value(true));
        mockMvc.perform(post("/api/v1/board-replies/{id}/withdrawals", replyId)
                        .header("Authorization", bearer(member)).header("Idempotency-Key", "p13-r-withdraw")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedRevision\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.withdrawn").value(true))
                .andExpect(jsonPath("$.data.content").value(nullValue()));
        mockMvc.perform(get("/api/v1/board-replies/{id}/revisions", replyId)
                        .header("Authorization", bearer(member)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].content").value(nullValue()));
    }

    @Test
    void chatSequenceResolutionWithdrawChangesAndCursor() throws Exception {
        String leader = login("student_demo_01", "demo-password-01");
        String member = login("student_demo_02", "demo-password-02");
        String projectId = project(leader, member, "p13-chat");
        JsonNode first = data(mockMvc.perform(post("/api/v1/projects/{id}/chat-messages", projectId)
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "p13-c1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientMessageId\":\"m1\",\"content\":\"第一条\",\"refs\":[],\"mentions\":[]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.sequence").value(1)).andReturn());
        JsonNode second = data(mockMvc.perform(post("/api/v1/projects/{id}/chat-messages", projectId)
                        .header("Authorization", bearer(member)).header("Idempotency-Key", "p13-c2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientMessageId\":\"m2\",\"content\":\"第二条\",\"refs\":[],\"mentions\":[]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.sequence").value(2)).andReturn());
        mockMvc.perform(post("/api/v1/projects/{id}/chat-messages", projectId)
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "p13-c1-replay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientMessageId\":\"m1\",\"content\":\"第一条\",\"refs\":[],\"mentions\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(first.path("id").asText()));
        mockMvc.perform(post("/api/v1/projects/{id}/chat-messages", projectId)
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "p13-c1-diff")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientMessageId\":\"m1\",\"content\":\"不同正文\",\"refs\":[],\"mentions\":[]}"))
                .andExpect(status().isConflict());
        JsonNode baseline = data(mockMvc.perform(get("/api/v1/projects/{id}/chat-messages", projectId)
                        .param("afterSequence", "0").param("limit", "50")
                        .header("Authorization", bearer(member)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.latestSequence").value(2))
                .andExpect(jsonPath("$.data.nextChangeCursor").isString())
                .andReturn());
        String syncCursor = baseline.path("nextChangeCursor").asText();
        mockMvc.perform(get("/api/v1/projects/{id}/chat-message-resolutions", projectId)
                        .param("clientMessageId", "m1")
                        .header("Authorization", bearer(leader)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.found").value(true))
                .andExpect(jsonPath("$.data.message.content").value("第一条"));
        mockMvc.perform(post("/api/v1/chat-messages/{id}/withdrawals", second.path("id").asText())
                        .header("Authorization", bearer(member)).header("Idempotency-Key", "p13-withdraw")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedRevision\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.withdrawn").value(true))
                .andExpect(jsonPath("$.data.content").value(nullValue()));
        mockMvc.perform(get("/api/v1/projects/{id}/chat-messages", projectId)
                        .param("afterChangeCursor", syncCursor)
                        .header("Authorization", bearer(member)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.changes.length()").value(1))
                .andExpect(jsonPath("$.data.changes[0].withdrawn").value(true));
        mockMvc.perform(put("/api/v1/projects/{id}/me/read-cursor", projectId)
                        .header("Authorization", bearer(member)).header("Idempotency-Key", "p13-read-2")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"sequence\":2}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.lastSequence").value(2));
        mockMvc.perform(put("/api/v1/projects/{id}/me/read-cursor", projectId)
                        .header("Authorization", bearer(member)).header("Idempotency-Key", "p13-read-beyond")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"sequence\":9}"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(get("/api/v1/projects/{id}/me/read-cursor", projectId)
                        .header("Authorization", bearer(member)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.lastSequence").value(2));
    }

    @Test
    void chatPagesDoNotOverlapAndLeavingStopsBodyAccess() throws Exception {
        String leader = login("student_demo_01", "demo-password-01");
        String member = login("student_demo_02", "demo-password-02");
        String projectId = project(leader, member, "p13-pages");
        for (int i = 1; i <= 25; i++) {
            mockMvc.perform(post("/api/v1/projects/{id}/chat-messages", projectId)
                            .header("Authorization", bearer(leader))
                            .header("Idempotency-Key", "p13-page-" + i)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"clientMessageId\":\"page-" + i + "\",\"content\":\"分页消息 "
                                    + i + "\",\"refs\":[],\"mentions\":[]}"))
                    .andExpect(status().isOk());
        }
        JsonNode first = data(mockMvc.perform(get("/api/v1/projects/{id}/chat-messages", projectId)
                        .param("limit", "10").param("afterSequence", "0")
                        .header("Authorization", bearer(member)))
                .andExpect(status().isOk()).andReturn());
        assertThat(first.path("items").size()).isEqualTo(10);
        long next = first.path("nextAfterSequence").asLong();
        JsonNode second = data(mockMvc.perform(get("/api/v1/projects/{id}/chat-messages", projectId)
                        .param("limit", "10").param("afterSequence", String.valueOf(next))
                        .header("Authorization", bearer(member)))
                .andExpect(status().isOk()).andReturn());
        assertThat(second.path("items").get(0).path("sequence").asLong()).isGreaterThan(next);
        assertThat(second.path("items").get(0).path("id").asText())
                .isNotEqualTo(first.path("items").get(0).path("id").asText());

        jdbc.update("UPDATE membership SET status='LEFT' WHERE project_id=? AND user_id=?",
                projectId, "user_demo_student_02");
        mockMvc.perform(get("/api/v1/projects/{id}/chat-messages", projectId)
                        .header("Authorization", bearer(member)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/projects/{id}/board-topics", projectId)
                        .header("Authorization", bearer(member)))
                .andExpect(status().isForbidden());
    }

    @Test
    void archivedProjectReadOnlyAndNonMemberDenied() throws Exception {
        String leader = login("student_demo_01", "demo-password-01");
        String member = login("student_demo_02", "demo-password-02");
        String assistant = login("assistant_demo_01", "demo-password-04");
        String projectId = project(leader, member, "p13-archive");
        mockMvc.perform(post("/api/v1/projects/{id}/board-topics", projectId)
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "p13-arch-topic")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"归档主题\",\"content\":\"只读前\",\"tag\":\"MATERIAL\",\"refs\":[],\"mentions\":[]}"))
                .andExpect(status().isOk());
        jdbc.update("UPDATE project SET status='ARCHIVED', archived_at=? WHERE id=?",
                java.time.OffsetDateTime.ofInstant(BASE, ZoneOffset.UTC), projectId);
        mockMvc.perform(get("/api/v1/projects/{id}/board-topics", projectId)
                        .header("Authorization", bearer(member)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/projects/{id}/board-topics", projectId)
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "p13-arch-write")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"不允许\",\"content\":\"写入\",\"tag\":\"MATERIAL\",\"refs\":[],\"mentions\":[]}"))
                .andExpect(status().isConflict());
        mockMvc.perform(get("/api/v1/projects/{id}/board-topics", projectId)
                        .header("Authorization", bearer(assistant)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/projects/{id}/chat-messages", projectId)
                        .header("Authorization", bearer(member)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/projects/{id}/chat-messages", projectId)
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "p13-arch-chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientMessageId\":\"m1\",\"content\":\"归档后写入\",\"refs\":[],\"mentions\":[]}"))
                .andExpect(status().isConflict());
    }

    private String project(String leader, String member, String key) throws Exception {
        JsonNode match = data(mockMvc.perform(post("/api/v1/squares/matches")
                        .header("Authorization", bearer(leader)).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("schoolId", SCHOOL, "termId", TERM,
                                "teacherName", "示例教师", "slots", slots(),
                                "courseNameId", "course_name_demo_mobile"))))
                .andExpect(status().isOk()).andReturn());
        JsonNode square = data(mockMvc.perform(post("/api/v1/squares")
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", key + "-sq")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("schoolId", SCHOOL, "termId", TERM,
                                "teacherName", "示例教师", "slots", slots(),
                                "courseNameId", "course_name_demo_mobile",
                                "candidateCheckToken", match.path("candidateCheckToken").asText()))))
                .andExpect(status().isOk()).andReturn());
        JsonNode assignment = data(mockMvc.perform(post("/api/v1/squares/{id}/assignments",
                                square.path("id").asText())
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", key + "-asg")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("name", "社区作业", "requirements", "演示",
                                "hardDueAt", "2026-12-15T15:59:59Z", "minMembers", 2, "maxMembers", 6,
                                "sourceReferences", List.of()))))
                .andExpect(status().isOk()).andReturn());
        String teacher = login("teacher_demo_01", "demo-password-03");
        mockMvc.perform(post("/api/v1/assignments/{id}/confirmations", assignment.path("id").asText())
                        .header("Authorization", bearer(teacher)).header("Idempotency-Key", key + "-conf")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"decision\":\"CONFIRM_NEW\",\"reason\":\"ok\"}"))
                .andExpect(status().isOk());
        JsonNode project = data(mockMvc.perform(post("/api/v1/projects")
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", key + "-project")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("squareId", square.path("id").asText(),
                                "assignmentId", assignment.path("id").asText(), "mode", "QUICK",
                                "name", "社区组", "capacity", 3, "discussionTime", "周三",
                                "roles", List.of()))))
                .andExpect(status().isOk()).andReturn());
        String projectId = project.path("id").asText();
        JsonNode current = data(mockMvc.perform(get("/api/v1/projects/{id}", projectId)
                        .header("Authorization", bearer(leader))).andExpect(status().isOk()).andReturn());
        JsonNode invite = data(mockMvc.perform(post("/api/v1/projects/{id}/invitations", projectId)
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", key + "-inv")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("expectedProjectRevision",
                                current.path("revision").asInt(), "recipientId", "user_demo_student_02",
                                "message", "加入社区"))))
                .andExpect(status().isOk()).andReturn());
        mockMvc.perform(post("/api/v1/invitations/{id}/responses", invite.path("invitation").path("id").asText())
                        .header("Authorization", bearer(member)).header("Idempotency-Key", key + "-acc")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"decision\":\"ACCEPT\"}"))
                .andExpect(status().isOk());
        return projectId;
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
