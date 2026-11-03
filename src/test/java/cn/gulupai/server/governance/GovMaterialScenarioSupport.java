package cn.gulupai.server.governance;

import cn.gulupai.server.it.TestSchemaCleaner;
import cn.gulupai.server.common.clock.MutableClock;
import cn.gulupai.server.identity.cli.AcademicImportService;
import cn.gulupai.server.identity.cli.AdminImportService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * U01/U02 材料状态与时间用例的真实 HTTP + PostgreSQL 共同夹具。
 * 只读/写业务动作走 HTTP；只有制造升级前数据、数据异常或控制两连接锁顺序时才用 JDBC。
 */
abstract class GovMaterialScenarioSupport {

    protected static final Instant BASE = Instant.parse("2026-10-31T00:00:00Z");
    protected static final String SCHOOL = "school_demo_01";
    protected static final String TERM = "term_demo_2026a";
    protected static final String LEADER = "user_demo_student_01";
    protected static final String MEMBER = "user_demo_student_02";
    protected static final String ASSISTANT = "user_demo_assistant_01";
    protected static final String TEACHER_A = "user_demo_teacher_01";
    protected static final String TEACHER_B = "user_demo_teacher_02";

    @Autowired protected MockMvc mockMvc;
    @Autowired protected ObjectMapper objectMapper;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected DataSource dataSource;
    @Autowired protected MutableClock clock;
    @Autowired protected AdminImportService userImportService;
    @Autowired protected AcademicImportService academicImportService;

    @BeforeEach
    void setUpMaterialScenario() throws Exception {
        clearAll();
        clock.setInstant(BASE);
        userImportService.importUsers(Path.of("admin/fixtures/p01-demo-users.json"), false);
        academicImportService.importAcademic(Path.of("admin/fixtures/p02-demo-academic.json"), false);
    }

    @AfterEach
    void tearDownMaterialScenario() {
        clearAll();
    }

    protected Scenario materialScenario(String prefix) throws Exception {
        String leader = login("student_demo_01", "demo-password-01");
        String member = login("student_demo_02", "demo-password-02");
        String teacher = login("teacher_demo_01", "demo-password-03");
        Project project = projectObj(leader, prefix);
        invite(leader, project.id(), MEMBER);
        acceptInvite(member, project.id());
        String changeId = createExit(member, project.id(), MEMBER);
        String caseId = caseIdOfChange(changeId);
        acceptCase(teacher, caseId);
        String materialId = requestMaterial(teacher, caseId, MEMBER);
        return new Scenario(project.id(), caseId, materialId, leader, member, teacher);
    }

    protected MvcResult respond(String token, String materialId, String content, int expectedRevision, String key)
            throws Exception {
        return mockMvc.perform(post("/api/v1/material-requests/{id}/responses", materialId)
                .header("Authorization", bearer(token)).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "content", content,
                        "expectedRevision", expectedRevision,
                        "evidenceRefs", List.of(Map.of("kind", "LINK",
                                "url", "https://example.test/evidence",
                                "title", "证据")))))).andReturn();
    }

    protected MvcResult respondOk(String token, String materialId, String content, int expectedRevision, String key)
            throws Exception {
        return mockMvc.perform(post("/api/v1/material-requests/{id}/responses", materialId)
                .header("Authorization", bearer(token)).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "content", content,
                        "expectedRevision", expectedRevision,
                        "evidenceRefs", List.of(Map.of("kind", "LINK",
                                "url", "https://example.test/evidence",
                                "title", "证据"))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.response.content").value(content))
                .andReturn();
    }

    protected MvcResult review(String token, String materialId, int responseVersion, String decision,
                               String reason, int expectedRevision, String key) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("responseVersion", responseVersion);
        body.put("decision", decision);
        if (reason != null) {
            body.put("reason", reason);
        }
        body.put("expectedRevision", expectedRevision);
        return mockMvc.perform(post("/api/v1/material-requests/{id}/reviews", materialId)
                .header("Authorization", bearer(token)).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body))).andReturn();
    }

    protected MvcResult reviewOk(String token, String materialId, int responseVersion, String decision,
                                 String reason, int expectedRevision, String key) throws Exception {
        return mockMvc.perform(post("/api/v1/material-requests/{id}/reviews", materialId)
                .header("Authorization", bearer(token)).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(reviewBody(responseVersion, decision, reason, expectedRevision)))
                .andExpect(status().isOk()).andReturn();
    }

    protected String reviewBody(int responseVersion, String decision, String reason, int expectedRevision)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("responseVersion", responseVersion);
        body.put("decision", decision);
        if (reason != null) {
            body.put("reason", reason);
        }
        body.put("expectedRevision", expectedRevision);
        return objectMapper.writeValueAsString(body);
    }

    protected MvcResult withdraw(String token, String materialId, int expectedRevision, String reason, String key)
            throws Exception {
        return mockMvc.perform(post("/api/v1/material-requests/{id}/withdrawals", materialId)
                .header("Authorization", bearer(token)).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "expectedRevision", expectedRevision, "reason", reason)))).andReturn();
    }

    protected MvcResult withdrawOk(String token, String materialId, int expectedRevision, String reason, String key)
            throws Exception {
        return mockMvc.perform(post("/api/v1/material-requests/{id}/withdrawals", materialId)
                .header("Authorization", bearer(token)).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "expectedRevision", expectedRevision, "reason", reason))))
                .andExpect(status().isOk()).andReturn();
    }

    protected JsonNode materialData(String token, String materialId) throws Exception {
        return data(mockMvc.perform(get("/api/v1/material-requests/{id}", materialId)
                .header("Authorization", bearer(token))).andExpect(status().isOk()).andReturn());
    }

    protected JsonNode rawMaterialJson(String token, String materialId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/material-requests/{id}", materialId)
                .header("Authorization", bearer(token))).andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    protected void closeCase(String token, String caseId, String key) throws Exception {
        mockMvc.perform(post("/api/v1/personnel-cases/{id}/closures", caseId)
                .header("Authorization", bearer(token)).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedRevision\":" + caseRevision(caseId) + ",\"reason\":\"结案\"}"))
                .andExpect(status().isOk());
    }

    protected String caseIdOfChange(String changeId) {
        return jdbc.queryForObject("SELECT id FROM personnel_case WHERE member_change_id = ?", String.class, changeId);
    }

    protected int caseRevision(String caseId) {
        return jdbc.queryForObject("SELECT revision FROM personnel_case WHERE id = ?", Integer.class, caseId);
    }

    protected int materialRevision(String materialId) {
        return jdbc.queryForObject("SELECT revision FROM material_request WHERE id = ?", Integer.class, materialId);
    }

    protected int materialResponseVersion(String materialId) {
        return jdbc.queryForObject("SELECT response_version FROM material_request WHERE id = ?",
                Integer.class, materialId);
    }

    protected String materialStatus(String materialId) {
        return jdbc.queryForObject("SELECT status FROM material_request WHERE id = ?", String.class, materialId);
    }

    protected int responseCount(String materialId) {
        return jdbc.queryForObject("SELECT count(*) FROM material_response WHERE material_id = ?",
                Integer.class, materialId);
    }

    protected int reviewCount(String materialId) {
        return jdbc.queryForObject("SELECT count(*) FROM material_review WHERE material_id = ?",
                Integer.class, materialId);
    }

    protected boolean responseWithdrawn(String materialId, int version) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT withdrawn FROM material_response WHERE material_id = ? AND version = ?",
                Boolean.class, materialId, version));
    }

    protected String materialResponseId(String materialId, int version) {
        return jdbc.queryForObject("SELECT id FROM material_response WHERE material_id = ? AND version = ?",
                String.class, materialId, version);
    }

    protected String caseStatus(String caseId) {
        return jdbc.queryForObject("SELECT status FROM personnel_case WHERE id = ?", String.class, caseId);
    }

    protected String login(String loginId, String password) throws Exception {
        return data(mockMvc.perform(post("/api/v1/auth/sessions").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("loginId", loginId, "password", password))))
                .andExpect(status().isOk()).andReturn()).get("accessToken").asText();
    }

    protected static String bearer(String token) {
        return "Bearer " + token;
    }

    protected JsonNode data(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
    }

    protected String project(String token, String key) throws Exception {
        return projectObj(token, key).id();
    }

    protected Project projectObj(String token, String key) throws Exception {
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
        JsonNode created = data(mockMvc.perform(post("/api/v1/projects")
                        .header("Authorization", bearer(token)).header("Idempotency-Key", key + "-project")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "squareId", square.get("id").asText(), "assignmentId", assignment.get("id").asText(),
                                "mode", "QUICK", "name", "治理组", "capacity", 4, "discussionTime", "周三",
                                "roles", List.of()))))
                .andExpect(status().isOk()).andReturn());
        return new Project(created.get("id").asText(), created.get("stages").get(0).get("id").asText());
    }

    protected void invite(String leader, String projectId, String userId) throws Exception {
        int revision = data(mockMvc.perform(get("/api/v1/projects/{id}", projectId)
                .header("Authorization", bearer(leader))).andExpect(status().isOk()).andReturn())
                .get("revision").asInt();
        ObjectNode body = objectMapper.createObjectNode();
        body.put("expectedProjectRevision", revision);
        body.put("recipientId", userId);
        body.put("message", "加入");
        mockMvc.perform(post("/api/v1/projects/{id}/invitations", projectId)
                        .header("Authorization", bearer(leader))
                        .header("Idempotency-Key", "invite-" + projectId + "-" + userId)
                        .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk());
    }

    protected void acceptInvite(String token, String projectId) throws Exception {
        String invitationId = jdbc.queryForObject("""
                SELECT id FROM team_invitation WHERE project_id = ? AND status = 'PENDING'
                ORDER BY created_at DESC LIMIT 1
                """, String.class, projectId);
        mockMvc.perform(post("/api/v1/invitations/{id}/responses", invitationId)
                        .header("Authorization", bearer(token)).header("Idempotency-Key", "accept-inv-" + invitationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"decision\":\"ACCEPT\"}"))
                .andExpect(status().isOk());
    }

    protected String createExit(String token, String projectId, String userId) throws Exception {
        int revision = jdbc.queryForObject("""
                SELECT revision FROM membership WHERE project_id = ? AND user_id = ? AND status = 'ACTIVE'
                """, Integer.class, projectId, userId);
        return data(mockMvc.perform(post("/api/v1/projects/{id}/member-changes", projectId)
                        .header("Authorization", bearer(token)).header("Idempotency-Key",
                                "exit-" + projectId + "-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"EXIT\",\"reason\":\"离开\",\"expectedMembershipRevision\":"
                                + revision + "}"))
                .andExpect(status().isOk()).andReturn()).get("id").asText();
    }

    protected void acceptCase(String token, String caseId) throws Exception {
        mockMvc.perform(post("/api/v1/personnel-cases/{id}/acceptances", caseId)
                        .header("Authorization", bearer(token)).header("Idempotency-Key", "accept-" + caseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":" + caseRevision(caseId) + "}"))
                .andExpect(status().isOk());
    }

    protected String requestMaterial(String token, String caseId, String recipient) throws Exception {
        return data(mockMvc.perform(post("/api/v1/personnel-cases/{id}/material-requests", caseId)
                        .header("Authorization", bearer(token)).header("Idempotency-Key", "material-" + caseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(materialCreate(caseRevision(caseId), recipient)))
                .andExpect(status().isOk()).andReturn()).get("id").asText();
    }

    protected String materialCreate(int caseRevision, String recipient) {
        return "{\"expectedCaseRevision\":" + caseRevision + ",\"recipientId\":\"" + recipient + "\","
                + "\"requirements\":\"提交明确正文\",\"dueAt\":\"2026-11-10T15:59:59Z\"}";
    }

    protected void clearAll() {
        TestSchemaCleaner.deleteAll(jdbc, List.of(
                "material_review", "material_response",
                "personnel_handoff", "material_request", "personnel_record", "personnel_case_grant", "personnel_case",
                "removal_ballot", "handover_ack", "handover_item", "member_change_event",
                "member_change", "removal_vote",
                "teacher_summary_consent", "teacher_summary", "grade_appeal", "grade_snapshot",
                "grade_publication", "settlement", "ledger_correction", "grade_draft", "grade_rule",
                "contribution_confirmation", "unit_acceptance", "acceptance", "submission",
                "delivery_draft", "proposal_response", "unit_change_proposal",
                "reviewer_invitation", "reviewer_arrangement", "agreement_ack", "unit_share",
                "task_dependency", "criterion", "delivery_unit", "agreement", "task", "user_project_color",
                "role_ack", "role_negotiation", "announcement_reminder", "announcement_preview",
                "announcement_receipt", "announcement_recipient", "announcement_version", "help_article",
                "help_feedback", "support_route", "support_ticket", "support_reply", "support_history",
                "operator_grant", "content_report", "report_handling", "announcement",
                "notification_preference", "notification", "board_topic", "board_reply", "content_revision",
                "topic_follow", "chat_counter", "chat_message", "chat_change", "chat_read_state",
                "notification_outbox", "dice_round", "dice_roll", "dice_participant", "dice_session",
                "ai_reference", "peer_review_version", "peer_review",
                "work_version", "work", "project_code", "seat_reservation", "team_invitation",
                "team_application", "assignment_team_slot", "membership", "role_slot", "project_stage",
                "project", "teacher_transfer", "teacher_claim", "teacher_assignment", "class_grant",
                "course_code", "course_join_request", "cursor_snapshot", "assignment", "course_membership",
                "merge_ballot", "square_merge", "preview_token", "square", "verified_enrollment",
                "verified_course", "course_name", "period", "term", "operation_receipt", "access_token",
                "auth_session", "audit_event", "rate_window", "import_batch", "user_role", "user_profile",
                "app_user", "school"));
    }

    protected record Project(String id, String stageId) {
    }

    protected record Scenario(String projectId, String caseId, String materialId, String leader,
                              String member, String teacher) {
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
