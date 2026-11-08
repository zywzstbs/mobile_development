package cn.gulupai.server.notice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.gulupai.server.identity.cli.AcademicImportService;
import cn.gulupai.server.identity.cli.AdminImportService;
import cn.gulupai.server.it.TestSchemaCleaner;
import cn.gulupai.server.notice.application.NoticeOutboxWorker;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * R3 持久化与事务组回归：用真实 PostgreSQL 验证聊天提到通知的源事件版本、
 * 坏载荷零写入、重复投递去重，以及第二个接收人失败时整批回滚。
 *
 * <p>test profile 关闭后台轮询，这里显式调用 Spring 注入的 worker Bean，
 * 因此每次 {@code drain()} 都是真实事务边界。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class NoticeOutboxSourceVersionIT {

    private static final String SCHOOL = "school_demo_01";
    private static final String TERM = "term_demo_2026a";
    private static final String LEADER = "user_demo_student_01";
    private static final String MEMBER = "user_demo_student_02";
    private static final String TEACHER = "user_demo_teacher_01";
    private static final String FAILING_USER = LEADER;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private NoticeOutboxWorker worker;
    @Autowired private AdminImportService users;
    @Autowired private AcademicImportService academics;

    @BeforeEach
    void setUp() {
        clearAll();
        dropFailureInjection();
        users.importUsers(Path.of("admin/fixtures/p01-demo-users.json"), false);
        academics.importAcademic(Path.of("admin/fixtures/p02-demo-academic.json"), false);
    }

    @AfterEach
    void tearDown() {
        dropFailureInjection();
        clearAll();
    }

    /**
     * 真实聊天流程：生产者写入 outbox 的 sourceVersion 等于消息当时的 revision，
     * 投递后的通知保存同一个冻结值，重复 drain 不新增。
     */
    @Test
    void realChatMentionKeepsTheProducerFrozenSourceVersion() throws Exception {
        String leader = login("student_demo_01", "demo-password-01");
        String member = login("student_demo_02", "demo-password-02");
        String projectId = project(leader, member, "p71-outbox");
        String messageId = sendMention(leader, projectId, "p71-outbox-send", "outbox 回归", MEMBER);

        int messageRevision = jdbc.queryForObject("SELECT revision FROM chat_message WHERE id = ?",
                Integer.class, messageId);
        JsonNode payload = mapper.readTree(jdbc.queryForObject("""
                SELECT payload_json::text FROM notification_outbox
                WHERE project_id = ? AND event_type = 'CHAT_MESSAGE_SENT'
                """, String.class, projectId));
        assertThat(payload.path("messageId").asText()).isEqualTo(messageId);
        assertThat(payload.path("sourceVersion").asInt()).isEqualTo(messageRevision);

        assertThat(worker.drain()).isEqualTo(1);

        Integer sourceVersion = jdbc.queryForObject("""
                SELECT source_version FROM notification
                WHERE user_id = ? AND source_type = 'CHAT_MESSAGE' AND source_id = ?
                """, Integer.class, MEMBER, messageId);
        assertThat(sourceVersion).isEqualTo(messageRevision);
        assertThat(processedForProject(projectId)).isTrue();

        // 同一事件再次投递不新增通知，也不会改写已写入的源版本。
        assertThat(worker.drain()).isZero();
        assertThat(notificationCount(MEMBER)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT source_version FROM notification
                WHERE user_id = ? AND source_type = 'CHAT_MESSAGE' AND source_id = ?
                """, Integer.class, MEMBER, messageId)).isEqualTo(messageRevision);

        // 读取侧投影的是 JSON 整数正值，与真实源事件一致。
        JsonNode item = onlyNotification(member);
        assertThat(item.get("sourceType").asText()).isEqualTo("CHAT_MESSAGE");
        assertThat(item.get("sourceVersion").isIntegralNumber()).isTrue();
        assertThat(item.get("sourceVersion").asInt()).isEqualTo(messageRevision);
    }

    /** 同一个合法事件的两个真实接收人得到相同源版本，事件只完成一次。 */
    @Test
    void oneEventWithTwoRecipientsSharesTheSameSourceVersion() {
        insertOutbox("outbox_p71_two", """
                {"messageId":"message_p71_two","sequence":1,"sourceVersion":7,
                 "mentions":["%s","%s"]}
                """.formatted(MEMBER, TEACHER), 0);

        assertThat(worker.drain()).isEqualTo(1);

        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT user_id, event_version, source_version FROM notification
                WHERE source_type = 'CHAT_MESSAGE' AND source_id = 'message_p71_two'
                ORDER BY user_id
                """);
        assertThat(rows).hasSize(2);
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.get("event_version")).isEqualTo(1);
            assertThat(row.get("source_version")).isEqualTo(7);
        });
        assertThat(processed("outbox_p71_two")).isTrue();
    }

    /** 同批坏事件零新增且保持 pending，合法事件照常完成。 */
    @Test
    void badVersionInTheSameBatchStaysPendingWhileTheLegalEventCompletes() {
        insertOutbox("outbox_p71_bad", """
                {"messageId":"message_p71_bad","sequence":1,"sourceVersion":1.75,
                 "mentions":["%s"]}
                """.formatted(MEMBER), -1);
        insertOutbox("outbox_p71_legal", """
                {"messageId":"message_p71_legal","sequence":2,"sourceVersion":3,
                 "mentions":["%s"]}
                """.formatted(TEACHER), 0);

        assertThat(worker.drain()).isEqualTo(1);

        assertThat(notificationCountForSource("message_p71_bad")).isZero();
        assertThat(notificationCountForSource("message_p71_legal")).isEqualTo(1);
        assertThat(processed("outbox_p71_bad")).isFalse();
        assertThat(processed("outbox_p71_legal")).isTrue();
    }

    /**
     * 事件版本在消息 revision 变化后仍然冻结：撤回把消息 revision 推到 2，
     * 通知仍保存事件当时的 1，不跟随消息当前版本。
     */
    @Test
    void frozenEventVersionSurvivesLaterMessageRevision() throws Exception {
        String leader = login("student_demo_01", "demo-password-01");
        String member = login("student_demo_02", "demo-password-02");
        String projectId = project(leader, member, "p71-frozen");
        String messageId = sendMention(leader, projectId, "p71-frozen-send", "撤回前的正文", MEMBER);

        mockMvc.perform(post("/api/v1/chat-messages/{id}/withdrawals", messageId)
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "p71-frozen-withdraw")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedRevision\":1}"))
                .andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT revision FROM chat_message WHERE id = ?", Integer.class,
                messageId)).isEqualTo(2);
        // 本批次有两条事件：发送事件产生通知，撤回事件按现有行为只标记完成。
        assertThat(worker.drain()).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT source_version FROM notification
                WHERE user_id = ? AND source_type = 'CHAT_MESSAGE' AND source_id = ?
                """, Integer.class, MEMBER, messageId)).isEqualTo(1);
        assertThat(notificationCountForSource(messageId)).isEqualTo(1);
    }

    /** 公告路径的源版本同样是 JSON 整数正值：读取侧不区分来源类型都能拿到真实版本。 */
    @Test
    void announcementNotificationsAlsoExposeAnIntegralSourceVersion() throws Exception {
        String student = login("student_demo_01", "demo-password-01");
        String teacher = login("teacher_demo_01", "demo-password-03");
        String squareId = square(student, "p71-notice-square");

        JsonNode preview = data(mockMvc.perform(post("/api/v1/squares/{id}/announcement-previews", squareId)
                        .header("Authorization", bearer(teacher)).header("Idempotency-Key", "p71-notice-prev")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "expectedRevision", 1,
                                "publisherRole", "TEACHER",
                                "title", "通知版本回归",
                                "content", "公告源版本应为整数",
                                "category", "COURSE_INFO",
                                "importance", "NORMAL",
                                "requiresAcknowledgement", false,
                                "refs", List.of()))))
                .andExpect(status().isOk()).andReturn());
        mockMvc.perform(post("/api/v1/squares/{id}/announcements", squareId)
                        .header("Authorization", bearer(teacher)).header("Idempotency-Key", "p71-notice-publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "title", "通知版本回归",
                                "content", "公告源版本应为整数",
                                "publisherRole", "TEACHER",
                                "previewToken", preview.path("previewToken").asText()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.contentVersion").value(1));

        JsonNode item = onlyNotification(student);
        assertThat(item.get("sourceType").asText()).isEqualTo("ANNOUNCEMENT");
        assertThat(item.get("sourceVersion").isIntegralNumber()).isTrue();
        assertThat(item.get("sourceVersion").asInt()).isEqualTo(1);
    }

    /**
     * 第二个接收人真实 SQL 失败：异常向外传播，整批回滚（前面已写通知、已处理事件的
     * processed_at 一并回滚）。移除失败注入后重试一次成功，再次执行不再新增。
     */
    @Test
    void secondRecipientFailureRollsBackTheWholeBatchAndRetryCompletesIt() {
        insertOutbox("outbox_p71_first", """
                {"messageId":"message_p71_first","sequence":1,"sourceVersion":2,
                 "mentions":["%s"]}
                """.formatted(MEMBER), -1);
        insertOutbox("outbox_p71_failing", """
                {"messageId":"message_p71_failing","sequence":2,"sourceVersion":4,
                 "mentions":["%s","%s"]}
                """.formatted(TEACHER, FAILING_USER), 0);
        installFailureInjection(FAILING_USER);

        try {
            assertThatThrownBy(worker::drain).isInstanceOf(DataAccessException.class);
        } finally {
            dropFailureInjection();
        }

        // 事务结束后的真实数据：零新增通知，两条事件都还是待处理。
        assertThat(count("notification")).isZero();
        assertThat(notificationCountForSource("message_p71_first")).isZero();
        assertThat(notificationCountForSource("message_p71_failing")).isZero();
        assertThat(processed("outbox_p71_first")).isFalse();
        assertThat(processed("outbox_p71_failing")).isFalse();

        // 移除失败注入后重试：两条事件一起完成，接收人写齐。
        assertThat(worker.drain()).isEqualTo(2);
        assertThat(notificationCountForSource("message_p71_first")).isEqualTo(1);
        assertThat(notificationCountForSource("message_p71_failing")).isEqualTo(2);
        assertThat(processed("outbox_p71_first")).isTrue();
        assertThat(processed("outbox_p71_failing")).isTrue();

        // 幂等：再次执行不新增。
        assertThat(worker.drain()).isZero();
        assertThat(count("notification")).isEqualTo(3);
    }

    /**
     * 已读动作与随后 drain 是两个独立事务：drain 失败时外层 5xx 不能作为“已读动作未发生”的证明。
     */
    @Test
    void readActionsKeepTheReadCommitWhenTheFollowingDrainFails() throws Exception {
        String member = login("student_demo_02", "demo-password-02");
        String notificationId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO notification (id, user_id, source_type, source_id, category, title, body,
                    event_version, source_version, visible_at, created_at)
                VALUES (?, ?, 'ANNOUNCEMENT', 'announcement_p71_read', 'COURSE_NOTICE', '待读公告', '', 1, 1, now(), now())
                """, notificationId, MEMBER);
        insertOutbox("outbox_p71_read_fail", """
                {"messageId":"message_p71_read_fail","sequence":1,"sourceVersion":1,
                 "mentions":["%s"]}
                """.formatted(FAILING_USER), 0);
        installFailureInjection(FAILING_USER);

        try {
            mockMvc.perform(post("/api/v1/me/notifications/read-actions")
                            .header("Authorization", bearer(member))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(Map.of(
                                    "notificationIds", List.of(notificationId)))))
                    .andExpect(status().is5xxServerError());
        } finally {
            dropFailureInjection();
        }

        // 已读动作的事务已经提交；drain 事务整体回滚，坏掉的是后者而不是前者。
        assertThat(jdbc.queryForObject("SELECT read_at IS NOT NULL FROM notification WHERE id = ?",
                Boolean.class, notificationId)).isTrue();
        assertThat(processed("outbox_p71_read_fail")).isFalse();
        assertThat(notificationCount(FAILING_USER)).isZero();
    }

    // ---- 夹具 ----

    private String sendMention(String leader, String projectId, String key, String content, String mention)
            throws Exception {
        JsonNode sent = data(mockMvc.perform(post("/api/v1/projects/{id}/chat-messages", projectId)
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "clientMessageId", key,
                                "content", content,
                                "refs", List.of(),
                                "mentions", List.of(mention)))))
                .andExpect(status().isOk()).andReturn());
        return sent.path("id").asText();
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
        String squareId = square.path("id").asText();
        JsonNode assignment = data(mockMvc.perform(post("/api/v1/squares/{id}/assignments", squareId)
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", key + "-asg")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("name", "通知回归作业",
                                "requirements", "演示", "hardDueAt", "2026-12-15T15:59:59Z",
                                "minMembers", 2, "maxMembers", 6, "sourceReferences", List.of()))))
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
                        .content(mapper.writeValueAsString(Map.of("squareId", squareId,
                                "assignmentId", assignment.path("id").asText(), "mode", "QUICK",
                                "name", "通知回归组", "capacity", 3, "discussionTime", "周三",
                                "roles", List.of()))))
                .andExpect(status().isOk()).andReturn());
        String projectId = project.path("id").asText();
        JsonNode current = data(mockMvc.perform(get("/api/v1/projects/{id}", projectId)
                        .header("Authorization", bearer(leader))).andExpect(status().isOk()).andReturn());
        JsonNode invitation = data(mockMvc.perform(post("/api/v1/projects/{id}/invitations", projectId)
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", key + "-inv")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("expectedProjectRevision",
                                current.path("revision").asInt(), "recipientId", MEMBER,
                                "message", "加入通知回归组"))))
                .andExpect(status().isOk()).andReturn());
        mockMvc.perform(post("/api/v1/invitations/{id}/responses",
                                invitation.path("invitation").path("id").asText())
                        .header("Authorization", bearer(member)).header("Idempotency-Key", key + "-acc")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"decision\":\"ACCEPT\"}"))
                .andExpect(status().isOk());
        return projectId;
    }

    private String square(String studentToken, String key) throws Exception {
        JsonNode match = data(mockMvc.perform(post("/api/v1/squares/matches")
                        .header("Authorization", bearer(studentToken)).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("schoolId", SCHOOL, "termId", TERM,
                                "teacherName", "示例教师", "slots", slots(),
                                "courseNameId", "course_name_demo_mobile"))))
                .andExpect(status().isOk()).andReturn());
        String squareId = data(mockMvc.perform(post("/api/v1/squares")
                        .header("Authorization", bearer(studentToken)).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("schoolId", SCHOOL, "termId", TERM,
                                "teacherName", "示例教师", "slots", slots(),
                                "courseNameId", "course_name_demo_mobile",
                                "candidateCheckToken", match.path("candidateCheckToken").asText()))))
                .andExpect(status().isOk()).andReturn()).path("id").asText();
        // 公告发布者资格来自真实 teacher_assignment；显式建立，避免依赖其他流程的副产物。
        jdbc.update("""
                INSERT INTO teacher_assignment (id, square_id, user_id, active, started_at, revision)
                SELECT ?, ?, ?, true, now(), 1
                WHERE NOT EXISTS (
                    SELECT 1 FROM teacher_assignment WHERE square_id = ? AND user_id = ? AND active
                )
                """, UUID.randomUUID().toString(), squareId, TEACHER, squareId, TEACHER);
        return squareId;
    }

    private List<Map<String, Object>> slots() {
        return List.of(Map.of("weekday", 3, "periodId", "period_demo_2", "weekPattern", "ALL",
                "startWeek", 1, "endWeek", 16, "customWeeks", List.of()));
    }

    private void insertOutbox(String id, String payload, int secondsOffset) {
        jdbc.update("""
                INSERT INTO notification_outbox (id, project_id, event_type, payload_json, created_at)
                VALUES (?, NULL, 'CHAT_MESSAGE_SENT', ?::jsonb, now() + ?::interval)
                """, id, payload, secondsOffset + " seconds");
    }

    private boolean processed(String outboxId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT processed_at IS NOT NULL FROM notification_outbox WHERE id = ?",
                Boolean.class, outboxId));
    }

    private boolean processedForProject(String projectId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT bool_and(processed_at IS NOT NULL) FROM notification_outbox WHERE project_id = ?
                """, Boolean.class, projectId));
    }

    private int notificationCount(String userId) {
        return jdbc.queryForObject("SELECT count(*) FROM notification WHERE user_id = ?",
                Integer.class, userId);
    }

    private int notificationCountForSource(String sourceId) {
        return jdbc.queryForObject("SELECT count(*) FROM notification WHERE source_id = ?",
                Integer.class, sourceId);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private JsonNode onlyNotification(String token) throws Exception {
        JsonNode items = data(mockMvc.perform(get("/api/v1/me/notifications")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn()).get("items");
        assertThat(items).hasSize(1);
        return items.get(0);
    }

    /**
     * 用临时触发器为指定接收人注入真实数据库失败。测试资源在 finally 与 @AfterEach
     * 双重清理，避免影响其他 IT。
     */
    private void installFailureInjection(String userId) {
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION p71_fail_notification() RETURNS trigger AS $$
                BEGIN
                    IF NEW.user_id = '%s' THEN
                        RAISE EXCEPTION 'P71 injected notification failure';
                    END IF;
                    RETURN NEW;
                END;
                $$ LANGUAGE plpgsql
                """.formatted(userId));
        jdbc.execute("""
                CREATE TRIGGER p71_fail_notification BEFORE INSERT ON notification
                FOR EACH ROW EXECUTE FUNCTION p71_fail_notification()
                """);
    }

    private void dropFailureInjection() {
        jdbc.execute("DROP TRIGGER IF EXISTS p71_fail_notification ON notification");
        jdbc.execute("DROP FUNCTION IF EXISTS p71_fail_notification()");
    }

    private String login(String loginId, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/sessions").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("loginId", loginId, "password", password))))
                .andExpect(status().isOk()).andReturn();
        return data(result).get("accessToken").asText();
    }

    private JsonNode data(MvcResult result) throws Exception {
        return mapper.readTree(result.getResponse().getContentAsString()).get("data");
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private void clearAll() {
        TestSchemaCleaner.deleteAll(jdbc, List.of(
                "announcement_reminder", "announcement_preview", "announcement_receipt", "announcement_recipient",
                "announcement_version", "help_article", "help_feedback", "support_route", "support_ticket",
                "support_reply", "support_history", "operator_grant", "content_report", "report_handling",
                "announcement", "notification_preference", "notification", "notification_outbox",
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
                "import_batch", "user_role", "user_profile", "app_user", "school"));
    }
}
