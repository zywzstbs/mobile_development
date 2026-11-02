package cn.gulupai.server.governance;

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
import java.time.Duration;
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
@Import(GovP07IT.TestClockConfig.class)
class GovP07IT {

    private static final Instant BASE = Instant.parse("2026-10-31T00:00:00Z");
    private static final String SCHOOL = "school_demo_01";
    private static final String TERM = "term_demo_2026a";
    private static final String LEADER = "user_demo_student_01";
    private static final String MEMBER = "user_demo_student_02";
    private static final String ASSISTANT = "user_demo_assistant_01";

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
    void leaderHandoverMovesMembershipOnlyAfterSeparateAcceptance() throws Exception {
        String leader = login("student_demo_01", "demo-password-01");
        String member = login("student_demo_02", "demo-password-02");
        String assistant = login("assistant_demo_01", "demo-password-04");
        String teacher = login("teacher_demo_01", "demo-password-03");
        Project project = project(leader, "p07-leader");
        mockMvc.perform(post("/api/v1/projects/{id}/member-changes", project.id())
                        .header("Authorization", bearer(leader))
                        .header("Idempotency-Key", "p07-sole")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"EXIT\",\"reason\":\"只剩自己\",\"expectedMembershipRevision\":1}"))
                .andExpect(status().isUnprocessableEntity());
        invite(leader, project.id(), MEMBER, "p07-leader-invite");
        acceptInvite(member, project.id(), "p07-leader-accept");
        invite(leader, project.id(), ASSISTANT, "p07-leader-invite-a");
        acceptInvite(assistant, project.id(), "p07-leader-accept-a");
        int revision = membershipRevision(project.id(), LEADER);
        JsonNode change = data(mockMvc.perform(post("/api/v1/projects/{id}/member-changes", project.id())
                        .header("Authorization", bearer(leader))
                        .header("Idempotency-Key", "p07-exit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"EXIT\",\"reason\":\"组长离任\",\"expectedMembershipRevision\":" + revision + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("WAIT_PERSONNEL"))
                .andReturn());
        String changeId = change.get("id").asText();
        String caseId = jdbcTemplate.queryForObject(
                "SELECT id FROM personnel_case WHERE member_change_id = ?", String.class, changeId);
        mockMvc.perform(post("/api/v1/member-changes/{id}/personnel-decisions", changeId)
                        .header("Authorization", bearer(assistant))
                        .header("Idempotency-Key", "p07-assistant-decide")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(decision(change.get("revision").asInt(), "ALLOW_HANDOVER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("RECUSAL_REQUIRED"));
        mockMvc.perform(get("/api/v1/personnel-cases/{id}", caseId).header("Authorization", bearer(leader)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.staffRecords").doesNotExist());
        mockMvc.perform(post("/api/v1/personnel-cases/{id}/acceptances", caseId)
                        .header("Authorization", bearer(teacher))
                        .header("Idempotency-Key", "p07-case-accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/personnel-cases/{id}/updates", caseId)
                        .header("Authorization", bearer(teacher))
                        .header("Idempotency-Key", "p07-case-note")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":2,\"facts\":\"内部记录\",\"nextAction\":\"继续\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/personnel-cases/{id}", caseId).header("Authorization", bearer(leader)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.staffRecords").doesNotExist());
        mockMvc.perform(get("/api/v1/personnel-cases/{id}", caseId).header("Authorization", bearer(teacher)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.staffRecords.length()").value(1));
        JsonNode planned = data(mockMvc.perform(put("/api/v1/member-changes/{id}/handover-plan", changeId)
                        .header("Authorization", bearer(leader))
                        .header("Idempotency-Key", "p07-plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "expectedRevision", 1, "items", List.of(), "nextLeaderId", MEMBER, "notes", "交接"))))
                .andExpect(status().isOk()).andReturn());
        String leaderItem = planned.get("handoverPlan").get("items").get(0).get("id").asText();
        assertThat(planned.get("handoverPlan").get("items").get(0).get("kind").asText()).isEqualTo("LEADER");
        ack(member, changeId, planned.get("revision").asInt(), planned.get("planVersion").asInt(),
                List.of(leaderItem), "p07-ack-id");
        int acks = ackCount(changeId);
        JsonNode after = changeOf(leader, changeId);
        ack(member, changeId, after.get("revision").asInt(), after.get("planVersion").asInt(), List.of(), "p07-ack-empty");
        assertThat(ackCount(changeId)).isEqualTo(acks);
        mockMvc.perform(post("/api/v1/member-changes/{id}/handover-responses", changeId)
                        .header("Authorization", bearer(assistant))
                        .header("Idempotency-Key", "p07-ack-other")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ackBody(after.get("revision").asInt(), after.get("planVersion").asInt(), List.of(leaderItem))))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/member-changes/{id}/executions", changeId)
                        .header("Authorization", bearer(member))
                        .header("Idempotency-Key", "p07-exec-early")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":" + changeOf(leader, changeId).get("revision").asInt()
                                + ",\"planVersion\":1}"))
                .andExpect(status().isUnprocessableEntity());
        allow(teacher, changeId, changeOf(leader, changeId).get("revision").asInt());
        int readyRevision = changeOf(leader, changeId).get("revision").asInt();
        mockMvc.perform(post("/api/v1/member-changes/{id}/executions", changeId)
                        .header("Authorization", bearer(member))
                        .header("Idempotency-Key", "p07-exec")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":" + readyRevision + ",\"planVersion\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("EXECUTED"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM membership WHERE project_id = ? AND user_id = ?", String.class, project.id(), LEADER))
                .isEqualTo("LEFT");
        assertThat(jdbcTemplate.queryForObject("SELECT owner_id FROM project WHERE id = ?", String.class, project.id()))
                .isEqualTo(MEMBER);
        mockMvc.perform(get("/api/v1/me/membership-history").header("Authorization", bearer(leader)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1));
    }

    @Test
    void staleTaskAndPendingSubmissionBlockExecution() throws Exception {
        String leader = login("student_demo_01", "demo-password-01");
        String member = login("student_demo_02", "demo-password-02");
        String teacher = login("teacher_demo_01", "demo-password-03");
        Project project = project(leader, "p07-block");
        invite(leader, project.id(), MEMBER, "p07-block-invite");
        acceptInvite(member, project.id(), "p07-block-accept");
        JsonNode task = data(mockMvc.perform(post("/api/v1/projects/{id}/tasks", project.id())
                        .header("Authorization", bearer(leader))
                        .header("Idempotency-Key", "p07-task")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "title", "未完任务", "description", "", "mode", "ORDINARY", "stageId", project.stageId(),
                                "criteria", List.of(Map.of("text", "完成")), "dependencies", List.of(),
                                "dueAt", "2026-11-06T15:59:59Z"))))
                .andExpect(status().isOk()).andReturn());
        mockMvc.perform(post("/api/v1/tasks/{id}/claims", task.get("id").asText())
                        .header("Authorization", bearer(member))
                        .header("Idempotency-Key", "p07-claim")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"startNow\":true}"))
                .andExpect(status().isOk());
        int taskRevision = 2;
        JsonNode change = data(mockMvc.perform(post("/api/v1/projects/{id}/member-changes", project.id())
                        .header("Authorization", bearer(member))
                        .header("Idempotency-Key", "p07-member-exit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"EXIT\",\"reason\":\"离开\",\"expectedMembershipRevision\":"
                                + membershipRevision(project.id(), MEMBER) + "}"))
                .andExpect(status().isOk()).andReturn());
        String changeId = change.get("id").asText();
        allow(teacher, changeId, change.get("revision").asInt());
        JsonNode planned = data(mockMvc.perform(put("/api/v1/member-changes/{id}/handover-plan", changeId)
                        .header("Authorization", bearer(leader))
                        .header("Idempotency-Key", "p07-block-plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "expectedRevision", changeOf(leader, changeId).get("revision").asInt(),
                                "items", List.of(Map.of("taskId", task.get("id").asText(), "sourceTaskRevision", taskRevision,
                                        "recipientId", LEADER, "responsibility", "接续")),
                                "nextLeaderId", "", "notes", ""))))
                .andExpect(status().isOk()).andReturn());
        String itemId = planned.get("handoverPlan").get("items").get(0).get("id").asText();
        ack(leader, changeId, planned.get("revision").asInt(), planned.get("planVersion").asInt(), List.of(itemId), "p07-block-ack");
        mockMvc.perform(post("/api/v1/tasks/{id}/submissions", task.get("id").asText())
                        .header("Authorization", bearer(member))
                        .header("Idempotency-Key", "p07-pending")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedTaskRevision\":2,\"unitIds\":[],\"summary\":\"待验收\"}"))
                .andExpect(status().isOk());
        int revision = changeOf(leader, changeId).get("revision").asInt();
        mockMvc.perform(post("/api/v1/member-changes/{id}/executions", changeId)
                        .header("Authorization", bearer(leader))
                        .header("Idempotency-Key", "p07-block-exec")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":" + revision + ",\"planVersion\":1}"))
                .andExpect(status().isUnprocessableEntity());
        jdbcTemplate.update("UPDATE submission SET status = 'WITHDRAWN' WHERE task_id = ?", task.get("id").asText());
        jdbcTemplate.update("UPDATE task SET revision = revision + 1 WHERE id = ?", task.get("id").asText());
        mockMvc.perform(post("/api/v1/member-changes/{id}/executions", changeId)
                        .header("Authorization", bearer(leader))
                        .header("Idempotency-Key", "p07-stale-exec")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":" + revision + ",\"planVersion\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SOURCE_CHANGED"));
    }

    @Test
    void removalCancelsWhenElectorateChanges() throws Exception {
        String leader = login("student_demo_01", "demo-password-01");
        String member = login("student_demo_02", "demo-password-02");
        String assistant = login("assistant_demo_01", "demo-password-04");
        Project project = project(leader, "p07-vote");
        invite(leader, project.id(), MEMBER, "p07-vote-i1");
        acceptInvite(member, project.id(), "p07-vote-a1");
        invite(leader, project.id(), ASSISTANT, "p07-vote-i2");
        acceptInvite(assistant, project.id(), "p07-vote-a2");
        JsonNode vote = data(mockMvc.perform(post("/api/v1/projects/{id}/removal-votes", project.id())
                        .header("Authorization", bearer(leader))
                        .header("Idempotency-Key", "p07-vote")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "targetUserId", ASSISTANT, "facts", "长期未参与", "communicationHistory", "已沟通"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("STATEMENT"))
                .andReturn());
        clock.advance(Duration.ofHours(48));
        leader = login("student_demo_01", "demo-password-01");
        mockMvc.perform(put("/api/v1/removal-votes/{id}/me/ballot", vote.get("id").asText())
                        .header("Authorization", bearer(leader))
                        .header("Idempotency-Key", "p07-ballot")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":0,\"choice\":\"AGREE\"}"))
                .andExpect(status().isOk());
        jdbcTemplate.update("UPDATE membership SET status = 'LEFT' WHERE project_id = ? AND user_id = ?",
                project.id(), MEMBER);
        mockMvc.perform(get("/api/v1/removal-votes/{id}", vote.get("id").asText())
                        .header("Authorization", bearer(leader)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("QUALIFICATION_REVIEW"));
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM removal_ballot WHERE vote_id = ?",
                Integer.class, vote.get("id").asText())).isZero();
    }

    @Test
    void renewalMovesUnfinishedShare() throws Exception {
        String leader = login("student_demo_01", "demo-password-01");
        String member = login("student_demo_02", "demo-password-02");
        String teacher = login("teacher_demo_01", "demo-password-03");
        Project project = project(leader, "p07-renew");
        invite(leader, project.id(), MEMBER, "p07-renew-invite");
        acceptInvite(member, project.id(), "p07-renew-accept");
        ObjectNode body = objectMapper.createObjectNode();
        body.put("title", "正式任务");
        body.put("description", "");
        body.put("mode", "ASSESSED");
        body.put("stageId", project.stageId());
        body.set("criteria", objectMapper.createArrayNode());
        body.set("dependencies", objectMapper.createArrayNode());
        ObjectNode assessed = body.putObject("assessedPlan");
        assessed.put("ownerId", LEADER);
        assessed.put("dueAt", "2026-11-06T15:59:59Z");
        ObjectNode unit = assessed.putArray("units").addObject();
        unit.put("clientKey", "work");
        unit.put("title", "未完份额");
        unit.put("points", "10.00");
        ObjectNode share = unit.putArray("shares").addObject();
        share.put("userId", MEMBER);
        share.put("points", "10.00");
        share.put("responsibility", "实现");
        unit.putArray("criteria").addObject().put("text", "可以演示");
        assessed.putObject("reviewerPolicy").put("userId", LEADER).put("kind", "INDEPENDENT");
        JsonNode task = data(mockMvc.perform(post("/api/v1/projects/{id}/tasks", project.id())
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "p07-renew-task")
                        .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk()).andReturn());
        String agreementId = jdbcTemplate.queryForObject("SELECT id FROM agreement WHERE task_id = ?",
                String.class, task.get("id").asText());
        mockMvc.perform(post("/api/v1/agreements/{id}/responses", agreementId)
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "p07-renew-ack-l")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"decision\":\"ACCEPT\",\"comment\":\"\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/agreements/{id}/responses", agreementId)
                        .header("Authorization", bearer(member)).header("Idempotency-Key", "p07-renew-ack-m")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":2,\"decision\":\"ACCEPT\",\"comment\":\"\"}"))
                .andExpect(status().isOk());
        int taskRevision = data(mockMvc.perform(get("/api/v1/tasks/{id}", task.get("id").asText())
                .header("Authorization", bearer(leader))).andExpect(status().isOk()).andReturn()).get("revision").asInt();
        JsonNode invitation = data(mockMvc.perform(post("/api/v1/tasks/{id}/reviewer-invitations", task.get("id").asText())
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "p07-renew-review")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"recipientId\":\"" + LEADER + "\",\"expectedTaskRevision\":" + taskRevision + "}"))
                .andExpect(status().isOk()).andReturn());
        mockMvc.perform(post("/api/v1/reviewer-invitations/{id}/responses", invitation.get("id").asText())
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "p07-renew-review-ok")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"decision\":\"ACCEPT\"}"))
                .andExpect(status().isOk());
        JsonNode change = data(mockMvc.perform(post("/api/v1/projects/{id}/member-changes", project.id())
                        .header("Authorization", bearer(member)).header("Idempotency-Key", "p07-renew-exit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"EXIT\",\"reason\":\"离开\",\"expectedMembershipRevision\":"
                                + membershipRevision(project.id(), MEMBER) + "}"))
                .andExpect(status().isOk()).andReturn());
        allow(teacher, change.get("id").asText(), change.get("revision").asInt());
        int sourceRevision = data(mockMvc.perform(get("/api/v1/tasks/{id}", task.get("id").asText())
                .header("Authorization", bearer(leader))).andExpect(status().isOk()).andReturn()).get("revision").asInt();
        JsonNode planned = data(mockMvc.perform(put("/api/v1/member-changes/{id}/handover-plan", change.get("id").asText())
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "p07-renew-plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "expectedRevision", changeOf(leader, change.get("id").asText()).get("revision").asInt(),
                                "items", List.of(Map.of("taskId", task.get("id").asText(), "sourceTaskRevision", sourceRevision,
                                        "recipientId", LEADER, "responsibility", "接续未完成份额")),
                                "nextLeaderId", "", "notes", ""))))
                .andExpect(status().isOk()).andReturn());
        ack(leader, change.get("id").asText(), planned.get("revision").asInt(), planned.get("planVersion").asInt(),
                List.of(planned.get("handoverPlan").get("items").get(0).get("id").asText()), "p07-renew-item");
        mockMvc.perform(post("/api/v1/member-changes/{id}/executions", change.get("id").asText())
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "p07-renew-exec")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":" + changeOf(leader, change.get("id").asText()).get("revision").asInt()
                                + ",\"planVersion\":1}"))
                .andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForObject("SELECT handover_blocked FROM task WHERE id = ?", Boolean.class,
                task.get("id").asText())).isTrue();
        int taskNow = data(mockMvc.perform(get("/api/v1/tasks/{id}", task.get("id").asText())
                .header("Authorization", bearer(leader))).andExpect(status().isOk()).andReturn()).get("revision").asInt();
        mockMvc.perform(post("/api/v1/tasks/{id}/handover-agreement-renewals", task.get("id").asText())
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "p07-renew-sign")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":" + taskNow + ",\"reason\":\"重签\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"));
        assertThat(jdbcTemplate.queryForObject("""
                SELECT user_id FROM unit_share s JOIN delivery_unit u ON u.id = s.unit_id
                WHERE u.task_id = ? ORDER BY s.agreement_version DESC LIMIT 1
                """, String.class, task.get("id").asText())).isEqualTo(LEADER);
    }

    private void allow(String teacher, String changeId, int revision) throws Exception {
        acceptLinkedCase(teacher, changeId);
        mockMvc.perform(post("/api/v1/member-changes/{id}/personnel-decisions", changeId)
                        .header("Authorization", bearer(teacher))
                        .header("Idempotency-Key", "allow-" + changeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(decision(revision, "ALLOW_HANDOVER")))
                .andExpect(status().isOk());
    }

    /** 新链路要求人员决定由当前 assignee 办理；旧用例先让当前教师受理同一事项。 */
    private void acceptLinkedCase(String teacher, String changeId) throws Exception {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT id, assignee_id, revision FROM personnel_case WHERE member_change_id = ?", changeId);
        if (row.get("assignee_id") != null) {
            return;
        }
        mockMvc.perform(post("/api/v1/personnel-cases/{id}/acceptances", row.get("id"))
                        .header("Authorization", bearer(teacher))
                        .header("Idempotency-Key", "allow-accept-" + changeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":" + row.get("revision") + "}"))
                .andExpect(status().isOk());
    }

    private void ack(String token, String changeId, int revision, int planVersion, List<String> items, String key)
            throws Exception {
        mockMvc.perform(post("/api/v1/member-changes/{id}/handover-responses", changeId)
                        .header("Authorization", bearer(token))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ackBody(revision, planVersion, items)))
                .andExpect(status().isOk());
    }

    private String ackBody(int revision, int planVersion, List<String> items) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "expectedRevision", revision, "planVersion", planVersion, "itemIds", items, "decision", "ACCEPT"));
    }

    private String decision(int revision, String value) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "expectedRevision", revision, "decision", value, "placementPlan", "由组内接任",
                "reason", "同意交接", "allowBelowMinimum", false));
    }

    private JsonNode changeOf(String token, String changeId) throws Exception {
        return data(mockMvc.perform(get("/api/v1/member-changes/{id}", changeId)
                .header("Authorization", bearer(token))).andExpect(status().isOk()).andReturn());
    }

    private int ackCount(String changeId) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM handover_ack WHERE change_id = ?", Integer.class, changeId);
    }

    private int membershipRevision(String projectId, String userId) {
        return jdbcTemplate.queryForObject("""
                SELECT revision FROM membership WHERE project_id = ? AND user_id = ? AND status = 'ACTIVE'
                """, Integer.class, projectId, userId);
    }

    private void clearAll() {
        for (String table : List.of(
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
                "slots", List.of(Map.of("weekday", 3, "periodId", "period_demo_2", "weekPattern", "ALL",
                        "startWeek", 1, "endWeek", 16, "customWeeks", List.of())),
                "courseNameId", "course_name_demo_mobile"));
        JsonNode match = data(mockMvc.perform(post("/api/v1/squares/matches")
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON).content(matchBody))
                .andExpect(status().isOk()).andReturn());
        JsonNode square = data(mockMvc.perform(post("/api/v1/squares")
                        .header("Authorization", bearer(token)).header("Idempotency-Key", key + "-square")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "schoolId", SCHOOL, "termId", TERM, "teacherName", "示例教师",
                                "slots", List.of(Map.of("weekday", 3, "periodId", "period_demo_2", "weekPattern", "ALL",
                                        "startWeek", 1, "endWeek", 16, "customWeeks", List.of())),
                                "courseNameId", "course_name_demo_mobile",
                                "candidateCheckToken", match.get("candidateCheckToken").asText()))))
                .andExpect(status().isOk()).andReturn());
        JsonNode assignment = data(mockMvc.perform(post("/api/v1/squares/{id}/assignments", square.get("id").asText())
                        .header("Authorization", bearer(token)).header("Idempotency-Key", key + "-assignment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "课程设计", "requirements", "完成", "hardDueAt", "2026-12-15T15:59:59Z",
                                "minMembers", 2, "maxMembers", 6, "sourceReferences", List.of()))))
                .andExpect(status().isOk()).andReturn());
        String teacher = login("teacher_demo_01", "demo-password-03");
        mockMvc.perform(post("/api/v1/assignments/{id}/confirmations", assignment.get("id").asText())
                        .header("Authorization", bearer(teacher)).header("Idempotency-Key", key + "-confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"decision\":\"CONFIRM_NEW\",\"reason\":\"ok\"}"))
                .andExpect(status().isOk());
        JsonNode created = data(mockMvc.perform(post("/api/v1/projects")
                        .header("Authorization", bearer(token)).header("Idempotency-Key", key + "-project")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "squareId", square.get("id").asText(), "assignmentId", assignment.get("id").asText(),
                                "mode", "QUICK", "name", "治理组", "capacity", 4, "discussionTime", "周三", "roles", List.of()))))
                .andExpect(status().isOk()).andReturn());
        return new Project(created.get("id").asText(), created.get("stages").get(0).get("id").asText());
    }

    private void invite(String leader, String projectId, String userId, String key) throws Exception {
        int revision = data(mockMvc.perform(get("/api/v1/projects/{id}", projectId)
                .header("Authorization", bearer(leader))).andExpect(status().isOk()).andReturn()).get("revision").asInt();
        ObjectNode body = objectMapper.createObjectNode();
        body.put("expectedProjectRevision", revision);
        body.put("recipientId", userId);
        body.put("message", "加入");
        mockMvc.perform(post("/api/v1/projects/{id}/invitations", projectId)
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk());
    }

    private void acceptInvite(String token, String projectId, String key) throws Exception {
        String invitationId = jdbcTemplate.queryForObject("""
                SELECT id FROM team_invitation WHERE project_id = ? AND status = 'PENDING' ORDER BY created_at DESC LIMIT 1
                """, String.class, projectId);
        mockMvc.perform(post("/api/v1/invitations/{id}/responses", invitationId)
                        .header("Authorization", bearer(token)).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"decision\":\"ACCEPT\"}"))
                .andExpect(status().isOk());
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
