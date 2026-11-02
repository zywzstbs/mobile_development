package cn.gulupai.server.delivery;

import cn.gulupai.server.common.clock.MutableClock;
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
        mockMvc.perform(get("/api/v1/agreements/{id}", agreementId).header("Authorization", bearer(stranger)))
                .andExpect(status().isForbidden());
        JsonNode agreement = data(mockMvc.perform(get("/api/v1/agreements/{id}", agreementId)
                        .header("Authorization", bearer(leader)))
                .andExpect(status().isOk()).andReturn());
        assertThat(agreement.get("plan").get("reviewerPolicy").get("kind").asText()).isEqualTo("SELF_CHECK");
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

    private JsonNode taskOf(String token, String taskId) throws Exception {
        return data(mockMvc.perform(get("/api/v1/tasks/{id}", taskId).header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andReturn());
    }

    private int taskRevision(String token, String taskId) throws Exception {
        return taskOf(token, taskId).get("revision").asInt();
    }

    private void clearAll() {
        for (String table : List.of("file_reference", "file_authorization", "cleanup_plan", "stored_file",
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
                "rate_window", "import_batch", "user_role", "user_profile", "app_user", "school")) {
            jdbcTemplate.update("DELETE FROM " + table);
        }
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
