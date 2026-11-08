package cn.gulupai.server.delivery;

import cn.gulupai.server.it.TestSchemaCleaner;
import cn.gulupai.server.common.clock.MutableClock;
import cn.gulupai.server.common.idempotency.CanonicalJson;
import cn.gulupai.server.common.idempotency.IdempotencyCipher;
import cn.gulupai.server.identity.cli.AcademicImportService;
import cn.gulupai.server.identity.cli.AdminImportService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.util.ArrayList;
import java.util.Comparator;
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
@Import(DeliveryP06IT.TestClockConfig.class)
class DeliveryP06IT {

    private static final Instant BASE = Instant.parse("2026-10-31T00:00:00Z");
    private static final String SCHOOL = "school_demo_01";
    private static final String TERM = "term_demo_2026a";
    private static final String LEADER_ID = "user_demo_student_01";
    private static final String MEMBER_ID = "user_demo_student_02";
    private static final String ASSISTANT_ID = "user_demo_assistant_01";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private MutableClock clock;
    @Autowired private AdminImportService userImportService;
    @Autowired private AcademicImportService academicImportService;
    @Autowired private IdempotencyCipher cipher;
    @Autowired private CanonicalJson canonical;

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
    void rejectsInvalidPlansWithoutWriting() throws Exception {
        String leader = login("student_demo_01", "demo-password-01");
        String assistant = login("assistant_demo_01", "demo-password-04");
        Project project = project(leader, "p06-invalid");
        invite(leader, project.id(), ASSISTANT_ID, "p06-invalid-invite");
        acceptLatestInvitation(assistant, project.id(), "p06-invalid-accept");
        int before = taskCount(project.id());
        postTask(leader, project, plan(project, LEADER_ID, "INDEPENDENT", oneUnit(LEADER_ID, "10.00")), "p06-self-as-independent")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SELF_REVIEW_FORBIDDEN"));
        postTask(leader, project, plan(project, LEADER_ID, "BOGUS", oneUnit(LEADER_ID, "10.00")), "p06-bad-kind")
                .andExpect(status().isUnprocessableEntity());
        postTask(leader, project, plan(project, ASSISTANT_ID, "SELF_CHECK", oneUnit(LEADER_ID, "10.00")), "p06-kind-mismatch")
                .andExpect(status().isUnprocessableEntity());
        ObjectNode withCriteria = plan(project, ASSISTANT_ID, "INDEPENDENT", oneUnit(LEADER_ID, "10.00"));
        withCriteria.set("criteria", objectMapper.valueToTree(List.of(Map.of("text", "不能放在顶层"))));
        postTask(leader, project, withCriteria, "p06-top-criteria").andExpect(status().isUnprocessableEntity());
        postTask(leader, project, plan(project, ASSISTANT_ID, "INDEPENDENT", oneUnit(LEADER_ID, "4.00")), "p06-points")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("POINTS_INVALID"));
        assertThat(taskCount(project.id())).isEqualTo(before);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM agreement", Integer.class)).isZero();
    }

    @Test
    void selfCheckDoesNotScoreUntilPersonalConfirmation() throws Exception {
        String leader = login("student_demo_01", "demo-password-01");
        String stranger = login("student_demo_02", "demo-password-02");
        Project project = project(leader, "p06-self");
        JsonNode task = data(postTask(leader, project, plan(project, LEADER_ID, "SELF_CHECK", oneUnit(LEADER_ID, "10.00")),
                "p06-self-task").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mode").value("ASSESSED"))
                .andExpect(jsonPath("$.data.agreementStatus").value("PENDING"))
                .andExpect(jsonPath("$.data.claimable").value(false))
                .andExpect(jsonPath("$.data.reviewerAssignment.kind").value("SELF_CHECK"))
                .andExpect(jsonPath("$.data.units[0].shares[0].accepted").value(false))
                .andReturn());
        String agreementId = jdbcTemplate.queryForObject(
                "SELECT id FROM agreement WHERE task_id = ?", String.class, task.get("id").asText());
        // L03：任务详情里的 agreementId 必须指向以该 task_id 创建的约定，且不是任务 ID 本身。
        assertThat(task.get("agreementId").asText()).isEqualTo(agreementId)
                .isNotEqualTo(task.get("id").asText());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT task_id FROM agreement WHERE id = ?", String.class, agreementId))
                .isEqualTo(task.get("id").asText());
        mockMvc.perform(get("/api/v1/agreements/{id}", agreementId).header("Authorization", bearer(stranger)))
                .andExpect(status().isForbidden());
        JsonNode agreement = data(mockMvc.perform(get("/api/v1/agreements/{id}", agreementId)
                        .header("Authorization", bearer(leader)))
                .andExpect(status().isOk()).andReturn());
        assertThat(agreement.get("reviewerPolicy").get("kind").asText()).isEqualTo("SELF_CHECK");
        assertThat(agreement.get("ownerId").asText()).isEqualTo(LEADER_ID);
        ack(leader, agreementId, 1, "p06-self-ack");
        JsonNode active = data(mockMvc.perform(get("/api/v1/tasks/{id}", task.get("id").asText())
                        .header("Authorization", bearer(leader))).andExpect(status().isOk()).andReturn());
        assertThat(active.get("agreementStatus").asText()).isEqualTo("ACTIVE");
        assertThat(active.get("units").get(0).get("shares").get(0).get("accepted").asBoolean()).isTrue();
        String unitId = active.get("units").get(0).get("id").asText();
        String criterionId = active.get("units").get(0).get("criteria").get(0).get("id").asText();
        mockMvc.perform(get("/api/v1/tasks/{id}/me/draft", task.get("id").asText())
                        .header("Authorization", bearer(leader)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").value(nullValue()));
        saveDraft(leader, task.get("id").asText(), 0, List.of(unitId), active.get("revision").asInt(), 1, "p06-self-draft");
        JsonNode submission = submit(leader, task.get("id").asText(), active.get("revision").asInt(),
                List.of(unitId), 1, 1, "p06-self-submit");
        mockMvc.perform(post("/api/v1/submissions/{id}/self-checks", submission.get("id").asText())
                        .header("Authorization", bearer(leader))
                        .header("Idempotency-Key", "p06-self-check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(review(submission.get("revision").asInt(), List.of(unitId),
                                List.of(check(unitId, criterionId, true)), List.of(), "组里没有其他成员")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.acceptance.kind").value("SELF_CHECK"))
                .andExpect(jsonPath("$.data.taskStatus").value("DONE"));
        assertThat(confirmations(unitId)).isZero();
        mockMvc.perform(post("/api/v1/tasks/{taskId}/units/{unitId}/me/contribution-confirmations",
                        task.get("id").asText(), unitId)
                        .header("Authorization", bearer(stranger))
                        .header("Idempotency-Key", "p06-stranger-confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(contribution(taskRevision(leader, task.get("id").asText()),
                                submission.get("id").asText(), acceptanceId(submission.get("id").asText()))))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/tasks/{taskId}/units/{unitId}/me/contribution-confirmations",
                        task.get("id").asText(), unitId)
                        .header("Authorization", bearer(leader))
                        .header("Idempotency-Key", "p06-self-confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(contribution(taskRevision(leader, task.get("id").asText()),
                                submission.get("id").asText(), acceptanceId(submission.get("id").asText()))))
                .andExpect(status().isOk());
        assertThat(confirmations(unitId)).isEqualTo(1);
        int agreements = jdbcTemplate.queryForObject("SELECT count(*) FROM agreement WHERE task_id = ?", Integer.class,
                task.get("id").asText());
        ObjectNode replacement = plan(project, LEADER_ID, "SELF_CHECK", oneUnit(LEADER_ID, "10.00")).with("assessedPlan");
        ObjectNode body = objectMapper.createObjectNode();
        body.put("baseTaskRevision", taskRevision(leader, task.get("id").asText()));
        body.set("plan", replacement);
        mockMvc.perform(post("/api/v1/tasks/{id}/agreements", task.get("id").asText())
                        .header("Authorization", bearer(leader))
                        .header("Idempotency-Key", "p06-self-replace")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.version").value(2));
        ObjectNode dropped = replacement.deepCopy();
        dropped.putArray("units");
        body.set("plan", dropped);
        mockMvc.perform(post("/api/v1/tasks/{id}/agreements", task.get("id").asText())
                        .header("Authorization", bearer(leader))
                        .header("Idempotency-Key", "p06-self-drop")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isUnprocessableEntity());
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM agreement WHERE task_id = ? AND status = 'ACTIVE'",
                Integer.class, task.get("id").asText())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM agreement WHERE task_id = ?", Integer.class,
                task.get("id").asText())).isEqualTo(agreements + 1);
    }

    @Test
    void partialPassKeepsSnapshotsAndWaitsToApply() throws Exception {
        String leader = login("student_demo_01", "demo-password-01");
        String member = login("student_demo_02", "demo-password-02");
        Project project = project(leader, "p06-partial");
        invite(leader, project.id(), MEMBER_ID, "p06-partial-invite");
        acceptLatestInvitation(member, project.id(), "p06-partial-accept");
        JsonNode task = data(postTask(leader, project, sharedPlan(project), "p06-partial-task")
                .andExpect(status().isOk()).andReturn());
        String taskId = task.get("id").asText();
        String agreementId = jdbcTemplate.queryForObject(
                "SELECT id FROM agreement WHERE task_id = ?", String.class, taskId);
        ack(leader, agreementId, 1, "p06-partial-ack-1");
        ack(member, agreementId, 2, "p06-partial-ack-2");
        JsonNode active = taskOf(leader, taskId);
        String first = active.get("units").get(0).get("id").asText();
        String second = active.get("units").get(1).get("id").asText();
        String firstCriterion = active.get("units").get(0).get("criteria").get(0).get("id").asText();
        String secondCriterion = active.get("units").get(1).get("criteria").get(0).get("id").asText();
        JsonNode submission = submit(leader, taskId, active.get("revision").asInt(), List.of(first, second), 1, null,
                "p06-partial-submit");
        mockMvc.perform(post("/api/v1/submissions/{id}/self-checks", submission.get("id").asText())
                        .header("Authorization", bearer(leader))
                        .header("Idempotency-Key", "p06-partial-check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(review(submission.get("revision").asInt(), List.of(first, second), List.of(
                                check(first, firstCriterion, true), check(second, secondCriterion, false)),
                                List.of("第二项还没完成"), "两人共同承担，没有独立验收人")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.acceptance.decision").value("PARTIAL"))
                .andExpect(jsonPath("$.data.taskStatus").value("IN_PROGRESS"));
        assertThat(jdbcTemplate.queryForObject("SELECT passed FROM delivery_unit WHERE id = ?", Boolean.class, first)).isTrue();
        assertThat(jdbcTemplate.queryForObject("SELECT passed FROM delivery_unit WHERE id = ?", Boolean.class, second)).isFalse();
        assertThat(confirmations(first)).isZero();
        String oldDue = jdbcTemplate.queryForObject(
                "SELECT unit_due_snapshot_json ->> ? FROM submission WHERE id = ?", String.class, second, submission.get("id").asText());
        int agreements = jdbcTemplate.queryForObject("SELECT count(*) FROM agreement WHERE task_id = ?", Integer.class, taskId);
        mockMvc.perform(post("/api/v1/tasks/{id}/handover-agreement-renewals", taskId)
                        .header("Authorization", bearer(leader))
                        .header("Idempotency-Key", "p06-renew")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":" + taskRevision(leader, taskId) + ",\"reason\":\"交接\"}"))
                .andExpect(status().isUnprocessableEntity());
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM agreement WHERE task_id = ?", Integer.class, taskId))
                .isEqualTo(agreements);
        JsonNode proposal = data(mockMvc.perform(post("/api/v1/tasks/{id}/change-proposals", taskId)
                        .header("Authorization", bearer(leader))
                        .header("Idempotency-Key", "p06-proposal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "baseAgreementVersion", 1,
                                "reason", "只改第二项截止",
                                "changes", List.of(Map.of("unitId", second, "baseUnitRevision", 1,
                                        "fields", Map.of("dueAt", "2026-10-01T00:00:00Z")))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andReturn());
        mockMvc.perform(post("/api/v1/change-proposals/{id}/responses", proposal.get("id").asText())
                        .header("Authorization", bearer(leader))
                        .header("Idempotency-Key", "p06-proposal-leader")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"decision\":\"ACCEPT\",\"comment\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"));
        assertThat(jdbcTemplate.queryForObject("SELECT due_at FROM delivery_unit WHERE id = ?",
                java.time.OffsetDateTime.class, second).toInstant()).isEqualTo(Instant.parse("2026-11-06T15:59:59Z"));
        mockMvc.perform(post("/api/v1/change-proposals/{id}/responses", proposal.get("id").asText())
                        .header("Authorization", bearer(member))
                        .header("Idempotency-Key", "p06-proposal-member")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":2,\"decision\":\"ACCEPT\",\"comment\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPLIED"));
        assertThat(jdbcTemplate.queryForObject("SELECT due_at FROM delivery_unit WHERE id = ?",
                java.time.OffsetDateTime.class, second).toInstant()).isEqualTo(Instant.parse("2026-10-01T00:00:00Z"));
        assertThat(jdbcTemplate.queryForObject("SELECT due_at FROM delivery_unit WHERE id = ?",
                java.time.OffsetDateTime.class, first).toInstant()).isEqualTo(Instant.parse("2026-11-06T15:59:59Z"));
        assertThat(jdbcTemplate.queryForObject("SELECT due_at FROM task WHERE id = ?",
                java.time.OffsetDateTime.class, taskId).toInstant()).isEqualTo(Instant.parse("2026-11-06T15:59:59Z"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT unit_due_snapshot_json ->> ? FROM submission WHERE id = ?", String.class, second,
                submission.get("id").asText())).isEqualTo(oldDue);
        JsonNode again = submit(leader, taskId, taskRevision(leader, taskId), List.of(second), 2, null, "p06-resubmit");
        String latestCriterion = jdbcTemplate.queryForObject(
                "SELECT id FROM criterion WHERE unit_id = ? ORDER BY position DESC LIMIT 1", String.class, second);
        mockMvc.perform(post("/api/v1/submissions/{id}/self-checks", again.get("id").asText())
                        .header("Authorization", bearer(leader))
                        .header("Idempotency-Key", "p06-resubmit-check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(review(again.get("revision").asInt(), List.of(second),
                                List.of(check(second, latestCriterion, true)), List.of(), "补交后自检")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.taskStatus").value("DONE"));
        assertThat(confirmations(first)).isZero();
    }

    @Test
    void draftConflictAndIndependentReviewerRejectsContributor() throws Exception {
        String leader = login("student_demo_01", "demo-password-01");
        String assistant = login("assistant_demo_01", "demo-password-04");
        Project project = project(leader, "p06-draft");
        invite(leader, project.id(), ASSISTANT_ID, "p06-draft-invite");
        acceptLatestInvitation(assistant, project.id(), "p06-draft-accept");
        JsonNode task = data(postTask(leader, project, plan(project, ASSISTANT_ID, "INDEPENDENT", oneUnit(LEADER_ID, "10.00")),
                "p06-draft-task").andExpect(status().isOk()).andReturn());
        String taskId = task.get("id").asText();
        String agreementId = jdbcTemplate.queryForObject("SELECT id FROM agreement WHERE task_id = ?", String.class, taskId);
        ack(leader, agreementId, 1, "p06-draft-ack");
        JsonNode invitation = data(mockMvc.perform(post("/api/v1/tasks/{id}/reviewer-invitations", taskId)
                        .header("Authorization", bearer(leader))
                        .header("Idempotency-Key", "p06-invite-reviewer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"recipientId\":\"" + ASSISTANT_ID + "\",\"expectedTaskRevision\":"
                                + taskRevision(leader, taskId) + "}"))
                .andExpect(status().isOk()).andReturn());
        mockMvc.perform(post("/api/v1/reviewer-invitations/{id}/responses", invitation.get("id").asText())
                        .header("Authorization", bearer(assistant))
                        .header("Idempotency-Key", "p06-accept-reviewer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"decision\":\"ACCEPT\"}"))
                .andExpect(status().isOk());
        JsonNode active = taskOf(leader, taskId);
        assertThat(active.get("agreementStatus").asText()).isEqualTo("ACTIVE");
        String unitId = active.get("units").get(0).get("id").asText();
        String criterionId = active.get("units").get(0).get("criteria").get(0).get("id").asText();
        saveDraft(leader, taskId, 0, List.of(unitId), active.get("revision").asInt(), 1, "p06-draft-save");
        JsonNode submission = submit(leader, taskId, active.get("revision").asInt(), List.of(unitId), 1, 1, "p06-draft-submit");
        saveDraft(leader, taskId, 1, List.of(unitId), taskRevision(leader, taskId), 1, "p06-draft-newer");
        mockMvc.perform(post("/api/v1/submissions/{id}/withdrawals", submission.get("id").asText())
                        .header("Authorization", bearer(leader))
                        .header("Idempotency-Key", "p06-withdraw-conflict")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DRAFT_CONFLICT"));
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM submission WHERE id = ?", String.class,
                submission.get("id").asText())).isEqualTo("PENDING");
        mockMvc.perform(post("/api/v1/submissions/{id}/acceptances", submission.get("id").asText())
                        .header("Authorization", bearer(leader))
                        .header("Idempotency-Key", "p06-leader-review")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(review(1, List.of(unitId), List.of(check(unitId, criterionId, true)), List.of(), null)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SELF_REVIEW_FORBIDDEN"));
        mockMvc.perform(post("/api/v1/submissions/{id}/acceptances", submission.get("id").asText())
                        .header("Authorization", bearer(assistant))
                        .header("Idempotency-Key", "p06-assistant-review")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(review(1, List.of(unitId), List.of(check(unitId, criterionId, true)), List.of(), null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.taskStatus").value("DONE"));
        mockMvc.perform(post("/api/v1/submissions/{id}/withdrawals", submission.get("id").asText())
                        .header("Authorization", bearer(leader))
                        .header("Idempotency-Key", "p06-withdraw-late")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1}"))
                .andExpect(status().isConflict());
    }

    @Test
    void ordinarySubmissionUsesEmptyUnits() throws Exception {
        String leader = login("student_demo_01", "demo-password-01");
        String assistant = login("assistant_demo_01", "demo-password-04");
        Project project = project(leader, "p06-ordinary");
        invite(leader, project.id(), ASSISTANT_ID, "p06-ordinary-invite");
        acceptLatestInvitation(assistant, project.id(), "p06-ordinary-accept");
        JsonNode task = data(mockMvc.perform(post("/api/v1/projects/{id}/tasks", project.id())
                        .header("Authorization", bearer(leader))
                        .header("Idempotency-Key", "p06-ordinary-task")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "title", "普通任务", "description", "", "mode", "ORDINARY",
                                "stageId", project.stageId(),
                                "criteria", List.of(Map.of("text", "可以演示")),
                                "dependencies", List.of(), "dueAt", "2026-11-06T15:59:59Z"))))
                .andExpect(status().isOk()).andReturn());
        String taskId = task.get("id").asText();
        mockMvc.perform(post("/api/v1/tasks/{id}/claims", taskId)
                        .header("Authorization", bearer(leader))
                        .header("Idempotency-Key", "p06-claim")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"startNow\":true}"))
                .andExpect(status().isOk());
        int revision = taskRevision(leader, taskId);
        saveDraft(leader, taskId, 0, List.of(), revision, null, "p06-ordinary-draft");
        JsonNode submission = submit(leader, taskId, revision, List.of(), null, 1, "p06-ordinary-submit");
        assertThat(submission.get("unitIds")).isEmpty();
        mockMvc.perform(post("/api/v1/submissions/{id}/self-checks", submission.get("id").asText())
                        .header("Authorization", bearer(leader))
                        .header("Idempotency-Key", "p06-ordinary-self")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(review(1, List.of(), List.of(check(null, task.get("criteria").get(0).get("id").asText(), true)),
                                List.of(), "有其他成员")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SELF_REVIEW_FORBIDDEN"));
        JsonNode invitation = data(mockMvc.perform(post("/api/v1/tasks/{id}/reviewer-invitations", taskId)
                        .header("Authorization", bearer(leader))
                        .header("Idempotency-Key", "p06-ordinary-invite-review")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"recipientId\":\"" + ASSISTANT_ID + "\",\"expectedTaskRevision\":"
                                + taskRevision(leader, taskId) + "}"))
                .andExpect(status().isOk()).andReturn());
        mockMvc.perform(post("/api/v1/reviewer-invitations/{id}/responses", invitation.get("id").asText())
                        .header("Authorization", bearer(assistant))
                        .header("Idempotency-Key", "p06-ordinary-accept-review")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"decision\":\"ACCEPT\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/submissions/{id}/acceptances", submission.get("id").asText())
                        .header("Authorization", bearer(assistant))
                        .header("Idempotency-Key", "p06-ordinary-accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(review(1, List.of(), List.of(check(null, task.get("criteria").get(0).get("id").asText(), true)),
                                List.of(), null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.taskStatus").value("DONE"));
    }

    @Test
    void concurrentAcceptAndWithdrawLeaveOneTerminalOutcome() throws Exception {
        String leader = login("student_demo_01", "demo-password-01");
        String assistant = login("assistant_demo_01", "demo-password-04");
        Project project = project(leader, "p06-race");
        invite(leader, project.id(), ASSISTANT_ID, "p06-race-invite");
        acceptLatestInvitation(assistant, project.id(), "p06-race-accept");
        JsonNode task = data(mockMvc.perform(post("/api/v1/projects/{id}/tasks", project.id())
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "p06-race-task")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "title", "并发验收任务", "description", "", "mode", "ORDINARY",
                                "stageId", project.stageId(),
                                "criteria", List.of(Map.of("text", "可以演示")),
                                "dependencies", List.of(), "dueAt", "2026-11-06T15:59:59Z"))))
                .andExpect(status().isOk()).andReturn());
        String taskId = task.get("id").asText();
        mockMvc.perform(post("/api/v1/tasks/{id}/claims", taskId)
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "p06-race-claim")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"startNow\":true}")).andExpect(status().isOk());
        int revision = taskRevision(leader, taskId);
        JsonNode submission = submit(leader, taskId, revision, List.of(), null, null, "p06-race-submit");
        String submissionId = submission.get("id").asText();
        JsonNode invitation = data(mockMvc.perform(post("/api/v1/tasks/{id}/reviewer-invitations", taskId)
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "p06-race-review-invite")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"recipientId\":\"" + ASSISTANT_ID + "\",\"expectedTaskRevision\":"
                                + taskRevision(leader, taskId) + "}"))
                .andExpect(status().isOk()).andReturn());
        mockMvc.perform(post("/api/v1/reviewer-invitations/{id}/responses", invitation.get("id").asText())
                        .header("Authorization", bearer(assistant)).header("Idempotency-Key", "p06-race-review-accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"decision\":\"ACCEPT\"}"))
                .andExpect(status().isOk());
        String acceptBody = review(1, List.of(), List.of(check(null,
                task.get("criteria").get(0).get("id").asText(), true)), List.of(), null);
        java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(2);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        List<java.util.concurrent.Future<Integer>> futures = new java.util.ArrayList<>();
        try {
            futures.add(executor.submit(() -> {
                ready.countDown(); start.await(5, java.util.concurrent.TimeUnit.SECONDS);
                return mockMvc.perform(post("/api/v1/submissions/{id}/acceptances", submissionId)
                                .header("Authorization", bearer(assistant)).header("Idempotency-Key", "p06-race-acceptance")
                                .contentType(MediaType.APPLICATION_JSON).content(acceptBody))
                        .andReturn().getResponse().getStatus();
            }));
            futures.add(executor.submit(() -> {
                ready.countDown(); start.await(5, java.util.concurrent.TimeUnit.SECONDS);
                return mockMvc.perform(post("/api/v1/submissions/{id}/withdrawals", submissionId)
                                .header("Authorization", bearer(leader)).header("Idempotency-Key", "p06-race-withdraw")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"expectedRevision\":1}"))
                        .andReturn().getResponse().getStatus();
            }));
            ready.await(5, java.util.concurrent.TimeUnit.SECONDS); start.countDown();
            List<Integer> statuses = new java.util.ArrayList<>();
            for (var future : futures) statuses.add(future.get(10, java.util.concurrent.TimeUnit.SECONDS));
            assertThat(statuses).contains(200);
            assertThat(statuses).allMatch(status -> status == 200 || status == 409);
        } finally { executor.shutdownNow(); }
        String submissionStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM submission WHERE id = ?", String.class, submissionId);
        String taskStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM task WHERE id = ?", String.class, taskId);
        assertThat(List.of("REVIEWED", "WITHDRAWN")).contains(submissionStatus);
        assertThat(List.of("DONE", "IN_PROGRESS")).contains(taskStatus);
        assertThat(taskStatus).isEqualTo("DONE".equals(taskStatus) ? "DONE" : "IN_PROGRESS");
    }

    @Test
    void unresolvedOpinionBlocksTheChange() throws Exception {
        String leader = login("student_demo_01", "demo-password-01");
        Project project = project(leader, "p06-opinion");
        JsonNode task = data(postTask(leader, project, plan(project, LEADER_ID, "SELF_CHECK", oneUnit(LEADER_ID, "10.00")),
                "p06-opinion-task").andExpect(status().isOk()).andReturn());
        String taskId = task.get("id").asText();
        String agreementId = jdbcTemplate.queryForObject("SELECT id FROM agreement WHERE task_id = ?", String.class, taskId);
        ack(leader, agreementId, 1, "p06-opinion-ack");
        JsonNode active = taskOf(leader, taskId);
        String unitId = active.get("units").get(0).get("id").asText();
        JsonNode proposal = data(mockMvc.perform(post("/api/v1/tasks/{id}/change-proposals", taskId)
                        .header("Authorization", bearer(leader))
                        .header("Idempotency-Key", "p06-opinion-proposal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "baseAgreementVersion", 1, "reason", "改标题",
                                "changes", List.of(Map.of("unitId", unitId, "baseUnitRevision", 1,
                                        "fields", Map.of("title", "登录与会话")))))))
                .andExpect(status().isOk()).andReturn());
        mockMvc.perform(post("/api/v1/change-proposals/{id}/responses", proposal.get("id").asText())
                        .header("Authorization", bearer(leader))
                        .header("Idempotency-Key", "p06-opinion-request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"decision\":\"REQUEST_CHANGE\",\"comment\":\"标题还要再改\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"));
        String opinionId = data(mockMvc.perform(get("/api/v1/change-proposals/{id}", proposal.get("id").asText())
                        .header("Authorization", bearer(leader))).andExpect(status().isOk()).andReturn())
                .get("opinions").get(0).get("id").asText();
        mockMvc.perform(post("/api/v1/change-proposals/{id}/opinion-responses", proposal.get("id").asText())
                        .header("Authorization", bearer(leader))
                        .header("Idempotency-Key", "p06-opinion-reply")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":2,\"opinionId\":\"" + opinionId + "\",\"proposalText\":\"改为登录与退出\"}"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(post("/api/v1/change-proposals/{id}/responses", proposal.get("id").asText())
                        .header("Authorization", bearer(leader))
                        .header("Idempotency-Key", "p06-opinion-accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":2,\"decision\":\"ACCEPT\",\"comment\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"));
        assertThat(jdbcTemplate.queryForObject("SELECT title FROM delivery_unit WHERE id = ?", String.class, unitId))
                .isEqualTo("登录页面");
        mockMvc.perform(post("/api/v1/change-proposals/{id}/opinion-confirmations", proposal.get("id").asText())
                        .header("Authorization", bearer(leader))
                        .header("Idempotency-Key", "p06-opinion-agree")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":3,\"opinionId\":\"" + opinionId
                                + "\",\"decision\":\"AGREE\",\"comment\":\"可以\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPLIED"));
        assertThat(jdbcTemplate.queryForObject("SELECT title FROM delivery_unit WHERE id = ?", String.class, unitId))
                .isEqualTo("登录与会话");
        mockMvc.perform(get("/api/v1/tasks/{id}/change-proposals", taskId).header("Authorization", bearer(leader)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1));
        JsonNode second = data(mockMvc.perform(post("/api/v1/tasks/{id}/change-proposals", taskId)
                        .header("Authorization", bearer(leader))
                        .header("Idempotency-Key", "p06-opinion-second")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "baseAgreementVersion", 2, "reason", "再改一次",
                                "changes", List.of(Map.of("unitId", unitId, "baseUnitRevision",
                                        jdbcTemplate.queryForObject("SELECT revision FROM delivery_unit WHERE id = ?",
                                                Integer.class, unitId),
                                        "fields", Map.of("title", "登录页面改回来")))))))
                .andExpect(status().isOk()).andReturn());
        mockMvc.perform(post("/api/v1/change-proposals/{id}/withdrawals", second.get("id").asText())
                        .header("Authorization", bearer(leader))
                        .header("Idempotency-Key", "p06-opinion-withdraw")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"reason\":\"先撤回\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("WITHDRAWN"));
        assertThat(jdbcTemplate.queryForObject("SELECT title FROM delivery_unit WHERE id = ?", String.class, unitId))
                .isEqualTo("登录与会话");
    }

    private org.springframework.test.web.servlet.ResultActions postTask(String token, Project project, ObjectNode body,
                                                                        String key) throws Exception {
        return mockMvc.perform(post("/api/v1/projects/{id}/tasks", project.id())
                .header("Authorization", bearer(token))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    @Test
    void agreementProjectionUsesRealFactsAndReplayDoesNotInventThem() throws Exception {
        String leader = login("student_demo_01", "demo-password-01");
        String member = login("student_demo_02", "demo-password-02");
        Project project = project(leader, "p06-r6");
        invite(leader, project.id(), MEMBER_ID, "p06-r6-invite");
        acceptLatestInvitation(member, project.id(), "p06-r6-accept");
        JsonNode task = data(postTask(leader, project, sharedPlan(project), "p06-r6-task")
                .andExpect(status().isOk()).andReturn());
        String taskId = task.get("id").asText();
        String agreementId = jdbcTemplate.queryForObject(
                "SELECT id FROM agreement WHERE task_id = ?", String.class, taskId);
        int agreements = count("agreement");
        int acceptances = count("acceptance");
        int confirmations = count("contribution_confirmation");
        int shares = count("unit_share");
        int receipts = count("operation_receipt");
        JsonNode fresh = agreementOf(leader, agreementId);
        assertThat(count("agreement")).isEqualTo(agreements);
        assertThat(count("acceptance")).isEqualTo(acceptances);
        assertThat(count("contribution_confirmation")).isEqualTo(confirmations);
        assertThat(count("unit_share")).isEqualTo(shares);
        assertThat(count("operation_receipt")).isEqualTo(receipts);
        assertThat(fresh.get("memberChangeId")).isNull();
        assertThat(fresh.get("renewalReason")).isNull();
        assertThat(fresh.get("reviewerAcceptanceInherited")).isNull();
        assertThat(fresh.get("reviewerPolicy").get("displayName").asText()).isEqualTo("示例学生甲");
        assertThat(fresh.get("ownerName").asText()).isEqualTo("示例学生甲");
        assertThat(taskOf(leader, taskId).get("status").asText()).isNotEqualTo("DONE");
        for (JsonNode unit : fresh.get("units")) {
            assertThat(unit.get("status").asText()).isEqualTo("TODO");
            assertThat(unit.get("revision").asInt()).isEqualTo(1);
            assertThat(unit.get("dueAt").asText()).isEqualTo("2026-11-06T15:59:59Z");
            assertThat(unit.get("acceptance").size()).isZero();
            assertThat(unit.get("contributionConfirmations").size()).isZero();
            assertThat(unit.get("shares").get(0).get("userId").asText()).isEqualTo(LEADER_ID);
            assertThat(unit.get("shares").get(0).get("accepted").asBoolean()).isFalse();
            assertThat(unit.get("shares").get(0).get("displayName").asText()).isEqualTo("示例学生甲");
            assertThat(unit.get("shares").get(1).get("accepted").asBoolean()).isFalse();
            assertThat(unit.get("shares").get(1).get("displayName").asText()).isEqualTo("示例学生乙");
        }

        ack(leader, agreementId, 1, "p06-r6-ack-leader");
        JsonNode partial = agreementOf(leader, agreementId);
        for (JsonNode unit : partial.get("units")) {
            assertThat(unit.get("shares").get(0).get("accepted").asBoolean()).isTrue();
            assertThat(unit.get("shares").get(1).get("accepted").asBoolean()).isFalse();
        }
        ack(member, agreementId, 2, "p06-r6-ack-member");
        JsonNode active = taskOf(leader, taskId);
        String first = active.get("units").get(0).get("id").asText();
        String second = active.get("units").get(1).get("id").asText();
        String firstCriterion = active.get("units").get(0).get("criteria").get(0).get("id").asText();
        String secondCriterion = active.get("units").get(1).get("criteria").get(0).get("id").asText();
        JsonNode passedSubmission = submit(leader, taskId, active.get("revision").asInt(), List.of(first), 1, null,
                "p06-r6-pass-submit");
        mockMvc.perform(post("/api/v1/submissions/{id}/self-checks", passedSubmission.get("id").asText())
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "p06-r6-pass-check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(review(passedSubmission.get("revision").asInt(), List.of(first),
                                List.of(check(first, firstCriterion, true)), List.of(), "第一项自检")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.taskStatus").value("IN_PROGRESS"));
        JsonNode passed = agreementOf(leader, agreementId);
        JsonNode passedUnit = unitById(passed, first);
        JsonNode waitingUnit = unitById(passed, second);
        assertThat(passedUnit.get("status").asText()).isEqualTo("PASSED");
        assertThat(waitingUnit.get("status").asText()).isEqualTo("TODO");
        assertThat(waitingUnit.get("acceptance").size()).isZero();
        assertThat(passedUnit.get("contributionConfirmations").size()).isZero();
        JsonNode passedAcceptance = passedUnit.get("acceptance");
        assertThat(passedAcceptance.get("submissionId").asText()).isEqualTo(passedSubmission.get("id").asText());
        assertThat(passedAcceptance.get("reviewerId").asText()).isEqualTo(LEADER_ID);
        assertThat(passedAcceptance.get("kind").asText()).isEqualTo("SELF_CHECK");
        assertThat(passedAcceptance.get("decision").asText()).isEqualTo("PASSED");
        assertThat(passedAcceptance.get("reason").asText()).isEmpty();
        assertThat(passedAcceptance.get("revision").asInt()).isEqualTo(1);
        assertThat(passedAcceptance.get("acceptedAt").asText()).isNotBlank();
        assertThat(passedAcceptance.get("checks").size()).isEqualTo(1);
        assertThat(passedAcceptance.get("checks").get(0).get("unitId").asText()).isEqualTo(first);
        assertThat(passedAcceptance.get("checks").get(0).get("criterionId").asText()).isEqualTo(firstCriterion);
        assertThat(passedAcceptance.get("checks").get(0).get("met").asBoolean()).isTrue();
        assertThat(taskOf(leader, taskId).get("status").asText()).isNotEqualTo("DONE");

        String acceptanceId = passedAcceptance.get("id").asText();
        mockMvc.perform(post("/api/v1/tasks/{taskId}/units/{unitId}/me/contribution-confirmations", taskId, first)
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "p06-r6-confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(contribution(taskRevision(leader, taskId), passedSubmission.get("id").asText(), acceptanceId)))
                .andExpect(status().isOk());
        JsonNode confirmed = unitById(agreementOf(leader, agreementId), first).get("contributionConfirmations");
        assertThat(confirmed.size()).isEqualTo(1);
        assertThat(confirmed.get(0).get("userId").asText()).isEqualTo(LEADER_ID);
        assertThat(confirmed.get(0).get("acceptanceId").asText()).isEqualTo(acceptanceId);
        assertThat(confirmed.get(0).get("submissionId").asText()).isEqualTo(passedSubmission.get("id").asText());
        assertThat(confirmed.get(0).get("statement").asText()).isEqualTo("这部分是我完成的");
        assertThat(confirmed.get(0).get("createdAt").asText()).isNotBlank();

        JsonNode pendingSubmission = submit(leader, taskId, taskRevision(leader, taskId), List.of(second), 1, null,
                "p06-r6-pending");
        JsonNode pendingView = agreementOf(leader, agreementId);
        assertThat(unitById(pendingView, first).get("status").asText()).isEqualTo("PASSED");
        assertThat(unitById(pendingView, first).get("acceptance").get("id").asText()).isEqualTo(acceptanceId);
        assertThat(unitById(pendingView, second).get("status").asText()).isEqualTo("REVIEW_PENDING");
        assertThat(unitById(pendingView, second).get("acceptance").size()).isZero();
        assertThat(taskOf(leader, taskId).get("status").asText()).isEqualTo("REVIEW_PENDING");

        mockMvc.perform(post("/api/v1/submissions/{id}/self-checks", pendingSubmission.get("id").asText())
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "p06-r6-return-check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(review(pendingSubmission.get("revision").asInt(), List.of(second),
                                List.of(check(second, secondCriterion, false)), List.of("第二项还没完成"), "退回第二项")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.acceptance.decision").value("RETURNED"))
                .andExpect(jsonPath("$.data.taskStatus").value("IN_PROGRESS"));
        JsonNode returned = agreementOf(leader, agreementId);
        assertThat(unitById(returned, first).get("status").asText()).isEqualTo("PASSED");
        assertThat(unitById(returned, second).get("status").asText()).isEqualTo("RETURNED");
        assertThat(unitById(returned, second).get("acceptance").get("submissionId").asText())
                .isEqualTo(pendingSubmission.get("id").asText());
        assertThat(unitById(returned, second).get("acceptance").get("checks").get(0).get("met").asBoolean()).isFalse();
        String returnedAcceptance = unitById(returned, second).get("acceptance").get("id").asText();
        JsonNode resubmit = submit(leader, taskId, taskRevision(leader, taskId), List.of(second), 1, null,
                "p06-r6-resubmit");
        JsonNode resubmitted = agreementOf(leader, agreementId);
        assertThat(unitById(resubmitted, second).get("status").asText()).isEqualTo("REVIEW_PENDING");
        assertThat(unitById(resubmitted, second).get("acceptance").size()).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT id FROM acceptance WHERE id = ?", String.class, returnedAcceptance))
                .isEqualTo(returnedAcceptance);
        mockMvc.perform(post("/api/v1/submissions/{id}/withdrawals", resubmit.get("id").asText())
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "p06-r6-withdraw-resubmit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":" + resubmit.get("revision").asInt() + "}"))
                .andExpect(status().isOk());

        int secondRevision = jdbcTemplate.queryForObject(
                "SELECT revision FROM delivery_unit WHERE id = ?", Integer.class, second);
        JsonNode proposal = data(mockMvc.perform(post("/api/v1/tasks/{id}/change-proposals", taskId)
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "p06-r6-proposal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "baseAgreementVersion", 1, "reason", "只改第二项标题",
                                "changes", List.of(Map.of("unitId", second, "baseUnitRevision", secondRevision,
                                        "fields", Map.of("title", "第二项改名")))))))
                .andExpect(status().isOk()).andReturn());
        mockMvc.perform(post("/api/v1/change-proposals/{id}/responses", proposal.get("id").asText())
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "p06-r6-proposal-leader")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"decision\":\"ACCEPT\",\"comment\":\"\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/change-proposals/{id}/responses", proposal.get("id").asText())
                        .header("Authorization", bearer(member)).header("Idempotency-Key", "p06-r6-proposal-member")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":2,\"decision\":\"ACCEPT\",\"comment\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPLIED"));
        String newerId = jdbcTemplate.queryForObject(
                "SELECT id FROM agreement WHERE task_id = ? AND status = 'ACTIVE'", String.class, taskId);
        JsonNode oldVersion = agreementOf(leader, agreementId);
        assertThat(oldVersion.get("version").asInt()).isEqualTo(1);
        assertThat(oldVersion.get("memberChangeId")).isNull();
        assertThat(unitById(oldVersion, second).get("status").asText()).isEqualTo("RETURNED");
        assertThat(unitById(oldVersion, second).get("acceptance").get("id").asText()).isEqualTo(returnedAcceptance);
        assertThat(unitById(oldVersion, second).get("revision").asInt()).isEqualTo(1);
        JsonNode newer = agreementOf(leader, newerId);
        assertThat(unitById(newer, second).get("revision").asInt()).isEqualTo(secondRevision + 1);
        assertThat(unitById(newer, second).get("shares").get(1).get("accepted").asBoolean()).isTrue();
        submit(leader, taskId, taskRevision(leader, taskId), List.of(second), 2, null, "p06-r6-v2-submit");
        assertThat(unitById(agreementOf(leader, agreementId), second).get("status").asText()).isEqualTo("RETURNED");
        assertThat(unitById(agreementOf(leader, agreementId), second).get("acceptance").get("id").asText())
                .isEqualTo(returnedAcceptance);
        assertThat(unitById(agreementOf(leader, newerId), second).get("status").asText()).isEqualTo("REVIEW_PENDING");
        assertThat(unitById(agreementOf(leader, newerId), second).get("acceptance").size()).isZero();
        assertThat(unitById(agreementOf(leader, newerId), first).get("status").asText()).isEqualTo("PASSED");

        int agreementsNow = count("agreement");
        int acceptancesNow = count("acceptance");
        int confirmationsNow = count("contribution_confirmation");
        JsonNode replay = data(mockMvc.perform(post("/api/v1/agreements/{id}/responses", agreementId)
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "p06-r6-ack-leader")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"decision\":\"ACCEPT\",\"comment\":\"同意\"}"))
                .andExpect(status().isOk()).andReturn());
        assertThat(count("agreement")).isEqualTo(agreementsNow);
        assertThat(count("acceptance")).isEqualTo(acceptancesNow);
        assertThat(count("contribution_confirmation")).isEqualTo(confirmationsNow);
        for (JsonNode unit : replay.get("units")) {
            assertThat(unit.get("acceptance").size()).isZero();
            assertThat(unit.get("shares").get(1).get("accepted").asBoolean()).isFalse();
        }

        ObjectNode roundFive = objectMapper.createObjectNode();
        roundFive.put("id", agreementId);
        roundFive.put("taskId", taskId);
        roundFive.put("version", 3);
        roundFive.put("status", "ACTIVE");
        roundFive.put("ownerId", LEADER_ID);
        roundFive.put("dueAt", "2026-11-06T15:59:59Z");
        ObjectNode oldUnit = roundFive.putArray("units").addObject();
        oldUnit.put("id", first);
        oldUnit.put("clientKey", "one");
        oldUnit.put("title", "第一项");
        oldUnit.put("points", "10.00");
        ObjectNode oldShare = oldUnit.putArray("shares").addObject();
        oldShare.put("userId", LEADER_ID);
        oldShare.put("points", "6.00");
        oldShare.put("responsibility", "主要部分");
        oldUnit.putArray("criteria").addObject().put("id", firstCriterion).put("text", "第一项可以演示");
        roundFive.putObject("reviewerPolicy").put("userId", LEADER_ID).put("kind", "SELF_CHECK");
        roundFive.putArray("affectedUserIds").add(LEADER_ID);
        roundFive.put("totalPoints", "10.00");
        roundFive.put("ownerName", "示例学生甲");
        roundFive.put("confirmationSourceValid", true);
        roundFive.putArray("acknowledgements");
        roundFive.put("revision", 4);
        roundFive.put("createdAt", "2026-10-31T00:00:00Z");
        Map<String, Object> roundFiveBody = Map.of("expectedRevision", 1, "decision", "ACCEPT", "comment", "旧结构");
        jdbcTemplate.update("""
                INSERT INTO operation_receipt(id, user_id, method, path, key, body_hash, http_status,
                    business_code, result_ciphertext, key_id, resource_refs_json, expires_at)
                VALUES (?, ?, 'POST', ?, 'p06-r6-round5', ?, 200, 'OK', ?, ?, '[]'::jsonb, ?)
                """, java.util.UUID.randomUUID().toString(), LEADER_ID,
                "/api/v1/agreements/" + agreementId + "/responses", canonical.hash(roundFiveBody),
                cipher.encrypt(objectMapper.writeValueAsString(roundFive)), cipher.keyId(),
                java.time.OffsetDateTime.ofInstant(BASE.plusSeconds(86400), ZoneOffset.UTC));
        mockMvc.perform(post("/api/v1/agreements/{id}/responses", agreementId)
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "p06-r6-round5")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(roundFiveBody)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(3))
                .andExpect(jsonPath("$.data.revision").value(4))
                .andExpect(jsonPath("$.data.units[0].status").doesNotExist())
                .andExpect(jsonPath("$.data.units[0].revision").doesNotExist())
                .andExpect(jsonPath("$.data.units[0].acceptance").doesNotExist())
                .andExpect(jsonPath("$.data.units[0].shares[0].accepted").doesNotExist())
                .andExpect(jsonPath("$.data.reviewerAcceptanceInherited").doesNotExist());
        assertThat(count("agreement")).isEqualTo(agreementsNow);
    }

    @Test
    void handoverRenewalReportsItsOwnReasonAndInheritedAcceptance() throws Exception {
        String leader = login("student_demo_01", "demo-password-01");
        String member = login("student_demo_02", "demo-password-02");
        Project project = project(leader, "p06-r6-handover");
        invite(leader, project.id(), MEMBER_ID, "p06-r6-h-invite");
        acceptLatestInvitation(member, project.id(), "p06-r6-h-accept");
        JsonNode task = data(postTask(leader, project, plan(project, LEADER_ID, "SELF_CHECK",
                shareUnit("one", "未完与已过", LEADER_ID, MEMBER_ID)), "p06-r6-h-task")
                .andExpect(status().isOk()).andReturn());
        String taskId = task.get("id").asText();
        String agreementId = jdbcTemplate.queryForObject(
                "SELECT id FROM agreement WHERE task_id = ?", String.class, taskId);
        ack(leader, agreementId, 1, "p06-r6-h-ack-l");
        ack(member, agreementId, 2, "p06-r6-h-ack-m");
        JsonNode active = taskOf(leader, taskId);
        String unitId = active.get("units").get(0).get("id").asText();
        String criterionId = active.get("units").get(0).get("criteria").get(0).get("id").asText();
        JsonNode submission = submit(leader, taskId, active.get("revision").asInt(), List.of(unitId), 1, null,
                "p06-r6-h-submit");
        mockMvc.perform(post("/api/v1/submissions/{id}/self-checks", submission.get("id").asText())
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "p06-r6-h-check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(review(submission.get("revision").asInt(), List.of(unitId),
                                List.of(check(unitId, criterionId, true)), List.of(), "已通过后交接")))
                .andExpect(status().isOk());
        JsonNode ordinary = data(mockMvc.perform(post("/api/v1/projects/{id}/tasks", project.id())
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "p06-r6-h-ordinary")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "title", "普通任务", "description", "", "mode", "ORDINARY",
                                "stageId", project.stageId(), "criteria", List.of(Map.of("text", "可以演示")),
                                "dependencies", List.of(), "dueAt", "2026-11-06T15:59:59Z"))))
                .andExpect(status().isOk()).andReturn());
        String changeId = "change-r6-" + java.util.UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO member_change(id, project_id, target_user_id, initiator_id, kind, status,
                    plan_version, source_membership_revision, reason, revision, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'EXIT', 'EXECUTED', 1, 1, '离开', 1,
                        '2026-10-31T00:00:00Z', '2026-10-31T00:00:00Z')
                """, changeId, project.id(), MEMBER_ID, LEADER_ID);
        jdbcTemplate.update("""
                INSERT INTO handover_item(id, change_id, plan_version, kind, task_id,
                    source_task_revision, recipient_id, responsibility)
                VALUES (?, ?, 1, 'TASK', ?, 1, ?, '接续已通过份额')
                """, java.util.UUID.randomUUID().toString(), changeId, taskId, LEADER_ID);
        jdbcTemplate.update("""
                INSERT INTO handover_item(id, change_id, plan_version, kind, task_id,
                    source_task_revision, recipient_id, responsibility)
                VALUES (?, ?, 1, 'TASK', ?, 1, ?, '接续普通任务')
                """, java.util.UUID.randomUUID().toString(), changeId, ordinary.get("id").asText(), LEADER_ID);
        int acceptances = count("acceptance");
        JsonNode renewed = data(mockMvc.perform(post("/api/v1/tasks/{id}/handover-agreement-renewals", taskId)
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "p06-r6-h-renew")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":" + taskRevision(leader, taskId)
                                + ",\"reason\":\"交接后续约说明\"}"))
                .andExpect(status().isOk()).andReturn());
        assertThat(renewed.get("memberChangeId").asText()).isEqualTo(changeId);
        assertThat(renewed.get("renewalReason").asText()).isEqualTo("交接后续约说明");
        assertThat(renewed.get("reviewerAcceptanceInherited").asBoolean()).isTrue();
        assertThat(renewed.get("reviewerPolicy").get("displayName").asText()).isEqualTo("示例学生甲");
        assertThat(renewed.get("status").asText()).isEqualTo("PENDING");
        assertThat(renewed.get("units").get(0).get("status").asText()).isEqualTo("PASSED");
        assertThat(renewed.get("units").get(0).get("shares").get(0).get("accepted").asBoolean()).isFalse();
        assertThat(renewed.get("units").get(0).get("shares").get(1).get("accepted").asBoolean()).isFalse();
        JsonNode stored = agreementOf(leader, renewed.get("id").asText());
        assertThat(stored.get("renewalReason").asText()).isEqualTo("交接后续约说明");
        assertThat(stored.get("reviewerAcceptanceInherited").asBoolean()).isTrue();
        assertThat(count("acceptance")).isEqualTo(acceptances);
        JsonNode previous = agreementOf(leader, agreementId);
        assertThat(previous.get("memberChangeId")).isNull();
        assertThat(previous.get("reviewerAcceptanceInherited")).isNull();
        assertThat(previous.get("units").get(0).get("shares").get(0).get("accepted").asBoolean()).isTrue();

        int agreements = count("agreement");
        JsonNode plain = data(mockMvc.perform(post("/api/v1/tasks/{id}/handover-agreement-renewals",
                        ordinary.get("id").asText())
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "p06-r6-h-ordinary-renew")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":" + taskRevision(leader, ordinary.get("id").asText())
                                + ",\"reason\":\"普通任务续约\"}"))
                .andExpect(status().isOk()).andReturn());
        assertThat(plain.get("memberChangeId").asText()).isEqualTo(changeId);
        assertThat(plain.get("renewalReason").asText()).isEqualTo("普通任务续约");
        assertThat(plain.get("reviewerAcceptanceInherited").asBoolean()).isFalse();
        assertThat(plain.get("units").size()).isZero();
        assertThat(count("agreement")).isEqualTo(agreements);
        assertThat(count("acceptance")).isEqualTo(acceptances);
    }

    @Test
    void historicalAgreementReplayKeepsOriginalResultAndRechecksMembership() throws Exception {
        String leader = login("student_demo_01", "demo-password-01");
        Project project = project(leader, "p06-legacy");
        JsonNode task = data(postTask(leader, project, plan(project, LEADER_ID, "SELF_CHECK", oneUnit(LEADER_ID, "10.00")),
                "p06-legacy-task").andExpect(status().isOk()).andReturn());
        String taskId = task.path("id").asText();
        String agreementId = jdbcTemplate.queryForObject("SELECT id FROM agreement WHERE task_id=?", String.class, taskId);
        ack(leader, agreementId, 1, "p06-legacy-ack");
        int agreements = jdbcTemplate.queryForObject("SELECT count(*) FROM agreement WHERE task_id=?", Integer.class, taskId);
        int acks = jdbcTemplate.queryForObject("SELECT count(*) FROM agreement_ack WHERE agreement_id=?", Integer.class, agreementId);
        String ciphertext = jdbcTemplate.queryForObject(
                "SELECT result_ciphertext FROM operation_receipt WHERE key='p06-legacy-ack'", String.class);
        ObjectNode stored = (ObjectNode) objectMapper.readTree(cipher.decrypt(ciphertext));
        int originalVersion = stored.path("version").asInt();
        ObjectNode legacyPlan = objectMapper.createObjectNode();
        legacyPlan.set("ownerId", stored.get("ownerId"));
        legacyPlan.set("dueAt", stored.get("dueAt"));
        legacyPlan.set("units", stored.get("units"));
        legacyPlan.set("reviewerPolicy", stored.get("reviewerPolicy"));
        stored.set("plan", legacyPlan);
        stored.remove("ownerId");
        stored.remove("dueAt");
        stored.remove("units");
        stored.remove("reviewerPolicy");
        stored.remove("affectedUserIds");
        stored.remove("totalPoints");
        stored.remove("ownerName");
        stored.remove("confirmationSourceValid");
        jdbcTemplate.update("UPDATE operation_receipt SET result_ciphertext=? WHERE key='p06-legacy-ack'",
                cipher.encrypt(objectMapper.writeValueAsString(stored)));
        jdbcTemplate.update("UPDATE agreement SET version=99, status='SUPERSEDED' WHERE id=?", agreementId);

        mockMvc.perform(post("/api/v1/agreements/{id}/responses", agreementId)
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "p06-legacy-ack")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"decision\":\"ACCEPT\",\"comment\":\"同意\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(agreementId))
                .andExpect(jsonPath("$.data.version").value(originalVersion))
                .andExpect(jsonPath("$.data.ownerId").value(LEADER_ID))
                .andExpect(jsonPath("$.data.plan").doesNotExist());
        assertThat(jdbcTemplate.queryForObject("SELECT version FROM agreement WHERE id=?", Integer.class, agreementId))
                .isEqualTo(99);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM agreement WHERE task_id=?", Integer.class, taskId))
                .isEqualTo(agreements);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM agreement_ack WHERE agreement_id=?", Integer.class, agreementId))
                .isEqualTo(acks);

        mockMvc.perform(post("/api/v1/agreements/{id}/responses", agreementId)
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "p06-legacy-ack")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"decision\":\"ACCEPT\",\"comment\":\"另一句\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        String renewPath = "/api/v1/tasks/" + taskId + "/handover-agreement-renewals";
        Map<String, Object> renewBody = Map.of("expectedRevision", 1, "reason", "续约");
        ObjectNode handover = objectMapper.createObjectNode();
        handover.put("id", "handover-change-not-agreement");
        handover.put("taskId", taskId);
        handover.put("version", 0);
        handover.put("status", "ACTIVE");
        handover.put("ownerId", LEADER_ID);
        handover.put("dueAt", "2026-11-06T15:59:59Z");
        handover.set("units", objectMapper.createArrayNode());
        handover.putObject("reviewerPolicy").put("userId", "").put("kind", "INDEPENDENT");
        handover.set("affectedUserIds", objectMapper.createArrayNode());
        handover.put("totalPoints", "0");
        handover.put("ownerName", "示例学生甲");
        handover.put("confirmationSourceValid", true);
        handover.set("acknowledgements", objectMapper.createArrayNode());
        handover.put("revision", task.path("revision").asInt());
        handover.put("createdAt", "2026-10-31T00:00:00Z");
        jdbcTemplate.update("""
                INSERT INTO operation_receipt(id, user_id, method, path, key, body_hash, http_status,
                    business_code, result_ciphertext, key_id, resource_refs_json, expires_at)
                VALUES (?, ?, 'POST', ?, 'p06-handover-replay', ?, 200, 'OK', ?, ?, '[]'::jsonb, ?)
                """, java.util.UUID.randomUUID().toString(), LEADER_ID, renewPath, canonical.hash(renewBody),
                cipher.encrypt(objectMapper.writeValueAsString(handover)), cipher.keyId(),
                java.time.OffsetDateTime.ofInstant(BASE.plusSeconds(86400), ZoneOffset.UTC));
        mockMvc.perform(post(renewPath)
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "p06-handover-replay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(renewBody)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value("handover-change-not-agreement"))
                .andExpect(jsonPath("$.data.version").value(0));
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM agreement WHERE task_id=?", Integer.class, taskId))
                .isEqualTo(agreements);

        jdbcTemplate.update("""
                INSERT INTO operation_receipt(id, user_id, method, path, key, body_hash, http_status,
                    business_code, result_ciphertext, key_id, resource_refs_json, expires_at)
                VALUES (?, ?, 'POST', ?, 'p06-missing-replay', ?, 200, 'OK', ?, ?, '[]'::jsonb, ?)
                """, java.util.UUID.randomUUID().toString(), LEADER_ID,
                "/api/v1/agreements/missing-agreement/responses", canonical.hash(Map.of(
                        "expectedRevision", 1, "decision", "ACCEPT", "comment", "同意")),
                cipher.encrypt("{\"id\":\"secret\",\"taskId\":\"missing-task\",\"version\":1,\"status\":\"ACTIVE\",\"ownerId\":\""
                        + LEADER_ID + "\",\"dueAt\":\"2026-11-06T15:59:59Z\",\"units\":[],\"reviewerPolicy\":{\"userId\":\"\",\"kind\":\"INDEPENDENT\"},\"affectedUserIds\":[],\"totalPoints\":\"0\",\"ownerName\":\"\",\"confirmationSourceValid\":true,\"acknowledgements\":[],\"revision\":1,\"createdAt\":\"2026-10-31T00:00:00Z\"}"),
                cipher.keyId(), java.time.OffsetDateTime.ofInstant(BASE.plusSeconds(86400), ZoneOffset.UTC));
        mockMvc.perform(post("/api/v1/agreements/missing-agreement/responses")
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "p06-missing-replay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"decision\":\"ACCEPT\",\"comment\":\"同意\"}"))
                .andExpect(status().isForbidden());

        jdbcTemplate.update("UPDATE membership SET status='LEFT' WHERE user_id=? AND project_id=?", LEADER_ID, project.id());
        mockMvc.perform(post("/api/v1/agreements/{id}/responses", agreementId)
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "p06-legacy-ack")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"decision\":\"ACCEPT\",\"comment\":\"同意\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(renewPath)
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "p06-handover-replay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(renewBody)))
                .andExpect(status().isForbidden());
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM operation_receipt WHERE key IN ('p06-legacy-ack','p06-handover-replay')",
                Integer.class)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT version FROM agreement WHERE id=?", Integer.class, agreementId))
                .isEqualTo(99);
    }

    @Test
    void partialAdjustmentKeepsUnaffectedShareAcceptance() throws Exception {
        String leader = login("student_demo_01", "demo-password-01");
        String member = login("student_demo_02", "demo-password-02");
        String assistant = login("assistant_demo_01", "demo-password-04");
        Project project = project(leader, "p07-share");
        invite(leader, project.id(), MEMBER_ID, "p07-share-invite-a");
        acceptLatestInvitation(member, project.id(), "p07-share-join-a");
        invite(leader, project.id(), ASSISTANT_ID, "p07-share-invite-b");
        acceptLatestInvitation(assistant, project.id(), "p07-share-join-b");
        JsonNode task = data(postTask(leader, project, threePersonPlan(project), "p07-share-task")
                .andExpect(status().isOk()).andReturn());
        String taskId = task.get("id").asText();
        String firstId = agreementId(taskId, 1);
        jdbcTemplate.update("UPDATE unit_share SET signed = true WHERE unit_id IN (SELECT id FROM delivery_unit WHERE task_id = ?)",
                taskId);
        JsonNode signedOnly = agreementOf(leader, firstId);
        assertThat(shareOf(signedOnly, "u1", LEADER_ID).get("accepted").asBoolean()).isFalse();
        assertThat(shareOf(signedOnly, "u2", ASSISTANT_ID).get("accepted").asBoolean()).isFalse();
        jdbcTemplate.update("UPDATE unit_share SET signed = false WHERE unit_id IN (SELECT id FROM delivery_unit WHERE task_id = ?)",
                taskId);

        ack(leader, firstId, 1, "p07-share-ack-l");
        ackCurrent(leader, member, firstId, "p07-share-ack-a");
        int assistantAckRevision = agreementOf(leader, firstId).get("revision").asInt();
        ack(assistant, firstId, assistantAckRevision, "p07-share-ack-b");
        JsonNode active = agreementOf(leader, firstId);
        assertThat(active.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(shareOf(active, "u1", MEMBER_ID).get("accepted").asBoolean()).isTrue();
        assertThat(shareOf(active, "u2", ASSISTANT_ID).get("accepted").asBoolean()).isTrue();
        assertThat(shareOf(active, "u2", MEMBER_ID).get("accepted").asBoolean()).isTrue();

        String firstUnit = unitByKey(active, "u1").get("id").asText();
        String secondUnit = unitByKey(active, "u2").get("id").asText();
        JsonNode proposal = proposeTitle(leader, taskId, 1, firstUnit, unitRevision(firstUnit), "登录改名", "p07-share-p2");
        assertThat(texts(proposal.get("affectedUserIds"))).contains(LEADER_ID, MEMBER_ID).doesNotContain(ASSISTANT_ID);
        confirmCurrent(leader, proposal.get("id").asText(), "p07-share-p2-l");
        JsonNode applied = confirmCurrent(member, proposal.get("id").asText(), "p07-share-p2-a");
        assertThat(applied.get("status").asText()).isEqualTo("APPLIED");
        String secondId = agreementId(taskId, 2);
        JsonNode versionTwo = agreementOf(leader, secondId);
        assertThat(versionTwo.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(unitByKey(versionTwo, "u1").get("title").asText()).isEqualTo("登录改名");
        assertThat(shareOf(versionTwo, "u1", MEMBER_ID).get("accepted").asBoolean()).isTrue();
        assertThat(shareOf(versionTwo, "u2", ASSISTANT_ID).get("accepted").asBoolean()).isTrue();
        assertThat(shareOf(versionTwo, "u2", MEMBER_ID).get("accepted").asBoolean()).isTrue();
        assertThat(shareOf(versionTwo, "u2", ASSISTANT_ID).get("responsibility").asText()).isEqualTo("实现列表");
        assertThat(ackCount(secondId, ASSISTANT_ID)).isZero();
        assertThat(responseCount(ASSISTANT_ID)).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT signed FROM unit_share WHERE unit_id = ? AND user_id = ? AND agreement_version = 2
                """, Boolean.class, secondUnit, ASSISTANT_ID)).isTrue();
        JsonNode old = agreementOf(leader, firstId);
        assertThat(old.get("status").asText()).isEqualTo("SUPERSEDED");
        assertThat(unitByKey(old, "u1").get("title").asText()).isEqualTo("登录");
        assertThat(shareOf(old, "u2", ASSISTANT_ID).get("accepted").asBoolean()).isTrue();

        JsonNode again = proposeTitle(leader, taskId, 2, firstUnit, unitRevision(firstUnit), "登录再改", "p07-share-p3");
        assertThat(texts(again.get("affectedUserIds"))).doesNotContain(ASSISTANT_ID);
        confirmCurrent(leader, again.get("id").asText(), "p07-share-p3-l");
        assertThat(confirmCurrent(member, again.get("id").asText(), "p07-share-p3-a").get("status").asText())
                .isEqualTo("APPLIED");
        String thirdId = agreementId(taskId, 3);
        JsonNode versionThree = agreementOf(assistant, thirdId);
        assertThat(shareOf(versionThree, "u2", ASSISTANT_ID).get("accepted").asBoolean()).isTrue();
        assertThat(shareOf(agreementOf(leader, secondId), "u2", ASSISTANT_ID).get("accepted").asBoolean()).isTrue();
        assertThat(ackCount(secondId, ASSISTANT_ID)).isZero();
        assertThat(ackCount(thirdId, ASSISTANT_ID)).isZero();
        assertThat(responseCount(ASSISTANT_ID)).isZero();

        int agreements = count("agreement");
        JsonNode blocked = proposeTitle(leader, taskId, 3, secondUnit, unitRevision(secondUnit), "列表改名", "p07-share-blocked");
        assertThat(blocked.get("status").asText()).isEqualTo("PENDING");
        assertThat(count("agreement")).isEqualTo(agreements);
        JsonNode stillCurrent = agreementOf(leader, thirdId);
        assertThat(stillCurrent.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(unitByKey(stillCurrent, "u2").get("title").asText()).isEqualTo("列表");
        assertThat(shareOf(stillCurrent, "u2", ASSISTANT_ID).get("accepted").asBoolean()).isTrue();
        mockMvc.perform(post("/api/v1/change-proposals/{id}/withdrawals", blocked.get("id").asText())
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "p07-share-withdraw")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"reason\":\"先不改\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("WITHDRAWN"));

        JsonNode responsibility = data(mockMvc.perform(post("/api/v1/tasks/{id}/change-proposals", taskId)
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "p07-share-duty")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "baseAgreementVersion", 3, "reason", "只改未参与者的责任说明",
                                "changes", List.of(Map.of("unitId", secondUnit, "baseUnitRevision", unitRevision(secondUnit),
                                        "fields", Map.of("shares", List.of(
                                                Map.of("userId", MEMBER_ID, "points", "4.00", "responsibility", "接口"),
                                                Map.of("userId", ASSISTANT_ID, "points", "6.00", "responsibility", "改过的列表")))))))))
                .andExpect(status().isOk()).andReturn());
        assertThat(texts(responsibility.get("affectedUserIds"))).contains(ASSISTANT_ID);
        confirmCurrent(leader, responsibility.get("id").asText(), "p07-share-duty-l");
        confirmCurrent(member, responsibility.get("id").asText(), "p07-share-duty-a");
        assertThat(confirmCurrent(assistant, responsibility.get("id").asText(), "p07-share-duty-b").get("status").asText())
                .isEqualTo("APPLIED");
        String fourthId = agreementId(taskId, 4);
        JsonNode changedDuty = agreementOf(leader, fourthId);
        assertThat(shareOf(changedDuty, "u2", ASSISTANT_ID).get("responsibility").asText()).isEqualTo("改过的列表");
        assertThat(shareOf(changedDuty, "u2", ASSISTANT_ID).get("accepted").asBoolean()).isTrue();
        assertThat(shareOf(changedDuty, "u1", MEMBER_ID).get("accepted").asBoolean()).isTrue();
        JsonNode previousDuty = agreementOf(leader, thirdId);
        assertThat(shareOf(previousDuty, "u2", ASSISTANT_ID).get("responsibility").asText()).isEqualTo("实现列表");
        assertThat(shareOf(previousDuty, "u2", ASSISTANT_ID).get("accepted").asBoolean()).isTrue();
        assertThat(responseCount(ASSISTANT_ID)).isEqualTo(1);

        int beforeReplace = count("agreement");
        JsonNode replaced = data(mockMvc.perform(post("/api/v1/tasks/{id}/agreements", taskId)
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "p07-share-replace")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "baseTaskRevision", taskRevision(leader, taskId),
                                "plan", replacePlanFrom(changedDuty, LEADER_ID, "SELF_CHECK")))))
                .andExpect(status().isOk()).andReturn());
        assertThat(replaced.get("status").asText()).isEqualTo("PENDING");
        assertThat(replaced.get("version").asInt()).isEqualTo(5);
        assertThat(shareOf(replaced, "u2", ASSISTANT_ID).get("accepted").asBoolean()).isFalse();
        assertThat(shareOf(replaced, "u1", MEMBER_ID).get("accepted").asBoolean()).isFalse();
        assertThat(shareOf(agreementOf(leader, fourthId), "u2", ASSISTANT_ID).get("accepted").asBoolean()).isTrue();
        assertThat(count("agreement")).isEqualTo(beforeReplace + 1);

        int agreementsNow = count("agreement");
        int acks = count("agreement_ack");
        int responses = count("proposal_response");
        int shares = count("unit_share");
        int acceptances = count("acceptance");
        agreementOf(leader, firstId);
        agreementOf(member, thirdId);
        agreementOf(assistant, fourthId);
        agreementOf(leader, replaced.get("id").asText());
        assertThat(count("agreement")).isEqualTo(agreementsNow);
        assertThat(count("agreement_ack")).isEqualTo(acks);
        assertThat(count("proposal_response")).isEqualTo(responses);
        assertThat(count("unit_share")).isEqualTo(shares);
        assertThat(count("acceptance")).isEqualTo(acceptances);

        JsonNode replay = data(mockMvc.perform(post("/api/v1/agreements/{id}/responses", firstId)
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "p07-share-ack-l")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"decision\":\"ACCEPT\",\"comment\":\"同意\"}"))
                .andExpect(status().isOk()).andReturn());
        assertThat(shareOf(replay, "u2", ASSISTANT_ID).get("accepted").asBoolean()).isFalse();
        assertThat(shareOf(replay, "u1", LEADER_ID).get("accepted").asBoolean()).isTrue();
        assertThat(count("agreement")).isEqualTo(agreementsNow);
        assertThat(count("agreement_ack")).isEqualTo(acks);

        jdbcTemplate.update("UPDATE membership SET status = 'LEFT' WHERE user_id = ? AND project_id = ?",
                ASSISTANT_ID, project.id());
        mockMvc.perform(get("/api/v1/agreements/{id}", fourthId).header("Authorization", bearer(assistant)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/agreements/{id}/responses", firstId)
                        .header("Authorization", bearer(assistant)).header("Idempotency-Key", "p07-share-ack-b")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":" + assistantAckRevision + ",\"decision\":\"ACCEPT\",\"comment\":\"同意\"}"))
                .andExpect(status().isForbidden());
        assertThat(count("agreement")).isEqualTo(agreementsNow);
    }

    @Test
    void historicalHandoverKeepsTargetReviewerInheritance() throws Exception {
        String leader = login("student_demo_01", "demo-password-01");
        String member = login("student_demo_02", "demo-password-02");
        String assistant = login("assistant_demo_01", "demo-password-04");
        Project project = project(leader, "p07-review");
        invite(leader, project.id(), MEMBER_ID, "p07-review-invite");
        acceptLatestInvitation(member, project.id(), "p07-review-join");
        JsonNode task = data(postTask(leader, project, plan(project, LEADER_ID, "SELF_CHECK",
                shareUnit("one", "已通过项", LEADER_ID, MEMBER_ID)), "p07-review-task")
                .andExpect(status().isOk()).andReturn());
        String taskId = task.get("id").asText();
        String firstId = agreementId(taskId, 1);
        ack(leader, firstId, 1, "p07-review-ack-l");
        ackCurrent(leader, member, firstId, "p07-review-ack-a");
        JsonNode active = taskOf(leader, taskId);
        String unitId = active.get("units").get(0).get("id").asText();
        String criterionId = active.get("units").get(0).get("criteria").get(0).get("id").asText();
        JsonNode submission = submit(leader, taskId, active.get("revision").asInt(), List.of(unitId), 1, null,
                "p07-review-submit");
        mockMvc.perform(post("/api/v1/submissions/{id}/self-checks", submission.get("id").asText())
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "p07-review-check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(review(submission.get("revision").asInt(), List.of(unitId),
                                List.of(check(unitId, criterionId, true)), List.of(), "完成后交接")))
                .andExpect(status().isOk());
        String changeId = "change-p07-" + java.util.UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO member_change(id, project_id, target_user_id, initiator_id, kind, status,
                    plan_version, source_membership_revision, reason, revision, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'EXIT', 'EXECUTED', 1, 1, '离开', 1,
                        '2026-10-31T00:00:00Z', '2026-10-31T00:00:00Z')
                """, changeId, project.id(), MEMBER_ID, LEADER_ID);
        jdbcTemplate.update("""
                INSERT INTO handover_item(id, change_id, plan_version, kind, task_id,
                    source_task_revision, recipient_id, responsibility)
                VALUES (?, ?, 1, 'TASK', ?, 1, ?, '接续已通过份额')
                """, java.util.UUID.randomUUID().toString(), changeId, taskId, LEADER_ID);
        int revision = taskRevision(leader, taskId);
        String renewBody = "{\"expectedRevision\":" + revision + ",\"reason\":\"交接后续约说明\"}";
        JsonNode renewed = data(mockMvc.perform(post("/api/v1/tasks/{id}/handover-agreement-renewals", taskId)
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "p07-review-renew")
                        .contentType(MediaType.APPLICATION_JSON).content(renewBody))
                .andExpect(status().isOk()).andReturn());
        assertThat(renewed.get("version").asInt()).isEqualTo(2);
        assertThat(renewed.get("reviewerPolicy").get("userId").asText()).isEqualTo(LEADER_ID);
        assertThat(renewed.get("reviewerPolicy").get("kind").asText()).isEqualTo("SELF_CHECK");
        assertThat(renewed.get("reviewerAcceptanceInherited").asBoolean()).isTrue();
        String planBefore = jdbcTemplate.queryForObject("SELECT plan_json::text FROM agreement WHERE id = ?",
                String.class, renewed.get("id").asText());

        invite(leader, project.id(), ASSISTANT_ID, "p07-review-invite-b");
        acceptLatestInvitation(assistant, project.id(), "p07-review-join-b");
        JsonNode replaced = data(mockMvc.perform(post("/api/v1/tasks/{id}/agreements", taskId)
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "p07-review-replace")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "baseTaskRevision", taskRevision(leader, taskId),
                                "plan", replacePlanFrom(agreementOf(leader, renewed.get("id").asText()),
                                        ASSISTANT_ID, "INDEPENDENT")))))
                .andExpect(status().isOk()).andReturn());
        assertThat(replaced.get("reviewerPolicy").get("userId").asText()).isEqualTo(ASSISTANT_ID);
        assertThat(replaced.get("reviewerPolicy").get("kind").asText()).isEqualTo("INDEPENDENT");
        assertThat(replaced.get("reviewerAcceptanceInherited")).isNull();
        assertThat(jdbcTemplate.queryForObject("SELECT user_id FROM reviewer_arrangement WHERE task_id = ?",
                String.class, taskId)).isEqualTo(ASSISTANT_ID);
        assertThat(jdbcTemplate.queryForObject("SELECT kind FROM reviewer_arrangement WHERE task_id = ?",
                String.class, taskId)).isEqualTo("INDEPENDENT");
        assertThat(jdbcTemplate.queryForObject("SELECT plan_json::text FROM agreement WHERE id = ?",
                String.class, renewed.get("id").asText())).isEqualTo(planBefore);

        String submissionId = submission.get("id").asText();
        jdbcTemplate.update("UPDATE submission SET agreement_version = 50 WHERE id = ?", submissionId);
        JsonNode shifted = agreementOf(member, renewed.get("id").asText());
        assertThat(shifted.get("reviewerPolicy").get("kind").asText()).isEqualTo("SELF_CHECK");
        assertThat(shifted.get("reviewerAcceptanceInherited").asBoolean()).isFalse();
        jdbcTemplate.update("UPDATE submission SET agreement_version = 1 WHERE id = ?", submissionId);
        int agreements = count("agreement");
        int acceptances = count("acceptance");
        int arrangements = count("reviewer_arrangement");
        JsonNode historical = agreementOf(member, renewed.get("id").asText());
        assertThat(historical.get("memberChangeId").asText()).isEqualTo(changeId);
        assertThat(historical.get("reviewerPolicy").get("userId").asText()).isEqualTo(LEADER_ID);
        assertThat(historical.get("reviewerPolicy").get("kind").asText()).isEqualTo("SELF_CHECK");
        assertThat(historical.get("reviewerPolicy").get("displayName").asText()).isEqualTo("示例学生甲");
        assertThat(historical.get("reviewerAcceptanceInherited").asBoolean()).isTrue();
        JsonNode current = agreementOf(leader, replaced.get("id").asText());
        assertThat(current.get("reviewerPolicy").get("userId").asText()).isEqualTo(ASSISTANT_ID);
        assertThat(current.get("reviewerPolicy").get("kind").asText()).isEqualTo("INDEPENDENT");
        assertThat(current.get("reviewerAcceptanceInherited")).isNull();
        assertThat(count("agreement")).isEqualTo(agreements);
        assertThat(count("acceptance")).isEqualTo(acceptances);
        assertThat(count("reviewer_arrangement")).isEqualTo(arrangements);
        assertThat(jdbcTemplate.queryForObject("SELECT reviewer_id FROM acceptance WHERE submission_id = ?",
                String.class, submissionId)).isEqualTo(LEADER_ID);
        assertThat(jdbcTemplate.queryForObject("SELECT kind FROM acceptance WHERE submission_id = ?",
                String.class, submissionId)).isEqualTo("SELF_CHECK");

        JsonNode replay = data(mockMvc.perform(post("/api/v1/tasks/{id}/handover-agreement-renewals", taskId)
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "p07-review-renew")
                        .contentType(MediaType.APPLICATION_JSON).content(renewBody))
                .andExpect(status().isOk()).andReturn());
        assertThat(replay.get("id").asText()).isEqualTo(renewed.get("id").asText());
        assertThat(replay.get("reviewerAcceptanceInherited").asBoolean()).isTrue();
        assertThat(replay.get("reviewerPolicy").get("userId").asText()).isEqualTo(LEADER_ID);
        assertThat(count("agreement")).isEqualTo(agreements);
        mockMvc.perform(post("/api/v1/tasks/{id}/handover-agreement-renewals", taskId)
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "p07-review-renew")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":" + revision + ",\"reason\":\"另一句说明\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        jdbcTemplate.update("UPDATE membership SET status = 'LEFT' WHERE user_id = ? AND project_id = ?",
                LEADER_ID, project.id());
        mockMvc.perform(get("/api/v1/agreements/{id}", renewed.get("id").asText())
                        .header("Authorization", bearer(leader)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/tasks/{id}/handover-agreement-renewals", taskId)
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "p07-review-renew")
                        .contentType(MediaType.APPLICATION_JSON).content(renewBody))
                .andExpect(status().isForbidden());
        assertThat(count("agreement")).isEqualTo(agreements);
        assertThat(count("acceptance")).isEqualTo(acceptances);
    }

    private ObjectNode plan(Project project, String reviewerId, String kind, ObjectNode unit) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("title", "登录模块");
        root.put("description", "完成登录");
        root.put("mode", "ASSESSED");
        root.put("stageId", project.stageId());
        root.set("criteria", objectMapper.createArrayNode());
        root.set("dependencies", objectMapper.createArrayNode());
        ObjectNode assessed = root.putObject("assessedPlan");
        assessed.put("ownerId", LEADER_ID);
        assessed.put("dueAt", "2026-11-06T15:59:59Z");
        assessed.putArray("units").add(unit);
        assessed.putObject("reviewerPolicy").put("userId", reviewerId).put("kind", kind);
        return root;
    }

    private ObjectNode sharedPlan(Project project) {
        ObjectNode root = plan(project, LEADER_ID, "SELF_CHECK", shareUnit("one", "第一项", LEADER_ID, MEMBER_ID));
        root.with("assessedPlan").withArray("units").add(shareUnit("two", "第二项", LEADER_ID, MEMBER_ID));
        return root;
    }

    private ObjectNode oneUnit(String userId, String points) {
        ObjectNode unit = objectMapper.createObjectNode();
        unit.put("clientKey", "login");
        unit.put("title", "登录页面");
        unit.put("points", "10.00");
        ObjectNode share = unit.putArray("shares").addObject();
        share.put("userId", userId);
        share.put("points", points);
        share.put("responsibility", "实现登录");
        unit.putArray("criteria").addObject().put("text", "可以演示登录和退出");
        return unit;
    }

    private ObjectNode shareUnit(String key, String title, String left, String right) {
        ObjectNode unit = objectMapper.createObjectNode();
        unit.put("clientKey", key);
        unit.put("title", title);
        unit.put("points", "10.00");
        ObjectNode first = unit.putArray("shares").addObject();
        first.put("userId", left);
        first.put("points", "6.00");
        first.put("responsibility", "主要部分");
        ObjectNode second = unit.withArray("shares").addObject();
        second.put("userId", right);
        second.put("points", "4.00");
        second.put("responsibility", "配合部分");
        unit.putArray("criteria").addObject().put("text", title + "可以演示");
        return unit;
    }

    private ObjectNode threePersonPlan(Project project) {
        ObjectNode root = plan(project, LEADER_ID, "SELF_CHECK", personUnit("u1", "登录",
                shareNode(LEADER_ID, "4.00", "统筹"), shareNode(MEMBER_ID, "6.00", "实现登录")));
        root.with("assessedPlan").withArray("units").add(personUnit("u2", "列表",
                shareNode(MEMBER_ID, "4.00", "接口"), shareNode(ASSISTANT_ID, "6.00", "实现列表")));
        return root;
    }

    private ObjectNode personUnit(String key, String title, ObjectNode... shares) {
        ObjectNode unit = objectMapper.createObjectNode();
        unit.put("clientKey", key);
        unit.put("title", title);
        unit.put("points", "10.00");
        var array = unit.putArray("shares");
        for (ObjectNode share : shares) {
            array.add(share);
        }
        unit.putArray("criteria").addObject().put("text", title + "可以演示");
        return unit;
    }

    private ObjectNode shareNode(String userId, String points, String responsibility) {
        ObjectNode share = objectMapper.createObjectNode();
        share.put("userId", userId);
        share.put("points", points);
        share.put("responsibility", responsibility);
        return share;
    }

    private ObjectNode replacePlanFrom(JsonNode agreement, String reviewerId, String kind) {
        ObjectNode plan = objectMapper.createObjectNode();
        plan.put("ownerId", agreement.get("ownerId").asText());
        plan.put("dueAt", agreement.get("dueAt").asText());
        var units = plan.putArray("units");
        for (JsonNode unit : agreement.get("units")) {
            ObjectNode copy = units.addObject();
            copy.put("clientKey", unit.get("clientKey").asText());
            copy.put("title", unit.get("title").asText());
            copy.put("points", unit.get("points").asText());
            List<JsonNode> shares = new ArrayList<>();
            unit.get("shares").forEach(shares::add);
            shares.sort(Comparator.comparing(node -> node.get("userId").asText()));
            var copied = copy.putArray("shares");
            for (JsonNode share : shares) {
                copied.addObject()
                        .put("userId", share.get("userId").asText())
                        .put("points", share.get("points").asText())
                        .put("responsibility", share.get("responsibility").asText());
            }
            var criteria = copy.putArray("criteria");
            for (JsonNode criterion : unit.get("criteria")) {
                criteria.addObject().put("text", criterion.get("text").asText());
            }
        }
        plan.putObject("reviewerPolicy").put("userId", reviewerId).put("kind", kind);
        return plan;
    }

    private void ackCurrent(String reader, String actor, String agreementId, String key) throws Exception {
        ack(actor, agreementId, agreementOf(reader, agreementId).get("revision").asInt(), key);
    }

    private JsonNode proposeTitle(String token, String taskId, int agreementVersion, String unitId, int unitRevision,
                                  String title, String key) throws Exception {
        return data(mockMvc.perform(post("/api/v1/tasks/{id}/change-proposals", taskId)
                        .header("Authorization", bearer(token)).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "baseAgreementVersion", agreementVersion, "reason", "只改这一项",
                                "changes", List.of(Map.of("unitId", unitId, "baseUnitRevision", unitRevision,
                                        "fields", Map.of("title", title)))))))
                .andExpect(status().isOk()).andReturn());
    }

    private JsonNode confirmCurrent(String token, String proposalId, String key) throws Exception {
        int revision = data(mockMvc.perform(get("/api/v1/change-proposals/{id}", proposalId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andReturn()).get("revision").asInt();
        return data(mockMvc.perform(post("/api/v1/change-proposals/{id}/responses", proposalId)
                        .header("Authorization", bearer(token)).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":" + revision + ",\"decision\":\"ACCEPT\",\"comment\":\"\"}"))
                .andExpect(status().isOk()).andReturn());
    }

    private String agreementId(String taskId, int version) {
        return jdbcTemplate.queryForObject("SELECT id FROM agreement WHERE task_id = ? AND version = ?",
                String.class, taskId, version);
    }

    private int unitRevision(String unitId) {
        return jdbcTemplate.queryForObject("SELECT revision FROM delivery_unit WHERE id = ?", Integer.class, unitId);
    }

    private JsonNode unitByKey(JsonNode agreement, String clientKey) {
        for (JsonNode unit : agreement.get("units")) {
            if (clientKey.equals(unit.get("clientKey").asText())) {
                return unit;
            }
        }
        throw new AssertionError("缺少分项 " + clientKey);
    }

    private JsonNode shareOf(JsonNode agreement, String clientKey, String userId) {
        for (JsonNode share : unitByKey(agreement, clientKey).get("shares")) {
            if (userId.equals(share.get("userId").asText())) {
                return share;
            }
        }
        throw new AssertionError("缺少份额 " + userId);
    }

    private List<String> texts(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asText()));
        return values;
    }

    private int ackCount(String agreementId, String userId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM agreement_ack WHERE agreement_id = ? AND user_id = ?",
                Integer.class, agreementId, userId);
    }

    private int responseCount(String userId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM proposal_response WHERE user_id = ?", Integer.class, userId);
    }

    private void ack(String token, String agreementId, int revision, String key) throws Exception {
        mockMvc.perform(post("/api/v1/agreements/{id}/responses", agreementId)
                        .header("Authorization", bearer(token))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":" + revision + ",\"decision\":\"ACCEPT\",\"comment\":\"同意\"}"))
                .andExpect(status().isOk());
    }

    private void saveDraft(String token, String taskId, int revision, List<String> unitIds, int taskRevision,
                           Integer agreementVersion, String key) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("expectedRevision", revision);
        body.set("unitIds", objectMapper.valueToTree(unitIds));
        body.put("summary", "草稿");
        body.set("artifacts", objectMapper.createArrayNode());
        if (agreementVersion != null) {
            body.put("baseAgreementVersion", agreementVersion);
        }
        body.put("expectedTaskRevision", taskRevision);
        mockMvc.perform(put("/api/v1/tasks/{id}/me/draft", taskId)
                        .header("Authorization", bearer(token))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk());
    }

    private JsonNode submit(String token, String taskId, int taskRevision, List<String> unitIds,
                            Integer agreementVersion, Integer draftRevision, String key) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("expectedTaskRevision", taskRevision);
        body.set("unitIds", objectMapper.valueToTree(unitIds));
        body.put("summary", "提交说明");
        if (agreementVersion != null) {
            body.put("agreementVersion", agreementVersion);
        }
        if (draftRevision != null) {
            body.put("fromDraftRevision", draftRevision);
        }
        return data(mockMvc.perform(post("/api/v1/tasks/{id}/submissions", taskId)
                        .header("Authorization", bearer(token))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk()).andReturn());
    }

    private String review(int revision, List<String> unitIds, List<Map<String, Object>> checks, List<String> reasons,
                          String selfCheckReason) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("expectedRevision", revision);
        body.set("unitIds", objectMapper.valueToTree(unitIds));
        body.set("checks", objectMapper.valueToTree(checks));
        body.set("returnReasons", objectMapper.valueToTree(reasons));
        if (selfCheckReason != null) {
            body.put("selfCheckReason", selfCheckReason);
        }
        return objectMapper.writeValueAsString(body);
    }

    private Map<String, Object> check(String unitId, String criterionId, boolean met) {
        java.util.LinkedHashMap<String, Object> check = new java.util.LinkedHashMap<>();
        check.put("unitId", unitId);
        check.put("criterionId", criterionId);
        check.put("met", met);
        return check;
    }

    private String contribution(int revision, String submissionId, String acceptanceId) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "expectedRevision", revision, "submissionId", submissionId,
                "acceptanceId", acceptanceId, "statement", "这部分是我完成的"));
    }

    private String acceptanceId(String submissionId) {
        return jdbcTemplate.queryForObject("SELECT id FROM acceptance WHERE submission_id = ?", String.class, submissionId);
    }

    private int confirmations(String unitId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM contribution_confirmation WHERE unit_id = ?", Integer.class, unitId);
    }

    private JsonNode agreementOf(String token, String agreementId) throws Exception {
        return data(mockMvc.perform(get("/api/v1/agreements/{id}", agreementId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andReturn());
    }

    private JsonNode unitById(JsonNode agreement, String unitId) {
        for (JsonNode unit : agreement.get("units")) {
            if (unitId.equals(unit.get("id").asText())) {
                return unit;
            }
        }
        throw new AssertionError("缺少分项 " + unitId);
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private JsonNode taskOf(String token, String taskId) throws Exception {
        return data(mockMvc.perform(get("/api/v1/tasks/{id}", taskId).header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andReturn());
    }

    private int taskRevision(String token, String taskId) throws Exception {
        return taskOf(token, taskId).get("revision").asInt();
    }

    private void clearAll() {
        TestSchemaCleaner.deleteAll(jdbcTemplate, List.of("file_reference", "file_authorization", "cleanup_plan", "stored_file",
                "personnel_handoff", "material_request", "personnel_record", "personnel_case_grant", "personnel_case",
                "removal_ballot", "handover_ack", "handover_item", "member_change_event",
                "member_change", "removal_vote",
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
                "course_code", "course_join_request", "cursor_snapshot", "assignment", "course_membership",
                "merge_ballot", "square_merge", "preview_token", "square", "verified_enrollment", "verified_course", "course_name",
                "period", "term", "operation_receipt", "access_token", "auth_session", "audit_event",
                "rate_window", "import_batch", "user_role", "user_profile", "app_user", "school"));
    }

    private Project project(String token, String key) throws Exception {
        String matchBody = objectMapper.writeValueAsString(Map.of(
                "schoolId", SCHOOL, "termId", TERM, "teacherName", "示例教师",
                "slots", List.of(Map.of("weekday", 3, "periodId", "period_demo_2",
                        "weekPattern", "ALL", "startWeek", 1, "endWeek", 16, "customWeeks", List.of())),
                "courseNameId", "course_name_demo_mobile"));
        JsonNode match = data(mockMvc.perform(post("/api/v1/squares/matches")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(matchBody))
                .andExpect(status().isOk()).andReturn());
        String createBody = objectMapper.writeValueAsString(Map.of(
                "schoolId", SCHOOL, "termId", TERM, "teacherName", "示例教师",
                "slots", List.of(Map.of("weekday", 3, "periodId", "period_demo_2",
                        "weekPattern", "ALL", "startWeek", 1, "endWeek", 16, "customWeeks", List.of())),
                "courseNameId", "course_name_demo_mobile",
                "candidateCheckToken", match.get("candidateCheckToken").asText()));
        JsonNode square = data(mockMvc.perform(post("/api/v1/squares")
                        .header("Authorization", bearer(token))
                        .header("Idempotency-Key", key + "-square")
                        .contentType(MediaType.APPLICATION_JSON).content(createBody))
                .andExpect(status().isOk()).andReturn());
        JsonNode assignment = data(mockMvc.perform(post("/api/v1/squares/{id}/assignments", square.get("id").asText())
                        .header("Authorization", bearer(token))
                        .header("Idempotency-Key", key + "-assignment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "课程设计", "requirements", "完成可演示应用",
                                "hardDueAt", "2026-12-15T15:59:59Z", "minMembers", 2, "maxMembers", 6,
                                "sourceReferences", List.of()))))
                .andExpect(status().isOk()).andReturn());
        String teacher = login("teacher_demo_01", "demo-password-03");
        mockMvc.perform(post("/api/v1/assignments/{id}/confirmations", assignment.get("id").asText())
                        .header("Authorization", bearer(teacher))
                        .header("Idempotency-Key", key + "-confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"decision\":\"CONFIRM_NEW\",\"reason\":\"ok\"}"))
                .andExpect(status().isOk());
        JsonNode created = data(mockMvc.perform(post("/api/v1/projects")
                        .header("Authorization", bearer(token))
                        .header("Idempotency-Key", key + "-project")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "squareId", square.get("id").asText(), "assignmentId", assignment.get("id").asText(),
                                "mode", "QUICK", "name", "交付组", "capacity", 4,
                                "discussionTime", "周三", "roles", List.of()))))
                .andExpect(status().isOk()).andReturn());
        return new Project(created.get("id").asText(), created.get("stages").get(0).get("id").asText());
    }

    private void invite(String leader, String projectId, String userId, String key) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("expectedProjectRevision", data(mockMvc.perform(get("/api/v1/projects/{id}", projectId)
                .header("Authorization", bearer(leader))).andExpect(status().isOk()).andReturn()).get("revision").asInt());
        body.put("recipientId", userId);
        body.put("message", "加入项目");
        mockMvc.perform(post("/api/v1/projects/{id}/invitations", projectId)
                        .header("Authorization", bearer(leader))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk());
    }

    private void acceptLatestInvitation(String token, String projectId, String key) throws Exception {
        String invitationId = jdbcTemplate.queryForObject("""
                SELECT id FROM team_invitation WHERE project_id = ? AND status = 'PENDING'
                ORDER BY created_at DESC LIMIT 1
                """, String.class, projectId);
        mockMvc.perform(post("/api/v1/invitations/{id}/responses", invitationId)
                        .header("Authorization", bearer(token))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"decision\":\"ACCEPT\"}"))
                .andExpect(status().isOk());
    }

    private int taskCount(String projectId) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM task WHERE project_id = ?", Integer.class, projectId);
    }

    private String login(String loginId, String password) throws Exception {
        return data(mockMvc.perform(post("/api/v1/auth/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("loginId", loginId, "password", password))))
                .andExpect(status().isOk()).andReturn()).get("accessToken").asText();
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private JsonNode data(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
    }

    private record Project(String id, String stageId) {
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
