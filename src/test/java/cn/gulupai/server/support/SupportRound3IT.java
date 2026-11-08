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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(SupportRound3IT.TestClockConfig.class)
class SupportRound3IT {

    private static final Instant BASE = Instant.parse("2026-10-31T00:00:00Z");
    private static final String TEACHER_A = "user_demo_teacher_01";
    private static final String TEACHER_B = "user_demo_teacher_02";
    private static final String STUDENT = "user_demo_student_01";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MutableClock clock;
    @Autowired private AdminImportService users;
    @Autowired private AcademicImportService academics;
    @Autowired private HelpCatalogImportService helpImport;

    @BeforeEach
    void setUp() throws Exception {
        clearAll();
        clock.setInstant(BASE);
        users.importUsers(Path.of("admin/fixtures/p01-demo-users.json"), false);
        academics.importAcademic(Path.of("admin/fixtures/p02-demo-academic.json"), false);
        jdbc.update("""
                UPDATE help_content_pack SET version=10, updated_at=? WHERE id='GENERAL'
                """, at(BASE));
    }

    @AfterEach
    void tearDown() {
        clearAll();
    }

    @Test
    void courseArticleStaysWithItsOriginalCourse() throws Exception {
        String squareA = square("a");
        String squareB = square("b");
        teacher(squareA, TEACHER_A);
        teacher(squareB, TEACHER_B);
        article("help_b", "COURSE", squareB, "课程B", 1, "1.0.*");
        article("help_general", "GENERAL", null, "通用", 1, "1.0.*");
        String teacherA = login("teacher_demo_01", "demo-password-03");
        String teacherB = login("teacher_demo_02", "demo-password-05");
        int pack = packVersion();

        mockMvc.perform(put("/api/v1/help/articles/help_b")
                        .header("Authorization", bearer(teacherA)).header("Idempotency-Key", "r3-cross-a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(articleUpdate(1, "COURSE", squareA, "被A拿走", "步骤")))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/v1/help/articles/help_b")
                        .header("Authorization", bearer(teacherA)).header("Idempotency-Key", "r3-cross-b")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(articleUpdate(1, "COURSE", squareB, "A改B", "步骤")))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/v1/help/articles/help_b")
                        .header("Authorization", bearer(teacherB)).header("Idempotency-Key", "r3-switch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(articleUpdate(1, "GENERAL", null, "改成通用", "步骤")))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(put("/api/v1/help/articles/help_b")
                        .header("Authorization", bearer(teacherB)).header("Idempotency-Key", "r3-null")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(articleUpdate(1, "COURSE", null, "去掉课程", "步骤")))
                .andExpect(status().isUnprocessableEntity());
        assertUnchanged("help_b", "课程B", squareB, 1, 1);
        assertThat(packVersion()).isEqualTo(pack);

        grant(TEACHER_B, "HELP_CONTENT_WRITE", "GLOBAL", null);
        mockMvc.perform(put("/api/v1/help/articles/help_b")
                        .header("Authorization", bearer(teacherB)).header("Idempotency-Key", "r3-owner")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(articleUpdate(1, "COURSE", squareB, "本课教师修订", "步骤")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revision").value(2))
                .andExpect(jsonPath("$.data.contentVersion").value(2))
                .andExpect(jsonPath("$.data.squareId").value(squareB));
        assertThat(jdbc.queryForObject("SELECT square_id FROM help_article WHERE id='help_b'", String.class))
                .isEqualTo(squareB);
        assertThat(packVersion()).isEqualTo(pack);

        mockMvc.perform(put("/api/v1/help/articles/help_b")
                        .header("Authorization", bearer(teacherB)).header("Idempotency-Key", "r3-owner")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(articleUpdate(1, "COURSE", squareB, "本课教师修订", "步骤")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revision").value(2));
        assertThat(revision("help_b")).isEqualTo(2);
        assertThat(contentVersion("help_b")).isEqualTo(2);

        grant(TEACHER_A, "HELP_CONTENT_WRITE", "GLOBAL", null);
        mockMvc.perform(put("/api/v1/help/articles/help_b")
                        .header("Authorization", bearer(teacherA)).header("Idempotency-Key", "r3-global-not-teacher")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(articleUpdate(2, "COURSE", squareB, "全局授权改课", "步骤")))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT title FROM help_article WHERE id='help_b'", String.class))
                .isEqualTo("本课教师修订");
        jdbc.update("""
                UPDATE operator_grant SET active=false
                WHERE user_id=? AND capability='HELP_CONTENT_WRITE' AND scope_type='GLOBAL'
                """, TEACHER_A);

        jdbc.update("UPDATE teacher_assignment SET active=false WHERE square_id=? AND user_id=?", squareB, TEACHER_B);
        mockMvc.perform(put("/api/v1/help/articles/help_b")
                        .header("Authorization", bearer(teacherB)).header("Idempotency-Key", "r3-global-course")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(articleUpdate(1, "COURSE", squareB, "本课教师修订", "步骤")))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/v1/help/articles/help_b")
                        .header("Authorization", bearer(teacherB)).header("Idempotency-Key", "r3-after-revoke")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(articleUpdate(2, "COURSE", squareB, "撤权后", "步骤")))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT title FROM help_article WHERE id='help_b'", String.class))
                .isEqualTo("本课教师修订");
        assertThat(revision("help_b")).isEqualTo(2);

        grant(TEACHER_A, "HELP_CONTENT_WRITE", "SQUARE", squareA);
        mockMvc.perform(put("/api/v1/help/articles/help_general")
                        .header("Authorization", bearer(teacherA)).header("Idempotency-Key", "r3-square-grant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(articleUpdate(1, "GENERAL", null, "广场授权改通用", "步骤")))
                .andExpect(status().isForbidden());
        assertUnchanged("help_general", "通用", null, 1, 1);

        article("help_bad", "GENERAL", null, "不一致", 1, "1.0.*");
        jdbc.update("UPDATE help_article SET scope='COURSE' WHERE id='help_bad'");
        mockMvc.perform(put("/api/v1/help/articles/help_bad")
                        .header("Authorization", bearer(teacherB)).header("Idempotency-Key", "r3-inconsistent")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(articleUpdate(1, "GENERAL", null, "按请求改范围", "步骤")))
                .andExpect(status().isUnprocessableEntity());
        assertUnchanged("help_bad", "不一致", null, 1, 1);
        assertThat(jdbc.queryForObject("SELECT scope FROM help_article WHERE id='help_bad'", String.class))
                .isEqualTo("COURSE");
    }

    @Test
    void helpEditAndFeedbackKeepIdempotentResults() throws Exception {
        article("help_general", "GENERAL", null, "原标题", 1, "1.0.*");
        grant(TEACHER_B, "HELP_CONTENT_WRITE", "GLOBAL", null);
        String editor = login("teacher_demo_02", "demo-password-05");
        String student = login("student_demo_01", "demo-password-01");
        int pack = packVersion();
        String body = articleUpdate(1, "GENERAL", null, "新标题", "新步骤");

        mockMvc.perform(put("/api/v1/help/articles/help_general")
                        .header("Authorization", bearer(editor)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
        mockMvc.perform(put("/api/v1/help/articles/help_general")
                        .header("Authorization", bearer(editor)).header("Idempotency-Key", "中文键")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        assertUnchanged("help_general", "原标题", null, 1, 1);

        mockMvc.perform(put("/api/v1/help/articles/help_general")
                        .header("Authorization", bearer(editor)).header("Idempotency-Key", "r3-edit")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("新标题"))
                .andExpect(jsonPath("$.data.revision").value(2))
                .andExpect(jsonPath("$.data.contentVersion").value(2));
        assertThat(packVersion()).isEqualTo(pack + 1);

        mockMvc.perform(put("/api/v1/help/articles/help_general")
                        .header("Authorization", bearer(editor)).header("Idempotency-Key", "r3-edit")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revision").value(2))
                .andExpect(jsonPath("$.data.contentVersion").value(2));
        assertThat(revision("help_general")).isEqualTo(2);
        assertThat(contentVersion("help_general")).isEqualTo(2);
        assertThat(packVersion()).isEqualTo(pack + 1);

        mockMvc.perform(put("/api/v1/help/articles/help_general")
                        .header("Authorization", bearer(editor)).header("Idempotency-Key", "r3-edit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(articleUpdate(2, "GENERAL", null, "另一标题", "新步骤")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(jdbc.queryForObject("SELECT title FROM help_article WHERE id='help_general'", String.class))
                .isEqualTo("新标题");
        assertThat(packVersion()).isEqualTo(pack + 1);

        String feedback = feedbackBody(2, true, "STEPS_OUTDATED");
        mockMvc.perform(put("/api/v1/help/articles/help_general/me/feedback")
                        .header("Authorization", bearer(student)).header("Idempotency-Key", "r3-fb")
                        .contentType(MediaType.APPLICATION_JSON).content(feedback))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.helpful").value(true));
        mockMvc.perform(put("/api/v1/help/articles/help_general/me/feedback")
                        .header("Authorization", bearer(student)).header("Idempotency-Key", "r3-fb")
                        .contentType(MediaType.APPLICATION_JSON).content(feedback))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.helpful").value(true));
        assertThat(feedbackCount()).isEqualTo(1);

        mockMvc.perform(put("/api/v1/help/articles/help_general/me/feedback")
                        .header("Authorization", bearer(student)).header("Idempotency-Key", "r3-fb")
                        .contentType(MediaType.APPLICATION_JSON).content(feedbackBody(2, false, "STEPS_OUTDATED")))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT helpful FROM help_feedback WHERE article_id='help_general'", Boolean.class))
                .isTrue();

        mockMvc.perform(put("/api/v1/help/articles/help_general/me/feedback")
                        .header("Authorization", bearer(student)).header("Idempotency-Key", "r3-fb-2")
                        .contentType(MediaType.APPLICATION_JSON).content(feedbackBody(2, false, "STEPS_OUTDATED")))
                .andExpect(status().isOk());
        assertThat(feedbackCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT helpful FROM help_feedback WHERE article_id='help_general'", Boolean.class))
                .isFalse();

        mockMvc.perform(put("/api/v1/help/articles/help_general")
                        .header("Authorization", bearer(editor)).header("Idempotency-Key", "r3-edit-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(articleUpdate(2, "GENERAL", null, "再改", "第二步")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.contentVersion").value(3));
        mockMvc.perform(put("/api/v1/help/articles/help_general/me/feedback")
                        .header("Authorization", bearer(student)).header("Idempotency-Key", "r3-fb-2")
                        .contentType(MediaType.APPLICATION_JSON).content(feedbackBody(2, false, "STEPS_OUTDATED")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.contentVersion").value(2));
        assertThat(feedbackCount()).isEqualTo(1);
        mockMvc.perform(put("/api/v1/help/articles/help_general/me/feedback")
                        .header("Authorization", bearer(student)).header("Idempotency-Key", "r3-fb-stale")
                        .contentType(MediaType.APPLICATION_JSON).content(feedbackBody(2, true, "STEPS_OUTDATED")))
                .andExpect(status().isConflict());
        assertThat(feedbackCount()).isEqualTo(1);

        jdbc.update("UPDATE operator_grant SET active=false WHERE user_id=? AND capability='HELP_CONTENT_WRITE'", TEACHER_B);
        MvcResult hidden = mockMvc.perform(put("/api/v1/help/articles/help_general")
                        .header("Authorization", bearer(editor)).header("Idempotency-Key", "r3-edit")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden()).andReturn();
        assertThat(hidden.getResponse().getContentAsString()).doesNotContain("新标题");
        assertThat(jdbc.queryForObject("SELECT title FROM help_article WHERE id='help_general'", String.class))
                .isEqualTo("再改");
        assertThat(packVersion()).isEqualTo(pack + 2);
    }

    @Test
    void generalPackVersionChangesWhenAnyVisibleArticleChanges(@TempDir Path temp) throws Exception {
        article("help_a", "GENERAL", null, "A", 10, "1.0.*");
        article("help_b", "GENERAL", null, "B", 1, "1.0.*");
        grant(TEACHER_B, "HELP_CONTENT_WRITE", "GLOBAL", null);
        String editor = login("teacher_demo_02", "demo-password-05");
        assertThat(packVersion()).isEqualTo(10);

        mockMvc.perform(put("/api/v1/help/articles/help_b")
                        .header("Authorization", bearer(editor)).header("Idempotency-Key", "r3-pack-b")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(articleUpdate(1, "GENERAL", null, "B2", "步骤乙")))
                .andExpect(status().isOk());
        assertThat(contentVersion("help_a")).isEqualTo(10);
        assertThat(contentVersion("help_b")).isEqualTo(2);
        assertThat(packVersion()).isEqualTo(11);
        JsonNode changed = pack("10", "1.0.0");
        assertThat(changed.path("version").asInt()).isEqualTo(11);
        assertThat(articleField(changed, "help_b", "contentVersion")).isEqualTo("2");
        mockMvc.perform(get("/api/v1/help/content-pack").param("knownVersion", "11"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.articles").isEmpty())
                .andExpect(jsonPath("$.data.version").value(11));

        mockMvc.perform(put("/api/v1/help/articles/help_b")
                        .header("Authorization", bearer(editor)).header("Idempotency-Key", "r3-pack-b")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(articleUpdate(1, "GENERAL", null, "B2", "步骤乙")))
                .andExpect(status().isOk());
        assertThat(packVersion()).isEqualTo(11);

        mockMvc.perform(put("/api/v1/help/articles/help_b")
                        .header("Authorization", bearer(editor)).header("Idempotency-Key", "r3-pack-stale")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(articleUpdate(1, "GENERAL", null, "失败", "步骤")))
                .andExpect(status().isConflict());
        assertThat(packVersion()).isEqualTo(11);
        assertThat(jdbc.queryForObject("SELECT title FROM help_article WHERE id='help_b'", String.class)).isEqualTo("B2");

        int beforeLow = packVersion();
        Path created = temp.resolve("low.json");
        Files.writeString(created, """
                {"articles":[{"id":"help_low","audience":"GENERAL","title":"低版本","category":"GENERAL",
                "roles":[],"appVersionRange":"1.0.*","prerequisites":[],"steps":["新条目"],"outcomes":[],
                "failureAction":"重试","entry":{"type":"COURSE","id":null},"published":true}]}
                """);
        assertThat(helpImport.importArticles(created, true, "cli").created()).isEqualTo(1);
        assertThat(packVersion()).isEqualTo(beforeLow);
        helpImport.importArticles(created, false, "cli");
        assertThat(packVersion()).isEqualTo(beforeLow + 1);
        assertThat(articleField(pack(String.valueOf(beforeLow), null), "help_low", "contentVersion")).isEqualTo("1");

        Path courseOnly = temp.resolve("course.json");
        String squareA = square("a");
        Files.writeString(courseOnly, """
                {"articles":[{"id":"help_course_only","audience":"COURSE","squareId":"%s","title":"课",
                "category":"COURSE","roles":[],"appVersionRange":"1.0.*","prerequisites":[],"steps":["课"],
                "outcomes":[],"failureAction":"联系教师","entry":{"type":"COURSE","id":null},"published":true}]}
                """.formatted(squareA));
        int beforeCourse = packVersion();
        helpImport.importArticles(courseOnly, false, "cli");
        assertThat(packVersion()).isEqualTo(beforeCourse);
        assertThat(jdbc.queryForObject("SELECT scope FROM help_article WHERE id='help_course_only'", String.class))
                .isEqualTo("COURSE");

        Path withdraw = temp.resolve("withdraw.json");
        Files.writeString(withdraw, """
                {"articles":[{"id":"help_b","audience":"GENERAL","title":"B2","category":"GENERAL",
                "roles":["STUDENT"],"appVersionRange":"1.0.*","prerequisites":[],"steps":["步骤乙"],
                "outcomes":["完成"],"failureAction":"重试","entry":{"type":"COURSE","id":null},
                "published":false,"expectedRevision":2}]}
                """);
        int beforeWithdraw = packVersion();
        helpImport.importArticles(withdraw, false, "cli");
        assertThat(packVersion()).isEqualTo(beforeWithdraw + 1);
        assertThat(articleField(pack(String.valueOf(beforeWithdraw), "1.0.0"), "help_b", "id")).isNull();

        article("help_filter", "GENERAL", null, "筛选", 1, "1.0.*");
        jdbc.update("UPDATE help_article SET app_version_range='1.0.*' WHERE id='help_filter'");
        int beforeRange = packVersion();
        mockMvc.perform(put("/api/v1/help/articles/help_filter")
                        .header("Authorization", bearer(editor)).header("Idempotency-Key", "r3-range")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(articleUpdate(1, "GENERAL", null, "筛选", "步骤", "9.0.0")))
                .andExpect(status().isOk());
        assertThat(packVersion()).isEqualTo(beforeRange + 1);
        assertThat(articleField(pack(String.valueOf(beforeRange), "1.0.0"), "help_filter", "id")).isNull();

        jdbc.update("DELETE FROM help_feedback");
        jdbc.update("DELETE FROM help_article");
        String firstStamp = data(mockMvc.perform(get("/api/v1/help/content-pack")).andExpect(status().isOk()).andReturn())
                .path("updatedAt").asText();
        int emptyVersion = packVersion();
        clock.setInstant(BASE.plus(Duration.ofDays(4)));
        JsonNode again = data(mockMvc.perform(get("/api/v1/help/content-pack").param("knownVersion", "1"))
                .andExpect(status().isOk()).andReturn());
        assertThat(again.path("updatedAt").asText()).isEqualTo(firstStamp);
        assertThat(again.path("version").asInt()).isEqualTo(emptyVersion).isNotEqualTo(1);
        assertThat(again.path("articles")).isEmpty();
        String laterEditor = login("teacher_demo_02", "demo-password-05");

        article("help_c1", "GENERAL", null, "C1", 1, "1.0.*");
        article("help_c2", "GENERAL", null, "C2", 1, "1.0.*");
        int concurrentStart = packVersion();
        ExecutorService pool = Executors.newFixedThreadPool(3);
        CountDownLatch start = new CountDownLatch(1);
        List<String> observations = new ArrayList<>();
        try {
            Future<Integer> left = pool.submit(() -> editStatus(laterEditor, "help_c1", "r3-c1", "C1改", start));
            Future<Integer> right = pool.submit(() -> editStatus(laterEditor, "help_c2", "r3-c2", "C2改", start));
            Future<?> reader = pool.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                for (int i = 0; i < 20; i++) {
                    observations.add(pack(null, null).toString());
                }
                return null;
            });
            start.countDown();
            assertThat(left.get(20, TimeUnit.SECONDS)).isEqualTo(200);
            assertThat(right.get(20, TimeUnit.SECONDS)).isEqualTo(200);
            reader.get(20, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        for (String raw : observations) {
            JsonNode snapshot = mapper.readTree(raw);
            boolean c1 = "C1改".equals(articleField(snapshot, "help_c1", "title"));
            boolean c2 = "C2改".equals(articleField(snapshot, "help_c2", "title"));
            int changedCount = (c1 ? 1 : 0) + (c2 ? 1 : 0);
            assertThat(snapshot.path("version").asInt() - concurrentStart).isEqualTo(changedCount);
        }
        assertThat(packVersion()).isEqualTo(concurrentStart + 2);
        assertThat(jdbc.queryForObject("SELECT title FROM help_article WHERE id='help_c1'", String.class)).isEqualTo("C1改");
        assertThat(jdbc.queryForObject("SELECT title FROM help_article WHERE id='help_c2'", String.class)).isEqualTo("C2改");
    }

    @Test
    void revokedReportHandlerCannotReadExcerpt() throws Exception {
        String squareA = square("a");
        String squareB = square("b");
        report("report_assigned", STUDENT, TEACHER_B, "IN_PROGRESS", squareB, "SECRET_EXCERPT", 1);
        grant(TEACHER_B, "CONTENT_REPORT_HANDLE", "GLOBAL", null);
        String handler = login("teacher_demo_02", "demo-password-05");
        String other = login("teacher_demo_01", "demo-password-03");
        String reporter = login("student_demo_01", "demo-password-01");

        mockMvc.perform(get("/api/v1/content-reports/report_assigned").header("Authorization", bearer(handler)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.selectedExcerpt").value("SECRET_EXCERPT"));
        jdbc.update("""
                UPDATE operator_grant SET active=false
                WHERE user_id=? AND capability='CONTENT_REPORT_HANDLE'
                """, TEACHER_B);
        MvcResult revoked = mockMvc.perform(get("/api/v1/content-reports/report_assigned")
                        .header("Authorization", bearer(handler)))
                .andExpect(status().isForbidden()).andReturn();
        assertThat(revoked.getResponse().getContentAsString()).doesNotContain("SECRET_EXCERPT");

        grant(TEACHER_B, "CONTENT_REPORT_HANDLE", "SQUARE", squareA);
        MvcResult wrongScope = mockMvc.perform(get("/api/v1/content-reports/report_assigned")
                        .header("Authorization", bearer(handler)))
                .andExpect(status().isForbidden()).andReturn();
        assertThat(wrongScope.getResponse().getContentAsString()).doesNotContain("SECRET_EXCERPT");

        grant(TEACHER_A, "CONTENT_REPORT_HANDLE", "GLOBAL", null);
        MvcResult unassigned = mockMvc.perform(get("/api/v1/content-reports/report_assigned")
                        .header("Authorization", bearer(other)))
                .andExpect(status().isForbidden()).andReturn();
        assertThat(unassigned.getResponse().getContentAsString()).doesNotContain("SECRET_EXCERPT");

        mockMvc.perform(get("/api/v1/content-reports/report_assigned").header("Authorization", bearer(reporter)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.selectedExcerpt").value("SECRET_EXCERPT"));

        jdbc.update("UPDATE app_user SET disabled=true, account_status='DISABLED' WHERE id=?", STUDENT);
        MvcResult disabled = mockMvc.perform(get("/api/v1/content-reports/report_assigned")
                        .header("Authorization", bearer(reporter)))
                .andExpect(status().isUnauthorized()).andReturn();
        assertThat(disabled.getResponse().getContentAsString()).doesNotContain("SECRET_EXCERPT");
        assertThat(jdbc.queryForObject("SELECT assignee_id FROM content_report WHERE id='report_assigned'", String.class))
                .isEqualTo(TEACHER_B);
    }

    @Test
    void reportHandlingReplaysAndRejectsIllegalTransitions() throws Exception {
        report("report_new", STUDENT, null, "SUBMITTED", null, "片段", 1);
        grant(TEACHER_B, "CONTENT_REVIEW", "GLOBAL", null);
        String handler = login("teacher_demo_02", "demo-password-05");

        mockMvc.perform(post("/api/v1/content-reports/report_new/handling-actions")
                        .header("Authorization", bearer(handler)).header("Idempotency-Key", "r3-result-early")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reportAction(1, "RECORD_RESULT", "还没受理")))
                .andExpect(status().isForbidden());
        assertThat(reportStatus("report_new")).isEqualTo("SUBMITTED");
        assertThat(handlingCount("report_new")).isZero();

        jdbc.update("UPDATE content_report SET assignee_id=? WHERE id='report_new'", TEACHER_B);
        mockMvc.perform(post("/api/v1/content-reports/report_new/handling-actions")
                        .header("Authorization", bearer(handler)).header("Idempotency-Key", "r3-result-unaccepted")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reportAction(1, "RECORD_RESULT", "仍未受理")))
                .andExpect(status().isConflict());
        assertThat(reportStatus("report_new")).isEqualTo("SUBMITTED");
        assertThat(handlingCount("report_new")).isZero();
        jdbc.update("UPDATE content_report SET assignee_id=NULL WHERE id='report_new'");

        mockMvc.perform(post("/api/v1/content-reports/report_new/handling-actions")
                        .header("Authorization", bearer(handler)).header("Idempotency-Key", "r3-accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reportAction(1, "ACCEPT", "受理")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"));
        mockMvc.perform(post("/api/v1/content-reports/report_new/handling-actions")
                        .header("Authorization", bearer(handler)).header("Idempotency-Key", "r3-record")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reportAction(2, "RECORD_RESULT", "已记录")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("HANDLED"));
        mockMvc.perform(post("/api/v1/content-reports/report_new/handling-actions")
                        .header("Authorization", bearer(handler)).header("Idempotency-Key", "r3-close")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reportAction(3, "CLOSE", "关闭")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CLOSED"));
        assertThat(handlingCount("report_new")).isEqualTo(3);
        String refs = jdbc.queryForObject("""
                SELECT resource_refs_json::text FROM operation_receipt
                WHERE "key"='r3-close'
                """, String.class);
        assertThat(refs).contains("CONTENT_REPORT").doesNotContain("SUPPORT_TICKET");

        mockMvc.perform(post("/api/v1/content-reports/report_new/handling-actions")
                        .header("Authorization", bearer(handler)).header("Idempotency-Key", "r3-close")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reportAction(3, "CLOSE", "关闭")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CLOSED"));
        assertThat(handlingCount("report_new")).isEqualTo(3);

        mockMvc.perform(post("/api/v1/content-reports/report_new/handling-actions")
                        .header("Authorization", bearer(handler)).header("Idempotency-Key", "r3-reopen")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reportAction(4, "ACCEPT", "重开")))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/api/v1/content-reports/report_new/handling-actions")
                        .header("Authorization", bearer(handler)).header("Idempotency-Key", "r3-rerecord")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reportAction(4, "RECORD_RESULT", "再记")))
                .andExpect(status().isConflict());
        assertThat(reportStatus("report_new")).isEqualTo("CLOSED");
        assertThat(revisionOfReport("report_new")).isEqualTo(4);
        assertThat(handlingCount("report_new")).isEqualTo(3);

        jdbc.update("UPDATE operator_grant SET active=false WHERE user_id=? AND capability='CONTENT_REVIEW'", TEACHER_B);
        MvcResult replay = mockMvc.perform(post("/api/v1/content-reports/report_new/handling-actions")
                        .header("Authorization", bearer(handler)).header("Idempotency-Key", "r3-close")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reportAction(3, "CLOSE", "关闭")))
                .andExpect(status().isForbidden()).andReturn();
        assertThat(replay.getResponse().getContentAsString()).doesNotContain("片段");
        assertThat(handlingCount("report_new")).isEqualTo(3);

        report("report_race", STUDENT, null, "SUBMITTED", null, "竞争片段", 1);
        grant(TEACHER_B, "CONTENT_REPORT_HANDLE", "GLOBAL", null);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Integer> left = pool.submit(() -> handleStatus(handler, "report_race", "r3-race-1", start));
            Future<Integer> right = pool.submit(() -> handleStatus(handler, "report_race", "r3-race-2", start));
            start.countDown();
            List<Integer> statuses = new ArrayList<>(List.of(
                    left.get(20, TimeUnit.SECONDS), right.get(20, TimeUnit.SECONDS)));
            statuses.sort(Integer::compareTo);
            assertThat(statuses).containsExactly(200, 409);
        } finally {
            pool.shutdownNow();
        }
        assertThat(reportStatus("report_race")).isEqualTo("IN_PROGRESS");
        assertThat(revisionOfReport("report_race")).isEqualTo(2);
        assertThat(handlingCount("report_race")).isEqualTo(1);
    }

    private JsonNode pack(String knownVersion, String appVersion) throws Exception {
        var request = get("/api/v1/help/content-pack");
        if (knownVersion != null) {
            request.param("knownVersion", knownVersion);
        }
        if (appVersion != null) {
            request.param("appVersion", appVersion);
        }
        return data(mockMvc.perform(request).andExpect(status().isOk()).andReturn());
    }

    private String articleField(JsonNode pack, String id, String field) {
        for (JsonNode article : pack.path("articles")) {
            if (id.equals(article.path("id").asText())) {
                return article.path(field).asText();
            }
        }
        return null;
    }

    private int editStatus(String token, String id, String key, String title, CountDownLatch start) throws Exception {
        start.await(10, TimeUnit.SECONDS);
        return mockMvc.perform(put("/api/v1/help/articles/{id}", id)
                        .header("Authorization", bearer(token)).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(articleUpdate(1, "GENERAL", null, title, "步骤")))
                .andReturn().getResponse().getStatus();
    }

    private int handleStatus(String token, String id, String key, CountDownLatch start) throws Exception {
        start.await(10, TimeUnit.SECONDS);
        return mockMvc.perform(post("/api/v1/content-reports/{id}/handling-actions", id)
                        .header("Authorization", bearer(token)).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reportAction(1, "ACCEPT", "受理")))
                .andReturn().getResponse().getStatus();
    }

    private void assertUnchanged(String id, String title, String squareId, int revision, int contentVersion) {
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT title, square_id, revision, content_version FROM help_article WHERE id=?
                """, id);
        assertThat(row.get("title")).isEqualTo(title);
        assertThat(row.get("square_id")).isEqualTo(squareId);
        assertThat(((Number) row.get("revision")).intValue()).isEqualTo(revision);
        assertThat(((Number) row.get("content_version")).intValue()).isEqualTo(contentVersion);
    }

    private String articleUpdate(int revision, String scope, String squareId, String title, String step) {
        return articleUpdate(revision, scope, squareId, title, step, "1.0.*");
    }

    private String articleUpdate(int revision, String scope, String squareId, String title, String step, String range) {
        String square = squareId == null ? "null" : "\"" + squareId + "\"";
        return "{\"expectedRevision\":" + revision + ",\"article\":{\"title\":\"" + title
                + "\",\"category\":\"GENERAL\",\"roles\":[\"STUDENT\"],\"scope\":\"" + scope + "\",\"squareId\":" + square
                + ",\"appVersionRange\":\"" + range + "\",\"prerequisites\":[],\"steps\":[\"" + step + "\"],"
                + "\"outcomes\":[\"完成\"],\"failureAction\":\"重试\",\"entry\":{\"type\":\"COURSE\",\"id\":null}}}";
    }

    private String feedbackBody(int contentVersion, boolean helpful, String reason) {
        return "{\"contentVersion\":" + contentVersion + ",\"helpful\":" + helpful + ",\"reasonCode\":\"" + reason + "\"}";
    }

    private String reportAction(int revision, String action, String note) {
        return "{\"expectedRevision\":" + revision + ",\"action\":\"" + action + "\",\"note\":\"" + note + "\"}";
    }

    private void article(String id, String audience, String squareId, String title, int contentVersion, String range) {
        jdbc.update("""
                INSERT INTO help_article(id, audience, scope, square_id, title, summary, body, category,
                    app_version_range, published, revision, content_version, updated_at)
                VALUES (?, ?, ?, ?, ?, '', ?, 'GENERAL', ?, true, 1, ?, ?)
                """, id, audience, audience, squareId, title, title, range, contentVersion, at(BASE));
    }

    private void report(String id, String reporter, String assignee, String status, String squareId,
                        String excerpt, int revision) {
        jdbc.update("""
                INSERT INTO content_report(id, reporter_id, client_request_id, content_type, content_id,
                    source_version, excerpt, reason, status, assignee_id, created_at, updated_at, revision, square_id)
                VALUES (?, ?, ?, 'BOARD_TOPIC', 'topic_x', 1, ?, '原因说明', ?, ?, ?, ?, ?, ?)
                """, id, reporter, "client-" + id, excerpt, status, assignee, at(BASE), at(BASE), revision, squareId);
    }

    private String square(String suffix) {
        String id = "sq_r3_" + suffix;
        jdbc.update("""
                INSERT INTO square(id, school_id, term_id, name, teacher_name, slots_json,
                    canonical_key, square_no, status)
                VALUES (?, 'school_demo_01', 'term_demo_2026a', ?, '教师', '[]'::jsonb, ?, ?, 'ACTIVE')
                """, id, "第三轮" + suffix, (suffix + "c".repeat(64)).substring(0, 64), "S-R3-" + suffix);
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

    private int feedbackCount() {
        return jdbc.queryForObject("SELECT count(*) FROM help_feedback", Integer.class);
    }

    private String reportStatus(String id) {
        return jdbc.queryForObject("SELECT status FROM content_report WHERE id=?", String.class, id);
    }

    private int revisionOfReport(String id) {
        return jdbc.queryForObject("SELECT revision FROM content_report WHERE id=?", Integer.class, id);
    }

    private int handlingCount(String id) {
        return jdbc.queryForObject("SELECT count(*) FROM report_handling WHERE report_id=?", Integer.class, id);
    }

    private OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
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
