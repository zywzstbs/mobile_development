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
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
@Import(SupportRound4IT.TestClockConfig.class)
class SupportRound4IT {

    private static final Instant BASE = Instant.parse("2026-10-31T00:00:00Z");
    private static final String STUDENT = "user_demo_student_01";
    private static final String TEACHER_A = "user_demo_teacher_01";
    private static final String TEACHER_B = "user_demo_teacher_02";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MutableClock clock;
    @Autowired private TransactionTemplate transactions;
    @Autowired private AdminImportService users;
    @Autowired private AcademicImportService academics;
    @Autowired private HelpCatalogImportService helpImport;
    @Autowired private ContentReportAccess reportAccess;

    @BeforeEach
    void setUp() throws Exception {
        clearAll();
        clock.setInstant(BASE);
        users.importUsers(Path.of("admin/fixtures/p01-demo-users.json"), false);
        academics.importAcademic(Path.of("admin/fixtures/p02-demo-academic.json"), false);
        jdbc.update("""
                INSERT INTO support_route(id, type, name, available, category) VALUES
                ('route_support','SUPPORT','平台使用求助',true,'OPERATION')
                ON CONFLICT (id) DO NOTHING
                """);
        jdbc.update("UPDATE help_content_pack SET version=10, updated_at=? WHERE id='GENERAL'", at(BASE));
    }

    @AfterEach
    void tearDown() {
        clearAll();
    }

    @Test
    void importedStructureMatchesHttpAndRejectsLegacyOrStaleEdits(@TempDir Path temp) throws Exception {
        Path legacy = temp.resolve("legacy.json");
        Files.writeString(legacy, """
                {"articles":[{"id":"help_legacy","audience":"GENERAL","title":"旧文","summary":"摘要",
                "body":"%s","category":"GENERAL","appVersionRange":"1.0.*","published":true}]}
                """.formatted("正".repeat(501)));
        assertThatThrownBy(() -> helpImport.importArticles(legacy, true, "cli"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("help_legacy")
                .hasMessageContaining("不能截断");
        assertThat(count("SELECT count(*) FROM help_article")).isZero();
        assertThat(packVersion()).isEqualTo(10);

        Path shortLegacy = temp.resolve("short-legacy.json");
        Files.writeString(shortLegacy, """
                {"articles":[{"id":"help_short","audience":"GENERAL","title":"短旧文","summary":"",
                "body":"只有正文","category":"GENERAL","appVersionRange":"1.0.*","published":true}]}
                """);
        assertThatThrownBy(() -> helpImport.importArticles(shortLegacy, false, "cli"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能从 body 生成步骤");
        assertThat(count("SELECT count(*) FROM help_article")).isZero();

        Path created = temp.resolve("created.json");
        Files.writeString(created, articleFile("help_struct", "结构化标题", "第一步", "原结果", true, null));
        assertThat(helpImport.importArticles(created, true, "cli").created()).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM help_article")).isZero();
        assertThat(packVersion()).isEqualTo(10);
        assertThat(helpImport.importArticles(created, false, "cli").created()).isEqualTo(1);
        assertThat(packVersion()).isEqualTo(11);
        assertThat(helpImport.importArticles(created, false, "cli").unchanged()).isEqualTo(1);
        assertThat(packVersion()).isEqualTo(11);
        assertThat(revision("help_struct")).isEqualTo(1);
        assertThat(contentVersion("help_struct")).isEqualTo(1);

        mockMvc.perform(get("/api/v1/help/articles/help_struct"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.steps[0]").value("第一步"))
                .andExpect(jsonPath("$.data.outcomes[0]").value("原结果"))
                .andExpect(jsonPath("$.data.failureAction").value("按说明重试"))
                .andExpect(jsonPath("$.data.entry.type").value("COURSE"))
                .andExpect(jsonPath("$.data.contentVersion").value(1));
        mockMvc.perform(get("/api/v1/help/content-pack"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(11))
                .andExpect(jsonPath("$.data.articles[0].steps[0]").value("第一步"));
        assertThat(jdbc.queryForObject("SELECT body FROM help_article WHERE id='help_struct'", String.class))
                .isEqualTo("第一步");
        assertThat(jdbc.queryForObject("SELECT summary FROM help_article WHERE id='help_struct'", String.class))
                .isEqualTo("按说明重试");

        jdbc.update("""
                UPDATE help_article
                SET body='旧正文已经很长不应该再被当成步骤', steps_json='["旧步骤"]'::jsonb
                WHERE id='help_struct'
                """);
        Path replaced = temp.resolve("replaced.json");
        Files.writeString(replaced, articleFile("help_struct", "结构化标题", "新步骤", "原结果", true, 1));
        helpImport.importArticles(replaced, false, "cli");
        mockMvc.perform(get("/api/v1/help/articles/help_struct"))
                .andExpect(jsonPath("$.data.steps[0]").value("新步骤"))
                .andExpect(jsonPath("$.data.contentVersion").value(2));
        mockMvc.perform(get("/api/v1/help/content-pack").param("knownVersion", "11"))
                .andExpect(jsonPath("$.data.version").value(12))
                .andExpect(jsonPath("$.data.articles[0].steps[0]").value("新步骤"));
        assertThat(jdbc.queryForObject("SELECT body FROM help_article WHERE id='help_struct'", String.class))
                .isEqualTo("新步骤");

        Path outcomes = temp.resolve("outcomes.json");
        Files.writeString(outcomes, articleFile("help_struct", "结构化标题", "新步骤", "只改结果", true, 2));
        int beforeOutcome = packVersion();
        helpImport.importArticles(outcomes, false, "cli");
        assertThat(contentVersion("help_struct")).isEqualTo(3);
        assertThat(packVersion()).isEqualTo(beforeOutcome + 1);
        mockMvc.perform(get("/api/v1/help/articles/help_struct"))
                .andExpect(jsonPath("$.data.outcomes[0]").value("只改结果"))
                .andExpect(jsonPath("$.data.steps[0]").value("新步骤"));
        assertThat(helpImport.importArticles(outcomes, false, "cli").unchanged()).isEqualTo(1);
        assertThat(packVersion()).isEqualTo(beforeOutcome + 1);
        assertThat(contentVersion("help_struct")).isEqualTo(3);

        grant(TEACHER_B, "HELP_CONTENT_WRITE", "GLOBAL", null);
        String editor = login("teacher_demo_02", "demo-password-05");
        mockMvc.perform(put("/api/v1/help/articles/help_struct")
                        .header("Authorization", bearer(editor)).header("Idempotency-Key", "r4-edit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(articleUpdate(3, "在线标题", "在线步骤")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("在线标题"));
        int editedRevision = revision("help_struct");
        int editedPack = packVersion();
        assertThatThrownBy(() -> helpImport.importArticles(created, false, "cli"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不会覆盖在线编辑");
        assertThat(jdbc.queryForObject("SELECT title FROM help_article WHERE id='help_struct'", String.class))
                .isEqualTo("在线标题");
        assertThat(jdbc.queryForObject("SELECT steps_json::text FROM help_article WHERE id='help_struct'", String.class))
                .contains("在线步骤");
        assertThat(revision("help_struct")).isEqualTo(editedRevision);
        assertThat(packVersion()).isEqualTo(editedPack);
    }

    @Test
    void onlineEditStoresTwentyFullStepsAndRejectsALongerRequest(@TempDir Path temp) throws Exception {
        Path created = temp.resolve("wide.json");
        Files.writeString(created, articleFile("help_wide", "宽正文", "一步", "结果", true, null));
        helpImport.importArticles(created, false, "cli");
        grant(TEACHER_B, "HELP_CONTENT_WRITE", "GLOBAL", null);
        String editor = login("teacher_demo_02", "demo-password-05");
        String step = "a".repeat(HelpArticleText.MAX_STEP_CHARS);
        mockMvc.perform(put("/api/v1/help/articles/help_wide")
                        .header("Authorization", bearer(editor)).header("Idempotency-Key", "r5-wide")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(articleUpdateSteps(1, "宽正文", quotedSteps(HelpArticleText.MAX_STEPS, step))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.steps.length()").value(HelpArticleText.MAX_STEPS))
                .andExpect(jsonPath("$.data.steps[19]").value(step));
        assertThat(jdbc.queryForObject("SELECT char_length(body) FROM help_article WHERE id='help_wide'", Integer.class))
                .isEqualTo(HelpArticleText.MAX_DERIVED_BODY);
        assertThat(jdbc.queryForObject("SELECT body FROM help_article WHERE id='help_wide'", String.class))
                .isEqualTo(String.join("\n", java.util.Collections.nCopies(HelpArticleText.MAX_STEPS, step)));

        mockMvc.perform(put("/api/v1/help/articles/help_wide")
                        .header("Authorization", bearer(editor)).header("Idempotency-Key", "r5-wide-21")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(articleUpdateSteps(2, "超条数", quotedSteps(HelpArticleText.MAX_STEPS + 1, "短"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(put("/api/v1/help/articles/help_wide")
                        .header("Authorization", bearer(editor)).header("Idempotency-Key", "r5-wide-501")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(articleUpdateSteps(2, "超字数", "\"" + "b".repeat(HelpArticleText.MAX_STEP_CHARS + 1) + "\"")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        assertThat(revision("help_wide")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT char_length(body) FROM help_article WHERE id='help_wide'", Integer.class))
                .isEqualTo(HelpArticleText.MAX_DERIVED_BODY);
    }

    @Test
    void failedImportRollsBackArticleAndPack(@TempDir Path temp) throws Exception {
        Path original = temp.resolve("original.json");
        Files.writeString(original, articleFile("help_r4_old", "原标题", "原步骤", "结果", true, null));
        helpImport.importArticles(original, false, "cli");
        int pack = packVersion();
        Path batch = temp.resolve("batch.json");
        Files.writeString(batch, """
                {"articles":[%s,%s]}
                """.formatted(
                article("help_r4_new", "新标题", "新步骤", "结果", true, null),
                article("help_r4_old", "导入标题", "被覆盖的步骤", "结果", true, 1)));
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = pool.submit(() -> transactions.executeWithoutResult(status -> {
                jdbc.query("SELECT id FROM help_article WHERE id=? FOR UPDATE",
                        (ResultSetExtractor<Void>) rs -> null, "help_r4_old");
                locked.countDown();
                try {
                    if (!release.await(20, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("导入没有进入行锁");
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(ex);
                }
                jdbc.update("UPDATE help_article SET revision=2, title=? WHERE id=?", "在线编辑", "help_r4_old");
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            Future<String> importer = pool.submit(() -> {
                try {
                    helpImport.importArticles(batch, false, "cli");
                    return "COMMITTED";
                } catch (RuntimeException ex) {
                    return ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
                }
            });
            boolean waiting = false;
            for (int i = 0; i < 50; i++) {
                Integer pending = jdbc.queryForObject("SELECT count(*) FROM pg_locks WHERE NOT granted", Integer.class);
                if (pending != null && pending > 0) {
                    waiting = true;
                    break;
                }
                Thread.sleep(100);
            }
            assertThat(waiting).isTrue();
            release.countDown();
            assertThat(importer.get(20, TimeUnit.SECONDS)).contains("当前修订");
            holder.get(20, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertThat(count("SELECT count(*) FROM help_article WHERE id='help_r4_new'")).isZero();
        assertThat(jdbc.queryForObject("SELECT title FROM help_article WHERE id='help_r4_old'", String.class))
                .isEqualTo("在线编辑");
        assertThat(jdbc.queryForObject("SELECT steps_json::text FROM help_article WHERE id='help_r4_old'", String.class))
                .contains("原步骤");
        assertThat(packVersion()).isEqualTo(pack);
    }

    @Test
    void ticketCreationKeepsRequestAndBusinessIdempotency() throws Exception {
        grant(TEACHER_B, "SUPPORT_HANDLE", "GLOBAL", null);
        jdbc.update("UPDATE support_route SET receiver_user_id=? WHERE id='route_support'", TEACHER_B);
        String student = login("student_demo_01", "demo-password-01");
        String helper = login("teacher_demo_02", "demo-password-05");

        JsonNode first = data(postTicket(student, "K1", "client-a", "标题A").andExpect(status().isOk()).andReturn());
        JsonNode second = data(postTicket(student, "K2", "client-b", "标题B").andExpect(status().isOk()).andReturn());
        postTicket(student, "K1", "client-b", "标题B")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(count("SELECT count(*) FROM support_ticket")).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT id FROM support_ticket WHERE client_request_id='client-b'", String.class))
                .isEqualTo(second.path("id").asText());

        postTicket(student, null, "client-a", "标题A")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
        postTicket(student, "a".repeat(129), "client-a", "标题A")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
        assertThat(count("SELECT count(*) FROM support_ticket")).isEqualTo(2);

        JsonNode replay = data(postTicket(student, "K1", "client-a", "标题A").andExpect(status().isOk()).andReturn());
        assertThat(replay.path("id").asText()).isEqualTo(first.path("id").asText());
        assertThat(count("SELECT count(*) FROM support_ticket WHERE client_request_id='client-a'")).isEqualTo(1);

        JsonNode rebound = data(postTicket(student, "K3", "client-a", "标题A").andExpect(status().isOk()).andReturn());
        assertThat(rebound.path("id").asText()).isEqualTo(first.path("id").asText());
        assertThat(count("SELECT count(*) FROM operation_receipt WHERE key='K3'")).isEqualTo(1);
        postTicket(student, "K4", "client-a", "另一标题")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CLIENT_REQUEST_ID_REUSED"));
        assertThat(count("SELECT count(*) FROM support_ticket WHERE client_request_id='client-a'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM operation_receipt WHERE key='K4'")).isZero();

        mockMvc.perform(post("/api/v1/support-tickets/{id}/acceptances", first.path("id").asText())
                        .header("Authorization", bearer(helper)).header("Idempotency-Key", "r4-accept")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedRevision\":1}"))
                .andExpect(status().isOk());
        postTicket(student, "K1", "client-a", "标题A")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.data.id").value(first.path("id").asText()));
        assertThat(jdbc.queryForObject("SELECT status FROM support_ticket WHERE id=?", String.class,
                first.path("id").asText())).isEqualTo("IN_PROGRESS");

        raceSameClient(student);
        clock.setInstant(BASE.plus(Duration.ofDays(8)));
        String later = login("student_demo_01", "demo-password-01");
        JsonNode expired = data(postTicket(later, "K2", "client-b", "标题B").andExpect(status().isOk()).andReturn());
        assertThat(expired.path("id").asText()).isEqualTo(second.path("id").asText());
        assertThat(count("SELECT count(*) FROM support_ticket WHERE client_request_id='client-b'")).isEqualTo(1);
    }

    @Test
    void reportCreationRequiresScopedReceiverAndKeepsIdempotency() throws Exception {
        String topicA = topic("a", STUDENT);
        String topicB = topic("b", STUDENT);
        String student = login("student_demo_01", "demo-password-01");
        int reports = count("SELECT count(*) FROM content_report");
        int handling = count("SELECT count(*) FROM report_handling");
        int receipts = count("SELECT count(*) FROM operation_receipt");

        postReport(student, "R0", "client-b0", topicB)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("REPORT_ROUTE_UNAVAILABLE"));
        grant(TEACHER_A, "CONTENT_REVIEW", "SQUARE", "sq_r4_a");
        postReport(student, "R1", "client-b1", topicB)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("REPORT_ROUTE_UNAVAILABLE"));
        grant(TEACHER_B, "CONTENT_REPORT_HANDLE", "SQUARE", "sq_r4_b");
        jdbc.update("UPDATE app_user SET disabled=true WHERE id=?", TEACHER_B);
        postReport(student, "R2", "client-b2", topicB)
                .andExpect(status().isServiceUnavailable());
        jdbc.update("UPDATE app_user SET disabled=false WHERE id=?", TEACHER_B);
        jdbc.update("UPDATE operator_grant SET active=false WHERE user_id=? AND capability='CONTENT_REPORT_HANDLE'",
                TEACHER_B);
        postReport(student, "R3", "client-b3", topicB)
                .andExpect(status().isServiceUnavailable());
        assertThat(count("SELECT count(*) FROM content_report")).isEqualTo(reports);
        assertThat(count("SELECT count(*) FROM report_handling")).isEqualTo(handling);
        assertThat(count("SELECT count(*) FROM operation_receipt")).isEqualTo(receipts);
        assertThat(reportAccess.lockEligibleReceiver(null)).isFalse();

        jdbc.update("UPDATE operator_grant SET active=true WHERE user_id=? AND capability='CONTENT_REPORT_HANDLE'",
                TEACHER_B);
        JsonNode created = data(postReport(student, "R4", "client-b4", topicB).andExpect(status().isOk()).andReturn());
        assertThat(created.path("status").asText()).isEqualTo("SUBMITTED");
        assertThat(count("SELECT count(*) FROM content_report")).isEqualTo(reports + 1);

        grant(TEACHER_A, "CONTENT_REVIEW", "GLOBAL", null);
        JsonNode global = data(postReport(student, "R5", "client-a5", topicA).andExpect(status().isOk()).andReturn());
        assertThat(reportAccess.lockEligibleReceiver(null)).isTrue();

        jdbc.update("UPDATE operator_grant SET active=false WHERE capability IN ('CONTENT_REPORT_HANDLE','CONTENT_REVIEW')");
        postReport(student, "R4", "client-b4", topicB)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(created.path("id").asText()))
                .andExpect(jsonPath("$.data.status").value("SUBMITTED"));
        assertThat(count("SELECT count(*) FROM content_report WHERE client_request_id='client-b4'")).isEqualTo(1);

        String helper = login("teacher_demo_01", "demo-password-03");
        jdbc.update("""
                UPDATE operator_grant SET active=true
                WHERE user_id=? AND capability='CONTENT_REVIEW' AND scope_type='GLOBAL'
                """, TEACHER_A);
        mockMvc.perform(post("/api/v1/content-reports/{id}/handling-actions", global.path("id").asText())
                        .header("Authorization", bearer(helper)).header("Idempotency-Key", "r4-report-accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"action\":\"ACCEPT\",\"note\":\"受理\"}"))
                .andExpect(status().isOk());
        postReport(student, "R5", "client-a5", topicA)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.data.id").value(global.path("id").asText()));
        assertThat(jdbc.queryForObject("SELECT status FROM content_report WHERE id=?", String.class,
                global.path("id").asText())).isEqualTo("IN_PROGRESS");
        assertThat(count("SELECT count(*) FROM content_report WHERE client_request_id='client-a5'")).isEqualTo(1);

        postReport(student, null, "client-b4", topicB)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
        postReport(student, "R6", "client-b4", topicB)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(created.path("id").asText()));
        postReport(student, "R7", "client-b4-other", topicB).andExpect(status().isOk());
        MvcResult conflict = postReport(student, "R8", "client-b4", topicA).andReturn();
        assertThat(conflict.getResponse().getStatus()).isEqualTo(409);
        assertThat(mapper.readTree(conflict.getResponse().getContentAsString()).path("code").asText())
                .isEqualTo("CLIENT_REQUEST_ID_REUSED");
        assertThat(count("SELECT count(*) FROM content_report WHERE client_request_id='client-b4'")).isEqualTo(1);

        clock.setInstant(BASE.plus(Duration.ofDays(8)));
        String later = login("student_demo_01", "demo-password-01");
        postReport(later, "R4", "client-b4", topicB)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(created.path("id").asText()));
        assertThat(count("SELECT count(*) FROM content_report WHERE client_request_id='client-b4'")).isEqualTo(1);
    }

    @Test
    void anonymousGeneralHelpUsesTheSamePageContract() throws Exception {
        List<String> expected = new ArrayList<>();
        for (int i = 25; i >= 1; i--) {
            String id = String.format("help_page_%02d", i);
            expected.add(id);
            jdbc.update("""
                    INSERT INTO help_article(id, audience, scope, square_id, title, summary, body, category,
                        app_version_range, published, revision, content_version, updated_at)
                    VALUES (?, 'GENERAL', 'GENERAL', null, ?, '', ?, 'GENERAL', '1.0.*', true, 1, 1, ?)
                    """, id, "帮助" + i, "正文" + i, at(BASE.plusSeconds(i)));
        }
        JsonNode first = data(mockMvc.perform(get("/api/v1/help/articles")).andExpect(status().isOk()).andReturn());
        assertThat(first.path("items").size()).isEqualTo(20);
        assertThat(first.path("hasMore").asBoolean()).isTrue();
        assertThat(first.path("total").asInt()).isEqualTo(25);
        assertThat(first.path("nextCursor").asText()).isNotBlank();
        assertThat(first.path("items").get(0).path("id").asText()).isEqualTo("help_page_25");

        assertThat(walk("/api/v1/help/articles", null, null)).containsExactlyElementsOf(expected);
        String student = login("student_demo_01", "demo-password-01");
        assertThat(walk("/api/v1/help/articles", student, null)).containsExactlyElementsOf(expected);

        mockMvc.perform(get("/api/v1/help/articles").param("limit", "0"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(get("/api/v1/help/articles").param("limit", "101"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(get("/api/v1/help/articles").param("limit", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_JSON"));

        String cursor = first.path("nextCursor").asText();
        mockMvc.perform(get("/api/v1/help/articles").param("cursor", cursor).param("q", "别的"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CURSOR_EXPIRED"));
        mockMvc.perform(get("/api/v1/help/articles").param("cursor", cursor).param("role", "TEACHER"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CURSOR_EXPIRED"));
        mockMvc.perform(get("/api/v1/help/articles").param("cursor", cursor).param("appVersion", "9.9.9"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CURSOR_EXPIRED"));

        JsonNode page = data(mockMvc.perform(get("/api/v1/help/articles").param("limit", "20"))
                .andExpect(status().isOk()).andReturn());
        jdbc.update("""
                INSERT INTO help_article(id, audience, scope, title, summary, body, category, app_version_range,
                    published, revision, content_version, updated_at)
                VALUES ('help_page_99','GENERAL','GENERAL','新发布','','新','GENERAL','1.0.*',true,1,1,?)
                """, at(BASE.plusSeconds(100)));
        jdbc.update("UPDATE help_article SET published=false WHERE id='help_page_03'");
        JsonNode rest = data(mockMvc.perform(get("/api/v1/help/articles")
                        .param("limit", "20").param("cursor", page.path("nextCursor").asText()))
                .andExpect(status().isOk()).andReturn());
        List<String> restIds = new ArrayList<>();
        rest.path("items").forEach(item -> restIds.add(item.path("id").asText()));
        assertThat(restIds).containsExactly("help_page_05", "help_page_04", "help_page_02", "help_page_01");
        assertThat(rest.path("total").asInt()).isEqualTo(25);
        assertThat(rest.path("hasMore").asBoolean()).isFalse();

        mockMvc.perform(get("/api/v1/help/articles").header("Authorization", bearer(student))
                        .param("cursor", page.path("nextCursor").asText()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CURSOR_EXPIRED"));

        String square = square("course");
        teacher(square, TEACHER_A);
        jdbc.update("""
                INSERT INTO help_article(id, audience, scope, square_id, title, summary, body, category,
                    app_version_range, published, revision, content_version, updated_at)
                VALUES ('help_course_1','COURSE','COURSE',?,'课程一','','课程一','COURSE','1.0.*',true,1,1,?),
                       ('help_course_2','COURSE','COURSE',?,'课程二','','课程二','COURSE','1.0.*',true,1,1,?)
                """, square, at(BASE.plusSeconds(2)), square, at(BASE.plusSeconds(1)));
        mockMvc.perform(get("/api/v1/help/articles").param("squareId", square))
                .andExpect(status().isForbidden());
        String teacher = login("teacher_demo_01", "demo-password-03");
        mockMvc.perform(get("/api/v1/help/articles").header("Authorization", bearer(teacher))
                        .param("squareId", square).param("cursor", page.path("nextCursor").asText()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CURSOR_EXPIRED"));
        assertThat(walk("/api/v1/help/articles", teacher, square)).containsExactly("help_course_1", "help_course_2");

        clock.setInstant(BASE.plus(Duration.ofMinutes(16)));
        mockMvc.perform(get("/api/v1/help/articles").param("cursor", cursor))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CURSOR_EXPIRED"));
    }

    private void raceSameClient(String student) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<MvcResult> left = pool.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return postTicket(student, "race-k1", "client-race", "同一标题").andReturn();
            });
            Future<MvcResult> right = pool.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return postTicket(student, "race-k2", "client-race", "同一标题").andReturn();
            });
            start.countDown();
            MvcResult first = left.get(20, TimeUnit.SECONDS);
            MvcResult second = right.get(20, TimeUnit.SECONDS);
            assertThat(List.of(first.getResponse().getStatus(), second.getResponse().getStatus()))
                    .containsOnly(200);
            assertThat(data(first).path("id").asText()).isEqualTo(data(second).path("id").asText());
            assertThat(count("SELECT count(*) FROM support_ticket WHERE client_request_id='client-race'")).isEqualTo(1);

            CountDownLatch again = new CountDownLatch(1);
            Future<MvcResult> sameLeft = pool.submit(() -> {
                again.await(10, TimeUnit.SECONDS);
                return postTicket(student, "race-same", "client-same-key", "并发标题").andReturn();
            });
            Future<MvcResult> sameRight = pool.submit(() -> {
                again.await(10, TimeUnit.SECONDS);
                return postTicket(student, "race-same", "client-same-key", "并发标题").andReturn();
            });
            again.countDown();
            List<MvcResult> raced = List.of(sameLeft.get(20, TimeUnit.SECONDS), sameRight.get(20, TimeUnit.SECONDS));
            Set<String> ids = new LinkedHashSet<>();
            for (MvcResult result : raced) {
                int status = result.getResponse().getStatus();
                JsonNode body = mapper.readTree(result.getResponse().getContentAsString());
                if (status == 200) {
                    ids.add(body.path("data").path("id").asText());
                } else {
                    assertThat(status).isEqualTo(409);
                    assertThat(body.path("code").asText()).isEqualTo("OPERATION_IN_PROGRESS");
                }
            }
            assertThat(ids).hasSize(1);
            assertThat(count("SELECT count(*) FROM support_ticket WHERE client_request_id='client-same-key'"))
                    .isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    private List<String> walk(String path, String token, String squareId) throws Exception {
        List<String> ids = new ArrayList<>();
        String cursor = null;
        for (int i = 0; i < 40; i++) {
            var request = get(path).param("limit", "1");
            if (token != null) {
                request.header("Authorization", bearer(token));
            }
            if (squareId != null) {
                request.param("squareId", squareId);
            }
            if (cursor != null) {
                request.param("cursor", cursor);
            }
            JsonNode page = data(mockMvc.perform(request).andExpect(status().isOk()).andReturn());
            assertThat(page.path("items").size()).isEqualTo(1);
            if (squareId == null) {
                assertThat(page.path("total").asInt()).isEqualTo(25);
            }
            if (squareId != null) {
                assertThat(page.path("total").asInt()).isEqualTo(2);
            }
            ids.add(page.path("items").get(0).path("id").asText());
            if (!page.path("hasMore").asBoolean()) {
                assertThat(page.path("nextCursor").isNull() || page.path("nextCursor").asText().isBlank()).isTrue();
                break;
            }
            cursor = page.path("nextCursor").asText();
        }
        assertThat(new LinkedHashSet<>(ids)).containsExactlyElementsOf(ids);
        return ids;
    }

    private org.springframework.test.web.servlet.ResultActions postTicket(String token, String key, String clientId,
                                                                         String title) throws Exception {
        var request = post("/api/v1/me/support-tickets")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(ticketBody(clientId, title));
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return mockMvc.perform(request);
    }

    private org.springframework.test.web.servlet.ResultActions postReport(String token, String key, String clientId,
                                                                         String topicId) throws Exception {
        var request = post("/api/v1/me/content-reports")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"clientRequestId\":\"" + clientId + "\",\"source\":{\"type\":\"BOARD_TOPIC\",\"id\":\""
                        + topicId + "\",\"version\":1},\"selectedExcerpt\":\"这段内容需要被举报\",\"reason\":\"不当内容\"}");
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return mockMvc.perform(request);
    }

    private static String ticketBody(String clientId, String title) {
        return "{\"clientRequestId\":\"" + clientId + "\",\"routeId\":\"route_support\",\"category\":\"OPERATION\","
                + "\"title\":\"" + title + "\",\"content\":\"点击后显示NETWORK_ERROR。\",\"squareId\":null,"
                + "\"context\":{\"pageType\":\"APPLICATION\",\"resourceId\":null,\"errorCode\":\"NETWORK_ERROR\","
                + "\"appVersion\":\"0.59.0\"}}";
    }

    private static String articleFile(String id, String title, String step, String outcome, boolean published,
                                      Integer expectedRevision) {
        return "{\"articles\":[" + article(id, title, step, outcome, published, expectedRevision) + "]}";
    }

    private static String article(String id, String title, String step, String outcome, boolean published,
                                  Integer expectedRevision) {
        String revision = expectedRevision == null ? "" : ",\"expectedRevision\":" + expectedRevision;
        return "{\"id\":\"" + id + "\",\"audience\":\"GENERAL\",\"title\":\"" + title + "\",\"category\":\"GENERAL\","
                + "\"roles\":[\"STUDENT\"],\"appVersionRange\":\"1.0.*\",\"prerequisites\":[],\"steps\":[\"" + step
                + "\"],\"outcomes\":[\"" + outcome + "\"],\"failureAction\":\"按说明重试\","
                + "\"entry\":{\"type\":\"COURSE\",\"id\":null},\"published\":" + published + revision + "}";
    }

    private static String articleUpdate(int revision, String title, String step) {
        return articleUpdateSteps(revision, title, "\"" + step + "\"");
    }

    private static String articleUpdateSteps(int revision, String title, String quotedSteps) {
        return "{\"expectedRevision\":" + revision + ",\"article\":{\"title\":\"" + title
                + "\",\"category\":\"GENERAL\",\"roles\":[\"STUDENT\"],\"scope\":\"GENERAL\",\"squareId\":null,"
                + "\"appVersionRange\":\"1.0.*\",\"prerequisites\":[],\"steps\":[" + quotedSteps + "],"
                + "\"outcomes\":[\"完成\"],\"failureAction\":\"重试\",\"entry\":{\"type\":\"COURSE\",\"id\":null}}}";
    }

    private static String quotedSteps(int count, String step) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(ignored -> "\"" + step + "\"")
                .collect(java.util.stream.Collectors.joining(","));
    }

    private String topic(String suffix, String userId) {
        String squareId = square(suffix);
        String assignmentId = "as_r4_" + suffix;
        String projectId = "prj_r4_" + suffix;
        String topicId = "topic_r4_" + suffix;
        jdbc.update("""
                INSERT INTO assignment(id, square_id, name, requirements, origin, confirmation_status,
                    hard_due_at, min_members, max_members)
                VALUES (?, ?, '作业', '要求说明', 'TEACHER', 'CONFIRMED', ?, 1, 6)
                """, assignmentId, squareId, at(BASE.plus(Duration.ofDays(30))));
        jdbc.update("""
                INSERT INTO project(id, assignment_id, square_id, owner_id, mode, capacity, status)
                VALUES (?, ?, ?, ?, 'QUICK', 3, 'ACTIVE')
                """, projectId, assignmentId, squareId, userId);
        jdbc.update("""
                INSERT INTO membership(id, project_id, user_id, project_role, status, color, joined_at)
                VALUES (?, ?, ?, 'LEADER', 'ACTIVE', '#336699', ?)
                """, UUID.randomUUID().toString(), projectId, userId, at(BASE));
        jdbc.update("""
                INSERT INTO board_topic(id, project_id, author_id, title, content, tag, status, revision,
                    activity_at, created_at, updated_at)
                VALUES (?, ?, ?, '题', '这段内容需要被举报', 'QUESTION', 'OPEN', 1, ?, ?, ?)
                """, topicId, projectId, userId, at(BASE), at(BASE), at(BASE));
        return topicId;
    }

    private String square(String suffix) {
        String id = "sq_r4_" + suffix;
        jdbc.update("""
                INSERT INTO square(id, school_id, term_id, name, teacher_name, slots_json, canonical_key,
                    square_no, status)
                VALUES (?, 'school_demo_01', 'term_demo_2026a', ?, '教师', '[]'::jsonb, ?, ?, 'ACTIVE')
                """, id, "第四轮" + suffix, (suffix + "d".repeat(64)).substring(0, 64), "S-R4-" + suffix);
        return id;
    }

    private void teacher(String squareId, String userId) {
        jdbc.update("""
                INSERT INTO teacher_assignment(id, square_id, user_id, active, started_at, revision)
                VALUES (?, ?, ?, true, ?, 1)
                """, UUID.randomUUID().toString(), squareId, userId, at(BASE));
    }

    private void grant(String userId, String capability, String scopeType, String scopeId) {
        jdbc.update("""
                INSERT INTO operator_grant(id, user_id, scope_type, scope_id, capability, active, created_at)
                VALUES (?, ?, ?, ?, ?, true, ?)
                """, UUID.randomUUID().toString(), userId, scopeType, scopeId, capability, at(BASE));
    }

    private int packVersion() {
        return jdbc.queryForObject("SELECT version FROM help_content_pack WHERE id='GENERAL'", Integer.class);
    }

    private int revision(String id) {
        return jdbc.queryForObject("SELECT revision FROM help_article WHERE id=?", Integer.class, id);
    }

    private int contentVersion(String id) {
        return jdbc.queryForObject("SELECT content_version FROM help_article WHERE id=?", Integer.class, id);
    }

    private int count(String sql) {
        Integer count = jdbc.queryForObject(sql, Integer.class);
        return count == null ? 0 : count;
    }

    private OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private String login(String loginId, String password) throws Exception {
        return data(mockMvc.perform(post("/api/v1/auth/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(java.util.Map.of("loginId", loginId, "password", password))))
                .andExpect(status().isOk()).andReturn()).path("accessToken").asText();
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private JsonNode data(MvcResult result) throws Exception {
        return mapper.readTree(result.getResponse().getContentAsString()).path("data");
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

    @TestConfiguration
    static class TestClockConfig {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(BASE, ZoneOffset.UTC);
        }
    }
}
