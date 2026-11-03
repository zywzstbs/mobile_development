package cn.gulupai.server.grade;

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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F02/R02：结算必须区分未交、待验收、待确认与未完成交接。
 * 旧实现在份额已签、截止已过、尚无通过验收时直接 accepted=0，本组用例均会失败。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(GradeFixP09IT.TestClockConfig.class)
class GradeFixP09IT {

    private static final Instant BASE = Instant.parse("2026-10-31T00:00:00Z");
    private static final String SCHOOL = "school_demo_01";
    private static final String TERM = "term_demo_2026a";
    private static final String LEADER = "user_demo_student_01";
    private static final String MEMBER = "user_demo_student_02";
    private static final String TEACHER = "user_demo_teacher_01";
    private static final String EVIDENCE = """
            [{"kind":"LINK","url":"https://example.invalid/demo","title":"依据"}]""";

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

    @Test
    void r02PendingSubmissionBlocksZeroSettlementAndPublication() throws Exception {
        String leader = login("student_demo_01", "demo-password-01");
        String teacher = login("teacher_demo_01", "demo-password-03");
        Course course = course(leader, "f02-pending");
        Fact fact = seed(course.projectId(), LEADER, "30.00", true, false, false,
                "2026-10-01T00:00:00Z", 1);
        pendingSubmission(fact.taskId(), fact.unitId(), LEADER, 1);
        confirmRule(teacher, course.assignmentId(), "0.60", "f02-pending-rule");
        JsonNode ledger = ledger(teacher, course.assignmentId(), LEADER);
        assertThat(gaps(ledger)).contains("PENDING_SUBMISSION");
        assertThat(ledger.path("acceptedPoints").asText()).isEqualTo("0.00");
        mockMvc.perform(post("/api/v1/assignments/{id}/students/{student}/settlements",
                        course.assignmentId(), LEADER)
                        .header("Authorization", bearer(teacher)).header("Idempotency-Key", "f02-pending-settle")
                        .contentType(MediaType.APPLICATION_JSON).content(settleBody(ledger)))
                .andExpect(status().isUnprocessableEntity());

        String token = data(mockMvc.perform(post("/api/v1/assignments/{id}/grade-publication-previews",
                        course.assignmentId())
                        .header("Authorization", bearer(teacher)).header("Idempotency-Key", "f02-pending-preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentIds\":[\"" + LEADER + "\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rows[0].classification").value("BLOCKED"))
                .andReturn()).path("previewToken").asText();
        mockMvc.perform(post("/api/v1/assignments/{id}/grade-publications", course.assignmentId())
                        .header("Authorization", bearer(teacher)).header("Idempotency-Key", "f02-pending-publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"previewToken\":\"" + token + "\"}"))
                .andExpect(status().isUnprocessableEntity());
        assertThat(count("grade_snapshot")).isZero();
        assertThat(token).isNotBlank();
    }

    @Test
    void r02SignedExpiredNoSubmissionMayRetainZero() throws Exception {
        String leader = login("student_demo_01", "demo-password-01");
        String teacher = login("teacher_demo_01", "demo-password-03");
        Course course = course(leader, "f02-zero");
        seed(course.projectId(), LEADER, "30.00", true, false, false, "2026-10-01T00:00:00Z", 1);
        confirmRule(teacher, course.assignmentId(), "0.60", "f02-zero-rule");
        JsonNode ledger = ledger(teacher, course.assignmentId(), LEADER);
        assertThat(gaps(ledger)).isEmpty();
        mockMvc.perform(post("/api/v1/assignments/{id}/students/{student}/settlements",
                        course.assignmentId(), LEADER)
                        .header("Authorization", bearer(teacher)).header("Idempotency-Key", "f02-zero-settle")
                        .contentType(MediaType.APPLICATION_JSON).content(settleBody(ledger)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.acceptedPoints").value("0.00"))
                .andExpect(jsonPath("$.data.b").value(0));
    }

    @Test
    void r02PassedButUnconfirmedBlocksUntilConfirmation() throws Exception {
        String leader = login("student_demo_01", "demo-password-01");
        String teacher = login("teacher_demo_01", "demo-password-03");
        Course course = course(leader, "f02-unconfirmed");
        Fact fact = seed(course.projectId(), LEADER, "30.00", true, true, false, "2026-10-01T00:00:00Z", 1);
        confirmRule(teacher, course.assignmentId(), "0.60", "f02-unconf-rule");
        JsonNode blocked = ledger(teacher, course.assignmentId(), LEADER);
        assertThat(gaps(blocked)).contains("UNCONFIRMED");
        mockMvc.perform(post("/api/v1/assignments/{id}/students/{student}/settlements",
                        course.assignmentId(), LEADER)
                        .header("Authorization", bearer(teacher)).header("Idempotency-Key", "f02-unconf-settle")
                        .contentType(MediaType.APPLICATION_JSON).content(settleBody(blocked)))
                .andExpect(status().isUnprocessableEntity());
        jdbc.update("""
                INSERT INTO contribution_confirmation
                    (id, unit_id, user_id, acceptance_id, submission_id, statement, at)
                VALUES (?, ?, ?, ?, (SELECT submission_id FROM acceptance WHERE id = ?), '确认', ?)
                """, UUID.randomUUID().toString(), fact.unitId(), LEADER, fact.acceptanceId(),
                fact.acceptanceId(), at(0));
        JsonNode ready = ledger(teacher, course.assignmentId(), LEADER);
        assertThat(ready.path("sourceKey").asText()).isNotEqualTo(blocked.path("sourceKey").asText());
        assertThat(gaps(ready)).isEmpty();
        mockMvc.perform(post("/api/v1/assignments/{id}/students/{student}/settlements",
                        course.assignmentId(), LEADER)
                        .header("Authorization", bearer(teacher)).header("Idempotency-Key", "f02-unconf-ok")
                        .contentType(MediaType.APPLICATION_JSON).content(settleBody(ready)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.b").value(100));
    }

    @Test
    void r02PendingSubmissionOnlyBlocksCoveredUnit() throws Exception {
        String leader = login("student_demo_01", "demo-password-01");
        String teacher = login("teacher_demo_01", "demo-password-03");
        Course course = course(leader, "f02-mixed");
        Fact first = seed(course.projectId(), LEADER, "10.00", true, false, false,
                "2026-10-01T00:00:00Z", 1);
        Fact second = unit(first.taskId(), LEADER, "20.00", true, "2026-10-01T00:00:00Z", 2);
        pendingSubmission(first.taskId(), second.unitId(), LEADER, 2);
        confirmRule(teacher, course.assignmentId(), "0.60", "f02-mixed-rule");
        JsonNode ledger = ledger(teacher, course.assignmentId(), LEADER);
        assertThat(ledger.path("rows").size()).isEqualTo(2);
        assertThat(gaps(ledger)).containsExactly("PENDING_SUBMISSION");
        assertThat(ledger.path("gaps").get(0).path("resourceId").asText()).isEqualTo(second.unitId());
        mockMvc.perform(post("/api/v1/assignments/{id}/students/{student}/settlements",
                        course.assignmentId(), LEADER)
                        .header("Authorization", bearer(teacher)).header("Idempotency-Key", "f02-mixed-settle")
                        .contentType(MediaType.APPLICATION_JSON).content(settleBody(ledger)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void r02OpenOrUnfinishedHandoverBlocksSettlement() throws Exception {
        String leader = login("student_demo_01", "demo-password-01");
        String teacher = login("teacher_demo_01", "demo-password-03");
        Course course = course(leader, "f02-handover");
        Fact fact = seed(course.projectId(), LEADER, "30.00", true, true, true, "2026-10-01T00:00:00Z", 1);
        String changeId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO member_change
                    (id, project_id, target_user_id, initiator_id, kind, status, plan_version,
                     source_membership_revision, revision, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'EXIT', 'READY', 1, 1, 1, ?, ?)
                """, changeId, course.projectId(), LEADER, LEADER, at(0), at(0));
        jdbc.update("""
                INSERT INTO handover_item(id, change_id, plan_version, kind, task_id, source_task_revision,
                    recipient_id, responsibility)
                VALUES (?, ?, 1, 'TASK', ?, 1, ?, '实现')
                """, UUID.randomUUID().toString(), changeId, fact.taskId(), MEMBER);
        confirmRule(teacher, course.assignmentId(), "0.60", "f02-handover-rule");
        JsonNode open = ledger(teacher, course.assignmentId(), LEADER);
        assertThat(gaps(open)).contains("HANDOVER_PENDING");
        mockMvc.perform(post("/api/v1/assignments/{id}/students/{student}/settlements",
                        course.assignmentId(), LEADER)
                        .header("Authorization", bearer(teacher)).header("Idempotency-Key", "f02-handover-settle")
                        .contentType(MediaType.APPLICATION_JSON).content(settleBody(open)))
                .andExpect(status().isUnprocessableEntity());

        // 已执行但任务仍被标记为待重签：继续阻断。
        jdbc.update("UPDATE member_change SET status='EXECUTED' WHERE id=?", changeId);
        jdbc.update("UPDATE task SET handover_blocked=true WHERE id=?", fact.taskId());
        JsonNode executed = ledger(teacher, course.assignmentId(), LEADER);
        assertThat(gaps(executed)).contains("HANDOVER_PENDING");
        assertThat(executed.path("sourceKey").asText()).isNotEqualTo(open.path("sourceKey").asText());

        // 新约定生效后清除标记，阻断消失。
        jdbc.update("UPDATE task SET handover_blocked=false WHERE id=?", fact.taskId());
        JsonNode renewed = ledger(teacher, course.assignmentId(), LEADER);
        assertThat(gaps(renewed)).doesNotContain("HANDOVER_PENDING");
        mockMvc.perform(post("/api/v1/assignments/{id}/students/{student}/settlements",
                        course.assignmentId(), LEADER)
                        .header("Authorization", bearer(teacher)).header("Idempotency-Key", "f02-handover-ok")
                        .contentType(MediaType.APPLICATION_JSON).content(settleBody(renewed)))
                .andExpect(status().isOk());
    }

    @Test
    void r02CorrectionDoesNotBypassProceduralGaps() throws Exception {
        String leader = login("student_demo_01", "demo-password-01");
        String teacher = login("teacher_demo_01", "demo-password-03");
        Course course = course(leader, "f02-correction");
        Fact fact = seed(course.projectId(), LEADER, "30.00", true, false, false,
                "2026-10-01T00:00:00Z", 1);
        pendingSubmission(fact.taskId(), fact.unitId(), LEADER, 1);
        jdbc.update("""
                INSERT INTO ledger_correction
                    (id, assignment_id, student_id, task_id, unit_id, responsibility_points,
                     accepted_points, reason, evidence_refs_json, source_key, author_id, revision, created_at)
                VALUES (?, ?, ?, ?, ?, 30.00, 20.00, '更正', ?::jsonb, 'direct', ?, 1, ?)
                """, UUID.randomUUID().toString(), course.assignmentId(), LEADER, fact.taskId(),
                fact.unitId(), EVIDENCE, TEACHER, at(0));
        confirmRule(teacher, course.assignmentId(), "0.60", "f02-correction-rule");
        JsonNode ledger = ledger(teacher, course.assignmentId(), LEADER);
        assertThat(ledger.path("acceptedPoints").asText()).isEqualTo("20.00");
        assertThat(gaps(ledger)).contains("PENDING_SUBMISSION");
        mockMvc.perform(post("/api/v1/assignments/{id}/students/{student}/settlements",
                        course.assignmentId(), LEADER)
                        .header("Authorization", bearer(teacher)).header("Idempotency-Key", "f02-correction-settle")
                        .contentType(MediaType.APPLICATION_JSON).content(settleBody(ledger)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void r02SourceKeyInvalidatesWhenSubmissionChanges() throws Exception {
        String leader = login("student_demo_01", "demo-password-01");
        String teacher = login("teacher_demo_01", "demo-password-03");
        Course course = course(leader, "f02-source");
        Fact fact = seed(course.projectId(), LEADER, "30.00", true, true, true, "2026-10-01T00:00:00Z", 1);
        confirmRule(teacher, course.assignmentId(), "0.60", "f02-source-rule");
        JsonNode before = ledger(teacher, course.assignmentId(), LEADER);
        mockMvc.perform(post("/api/v1/assignments/{id}/students/{student}/settlements",
                        course.assignmentId(), LEADER)
                        .header("Authorization", bearer(teacher)).header("Idempotency-Key", "f02-source-first")
                        .contentType(MediaType.APPLICATION_JSON).content(settleBody(before)))
                .andExpect(status().isOk());
        pendingSubmission(fact.taskId(), fact.unitId(), LEADER, 2);
        JsonNode after = ledger(teacher, course.assignmentId(), LEADER);
        assertThat(after.path("sourceKey").asText()).isNotEqualTo(before.path("sourceKey").asText());
        mockMvc.perform(post("/api/v1/assignments/{id}/students/{student}/settlements",
                        course.assignmentId(), LEADER)
                        .header("Authorization", bearer(teacher)).header("Idempotency-Key", "f02-source-old")
                        .contentType(MediaType.APPLICATION_JSON).content(settleBody(before)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SOURCE_CHANGED"));
        jdbc.update("UPDATE submission SET status='WITHDRAWN' WHERE task_id=? AND status='PENDING'", fact.taskId());
        mockMvc.perform(post("/api/v1/assignments/{id}/students/{student}/settlements",
                        course.assignmentId(), LEADER)
                        .header("Authorization", bearer(teacher)).header("Idempotency-Key", "f02-source-new")
                        .contentType(MediaType.APPLICATION_JSON).content(settleBody(ledger(teacher,
                                course.assignmentId(), LEADER))))
                .andExpect(status().isOk());
    }

    @Test
    void r02BatchPublicationIsAtomicWhenOneBlocked() throws Exception {
        String leader = login("student_demo_01", "demo-password-01");
        String member = login("student_demo_02", "demo-password-02");
        String teacher = login("teacher_demo_01", "demo-password-03");
        Course course = course(leader, "f02-batch");
        membership(course.squareId(), MEMBER);
        seed(course.projectId(), LEADER, "30.00", true, true, true, "2026-10-01T00:00:00Z", 1);
        Fact blocked = seed(course.projectId(), MEMBER, "30.00", true, false, false,
                "2026-10-01T00:00:00Z", 1);
        pendingSubmission(blocked.taskId(), blocked.unitId(), MEMBER, 1);
        confirmRule(teacher, course.assignmentId(), "0.60", "f02-batch-rule");
        String token = data(mockMvc.perform(post("/api/v1/assignments/{id}/grade-publication-previews",
                        course.assignmentId())
                        .header("Authorization", bearer(teacher)).header("Idempotency-Key", "f02-batch-preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentIds\":[\"" + LEADER + "\",\"" + MEMBER + "\"]}"))
                .andExpect(status().isOk()).andReturn()).path("previewToken").asText();
        mockMvc.perform(post("/api/v1/assignments/{id}/grade-publications", course.assignmentId())
                        .header("Authorization", bearer(teacher)).header("Idempotency-Key", "f02-batch-publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"previewToken\":\"" + token + "\"}"))
                .andExpect(status().isUnprocessableEntity());
        assertThat(count("grade_snapshot")).isZero();
        assertThat(count("grade_publication")).isZero();
    }

    @Test
    void r02DueBoundaryIsConsistent() throws Exception {
        String leader = login("student_demo_01", "demo-password-01");
        String teacher = login("teacher_demo_01", "demo-password-03");
        Course course = course(leader, "f02-due");
        String due = "2026-10-31T00:10:00Z";
        seed(course.projectId(), LEADER, "30.00", true, false, false, due, 1);
        confirmRule(teacher, course.assignmentId(), "0.60", "f02-due-rule");
        clock.setInstant(Instant.parse("2026-10-31T00:09:59.999Z"));
        assertThat(gaps(ledger(teacher, course.assignmentId(), LEADER))).contains("BEFORE_DUE");
        clock.setInstant(Instant.parse(due));
        JsonNode atDue = ledger(teacher, course.assignmentId(), LEADER);
        assertThat(gaps(atDue)).isEmpty();
        mockMvc.perform(post("/api/v1/assignments/{id}/students/{student}/settlements",
                        course.assignmentId(), LEADER)
                        .header("Authorization", bearer(teacher)).header("Idempotency-Key", "f02-due-settle")
                        .contentType(MediaType.APPLICATION_JSON).content(settleBody(atDue)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.acceptedPoints").value("0.00"));
    }


    @Test
    void s05OnlyCurrentHandoverPlanVersionBlocksAndSourceIsStable() throws Exception {
        String leader = login("student_demo_01", "demo-password-01");
        String teacher = login("teacher_demo_01", "demo-password-03");
        Course course = course(leader, "s05-plan-version");
        Fact fact = seed(course.projectId(), LEADER, "30.00", true, true, true,
                "2026-10-01T00:00:00Z", 1);
        confirmRule(teacher, course.assignmentId(), "0.60", "s05-plan-rule");
        String noBlock = ledger(teacher, course.assignmentId(), LEADER).path("sourceKey").asText();

        String change = handoverChange(course.projectId(), LEADER, 1, "READY", fact.taskId(), 1);
        JsonNode v1 = ledger(teacher, course.assignmentId(), LEADER);
        assertThat(gaps(v1)).contains("HANDOVER_PENDING");
        String withBlock = v1.path("sourceKey").asText();

        // v2 合法移除该任务：旧 v1 item 仍在数据库，但不再阻止当前账本。
        jdbc.update("UPDATE member_change SET plan_version=2 WHERE id=?", change);
        JsonNode v2 = ledger(teacher, course.assignmentId(), LEADER);
        assertThat(gaps(v2)).doesNotContain("HANDOVER_PENDING");
        assertThat(v2.path("sourceKey").asText()).isEqualTo(noBlock);

        // 同一任务另一条有效阻断：仍阻止，且摘要纳入两条来源。
        // 先让第一条存在 v2 的当前 TASK 项，再叠加第二条来源。
        jdbc.update("""
                INSERT INTO handover_item(id, change_id, plan_version, kind, task_id,
                    source_task_revision, recipient_id, responsibility)
                VALUES (?, ?, 2, 'TASK', ?, 1, ?, '实现')
                """, UUID.randomUUID().toString(), change, fact.taskId(), MEMBER);
        String second = handoverChange(course.projectId(), MEMBER, 1, "READY", fact.taskId(), 1);
        JsonNode two = ledger(teacher, course.assignmentId(), LEADER);
        assertThat(gaps(two)).contains("HANDOVER_PENDING");
        assertThat(two.path("sourceKey").asText()).isNotEqualTo(withBlock);
        assertThat(two.path("sourceKey").asText())
                .isEqualTo(ledger(teacher, course.assignmentId(), LEADER).path("sourceKey").asText());

        // 移除其中一条，另一条仍阻断，摘要随真实来源变化。
        jdbc.update("UPDATE member_change SET status='CANCELLED' WHERE id=?", second);
        JsonNode one = ledger(teacher, course.assignmentId(), LEADER);
        assertThat(gaps(one)).contains("HANDOVER_PENDING");
        assertThat(one.path("sourceKey").asText()).isNotEqualTo(two.path("sourceKey").asText());

        // 已执行且 handover_blocked=true 继续阻断；解除后该 gap 消失。
        jdbc.update("UPDATE member_change SET status='EXECUTED' WHERE id=?", change);
        jdbc.update("UPDATE task SET handover_blocked=true WHERE id=?", fact.taskId());
        assertThat(gaps(ledger(teacher, course.assignmentId(), LEADER))).contains("HANDOVER_PENDING");
        jdbc.update("UPDATE task SET handover_blocked=false WHERE id=?", fact.taskId());
        assertThat(gaps(ledger(teacher, course.assignmentId(), LEADER))).doesNotContain("HANDOVER_PENDING");
    }

    // ---- helpers ----

    private JsonNode ledger(String teacher, String assignmentId, String studentId) throws Exception {
        return data(mockMvc.perform(get("/api/v1/assignments/{id}/students/{student}/ledger",
                        assignmentId, studentId).header("Authorization", bearer(teacher)))
                .andExpect(status().isOk()).andReturn());
    }

    private List<String> gaps(JsonNode ledger) {
        List<String> codes = new java.util.ArrayList<>();
        ledger.path("gaps").forEach(item -> codes.add(item.path("code").asText()));
        return codes;
    }

    private String settleBody(JsonNode ledger) {
        return "{\"expectedSourceKey\":\"" + ledger.path("sourceKey").asText() + "\",\"ruleVersion\":1}";
    }

    private String confirmRule(String teacher, String assignmentId, String weight, String key) throws Exception {
        int revision = jdbc.queryForObject("SELECT revision FROM assignment WHERE id = ?", Integer.class, assignmentId);
        String preview = data(mockMvc.perform(post("/api/v1/assignments/{id}/grading-rule-previews", assignmentId)
                        .header("Authorization", bearer(teacher)).header("Idempotency-Key", key + "-p")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"processWeight":"%s","description":"履约","reason":"确认",
                                 "expectedAssignmentRevision":%d}
                                """.formatted(weight, revision)))
                .andExpect(status().isOk()).andReturn()).path("previewToken").asText();
        mockMvc.perform(post("/api/v1/assignments/{id}/grading-rules", assignmentId)
                        .header("Authorization", bearer(teacher)).header("Idempotency-Key", key + "-ok")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"previewToken\":\"" + preview + "\"}"))
                .andExpect(status().isOk());
        return preview;
    }

    private Fact seed(String projectId, String studentId, String points, boolean signed, boolean passed,
                      boolean confirmed, String due, int unitPosition) {
        String stage = jdbc.queryForObject(
                "SELECT id FROM project_stage WHERE project_id = ? ORDER BY position LIMIT 1",
                String.class, projectId);
        String task = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO task(id, project_id, stage_id, title, description, mode, owner_id, due_at,
                    status, agreement_version, revision, created_at, updated_at, handover_blocked)
                VALUES (?, ?, ?, '正式任务', '', 'ASSESSED', ?, ?::timestamptz, 'TODO', 1, 1, ?, ?, false)
                """, task, projectId, stage, studentId, due, at(0), at(0));
        String agreement = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO agreement(id, task_id, version, status, base_task_revision, plan_json,
                    revision, created_at, updated_at)
                VALUES (?, ?, 1, 'ACTIVE', 1, '{}'::jsonb, 1, ?, ?)
                """, agreement, task, at(0), at(0));
        Fact fact = unit(task, studentId, points, signed, due, unitPosition);
        String acceptance = null;
        if (passed) {
            String submission = UUID.randomUUID().toString();
            acceptance = UUID.randomUUID().toString();
            jdbc.update("""
                    INSERT INTO submission(id, task_id, sequence, submitted_by, status, summary,
                        artifacts_json, unit_ids_json, criteria_snapshot, due_snapshot,
                        unit_due_snapshot_json, agreement_version, revision, submitted_at)
                    VALUES (?, ?, 1, ?, 'REVIEWED', '提交', '[]'::jsonb, ?::jsonb, '[]'::jsonb,
                        ?::timestamptz, '{}'::jsonb, 1, 1, ?)
                    """, submission, task, studentId, "[\"" + fact.unitId() + "\"]", due, at(0));
            jdbc.update("""
                    INSERT INTO acceptance(id, submission_id, reviewer_id, kind, checks_json, decision,
                        reason, accepted_at)
                    VALUES (?, ?, ?, 'INDEPENDENT', '[]'::jsonb, 'PASSED', '通过', ?)
                    """, acceptance, submission, TEACHER, at(0));
            jdbc.update("""
                    INSERT INTO unit_acceptance(unit_id, submission_id, acceptance_id, passed)
                    VALUES (?, ?, ?, true)
                    """, fact.unitId(), submission, acceptance);
            jdbc.update("UPDATE delivery_unit SET passed=true WHERE id=?", fact.unitId());
            if (confirmed) {
                jdbc.update("""
                        INSERT INTO contribution_confirmation(id, unit_id, user_id, acceptance_id,
                            submission_id, statement, at)
                        VALUES (?, ?, ?, ?, ?, '确认', ?)
                        """, UUID.randomUUID().toString(), fact.unitId(), studentId, acceptance,
                        submission, at(0));
            }
        }
        return new Fact(task, fact.unitId(), fact.shareId(), acceptance);
    }

    private Fact unit(String taskId, String studentId, String points, boolean signed, String due, int position) {
        String unit = UUID.randomUUID().toString();
        String share = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO delivery_unit(id, task_id, client_key, title, due_at, points, revision,
                    position, passed)
                VALUES (?, ?, ?, ?, ?::timestamptz, ?::numeric, 1, ?, false)
                """, unit, taskId, "u" + position, "分项" + position, due, points, position);
        jdbc.update("""
                INSERT INTO unit_share(id, unit_id, user_id, points, responsibility, agreement_version, signed)
                VALUES (?, ?, ?, ?::numeric, '实现', 1, ?)
                """, share, unit, studentId, points, signed);
        return new Fact(taskId, unit, share, null);
    }

    private void pendingSubmission(String taskId, String unitId, String submitterId, int sequence) {
        jdbc.update("""
                INSERT INTO submission(id, task_id, sequence, submitted_by, status, summary,
                    artifacts_json, unit_ids_json, criteria_snapshot, due_snapshot,
                    unit_due_snapshot_json, agreement_version, revision, submitted_at)
                VALUES (?, ?, ?, ?, 'PENDING', '待验收', '[]'::jsonb, ?::jsonb, '[]'::jsonb,
                    '2026-10-01T00:00:00Z', '{}'::jsonb, 1, 1, ?)
                """, UUID.randomUUID().toString(), taskId, sequence, submitterId,
                "[\"" + unitId + "\"]", at(0));
    }

    private void membership(String squareId, String userId) {
        jdbc.update("""
                INSERT INTO course_membership(id, square_id, user_id, status, joined_at, revision)
                VALUES (?, ?, ?, 'ACTIVE', ?, 1)
                ON CONFLICT ON CONSTRAINT uq_course_membership DO NOTHING
                """, UUID.randomUUID().toString(), squareId, userId, at(0));
    }

    private java.time.OffsetDateTime at(int seconds) {
        return java.time.OffsetDateTime.ofInstant(clock.instant().plusSeconds(seconds), ZoneOffset.UTC);
    }

    private int count(String table) {
        Integer value = jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
        return value == null ? 0 : value;
    }

    private Course course(String token, String key) throws Exception {
        var slots = List.of(Map.of("weekday", 3, "periodId", "period_demo_2", "weekPattern", "ALL",
                "startWeek", 1, "endWeek", 16, "customWeeks", List.of()));
        JsonNode match = data(mockMvc.perform(post("/api/v1/squares/matches")
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("schoolId", SCHOOL, "termId", TERM,
                                "teacherName", "示例教师", "slots", slots,
                                "courseNameId", "course_name_demo_mobile"))))
                .andExpect(status().isOk()).andReturn());
        JsonNode square = data(mockMvc.perform(post("/api/v1/squares")
                        .header("Authorization", bearer(token)).header("Idempotency-Key", key + "-square")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("schoolId", SCHOOL, "termId", TERM,
                                "teacherName", "示例教师", "slots", slots,
                                "courseNameId", "course_name_demo_mobile",
                                "candidateCheckToken", match.path("candidateCheckToken").asText()))))
                .andExpect(status().isOk()).andReturn());
        String teacher = login("teacher_demo_01", "demo-password-03");
        JsonNode assignment = data(mockMvc.perform(post("/api/v1/squares/{id}/assignments",
                                square.path("id").asText())
                        .header("Authorization", bearer(token)).header("Idempotency-Key", key + "-assignment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("name", "正式成绩作业", "requirements", "完成",
                                "hardDueAt", "2026-12-15T15:59:59Z", "minMembers", 1, "maxMembers", 6,
                                "sourceReferences", List.of()))))
                .andExpect(status().isOk()).andReturn());
        mockMvc.perform(post("/api/v1/assignments/{id}/confirmations", assignment.path("id").asText())
                        .header("Authorization", bearer(teacher)).header("Idempotency-Key", key + "-confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"decision\":\"CONFIRM_NEW\",\"reason\":\"ok\"}"))
                .andExpect(status().isOk());
        JsonNode project = data(mockMvc.perform(post("/api/v1/projects")
                        .header("Authorization", bearer(token)).header("Idempotency-Key", key + "-project")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("squareId", square.path("id").asText(),
                                "assignmentId", assignment.path("id").asText(), "mode", "QUICK",
                                "name", "成绩组", "capacity", 4, "discussionTime", "周三", "roles", List.of()))))
                .andExpect(status().isOk()).andReturn());
        return new Course(square.path("id").asText(), assignment.path("id").asText(),
                project.path("id").asText());
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
        TestSchemaCleaner.deleteAll(jdbc, List.of("chat_sync_cursor", "chat_delivery_range", "chat_change", "chat_message",
                "chat_read_state", "chat_counter", "notification_outbox", "topic_follow", "content_revision",
                "board_reply", "board_topic", "announcement_reminder", "announcement_preview",
                "announcement_receipt", "announcement_recipient", "announcement_version", "help_article",
                "help_feedback", "support_route", "support_ticket", "support_reply", "support_history",
                "operator_grant", "content_report", "report_handling", "announcement",
                "notification_preference", "notification", "dice_round", "dice_roll", "dice_participant",
                "dice_session", "report_snapshot", "delivery_release", "delivery_release_preview",
                "delivery_release_draft", "overall_reflection", "project_reflection", "file_reference",
                "file_authorization", "cleanup_plan", "stored_file", "personnel_handoff",
                "material_request", "personnel_record", "personnel_case_grant", "personnel_case", "removal_ballot",
                "handover_ack", "handover_item", "member_change_event", "member_change", "removal_vote",
                "teacher_summary_consent", "teacher_summary", "grade_appeal", "grade_snapshot",
                "grade_publication", "settlement", "ledger_correction", "grade_draft", "grade_rule",
                "contribution_confirmation", "unit_acceptance", "acceptance", "submission",
                "delivery_draft", "proposal_response", "unit_change_proposal", "reviewer_invitation",
                "reviewer_arrangement", "agreement_ack", "unit_share", "task_dependency", "criterion",
                "delivery_unit", "agreement", "task", "user_project_color", "role_ack", "role_negotiation",
                "ai_reference", "peer_review_version", "peer_review", "work_version", "work",
                "project_code", "seat_reservation", "team_invitation", "team_application",
                "assignment_team_slot", "membership", "role_slot", "project_stage", "project",
                "teacher_transfer", "teacher_claim", "teacher_assignment", "class_grant", "course_code",
                "course_join_request", "cursor_snapshot", "assignment", "course_membership", "merge_ballot",
                "square_merge", "preview_token", "square", "verified_enrollment", "verified_course",
                "course_name", "period", "term", "operation_receipt", "access_token", "auth_session",
                "audit_event", "rate_window", "import_batch", "user_role", "user_profile", "app_user",
                "school"));
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
    private String handoverChange(String projectId, String targetUserId, int planVersion, String status,
                                  String taskId, int itemPlanVersion) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO member_change
                    (id, project_id, target_user_id, initiator_id, kind, status, plan_version,
                     source_membership_revision, revision, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'EXIT', ?, ?, 1, 1, ?, ?)
                """, id, projectId, targetUserId, LEADER, status, planVersion, at(0), at(0));
        jdbc.update("""
                INSERT INTO handover_item(id, change_id, plan_version, kind, task_id,
                    source_task_revision, recipient_id, responsibility)
                VALUES (?, ?, ?, 'TASK', ?, 1, ?, '实现')
                """, UUID.randomUUID().toString(), id, itemPlanVersion, taskId, MEMBER);
        return id;
    }

}
