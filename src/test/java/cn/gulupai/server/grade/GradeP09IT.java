package cn.gulupai.server.grade;

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
@Import(GradeP09IT.TestClockConfig.class)
class GradeP09IT {

    private static final Instant BASE = Instant.parse("2026-10-31T00:00:00Z");
    private static final String SCHOOL = "school_demo_01";
    private static final String TERM = "term_demo_2026a";
    private static final String LEADER = "user_demo_student_01";
    private static final String MEMBER = "user_demo_student_02";
    private static final String TEACHER = "user_demo_teacher_01";
    private static final String ASSISTANT = "user_demo_assistant_01";
    private static final String EVIDENCE = """
            [{"kind":"LINK","url":"https://example.invalid/demo","title":"依据"}]""";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private MutableClock clock;
    @Autowired private AdminImportService userImportService;
    @Autowired private AcademicImportService academicImportService;

    @BeforeEach
    void setUp() throws Exception {
        clearAll();
        clock.setInstant(BASE);
        userImportService.importUsers(Path.of("admin/fixtures/p01-demo-users.json"), false);
        academicImportService.importAcademic(Path.of("admin/fixtures/p02-demo-academic.json"), false);
    }

    @AfterEach
    void tearDown() {
        clearAll();
    }

    @Test
    void documentedScorePublishesWithoutMergingIdentity() throws Exception {
        String leader = login("student_demo_01", "demo-password-01");
        String teacher = login("teacher_demo_01", "demo-password-03");
        String assistant = login("assistant_demo_01", "demo-password-04");
        Course course = course(leader, "p09-score");
        membership(course.squareId(), MEMBER);
        Fact fact = seed(course.projectId(), LEADER, "30.00", true, true, true, "2026-10-01T00:00:00Z");
        seedOrdinary(course.projectId(), LEADER);
        mockMvc.perform(get("/api/v1/assignments/{id}/students/{student}/ledger", course.assignmentId(), LEADER)
                        .header("Authorization", bearer(assistant)))
                .andExpect(status().isForbidden());
        JsonNode ledger = data(mockMvc.perform(get("/api/v1/assignments/{id}/students/{student}/ledger",
                        course.assignmentId(), LEADER).header("Authorization", bearer(teacher)))
                .andExpect(status().isOk()).andReturn());
        assertThat(ledger.get("rows")).hasSize(1);
        mockMvc.perform(post("/api/v1/assignments/{id}/students/{student}/ledger-corrections",
                        course.assignmentId(), LEADER)
                        .header("Authorization", bearer(teacher)).header("Idempotency-Key", "p09-correct")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedSourceKey":"%s","taskId":"%s","unitId":"%s",
                                 "correction":{"responsibilityPoints":"30.00","acceptedPoints":"20.00"},
                                 "reason":"验收只承认已演示部分","evidenceRefs":%s}
                                """.formatted(ledger.get("sourceKey").asText(), fact.taskId(), fact.unitId(), EVIDENCE)))
                .andExpect(status().isOk());
        JsonNode corrected = data(mockMvc.perform(get("/api/v1/assignments/{id}/students/{student}/ledger",
                        course.assignmentId(), LEADER).header("Authorization", bearer(leader)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.responsibilityPoints").value("30.00"))
                .andExpect(jsonPath("$.data.acceptedPoints").value("20.00"))
                .andExpect(jsonPath("$.data.basisStatus").value("READY"))
                .andReturn());
        String token = confirmRule(teacher, course.assignmentId(), "0.60", "p09-rule");
        JsonNode settled = data(mockMvc.perform(post("/api/v1/assignments/{id}/students/{student}/settlements",
                        course.assignmentId(), LEADER)
                        .header("Authorization", bearer(teacher)).header("Idempotency-Key", "p09-settle")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedSourceKey\":\"" + corrected.get("sourceKey").asText() + "\",\"ruleVersion\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.b").value(67))
                .andReturn());
        assertThat(token).isNotBlank();
        draft(teacher, course.assignmentId(), LEADER, 0, 80, 2, "有依据的调整", "p09-draft", "依据");
        String preview = preview(teacher, course.assignmentId(), LEADER, "p09-preview");
        mockMvc.perform(post("/api/v1/assignments/{id}/grade-publications", course.assignmentId())
                        .header("Authorization", bearer(teacher)).header("Idempotency-Key", "p09-publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"previewToken\":\"" + preview + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.grades[0].b").value(67))
                .andExpect(jsonPath("$.data.grades[0].g").value(74));
        assertThat(settled.get("id").asText()).isNotBlank();
        mockMvc.perform(get("/api/v1/me/grades").header("Authorization", bearer(leader)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1));
        mockMvc.perform(get("/api/v1/assignments/{id}/gradebook", course.assignmentId())
                        .header("Authorization", bearer(teacher)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[?(@.studentId=='" + LEADER + "')].classification").value("UNCHANGED"));
    }

    @Test
    void blockersLateSourceAndBatchStayConsistent() throws Exception {
        String leader = login("student_demo_01", "demo-password-01");
        String member = login("student_demo_02", "demo-password-02");
        String teacher = login("teacher_demo_01", "demo-password-03");
        Course course = course(leader, "p09-block");
        membership(course.squareId(), MEMBER);
        Fact unsigned = seed(course.projectId(), LEADER, "10.00", false, false, false, "2026-10-01T00:00:00Z");
        confirmRule(teacher, course.assignmentId(), "0.60", "p09-block-rule");
        JsonNode open = ledger(teacher, course.assignmentId(), LEADER);
        mockMvc.perform(post("/api/v1/assignments/{id}/students/{student}/settlements", course.assignmentId(), LEADER)
                        .header("Authorization", bearer(teacher)).header("Idempotency-Key", "p09-unsigned")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settleBody(open)))
                .andExpect(status().isUnprocessableEntity());
        jdbcTemplate.update("DELETE FROM unit_share WHERE id = ?", unsigned.shareId());
        jdbcTemplate.update("DELETE FROM delivery_unit WHERE id = ?", unsigned.unitId());
        jdbcTemplate.update("DELETE FROM agreement WHERE task_id = ?", unsigned.taskId());
        jdbcTemplate.update("DELETE FROM task WHERE id = ?", unsigned.taskId());

        Fact quiet = seed(course.projectId(), LEADER, "10.00", true, true, false, "2026-10-01T00:00:00Z");
        mockMvc.perform(post("/api/v1/assignments/{id}/students/{student}/settlements", course.assignmentId(), LEADER)
                        .header("Authorization", bearer(teacher)).header("Idempotency-Key", "p09-unconfirmed")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settleBody(ledger(teacher, course.assignmentId(), LEADER))))
                .andExpect(status().isUnprocessableEntity());
        jdbcTemplate.update("DELETE FROM unit_acceptance WHERE unit_id = ?", quiet.unitId());
        jdbcTemplate.update("DELETE FROM acceptance WHERE id = ?", quiet.acceptanceId());
        jdbcTemplate.update("DELETE FROM submission WHERE task_id = ?", quiet.taskId());

        seed(course.projectId(), LEADER, "10.00", true, false, false, "2026-12-01T00:00:00Z");
        mockMvc.perform(post("/api/v1/assignments/{id}/students/{student}/settlements", course.assignmentId(), LEADER)
                        .header("Authorization", bearer(teacher)).header("Idempotency-Key", "p09-early")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settleBody(ledger(teacher, course.assignmentId(), LEADER))))
                .andExpect(status().isUnprocessableEntity());

        Course late = course(leader, "p09-late");
        Fact missing = seed(late.projectId(), LEADER, "30.00", true, false, false, "2026-10-01T00:00:00Z");
        confirmRule(teacher, late.assignmentId(), "0.60", "p09-late-rule");
        JsonNode zero = ledger(teacher, late.assignmentId(), LEADER);
        assertThat(zero.get("acceptedPoints").asText()).isEqualTo("0.00");
        assertThat(zero.get("responsibilityPoints").asText()).isEqualTo("30.00");
        mockMvc.perform(post("/api/v1/assignments/{id}/students/{student}/settlements", late.assignmentId(), LEADER)
                        .header("Authorization", bearer(teacher)).header("Idempotency-Key", "p09-zero-accept")
                        .contentType(MediaType.APPLICATION_JSON).content(settleBody(zero)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.b").value(0));
        draft(teacher, late.assignmentId(), LEADER, 0, 80, 2, "有依据的调整", "p09-late-draft", "依据");
        publish(teacher, late.assignmentId(), LEADER, "p09-late");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT g FROM grade_snapshot WHERE assignment_id = ? AND version = 1", Integer.class, late.assignmentId()))
                .isEqualTo(34);
        passLate(missing, LEADER);
        JsonNode changed = ledger(teacher, late.assignmentId(), LEADER);
        assertThat(changed.get("sourceKey").asText()).isNotEqualTo(zero.get("sourceKey").asText());
        mockMvc.perform(post("/api/v1/assignments/{id}/students/{student}/settlements", late.assignmentId(), LEADER)
                        .header("Authorization", bearer(teacher)).header("Idempotency-Key", "p09-old-source")
                        .contentType(MediaType.APPLICATION_JSON).content(settleBody(zero)))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/api/v1/assignments/{id}/students/{student}/settlements", late.assignmentId(), LEADER)
                        .header("Authorization", bearer(teacher)).header("Idempotency-Key", "p09-new-source")
                        .contentType(MediaType.APPLICATION_JSON).content(settleBody(changed)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.b").value(100));
        draft(teacher, late.assignmentId(), LEADER, 1, 80, 2, "有依据的调整", "p09-late-draft-2", "迟交依据");
        publish(teacher, late.assignmentId(), LEADER, "p09-late-2");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT g FROM grade_snapshot WHERE assignment_id = ? AND version = 1", Integer.class, late.assignmentId()))
                .isEqualTo(34);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM grade_snapshot WHERE assignment_id = ?", Integer.class, late.assignmentId()))
                .isEqualTo(2);

        Course batch = course(leader, "p09-batch");
        membership(batch.squareId(), MEMBER);
        seed(batch.projectId(), LEADER, "30.00", true, true, true, "2026-10-01T00:00:00Z");
        Fact second = seed(batch.projectId(), MEMBER, "30.00", true, true, true, "2026-10-01T00:00:00Z");
        confirmRule(teacher, batch.assignmentId(), "0.60", "p09-batch-rule");
        settleReady(teacher, batch.assignmentId(), LEADER, "p09-batch-s1");
        settleReady(teacher, batch.assignmentId(), MEMBER, "p09-batch-s2");
        draft(teacher, batch.assignmentId(), LEADER, 0, 80, 0, "", "p09-batch-d1", "依据一");
        draft(teacher, batch.assignmentId(), MEMBER, 0, 80, 0, "", "p09-batch-d2", "依据二");
        String stalePreview = previewBoth(teacher, batch.assignmentId(), "p09-batch-preview");
        jdbcTemplate.update("UPDATE unit_share SET points = 10.00 WHERE id = ?", second.shareId());
        mockMvc.perform(post("/api/v1/assignments/{id}/grade-publications", batch.assignmentId())
                        .header("Authorization", bearer(teacher)).header("Idempotency-Key", "p09-stale")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"previewToken\":\"" + stalePreview + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PREVIEW_STALE"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM grade_snapshot WHERE assignment_id = ?", Integer.class, batch.assignmentId()))
                .isZero();

        seed(course.projectId(), MEMBER, "0.00", true, true, true, "2026-10-01T00:00:00Z");
        mockMvc.perform(post("/api/v1/assignments/{id}/students/{student}/settlements", course.assignmentId(), MEMBER)
                        .header("Authorization", bearer(teacher)).header("Idempotency-Key", "p09-denominator")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settleBody(ledger(teacher, course.assignmentId(), MEMBER))))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void revisionAppealConsentAndRevokedTeacher() throws Exception {
        String leader = login("student_demo_01", "demo-password-01");
        String teacher = login("teacher_demo_01", "demo-password-03");
        Course course = course(leader, "p09-appeal");
        seed(course.projectId(), LEADER, "30.00", true, true, true, "2026-10-01T00:00:00Z");
        confirmRule(teacher, course.assignmentId(), "0.60", "p09-appeal-rule");
        JsonNode ledger = ledger(teacher, course.assignmentId(), LEADER);
        mockMvc.perform(post("/api/v1/assignments/{id}/students/{student}/ledger-corrections",
                        course.assignmentId(), LEADER)
                        .header("Authorization", bearer(teacher)).header("Idempotency-Key", "p09-appeal-correct")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedSourceKey":"%s","taskId":"%s","unitId":"%s",
                                 "correction":{"responsibilityPoints":"30.00","acceptedPoints":"20.00"},
                                 "reason":"验收只承认已演示部分","evidenceRefs":%s}
                                """.formatted(ledger.get("sourceKey").asText(),
                                ledger.get("rows").get(0).get("taskId").asText(),
                                ledger.get("rows").get(0).get("unitId").asText(), EVIDENCE)))
                .andExpect(status().isOk());
        settleReady(teacher, course.assignmentId(), LEADER, "p09-appeal-settle");
        draft(teacher, course.assignmentId(), LEADER, 0, 80, 2, "有依据的调整", "p09-appeal-draft", "依据");
        publish(teacher, course.assignmentId(), LEADER, "p09-appeal-pub");
        String first = jdbcTemplate.queryForObject(
                "SELECT id FROM grade_snapshot WHERE assignment_id = ? AND version = 1", String.class, course.assignmentId());
        JsonNode appeal = data(mockMvc.perform(post("/api/v1/grade-snapshots/{id}/appeals", first)
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "p09-appeal-open")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"issue\":\"分数需要复核\",\"sourceRefs\":[],\"evidenceRefs\":" + EVIDENCE + "}"))
                .andExpect(status().isOk()).andReturn());
        mockMvc.perform(post("/api/v1/grade-appeals/{id}/responses", appeal.get("id").asText())
                        .header("Authorization", bearer(teacher)).header("Idempotency-Key", "p09-appeal-revise")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedRevision":1,"decision":"REVISE","reason":"同意按新依据修订",
                                 "expectedCurrentGradeSnapshotId":"%s"}
                                """.formatted(first)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("AWAITING_REVISED_PUBLICATION"));
        draft(teacher, course.assignmentId(), LEADER, 1, 80, 2, "有依据的调整", "p09-appeal-draft-2", "新依据");
        settleReady(teacher, course.assignmentId(), LEADER, "p09-appeal-settle-2");
        publish(teacher, course.assignmentId(), LEADER, "p09-appeal-pub-2");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM grade_appeal WHERE id = ?", String.class,
                appeal.get("id").asText())).isEqualTo("AWAITING_REVISED_PUBLICATION");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT g FROM grade_snapshot WHERE id = ?", Integer.class, first)).isEqualTo(74);
        draft(teacher, course.assignmentId(), LEADER, 2, 80, 2, "有依据的调整", "p09-appeal-draft-3", "再一份依据");
        String fresh = data(mockMvc.perform(post("/api/v1/assignments/{id}/grade-publication-previews", course.assignmentId())
                        .header("Authorization", bearer(teacher)).header("Idempotency-Key", "p09-appeal-link-preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"studentIds":["%s"],"appealLinks":[{"studentId":"%s","appealId":"%s"}]}
                                """.formatted(LEADER, LEADER, appeal.get("id").asText())))
                .andExpect(status().isOk()).andReturn()).get("previewToken").asText();
        mockMvc.perform(post("/api/v1/assignments/{id}/grade-publications", course.assignmentId())
                        .header("Authorization", bearer(teacher)).header("Idempotency-Key", "p09-appeal-link")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"previewToken\":\"" + fresh + "\"}"))
                .andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM grade_appeal WHERE id = ?", String.class,
                appeal.get("id").asText())).isEqualTo("RESOLVED_REVISED");
        assertThat(jdbcTemplate.queryForObject("SELECT revised_snapshot_id FROM grade_appeal WHERE id = ?",
                String.class, appeal.get("id").asText())).isNotEqualTo(first);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT g FROM grade_snapshot WHERE assignment_id = ? AND version = 3", Integer.class,
                course.assignmentId())).isEqualTo(74);

        String summaryId = data(mockMvc.perform(put("/api/v1/assignments/{id}/students/{student}/teacher-summary",
                        course.assignmentId(), LEADER)
                        .header("Authorization", bearer(teacher)).header("Idempotency-Key", "p09-summary")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedRevision":0,"sourceGradeSnapshotId":"%s","summary":"完成了可演示部分"}
                                """.formatted(first)))
                .andExpect(status().isOk()).andReturn()).get("summary").get("id").asText();
        mockMvc.perform(post("/api/v1/teacher-summaries/{id}/consents", summaryId)
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "p09-show")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"expectedContentVersion\":1,\"decision\":\"SHOW\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.consent.decision").value("SHOW"));
        String latest = jdbcTemplate.queryForObject(
                "SELECT id FROM grade_snapshot WHERE assignment_id = ? AND version = 3", String.class, course.assignmentId());
        mockMvc.perform(put("/api/v1/assignments/{id}/students/{student}/teacher-summary", course.assignmentId(), LEADER)
                        .header("Authorization", bearer(teacher)).header("Idempotency-Key", "p09-summary-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedRevision":1,"sourceGradeSnapshotId":"%s","summary":"修订后的评价"}
                                """.formatted(latest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.version").value(2));
        mockMvc.perform(get("/api/v1/teacher-summaries/{id}", summaryId).header("Authorization", bearer(leader)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.version").value(2))
                .andExpect(jsonPath("$.data.summary.consent").isEmpty());
        mockMvc.perform(post("/api/v1/teacher-summaries/{id}/withdrawals", summaryId)
                        .header("Authorization", bearer(teacher)).header("Idempotency-Key", "p09-withdraw")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":2,\"reason\":\"评价撤回不影响成绩\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.status").value("WITHDRAWN"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM grade_snapshot WHERE assignment_id = ?", Integer.class, course.assignmentId()))
                .isEqualTo(3);

        Course revoked = course(leader, "p09-revoke");
        seed(revoked.projectId(), LEADER, "30.00", true, true, true, "2026-10-01T00:00:00Z");
        confirmRule(teacher, revoked.assignmentId(), "0.60", "p09-revoke-rule");
        settleReady(teacher, revoked.assignmentId(), LEADER, "p09-revoke-settle");
        draft(teacher, revoked.assignmentId(), LEADER, 0, 80, 0, "", "p09-revoke-draft", "依据");
        String token = preview(teacher, revoked.assignmentId(), LEADER, "p09-revoke-preview");
        jdbcTemplate.update("UPDATE teacher_assignment SET active = false WHERE square_id = ? AND user_id = ?",
                revoked.squareId(), TEACHER);
        mockMvc.perform(post("/api/v1/assignments/{id}/grade-publications", revoked.assignmentId())
                        .header("Authorization", bearer(teacher)).header("Idempotency-Key", "p09-revoke-publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"previewToken\":\"" + token + "\"}"))
                .andExpect(status().isForbidden());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM grade_snapshot WHERE assignment_id = ?", Integer.class, revoked.assignmentId()))
                .isZero();
    }

    private String confirmRule(String teacher, String assignmentId, String weight, String key) throws Exception {
        int revision = jdbcTemplate.queryForObject("SELECT revision FROM assignment WHERE id = ?", Integer.class, assignmentId);
        String preview = data(mockMvc.perform(post("/api/v1/assignments/{id}/grading-rule-previews", assignmentId)
                        .header("Authorization", bearer(teacher)).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"processWeight":"%s","description":"履约与成果","reason":"确认","expectedAssignmentRevision":%d}
                                """.formatted(weight, revision)))
                .andExpect(status().isOk()).andReturn()).get("previewToken").asText();
        mockMvc.perform(post("/api/v1/assignments/{id}/grading-rules", assignmentId)
                        .header("Authorization", bearer(teacher)).header("Idempotency-Key", key + "-ok")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"previewToken\":\"" + preview + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.processWeight").value(weight));
        return preview;
    }

    private void draft(String teacher, String assignmentId, String studentId, int revision, int q, int delta,
                       String reason, String key, String title) throws Exception {
        mockMvc.perform(put("/api/v1/assignments/{id}/students/{student}/grade-draft", assignmentId, studentId)
                        .header("Authorization", bearer(teacher)).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedRevision":%d,"q":%d,"delta":%d,"deltaReason":"%s",
                                 "evidenceRefs":[{"kind":"LINK","url":"https://example.invalid/demo","title":"%s"}]}
                                """.formatted(revision, q, delta, reason, title)))
                .andExpect(status().isOk());
    }

    private String preview(String teacher, String assignmentId, String studentId, String key) throws Exception {
        return data(mockMvc.perform(post("/api/v1/assignments/{id}/grade-publication-previews", assignmentId)
                        .header("Authorization", bearer(teacher)).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentIds\":[\"" + studentId + "\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rows[0].finalGrade").exists())
                .andReturn()).get("previewToken").asText();
    }

    private String previewBoth(String teacher, String assignmentId, String key) throws Exception {
        return data(mockMvc.perform(post("/api/v1/assignments/{id}/grade-publication-previews", assignmentId)
                        .header("Authorization", bearer(teacher)).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentIds\":[\"" + LEADER + "\",\"" + MEMBER + "\"]}"))
                .andExpect(status().isOk()).andReturn()).get("previewToken").asText();
    }

    private void publish(String teacher, String assignmentId, String studentId, String key) throws Exception {
        String token = preview(teacher, assignmentId, studentId, key + "-preview");
        mockMvc.perform(post("/api/v1/assignments/{id}/grade-publications", assignmentId)
                        .header("Authorization", bearer(teacher)).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"previewToken\":\"" + token + "\"}"))
                .andExpect(status().isOk());
    }

    private void settleReady(String teacher, String assignmentId, String studentId, String key) throws Exception {
        mockMvc.perform(post("/api/v1/assignments/{id}/students/{student}/settlements", assignmentId, studentId)
                        .header("Authorization", bearer(teacher)).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settleBody(ledger(teacher, assignmentId, studentId))))
                .andExpect(status().isOk());
    }

    private JsonNode ledger(String teacher, String assignmentId, String studentId) throws Exception {
        return data(mockMvc.perform(get("/api/v1/assignments/{id}/students/{student}/ledger", assignmentId, studentId)
                        .header("Authorization", bearer(teacher))).andExpect(status().isOk()).andReturn());
    }

    private String settleBody(JsonNode ledger) {
        return "{\"expectedSourceKey\":\"" + ledger.get("sourceKey").asText() + "\",\"ruleVersion\":1}";
    }

    private void passLate(Fact fact, String studentId) {
        String submission = UUID.randomUUID().toString();
        String acceptance = UUID.randomUUID().toString();
        jdbcTemplate.update("UPDATE delivery_unit SET passed = true WHERE id = ?", fact.unitId());
        jdbcTemplate.update("""
                INSERT INTO submission
                    (id, task_id, sequence, submitted_by, status, summary, artifacts_json, unit_ids_json,
                     criteria_snapshot, due_snapshot, unit_due_snapshot_json, agreement_version, revision, submitted_at)
                VALUES (?, ?, 1, ?, 'REVIEWED', '迟交', '[]'::jsonb, '[]'::jsonb, '[]'::jsonb,
                        '2026-10-01T00:00:00Z', '{}'::jsonb, 1, 1, '2026-10-31T00:00:00Z')
                """, submission, fact.taskId(), studentId);
        jdbcTemplate.update("""
                INSERT INTO acceptance
                    (id, submission_id, reviewer_id, kind, checks_json, decision, reason, accepted_at)
                VALUES (?, ?, ?, 'INDEPENDENT', '[]'::jsonb, 'PASSED', '迟交通过', '2026-10-31T00:00:00Z')
                """, acceptance, submission, TEACHER);
        jdbcTemplate.update("""
                INSERT INTO unit_acceptance (unit_id, submission_id, acceptance_id, passed)
                VALUES (?, ?, ?, true)
                """, fact.unitId(), submission, acceptance);
        jdbcTemplate.update("""
                INSERT INTO contribution_confirmation
                    (id, unit_id, user_id, acceptance_id, submission_id, statement, at)
                VALUES (?, ?, ?, ?, ?, '确认', '2026-10-31T00:00:00Z')
                """, UUID.randomUUID().toString(), fact.unitId(), studentId, acceptance, submission);
    }

    private Fact seed(String projectId, String studentId, String points, boolean signed, boolean passed,
                      boolean confirmed, String due) {
        String stage = jdbcTemplate.queryForObject(
                "SELECT id FROM project_stage WHERE project_id = ? ORDER BY position LIMIT 1", String.class, projectId);
        String task = UUID.randomUUID().toString();
        String unit = UUID.randomUUID().toString();
        String share = UUID.randomUUID().toString();
        String agreement = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO task
                    (id, project_id, stage_id, title, description, mode, owner_id, due_at, status,
                     agreement_version, revision, created_at, updated_at, handover_blocked)
                VALUES (?, ?, ?, '登录页面', '', 'ASSESSED', ?, ?::timestamptz, 'TODO', 1, 1,
                        '2026-10-31T00:00:00Z', '2026-10-31T00:00:00Z', false)
                """, task, projectId, stage, studentId, due);
        jdbcTemplate.update("""
                INSERT INTO delivery_unit
                    (id, task_id, client_key, title, due_at, points, revision, position, passed)
                VALUES (?, ?, 'u1', '登录页面', ?::timestamptz, ?::numeric, 1, 1, ?)
                """, unit, task, due, points, passed);
        jdbcTemplate.update("""
                INSERT INTO agreement
                    (id, task_id, version, status, base_task_revision, plan_json, revision, created_at, updated_at)
                VALUES (?, ?, 1, 'ACTIVE', 1, '{}'::jsonb, 1, '2026-10-31T00:00:00Z', '2026-10-31T00:00:00Z')
                """, agreement, task);
        jdbcTemplate.update("""
                INSERT INTO unit_share
                    (id, unit_id, user_id, points, responsibility, agreement_version, signed)
                VALUES (?, ?, ?, ?::numeric, '实现', 1, ?)
                """, share, unit, studentId, points, signed);
        String acceptance = null;
        if (passed) {
            String submission = UUID.randomUUID().toString();
            acceptance = UUID.randomUUID().toString();
            jdbcTemplate.update("""
                    INSERT INTO submission
                        (id, task_id, sequence, submitted_by, status, summary, artifacts_json, unit_ids_json,
                         criteria_snapshot, due_snapshot, unit_due_snapshot_json, agreement_version, revision, submitted_at)
                    VALUES (?, ?, 1, ?, 'REVIEWED', '提交', '[]'::jsonb, '[]'::jsonb, '[]'::jsonb,
                            ?::timestamptz, '{}'::jsonb, 1, 1, '2026-10-01T00:00:00Z')
                    """, submission, task, studentId, due);
            jdbcTemplate.update("""
                    INSERT INTO acceptance
                        (id, submission_id, reviewer_id, kind, checks_json, decision, reason, accepted_at)
                    VALUES (?, ?, ?, 'INDEPENDENT', '[]'::jsonb, 'PASSED', '通过', '2026-10-01T00:00:00Z')
                    """, acceptance, submission, TEACHER);
            jdbcTemplate.update("""
                    INSERT INTO unit_acceptance (unit_id, submission_id, acceptance_id, passed)
                    VALUES (?, ?, ?, true)
                    """, unit, submission, acceptance);
            if (confirmed) {
                jdbcTemplate.update("""
                        INSERT INTO contribution_confirmation
                            (id, unit_id, user_id, acceptance_id, submission_id, statement, at)
                        VALUES (?, ?, ?, ?, ?, '确认', '2026-10-01T00:00:00Z')
                        """, UUID.randomUUID().toString(), unit, studentId, acceptance, submission);
            }
        }
        return new Fact(task, unit, share, acceptance);
    }

    private void seedOrdinary(String projectId, String studentId) {
        String stage = jdbcTemplate.queryForObject(
                "SELECT id FROM project_stage WHERE project_id = ? ORDER BY position LIMIT 1", String.class, projectId);
        String task = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO task
                    (id, project_id, stage_id, title, description, mode, owner_id, status, revision, created_at, updated_at)
                VALUES (?, ?, ?, '普通事项', '', 'ORDINARY', ?, 'TODO', 1, '2026-10-31T00:00:00Z', '2026-10-31T00:00:00Z')
                """, task, projectId, stage, studentId);
    }

    private Course course(String token, String key) throws Exception {
        int weekday = 3;
        String period = "period_demo_2";
        String courseName = "course_name_demo_mobile";
        var slots = java.util.List.of(Map.of("weekday", weekday, "periodId", period, "weekPattern", "ALL",
                "startWeek", 1, "endWeek", 16, "customWeeks", java.util.List.of()));
        JsonNode match = data(mockMvc.perform(post("/api/v1/squares/matches")
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "schoolId", SCHOOL, "termId", TERM, "teacherName", "示例教师",
                                "slots", slots, "courseNameId", courseName))))
                .andExpect(status().isOk()).andReturn());
        JsonNode square = data(mockMvc.perform(post("/api/v1/squares")
                        .header("Authorization", bearer(token)).header("Idempotency-Key", key + "-square")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "schoolId", SCHOOL, "termId", TERM, "teacherName", "示例教师",
                                "slots", slots, "courseNameId", courseName,
                                "candidateCheckToken", match.get("candidateCheckToken").asText()))))
                .andExpect(status().isOk()).andReturn());
        String teacher = login("teacher_demo_01", "demo-password-03");
        JsonNode assignment = data(mockMvc.perform(post("/api/v1/squares/{id}/assignments", square.get("id").asText())
                        .header("Authorization", bearer(token)).header("Idempotency-Key", key + "-assignment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "课程设计", "requirements", "完成", "hardDueAt", "2026-12-15T15:59:59Z",
                                "minMembers", 1, "maxMembers", 6, "sourceReferences", java.util.List.of()))))
                .andExpect(status().isOk()).andReturn());
        mockMvc.perform(post("/api/v1/assignments/{id}/confirmations", assignment.get("id").asText())
                        .header("Authorization", bearer(teacher)).header("Idempotency-Key", key + "-confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"decision\":\"CONFIRM_NEW\",\"reason\":\"ok\"}"))
                .andExpect(status().isOk());
        String project = data(mockMvc.perform(post("/api/v1/projects")
                        .header("Authorization", bearer(token)).header("Idempotency-Key", key + "-project")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "squareId", square.get("id").asText(), "assignmentId", assignment.get("id").asText(),
                                "mode", "QUICK", "name", "成绩组", "capacity", 4, "discussionTime", "周三",
                                "roles", java.util.List.of()))))
                .andExpect(status().isOk()).andReturn()).get("id").asText();
        return new Course(square.get("id").asText(), assignment.get("id").asText(), project);
    }

    private void membership(String squareId, String userId) {
        jdbcTemplate.update("""
                INSERT INTO course_membership (id, square_id, user_id, status, joined_at, revision)
                VALUES (?, ?, ?, 'ACTIVE', '2026-10-31T00:00:00Z', 1)
                ON CONFLICT ON CONSTRAINT uq_course_membership DO NOTHING
                """, UUID.randomUUID().toString(), squareId, userId);
    }

    private void clearAll() {
        for (String table : java.util.List.of(
                "teacher_summary_consent", "teacher_summary", "grade_appeal", "grade_snapshot",
                "grade_publication", "settlement", "ledger_correction", "grade_draft", "grade_rule",
                "contribution_confirmation", "unit_acceptance", "acceptance", "submission",
                "delivery_draft", "proposal_response", "unit_change_proposal",
                "reviewer_invitation", "reviewer_arrangement", "agreement_ack", "unit_share",
                "task_dependency", "criterion", "delivery_unit", "agreement", "task", "user_project_color",
                "role_ack", "role_negotiation", "announcement_reminder", "announcement_preview", "announcement_receipt",
                "announcement_recipient", "announcement_version", "help_article", "help_feedback", "support_route", "support_ticket",
                "support_reply", "support_history", "operator_grant", "content_report",
                "report_handling", "announcement",
                "notification_preference", "notification", "board_topic", "board_reply", "content_revision", "topic_follow",
                "chat_counter", "chat_message", "chat_change", "chat_read_state",
                "notification_outbox", "dice_round", "dice_roll", "dice_participant", "dice_session", "ai_reference", "peer_review_version", "peer_review",
                "work_version", "work", "project_code", "seat_reservation", "team_invitation",
                "team_application", "assignment_team_slot", "membership", "role_slot", "project_stage",
                "project", "teacher_transfer", "teacher_claim", "teacher_assignment", "class_grant",
                "course_code", "course_join_request", "merge_ballot", "square_merge", "cursor_snapshot",
                "assignment", "course_membership", "preview_token", "square",
                "verified_enrollment", "verified_course", "course_name", "period", "term",
                "operation_receipt", "access_token", "auth_session", "audit_event",
                "rate_window", "import_batch", "user_role", "user_profile", "app_user", "school")) {
            jdbcTemplate.update("DELETE FROM " + table);
        }
    }

    private String login(String loginId, String password) throws Exception {
        return data(mockMvc.perform(post("/api/v1/auth/sessions").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("loginId", loginId, "password", password))))
                .andExpect(status().isOk()).andReturn()).get("accessToken").asText();
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private JsonNode data(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
    }

    private record Course(String squareId, String assignmentId, String projectId) {
    }

    private record Fact(String taskId, String unitId, String shareId, String acceptanceId) {
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
