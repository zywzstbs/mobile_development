package cn.gulupai.server.community;

import cn.gulupai.server.it.TestSchemaCleaner;
import cn.gulupai.server.common.clock.MutableClock;
import cn.gulupai.server.common.security.Hashing;
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
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F01/R01、F03/R07、F04/R05、F05/R06 的真实 PostgreSQL 回归。
 * 每个用例都要求旧实现失败：旧回放会恢复撤回正文、旧变更流无界且游标无绑定、
 * 旧阅读游标只校验 latestSequence。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(ChatFixP13IT.TestClockConfig.class)
class ChatFixP13IT {

    private static final Instant BASE = Instant.parse("2026-10-31T00:00:00Z");
    private static final String SCHOOL = "school_demo_01";
    private static final String TERM = "term_demo_2026a";
    private static final String LEADER = "user_demo_student_01";
    private static final String MEMBER = "user_demo_student_02";
    private static final String TEACHER = "user_demo_teacher_01";

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

    // ---- R01：撤回后的幂等回放 ----

    @Test
    void r01ChatReplayNeverRestoresWithdrawnBodyOrRefs() throws Exception {
        String leader = login("student_demo_01", "demo-password-01");
        String member = login("student_demo_02", "demo-password-02");
        String projectId = project(leader, member, "f01-chat");
        String taskId = task(projectId, LEADER);
        String body = """
                {"clientMessageId":"r01-m1","content":"撤回前的正文",
                 "refs":[{"type":"TASK","id":"%s","version":1}],"mentions":["%s"]}
                """.formatted(taskId, MEMBER);
        JsonNode sent = data(mockMvc.perform(post("/api/v1/projects/{id}/chat-messages", projectId)
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "f01-send")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn());
        assertThat(count("chat_message", projectId)).isEqualTo(1);
        assertThat(count("chat_change", projectId)).isEqualTo(1);
        assertThat(count("notification_outbox", projectId)).isEqualTo(1);

        mockMvc.perform(post("/api/v1/chat-messages/{id}/withdrawals", sent.path("id").asText())
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "f01-withdraw")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedRevision\":1}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/projects/{id}/chat-messages", projectId)
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "f01-send")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(sent.path("id").asText()))
                .andExpect(jsonPath("$.data.sequence").value(sent.path("sequence").asLong()))
                .andExpect(jsonPath("$.data.withdrawn").value(true))
                .andExpect(jsonPath("$.data.content").value(nullValue()))
                .andExpect(jsonPath("$.data.refs.length()").value(0))
                .andExpect(jsonPath("$.data.mentions.length()").value(0));
        assertThat(count("chat_message", projectId)).isEqualTo(1);
        assertThat(count("chat_change", projectId)).isEqualTo(2);
        assertThat(count("notification_outbox", projectId)).isEqualTo(2);

        // 离组后不得回放：权限优先于回执。
        jdbc.update("UPDATE membership SET status='LEFT' WHERE project_id=? AND user_id=?", projectId, LEADER);
        jdbc.update("UPDATE project SET authorization_epoch=authorization_epoch+1 WHERE id=?", projectId);
        mockMvc.perform(post("/api/v1/projects/{id}/chat-messages", projectId)
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "f01-send")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/projects/{id}/chat-messages", projectId)
                        .header("Authorization", bearer(member)).header("Idempotency-Key", "f01-teacher")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"clientMessageId":"r01-teacher","content":"教师替发","refs":[],"mentions":[]}
                                """))
                .andExpect(status().isOk());
        String teacher = login("teacher_demo_01", "demo-password-03");
        mockMvc.perform(post("/api/v1/projects/{id}/chat-messages", projectId)
                        .header("Authorization", bearer(teacher)).header("Idempotency-Key", "f01-teacher")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"clientMessageId":"r01-teacher","content":"教师替发","refs":[],"mentions":[]}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void r01ArchivedProjectReplayIsCurrentReadOnlyProjection() throws Exception {
        String leader = login("student_demo_01", "demo-password-01");
        String member = login("student_demo_02", "demo-password-02");
        String projectId = project(leader, member, "f01-archive");
        String body = "{\"clientMessageId\":\"r01-arch\",\"content\":\"归档前消息\",\"refs\":[],\"mentions\":[]}";
        mockMvc.perform(post("/api/v1/projects/{id}/chat-messages", projectId)
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "f01-arch-send")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        jdbc.update("UPDATE project SET status='ARCHIVED', archived_at=? WHERE id=?",
                java.time.OffsetDateTime.ofInstant(BASE, ZoneOffset.UTC), projectId);
        mockMvc.perform(post("/api/v1/projects/{id}/chat-messages", projectId)
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "f01-arch-send")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.withdrawn").value(false));
        // 新发送仍按只读拒绝。
        mockMvc.perform(post("/api/v1/projects/{id}/chat-messages", projectId)
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "f01-arch-new")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientMessageId\":\"r01-arch-2\",\"content\":\"新消息\",\"refs\":[],\"mentions\":[]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROJECT_READ_ONLY"));
        assertThat(count("chat_message", projectId)).isEqualTo(1);
    }

    @Test
    void r01BoardReplayRevisionAndReferenceStayWithdrawn() throws Exception {
        String leader = login("student_demo_01", "demo-password-01");
        String member = login("student_demo_02", "demo-password-02");
        String projectId = project(leader, member, "f01-board");
        JsonNode topic = data(mockMvc.perform(post("/api/v1/projects/{id}/board-topics", projectId)
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "f01-topic")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"撤回主题","content":"主题正文","tag":"QUESTION","refs":[],"mentions":["%s"]}
                                """.formatted(MEMBER)))
                .andExpect(status().isOk()).andReturn());
        String topicId = topic.path("id").asText();
        JsonNode reply = data(mockMvc.perform(post("/api/v1/board-topics/{id}/replies", topicId)
                        .header("Authorization", bearer(member)).header("Idempotency-Key", "f01-reply")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedTopicRevision\":1,\"content\":\"回复正文\",\"replyToId\":null,\"mentions\":[]}"))
                .andExpect(status().isOk()).andReturn());
        String replyId = reply.path("id").asText();
        mockMvc.perform(post("/api/v1/board-replies/{id}/withdrawals", replyId)
                        .header("Authorization", bearer(member)).header("Idempotency-Key", "f01-reply-wd")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedRevision\":1}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/board-topics/{id}/withdrawals", topicId)
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "f01-topic-wd")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedRevision\":1}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/projects/{id}/board-topics", projectId)
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "f01-topic")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"撤回主题","content":"主题正文","tag":"QUESTION","refs":[],"mentions":["%s"]}
                                """.formatted(MEMBER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.withdrawn").value(true))
                .andExpect(jsonPath("$.data.content").value(nullValue()))
                .andExpect(jsonPath("$.data.refs.length()").value(0))
                .andExpect(jsonPath("$.data.mentions.length()").value(0));
        mockMvc.perform(post("/api/v1/board-topics/{id}/replies", topicId)
                        .header("Authorization", bearer(member)).header("Idempotency-Key", "f01-reply")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedTopicRevision\":1,\"content\":\"回复正文\",\"replyToId\":null,\"mentions\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.withdrawn").value(true))
                .andExpect(jsonPath("$.data.content").value(nullValue()));
        mockMvc.perform(get("/api/v1/board-topics/{id}/revisions", topicId)
                        .header("Authorization", bearer(leader)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].content").value(nullValue()));
        mockMvc.perform(get("/api/v1/board-replies/{id}/revisions", replyId)
                        .header("Authorization", bearer(member)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].content").value(nullValue()));
    }

    @Test
    void r01LegacyReceiptAndMissingResourceHaveDefinedBehavior() throws Exception {
        String leader = login("student_demo_01", "demo-password-01");
        String member = login("student_demo_02", "demo-password-02");
        String projectId = project(leader, member, "f01-legacy");
        String body = "{\"clientMessageId\":\"legacy-1\",\"content\":\"旧消息\",\"refs\":[],\"mentions\":[]}";
        JsonNode sent = data(mockMvc.perform(post("/api/v1/projects/{id}/chat-messages", projectId)
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "f01-legacy-send")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn());
        jdbc.update("UPDATE chat_message SET request_hash=NULL, request_hash_version=NULL WHERE id=?",
                sent.path("id").asText());
        mockMvc.perform(post("/api/v1/projects/{id}/chat-messages", projectId)
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "f01-legacy-retry")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CLIENT_MESSAGE_ID_REUSED"));

        String secondBody = "{\"clientMessageId\":\"legacy-2\",\"content\":\"将被删除\",\"refs\":[],\"mentions\":[]}";
        JsonNode second = data(mockMvc.perform(post("/api/v1/projects/{id}/chat-messages", projectId)
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "f01-missing-send")
                        .contentType(MediaType.APPLICATION_JSON).content(secondBody))
                .andExpect(status().isOk()).andReturn());
        jdbc.update("DELETE FROM chat_change WHERE message_id=?", second.path("id").asText());
        jdbc.update("DELETE FROM chat_message WHERE id=?", second.path("id").asText());
        mockMvc.perform(post("/api/v1/projects/{id}/chat-messages", projectId)
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "f01-missing-send")
                        .contentType(MediaType.APPLICATION_JSON).content(secondBody))
                .andExpect(status().isNotFound());
    }

    // ---- R07：完整请求摘要 ----

    @Test
    void r07ClientMessageIdBindsFullRequest() throws Exception {
        String leader = login("student_demo_01", "demo-password-01");
        String member = login("student_demo_02", "demo-password-02");
        String projectId = project(leader, member, "f03-chat");
        String taskId = task(projectId, LEADER);
        String full = """
                {"clientMessageId":"r07-m1","content":"同一正文",
                 "refs":[{"type":"TASK","id":"%s","version":1}],"mentions":["%s"]}
                """.formatted(taskId, MEMBER);
        JsonNode first = data(mockMvc.perform(post("/api/v1/projects/{id}/chat-messages", projectId)
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "f03-a")
                        .contentType(MediaType.APPLICATION_JSON).content(full))
                .andExpect(status().isOk()).andReturn());
        mockMvc.perform(post("/api/v1/projects/{id}/chat-messages", projectId)
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "f03-b")
                        .contentType(MediaType.APPLICATION_JSON).content(full))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(first.path("id").asText()));
        assertThat(count("chat_message", projectId)).isEqualTo(1);

        mockMvc.perform(post("/api/v1/projects/{id}/chat-messages", projectId)
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "f03-ref-version")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"clientMessageId":"r07-m1","content":"同一正文",
                                 "refs":[{"type":"TASK","id":"%s","version":2}],"mentions":["%s"]}
                                """.formatted(taskId, MEMBER)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CLIENT_MESSAGE_ID_REUSED"));
        mockMvc.perform(post("/api/v1/projects/{id}/chat-messages", projectId)
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "f03-mentions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"clientMessageId":"r07-m1","content":"同一正文",
                                 "refs":[{"type":"TASK","id":"%s","version":1}],"mentions":[]}
                                """.formatted(taskId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CLIENT_MESSAGE_ID_REUSED"));
        mockMvc.perform(post("/api/v1/projects/{id}/chat-messages", projectId)
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "f03-content")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"clientMessageId":"r07-m1","content":"不同正文",
                                 "refs":[{"type":"TASK","id":"%s","version":1}],"mentions":["%s"]}
                                """.formatted(taskId, MEMBER)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CLIENT_MESSAGE_ID_REUSED"));

        mockMvc.perform(post("/api/v1/chat-messages/{id}/withdrawals", first.path("id").asText())
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "f03-withdraw")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedRevision\":1}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/projects/{id}/chat-messages", projectId)
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "f03-after-withdraw")
                        .contentType(MediaType.APPLICATION_JSON).content(full))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.withdrawn").value(true))
                .andExpect(jsonPath("$.data.content").value(nullValue()));
        mockMvc.perform(post("/api/v1/projects/{id}/chat-messages", projectId)
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "f03-after-diff")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"clientMessageId":"r07-m1","content":"不同正文",
                                 "refs":[{"type":"TASK","id":"%s","version":1}],"mentions":["%s"]}
                                """.formatted(taskId, MEMBER)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CLIENT_MESSAGE_ID_REUSED"));
    }

    @Test
    void replyIsStoredReadBackAndConflictsWhenTargetChanges() throws Exception {
        String leader = login("student_demo_01", "demo-password-01");
        String member = login("student_demo_02", "demo-password-02");
        String projectId = project(leader, member, "reply-store");
        JsonNode root = data(mockMvc.perform(post("/api/v1/projects/{id}/chat-messages", projectId)
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "reply-root")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientMessageId\":\"reply-root\",\"content\":\"原消息\",\"refs\":[],\"mentions\":[]}"))
                .andExpect(status().isOk()).andReturn());
        String rootId = root.path("id").asText();
        String replyBody = """
                {"clientMessageId":"reply-1","content":"回复正文","refs":[],"mentions":[],"replyToId":"%s"}
                """.formatted(rootId);
        JsonNode reply = data(mockMvc.perform(post("/api/v1/projects/{id}/chat-messages", projectId)
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "reply-send")
                        .contentType(MediaType.APPLICATION_JSON).content(replyBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.replyToId").value(rootId))
                .andExpect(jsonPath("$.data.content").value("回复正文"))
                .andReturn());
        String replyId = reply.path("id").asText();
        Map<String, Object> stored = jdbc.queryForMap(
                "SELECT reply_to_id, request_hash_version FROM chat_message WHERE id = ?", replyId);
        assertThat(stored.get("reply_to_id")).isEqualTo(rootId);
        assertThat(((Number) stored.get("request_hash_version")).intValue()).isEqualTo(2);

        JsonNode page = data(mockMvc.perform(get("/api/v1/projects/{id}/chat-messages", projectId)
                        .header("Authorization", bearer(member)))
                .andExpect(status().isOk()).andReturn());
        JsonNode readBack = null;
        for (JsonNode item : page.path("items")) {
            if (replyId.equals(item.path("id").asText())) {
                readBack = item;
            }
        }
        assertThat(readBack).isNotNull();
        assertThat(readBack.path("replyToId").asText()).isEqualTo(rootId);
        assertThat(readBack.path("content").asText()).isEqualTo("回复正文");

        mockMvc.perform(post("/api/v1/projects/{id}/chat-messages", projectId)
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "reply-retry")
                        .contentType(MediaType.APPLICATION_JSON).content(replyBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(replyId))
                .andExpect(jsonPath("$.data.replyToId").value(rootId));
        assertThat(count("chat_message", projectId)).isEqualTo(2);

        JsonNode other = data(mockMvc.perform(post("/api/v1/projects/{id}/chat-messages", projectId)
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "reply-other")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientMessageId\":\"reply-other\",\"content\":\"另一条\",\"refs\":[],\"mentions\":[]}"))
                .andExpect(status().isOk()).andReturn());
        mockMvc.perform(post("/api/v1/projects/{id}/chat-messages", projectId)
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "reply-conflict")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"clientMessageId":"reply-1","content":"回复正文","refs":[],"mentions":[],"replyToId":"%s"}
                                """.formatted(other.path("id").asText())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CLIENT_MESSAGE_ID_REUSED"));
        assertThat(count("chat_message", projectId)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT reply_to_id FROM chat_message WHERE id = ?", String.class, replyId))
                .isEqualTo(rootId);
    }

    // ---- R05：有界变更同步与游标绑定 ----

    @Test
    void r05ChangesAreBoundedBaselineAndCursorBound() throws Exception {
        String leader = login("student_demo_01", "demo-password-01");
        String member = login("student_demo_02", "demo-password-02");
        String projectId = project(leader, member, "f04-changes");
        JsonNode baseline = data(mockMvc.perform(get("/api/v1/projects/{id}/chat-messages", projectId)
                        .header("Authorization", bearer(member)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.changes.length()").value(0))
                .andExpect(jsonPath("$.data.hasMoreChanges").value(false))
                .andReturn());
        String token = baseline.path("nextChangeCursor").asText();
        assertThat(token).isNotBlank();

        seedMessages(projectId, LEADER, 101, "同步消息");
        JsonNode firstBatch = data(mockMvc.perform(get("/api/v1/projects/{id}/chat-messages", projectId)
                        .param("afterChangeCursor", token)
                        .header("Authorization", bearer(member)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.changes.length()").value(100))
                .andExpect(jsonPath("$.data.hasMoreChanges").value(true))
                .andReturn());
        String boundaryToken = firstBatch.path("nextChangeCursor").asText();
        assertThat(boundaryToken).isNotEqualTo(token);
        // 输入 token 不可变：同一旧 token 重试仍返回同一批，不因上次 HTTP 提交而跳过。
        JsonNode retry = data(mockMvc.perform(get("/api/v1/projects/{id}/chat-messages", projectId)
                        .param("afterChangeCursor", token)
                        .header("Authorization", bearer(member)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.changes.length()").value(100))
                .andExpect(jsonPath("$.data.hasMoreChanges").value(true))
                .andReturn());
        assertThat(retry.path("nextChangeCursor").asText()).isNotEqualTo(token);
        Long original = jdbc.queryForObject("""
                SELECT last_change_sequence FROM chat_sync_cursor
                WHERE token_hash = ? AND user_id = ? AND project_id = ?
                """, Long.class, Hashing.sha256Hex(token), MEMBER, projectId);
        assertThat(original).isZero();
        JsonNode secondBatch = data(mockMvc.perform(get("/api/v1/projects/{id}/chat-messages", projectId)
                        .param("afterChangeCursor", boundaryToken)
                        .header("Authorization", bearer(member)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.changes.length()").value(1))
                .andExpect(jsonPath("$.data.hasMoreChanges").value(false))
                .andReturn());
        String at101 = secondBatch.path("nextChangeCursor").asText();

        // 同一消息的发送+撤回两个事件在同一批内按 id 合并为当前撤回投影。
        jdbc.update("""
                INSERT INTO chat_message(id, project_id, sequence, client_message_id, author_id,
                    content, refs_json, mentions_json, withdrawn, revision, created_at, updated_at)
                VALUES ('dedup-msg', ?, 102, 'dedup', ?, NULL, '[]'::jsonb, '[]'::jsonb, true, 2, ?, ?)
                """, projectId, LEADER, at(0), at(0));
        jdbc.update("INSERT INTO chat_change(id, project_id, change_sequence, message_id, message_revision, created_at) VALUES (?, ?, 102, 'dedup-msg', 1, ?)",
                UUID.randomUUID().toString(), projectId, at(0));
        jdbc.update("INSERT INTO chat_change(id, project_id, change_sequence, message_id, message_revision, created_at) VALUES (?, ?, 103, 'dedup-msg', 2, ?)",
                UUID.randomUUID().toString(), projectId, at(1));
        jdbc.update("UPDATE chat_counter SET last_change_sequence=103 WHERE project_id=?", projectId);
        JsonNode dedup = data(mockMvc.perform(get("/api/v1/projects/{id}/chat-messages", projectId)
                        .param("afterChangeCursor", at101)
                        .header("Authorization", bearer(member)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.changes.length()").value(1))
                .andExpect(jsonPath("$.data.changes[0].id").value("dedup-msg"))
                .andExpect(jsonPath("$.data.changes[0].revision").value(2))
                .andExpect(jsonPath("$.data.changes[0].withdrawn").value(true))
                .andReturn());
        String latestToken = dedup.path("nextChangeCursor").asText();

        // 他人、伪造、跨项目的游标均拒绝；权限优先。
        mockMvc.perform(get("/api/v1/projects/{id}/chat-messages", projectId)
                        .param("afterChangeCursor", latestToken)
                        .header("Authorization", bearer(leader)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHAT_SYNC_EXPIRED"));
        mockMvc.perform(get("/api/v1/projects/{id}/chat-messages", projectId)
                        .param("afterChangeCursor", "forged-cursor")
                        .header("Authorization", bearer(member)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHAT_SYNC_EXPIRED"));
        jdbc.update("UPDATE membership SET status='LEFT' WHERE project_id=? AND user_id=?", projectId, MEMBER);
        jdbc.update("UPDATE project SET authorization_epoch=authorization_epoch+1 WHERE id=?", projectId);
        mockMvc.perform(get("/api/v1/projects/{id}/chat-messages", projectId)
                        .param("afterChangeCursor", "forged-cursor")
                        .header("Authorization", bearer(member)))
                .andExpect(status().isForbidden());
    }

    @Test
    void r05CursorExpiresAtSevenDaysAndEpochChange() throws Exception {
        String leader = login("student_demo_01", "demo-password-01");
        String member = login("student_demo_02", "demo-password-02");
        String projectId = project(leader, member, "f04-expire");
        String token = data(mockMvc.perform(get("/api/v1/projects/{id}/chat-messages", projectId)
                        .header("Authorization", bearer(member)))
                .andExpect(status().isOk()).andReturn()).path("nextChangeCursor").asText();

        jdbc.update("UPDATE project SET authorization_epoch=authorization_epoch+1 WHERE id=?", projectId);
        mockMvc.perform(get("/api/v1/projects/{id}/chat-messages", projectId)
                        .param("afterChangeCursor", token)
                        .header("Authorization", bearer(member)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHAT_SYNC_EXPIRED"));

        // 重新建立基线后，到期前 1 秒仍可用，正好到期即失效。
        String fresh = data(mockMvc.perform(get("/api/v1/projects/{id}/chat-messages", projectId)
                        .header("Authorization", bearer(member)))
                .andExpect(status().isOk()).andReturn()).path("nextChangeCursor").asText();
        clock.setInstant(BASE.plusSeconds(7 * 24 * 3600 - 1));
        String reLogin = login("student_demo_02", "demo-password-02");
        mockMvc.perform(get("/api/v1/projects/{id}/chat-messages", projectId)
                        .param("afterChangeCursor", fresh)
                        .header("Authorization", bearer(reLogin)))
                .andExpect(status().isOk());
        clock.setInstant(BASE.plusSeconds(7 * 24 * 3600));
        String lastLogin = login("student_demo_02", "demo-password-02");
        mockMvc.perform(get("/api/v1/projects/{id}/chat-messages", projectId)
                        .param("afterChangeCursor", fresh)
                        .header("Authorization", bearer(lastLogin)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHAT_SYNC_EXPIRED"));
    }

    @Test
    void r05HistoryBoundariesDoNotAdvanceIncrementalPosition() throws Exception {
        String leader = login("student_demo_01", "demo-password-01");
        String member = login("student_demo_02", "demo-password-02");
        String projectId = project(leader, member, "f04-pages");
        seedMessages(projectId, LEADER, 25, "历史消息");
        jdbc.update("UPDATE chat_message SET content='唯一关键字' WHERE project_id=? AND sequence=13", projectId);

        JsonNode latest = data(mockMvc.perform(get("/api/v1/projects/{id}/chat-messages", projectId)
                        .param("limit", "10").header("Authorization", bearer(member)))
                .andExpect(status().isOk()).andReturn());
        assertThat(latest.path("items").size()).isEqualTo(10);
        assertThat(latest.path("items").get(0).path("sequence").asLong()).isEqualTo(16);
        assertThat(latest.path("hasMore").asBoolean()).isTrue();
        assertThat(latest.path("nextAfterSequence").asLong()).isEqualTo(25);

        JsonNode before = data(mockMvc.perform(get("/api/v1/projects/{id}/chat-messages", projectId)
                        .param("beforeSequence", "16").param("limit", "10")
                        .header("Authorization", bearer(member)))
                .andExpect(status().isOk()).andReturn());
        assertThat(before.path("items").size()).isEqualTo(10);
        assertThat(before.path("items").get(0).path("sequence").asLong()).isEqualTo(6);
        assertThat(before.path("hasMore").asBoolean()).isTrue();
        assertThat(before.path("nextAfterSequence").asLong()).isZero();

        JsonNode after = data(mockMvc.perform(get("/api/v1/projects/{id}/chat-messages", projectId)
                        .param("afterSequence", "20").param("limit", "10")
                        .header("Authorization", bearer(member)))
                .andExpect(status().isOk()).andReturn());
        assertThat(after.path("items").size()).isEqualTo(5);
        assertThat(after.path("hasMore").asBoolean()).isFalse();
        assertThat(after.path("nextAfterSequence").asLong()).isEqualTo(25);

        JsonNode search = data(mockMvc.perform(get("/api/v1/projects/{id}/chat-messages", projectId)
                        .param("q", "唯一关键字").param("limit", "10")
                        .header("Authorization", bearer(member)))
                .andExpect(status().isOk()).andReturn());
        assertThat(search.path("items").size()).isEqualTo(1);
        assertThat(search.path("nextAfterSequence").asLong()).isZero();
        mockMvc.perform(get("/api/v1/projects/{id}/chat-messages", projectId)
                        .param("afterSequence", "1").param("beforeSequence", "2")
                        .header("Authorization", bearer(member)))
                .andExpect(status().isUnprocessableEntity());
    }

    // ---- R06：连续下发与阅读游标 ----

    @Test
    void r06ReadCursorRequiresContinuousDeliveredRangeAndIdempotency() throws Exception {
        String leader = login("student_demo_01", "demo-password-01");
        String member = login("student_demo_02", "demo-password-02");
        String projectId = project(leader, member, "f05-cursor");
        seedMessages(projectId, LEADER, 100, "阅读消息");

        mockMvc.perform(put("/api/v1/projects/{id}/me/read-cursor", projectId)
                        .header("Authorization", bearer(member))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"sequence\":100}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
        mockMvc.perform(put("/api/v1/projects/{id}/me/read-cursor", projectId)
                        .header("Authorization", bearer(member)).header("Idempotency-Key", "f05-before-read")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"sequence\":100}"))
                .andExpect(status().isUnprocessableEntity());

        mockMvc.perform(get("/api/v1/projects/{id}/chat-messages", projectId)
                        .param("afterSequence", "90").param("limit", "10")
                        .header("Authorization", bearer(member)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(10));
        mockMvc.perform(put("/api/v1/projects/{id}/me/read-cursor", projectId)
                        .header("Authorization", bearer(member)).header("Idempotency-Key", "f05-gap")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"sequence\":100}"))
                .andExpect(status().isUnprocessableEntity());

        mockMvc.perform(get("/api/v1/projects/{id}/chat-messages", projectId)
                        .param("afterSequence", "0").param("limit", "90")
                        .header("Authorization", bearer(member)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(90));
        mockMvc.perform(put("/api/v1/projects/{id}/me/read-cursor", projectId)
                        .header("Authorization", bearer(member)).header("Idempotency-Key", "f05-advance")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"sequence\":100}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lastSequence").value(100));
        // 同一键同体重放返回当前合法位置。
        mockMvc.perform(put("/api/v1/projects/{id}/me/read-cursor", projectId)
                        .header("Authorization", bearer(member)).header("Idempotency-Key", "f05-advance")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"sequence\":100}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lastSequence").value(100));
        // 同一键异体仍按统一幂等协议冲突。
        mockMvc.perform(put("/api/v1/projects/{id}/me/read-cursor", projectId)
                        .header("Authorization", bearer(member)).header("Idempotency-Key", "f05-advance")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"sequence\":50}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        mockMvc.perform(put("/api/v1/projects/{id}/me/read-cursor", projectId)
                        .header("Authorization", bearer(member)).header("Idempotency-Key", "f05-beyond")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"sequence\":120}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void r06MultiDeviceMaxSearchGapAndLeaveRejoin() throws Exception {
        String leader = login("student_demo_01", "demo-password-01");
        String member = login("student_demo_02", "demo-password-02");
        String projectId = project(leader, member, "f05-multi");
        seedMessages(projectId, LEADER, 100, "多设备消息");

        // GET 不改变已读；两设备分别拉取连续段后可合法推进到 max。
        mockMvc.perform(get("/api/v1/projects/{id}/me/read-cursor", projectId)
                        .header("Authorization", bearer(member)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.lastSequence").value(0));
        mockMvc.perform(get("/api/v1/projects/{id}/chat-messages", projectId)
                        .param("afterSequence", "0").param("limit", "50")
                        .header("Authorization", bearer(member)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/projects/{id}/chat-messages", projectId)
                        .param("afterSequence", "50").param("limit", "50")
                        .header("Authorization", bearer(member)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/projects/{id}/me/read-cursor", projectId)
                        .header("Authorization", bearer(member)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.lastSequence").value(0));
        mockMvc.perform(put("/api/v1/projects/{id}/me/read-cursor", projectId)
                        .header("Authorization", bearer(member)).header("Idempotency-Key", "f05-device-b")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"sequence\":100}"))
                .andExpect(status().isOk());
        // 旧设备上报更小值不得回退。
        mockMvc.perform(put("/api/v1/projects/{id}/me/read-cursor", projectId)
                        .header("Authorization", bearer(member)).header("Idempotency-Key", "f05-device-a")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"sequence\":60}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lastSequence").value(100));

        // 搜索只命中一条不能覆盖中间缺口。
        String searchProject = project(leader, member, "f05-search");
        seedMessages(searchProject, LEADER, 100, "搜索消息");
        jdbc.update("UPDATE chat_message SET content='唯一命中' WHERE project_id=? AND sequence=50", searchProject);
        mockMvc.perform(get("/api/v1/projects/{id}/chat-messages", searchProject)
                        .param("q", "唯一命中")
                        .header("Authorization", bearer(member)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1));
        mockMvc.perform(put("/api/v1/projects/{id}/me/read-cursor", searchProject)
                        .header("Authorization", bearer(member)).header("Idempotency-Key", "f05-search-gap")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"sequence\":100}"))
                .andExpect(status().isUnprocessableEntity());

        // 撤回占位保留序号，可连续补齐；先 GET 再 PUT 才变化。
        jdbc.update("UPDATE chat_message SET content=NULL, withdrawn=true, revision=2 WHERE project_id=? AND sequence=50",
                searchProject);
        mockMvc.perform(get("/api/v1/projects/{id}/chat-messages", searchProject)
                        .param("afterSequence", "0").param("limit", "100")
                        .header("Authorization", bearer(member)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[49].withdrawn").value(true))
                .andExpect(jsonPath("$.data.items[49].content").value(nullValue()));
        mockMvc.perform(get("/api/v1/projects/{id}/me/read-cursor", searchProject)
                        .header("Authorization", bearer(member)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.lastSequence").value(0));
        mockMvc.perform(put("/api/v1/projects/{id}/me/read-cursor", searchProject)
                        .header("Authorization", bearer(member)).header("Idempotency-Key", "f05-placeholder")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"sequence\":100}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lastSequence").value(100));

        // 离组重入后旧下发代次失效，需重新拉取。
        String rejoinProject = project(leader, member, "f05-rejoin");
        seedMessages(rejoinProject, LEADER, 100, "重入消息");
        mockMvc.perform(get("/api/v1/projects/{id}/chat-messages", rejoinProject)
                        .param("afterSequence", "0").param("limit", "100")
                        .header("Authorization", bearer(member)))
                .andExpect(status().isOk());
        jdbc.update("UPDATE membership SET status='LEFT' WHERE project_id=? AND user_id=?", rejoinProject, MEMBER);
        jdbc.update("UPDATE project SET authorization_epoch=authorization_epoch+1 WHERE id=?", rejoinProject);
        jdbc.update("""
                INSERT INTO membership(id, project_id, user_id, project_role, status, color, joined_at, revision)
                VALUES (?, ?, ?, 'MEMBER', 'ACTIVE', '#236B56', ?, 1)
                """, UUID.randomUUID().toString(), rejoinProject, MEMBER, at(0));
        jdbc.update("UPDATE project SET authorization_epoch=authorization_epoch+1 WHERE id=?", rejoinProject);
        mockMvc.perform(put("/api/v1/projects/{id}/me/read-cursor", rejoinProject)
                        .header("Authorization", bearer(member)).header("Idempotency-Key", "f05-old-epoch")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"sequence\":100}"))
                .andExpect(status().isUnprocessableEntity());
    }


    @Test
    void r07ConcurrentSameClientMessageIdHasSingleEffect() throws Exception {
        String leader = login("student_demo_01", "demo-password-01");
        String member = login("student_demo_02", "demo-password-02");
        String projectId = project(leader, member, "f03-concurrent");
        String same = "{\"clientMessageId\":\"c-same\",\"content\":\"并发同请求\",\"refs\":[],\"mentions\":[]}";
        List<MvcResult> sameResults = concurrently(
                () -> mockMvc.perform(post("/api/v1/projects/{id}/chat-messages", projectId)
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "f03-c-a")
                        .contentType(MediaType.APPLICATION_JSON).content(same)).andReturn(),
                () -> mockMvc.perform(post("/api/v1/projects/{id}/chat-messages", projectId)
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "f03-c-b")
                        .contentType(MediaType.APPLICATION_JSON).content(same)).andReturn());
        assertThat(sameResults).allSatisfy(result ->
                assertThat(result.getResponse().getStatus()).isEqualTo(200));
        String firstId = mapper.readTree(sameResults.get(0).getResponse().getContentAsString())
                .path("data").path("id").asText();
        String secondId = mapper.readTree(sameResults.get(1).getResponse().getContentAsString())
                .path("data").path("id").asText();
        assertThat(firstId).isEqualTo(secondId);
        assertThat(count("chat_message", projectId)).isEqualTo(1);

        String different = "{\"clientMessageId\":\"c-diff\",\"content\":\"并发异请求\",\"refs\":[],\"mentions\":[]}";
        List<MvcResult> diffResults = concurrently(
                () -> mockMvc.perform(post("/api/v1/projects/{id}/chat-messages", projectId)
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "f03-d-a")
                        .contentType(MediaType.APPLICATION_JSON).content(different)).andReturn(),
                () -> mockMvc.perform(post("/api/v1/projects/{id}/chat-messages", projectId)
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "f03-d-b")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientMessageId\":\"c-diff\",\"content\":\"不同正文\",\"refs\":[],\"mentions\":[]}"))
                        .andReturn());
        List<Integer> statuses = diffResults.stream().map(result -> result.getResponse().getStatus()).sorted().toList();
        assertThat(statuses).containsExactly(200, 409);
        assertThat(count("chat_message", projectId)).isEqualTo(2);
    }


    @Test
    void s01LostResponseRetryStillDeliversOldWithdrawal() throws Exception {
        String leader = login("student_demo_01", "demo-password-01");
        String member = login("student_demo_02", "demo-password-02");
        String projectId = project(leader, member, "s01-lost");
        String body = "{\"clientMessageId\":\"s01-m1\",\"content\":\"将撤回\",\"refs\":[],\"mentions\":[]}";
        JsonNode sent = data(mockMvc.perform(post("/api/v1/projects/{id}/chat-messages", projectId)
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "s01-send")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn());
        String cursor = data(mockMvc.perform(get("/api/v1/projects/{id}/chat-messages", projectId)
                        .header("Authorization", bearer(member)))
                .andExpect(status().isOk()).andReturn()).path("nextChangeCursor").asText();
        mockMvc.perform(post("/api/v1/chat-messages/{id}/withdrawals", sent.path("id").asText())
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "s01-withdraw")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedRevision\":1}"))
                .andExpect(status().isOk());

        // 第一次响应“在网络中丢失”，客户端仍用同一个旧 token 重试。
        mockMvc.perform(get("/api/v1/projects/{id}/chat-messages", projectId)
                        .param("afterChangeCursor", cursor)
                        .header("Authorization", bearer(member)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.changes.length()").value(1))
                .andExpect(jsonPath("$.data.changes[0].withdrawn").value(true));
        mockMvc.perform(get("/api/v1/projects/{id}/chat-messages", projectId)
                        .param("afterChangeCursor", cursor)
                        .header("Authorization", bearer(member)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.changes.length()").value(1))
                .andExpect(jsonPath("$.data.changes[0].withdrawn").value(true));
        assertThat(jdbc.queryForObject("""
                SELECT last_change_sequence FROM chat_sync_cursor
                WHERE token_hash = ? AND user_id = ? AND project_id = ?
                """, Long.class, Hashing.sha256Hex(cursor), MEMBER, projectId)).isEqualTo(1);
    }

    @Test
    void s01ConcurrentRetrySameCursorKeepsEventsAndDoesNotGrowOnIdle() throws Exception {
        String leader = login("student_demo_01", "demo-password-01");
        String member = login("student_demo_02", "demo-password-02");
        String projectId = project(leader, member, "s01-concurrent");
        String cursor = data(mockMvc.perform(get("/api/v1/projects/{id}/chat-messages", projectId)
                        .header("Authorization", bearer(member)))
                .andExpect(status().isOk()).andReturn()).path("nextChangeCursor").asText();
        seedMessages(projectId, LEADER, 50, "并发同步");
        List<MvcResult> results = concurrently(
                () -> mockMvc.perform(get("/api/v1/projects/{id}/chat-messages", projectId)
                        .param("afterChangeCursor", cursor)
                        .header("Authorization", bearer(member))).andReturn(),
                () -> mockMvc.perform(get("/api/v1/projects/{id}/chat-messages", projectId)
                        .param("afterChangeCursor", cursor)
                        .header("Authorization", bearer(member))).andReturn());
        for (MvcResult result : results) {
            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            JsonNode data = mapper.readTree(result.getResponse().getContentAsString()).path("data");
            assertThat(data.path("changes").size()).isEqualTo(50);
            assertThat(data.path("nextChangeCursor").asText()).isNotEqualTo(cursor);
        }
        assertThat(jdbc.queryForObject("""
                SELECT last_change_sequence FROM chat_sync_cursor
                WHERE token_hash = ? AND user_id = ? AND project_id = ?
                """, Long.class, Hashing.sha256Hex(cursor), MEMBER, projectId)).isZero();

        // 无新事件时返回仍有效的同一 token，不每 5 秒插入新行。
        String boundary = mapper.readTree(results.get(0).getResponse().getContentAsString())
                .path("data").path("nextChangeCursor").asText();
        int before = jdbc.queryForObject(
                "SELECT count(*) FROM chat_sync_cursor WHERE user_id=? AND project_id=?",
                Integer.class, MEMBER, projectId);
        JsonNode idle = data(mockMvc.perform(get("/api/v1/projects/{id}/chat-messages", projectId)
                        .param("afterChangeCursor", boundary)
                        .header("Authorization", bearer(member)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.changes.length()").value(0))
                .andReturn());
        assertThat(idle.path("nextChangeCursor").asText()).isEqualTo(boundary);
        int after = jdbc.queryForObject(
                "SELECT count(*) FROM chat_sync_cursor WHERE user_id=? AND project_id=?",
                Integer.class, MEMBER, projectId);
        assertThat(after).isEqualTo(before);
    }

    // ---- helpers ----

    private void seedMessages(String projectId, String authorId, int count, String prefix) {
        for (int i = 1; i <= count; i++) {
            String id = UUID.randomUUID().toString();
            jdbc.update("""
                    INSERT INTO chat_message(id, project_id, sequence, client_message_id, author_id,
                        content, refs_json, mentions_json, withdrawn, revision, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, '[]'::jsonb, '[]'::jsonb, false, 1, ?, ?)
                    """, id, projectId, i, "seed-" + i, authorId, prefix + " " + i, at(0), at(0));
            jdbc.update("""
                    INSERT INTO chat_change(id, project_id, change_sequence, message_id, message_revision, created_at)
                    VALUES (?, ?, ?, ?, 1, ?)
                    """, UUID.randomUUID().toString(), projectId, i, id, at(0));
        }
        jdbc.update("""
                INSERT INTO chat_counter(project_id, next_sequence, last_change_sequence)
                VALUES (?, ?, ?)
                ON CONFLICT (project_id) DO UPDATE
                    SET next_sequence = EXCLUDED.next_sequence,
                        last_change_sequence = EXCLUDED.last_change_sequence
                """, projectId, count + 1, count);
    }

    private String task(String projectId, String ownerId) {
        String stage = jdbc.queryForObject(
                "SELECT id FROM project_stage WHERE project_id = ? ORDER BY position LIMIT 1",
                String.class, projectId);
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO task(id, project_id, stage_id, title, description, mode, owner_id, due_at,
                    status, revision, created_at, updated_at)
                VALUES (?, ?, ?, '聊天引用任务', '', 'ORDINARY', ?, '2026-12-01T00:00:00Z', 'TODO', 1, ?, ?)
                """, id, projectId, stage, ownerId, at(0), at(0));
        return id;
    }

    private int count(String table, String projectId) {
        Integer value = jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE project_id = ?",
                Integer.class, projectId);
        return value == null ? 0 : value;
    }

    private java.time.OffsetDateTime at(int seconds) {
        return java.time.OffsetDateTime.ofInstant(clock.instant().plusSeconds(seconds), ZoneOffset.UTC);
    }

    private String project(String leader, String member, String key) throws Exception {
        return project(leader, member, key, "course_name_demo_mobile", 3, "period_demo_2");
    }

    private String project(String leader, String member, String key, String courseName,
                           int weekday, String period) throws Exception {
        JsonNode match = data(mockMvc.perform(post("/api/v1/squares/matches")
                        .header("Authorization", bearer(leader)).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("schoolId", SCHOOL, "termId", TERM,
                                "teacherName", "示例教师", "slots", slots(weekday, period),
                                "courseNameId", courseName))))
                .andExpect(status().isOk()).andReturn());
        JsonNode square = data(mockMvc.perform(post("/api/v1/squares")
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", key + "-sq")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("schoolId", SCHOOL, "termId", TERM,
                                "teacherName", "示例教师", "slots", slots(weekday, period),
                                "courseNameId", courseName,
                                "candidateCheckToken", match.path("candidateCheckToken").asText()))))
                .andExpect(status().isOk()).andReturn());
        JsonNode assignment = data(mockMvc.perform(post("/api/v1/squares/{id}/assignments",
                                square.path("id").asText())
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", key + "-asg")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("name", "聊天修复作业", "requirements", "演示",
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
                                "name", "聊天修复组", "capacity", 3, "discussionTime", "周三",
                                "roles", List.of()))))
                .andExpect(status().isOk()).andReturn());
        String projectId = project.path("id").asText();
        JsonNode current = data(mockMvc.perform(get("/api/v1/projects/{id}", projectId)
                        .header("Authorization", bearer(leader))).andExpect(status().isOk()).andReturn());
        JsonNode invite = data(mockMvc.perform(post("/api/v1/projects/{id}/invitations", projectId)
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", key + "-inv")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("expectedProjectRevision",
                                current.path("revision").asInt(), "recipientId", MEMBER,
                                "message", "加入聊天修复"))))
                .andExpect(status().isOk()).andReturn());
        mockMvc.perform(post("/api/v1/invitations/{id}/responses",
                                invite.path("invitation").path("id").asText())
                        .header("Authorization", bearer(member)).header("Idempotency-Key", key + "-acc")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"decision\":\"ACCEPT\"}"))
                .andExpect(status().isOk());
        return projectId;
    }

    private List<Map<String, Object>> slots(int weekday, String period) {
        return List.of(Map.of("weekday", weekday, "periodId", period, "weekPattern", "ALL",
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

    private void clearAll() {
        TestSchemaCleaner.deleteAll(jdbc, List.of("chat_sync_cursor", "chat_delivery_range",
                "notification_outbox", "chat_change", "chat_message",
                "chat_read_state", "chat_counter", "topic_follow", "content_revision",
                "board_reply", "announcement_reminder", "announcement_preview", "announcement_receipt",
                "announcement_recipient", "announcement_version", "help_article", "help_feedback",
                "support_route", "support_ticket",
                "support_reply", "support_history", "operator_grant", "content_report",
                "report_handling", "announcement",
                "notification_preference", "notification", "board_topic", "dice_round", "dice_roll",
                "dice_participant", "dice_session", "report_snapshot", "delivery_release",
                "delivery_release_preview", "delivery_release_draft", "overall_reflection",
                "project_reflection",
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

    @TestConfiguration
    static class TestClockConfig {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(BASE, ZoneOffset.UTC);
        }
    }
    private List<MvcResult> concurrently(Callable<MvcResult> first, Callable<MvcResult> second)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        Callable<MvcResult> wrappedFirst = () -> {
            ready.countDown();
            go.await();
            return first.call();
        };
        Callable<MvcResult> wrappedSecond = () -> {
            ready.countDown();
            go.await();
            return second.call();
        };
        try {
            Future<MvcResult> a = pool.submit(wrappedFirst);
            Future<MvcResult> b = pool.submit(wrappedSecond);
            ready.await(5, TimeUnit.SECONDS);
            go.countDown();
            return List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

}
