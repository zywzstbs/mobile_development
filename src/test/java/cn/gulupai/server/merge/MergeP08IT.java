package cn.gulupai.server.merge;

import cn.gulupai.server.it.TestSchemaCleaner;
import cn.gulupai.server.common.clock.MutableClock;
import cn.gulupai.server.common.security.Hashing;
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
@Import(MergeP08IT.TestClockConfig.class)
class MergeP08IT {

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
    void mergeKeepsAssignmentIdentityAndDropsOldAuthority() throws Exception {
        String leader = login("student_demo_01", "demo-password-01");
        String member = login("student_demo_02", "demo-password-02");
        String teacher = login("teacher_demo_01", "demo-password-03");
        Square source = square(leader, "p08-source", "course_name_demo_mobile", 3, "period_demo_2");
        Square otherClass = square(leader, "p08-other", "course_name_demo_db", 5, "period_demo_3");
        String targetId = cloneSquare(source.id());
        membership(source.id(), MEMBER);
        membership(targetId, LEADER);
        membership(targetId, MEMBER);
        teacherOf(targetId);
        String targetAssignment = assignment(leader, teacher, targetId, "p08-target-assignment");
        String targetProject = project(leader, targetId, targetAssignment, "p08-target-project");
        issueCode(teacher, source.id(), "p08-code");
        grant(source.id());
        mockMvc.perform(post("/api/v1/square-merge-proposals")
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "p08-different")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(proposal(source.id(), otherClass.id(), revision(source.id()), revision(otherClass.id()))))
                .andExpect(status().isUnprocessableEntity());
        JsonNode created = data(mockMvc.perform(post("/api/v1/square-merge-proposals")
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "p08-create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(proposal(source.id(), targetId, revision(source.id()), revision(targetId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("VOTING"))
                .andExpect(jsonPath("$.data.sides[0].requiredCount").value(2))
                .andReturn());
        String proposalId = created.get("id").asText();
        vote(leader, proposalId, source.id(), "p08-s1");
        vote(member, proposalId, source.id(), "p08-s2");
        vote(leader, proposalId, targetId, "p08-t1");
        mockMvc.perform(post("/api/v1/square-merge-proposals/{id}/executions", proposalId)
                        .header("Authorization", bearer(teacher)).header("Idempotency-Key", "p08-early")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":" + revisionOf(teacher, proposalId) + "}"))
                .andExpect(status().isUnprocessableEntity());
        JsonNode waiting = vote(member, proposalId, targetId, "p08-t2");
        assertThat(waiting.get("status").asText()).isEqualTo("WAIT_FACT");
        assertThat(waiting.get("sides").get(0).get("eligibleCount").asInt()).isPositive();
        assertThat(waiting.get("sides").get(0).get("passed").asBoolean()).isTrue();
        mockMvc.perform(post("/api/v1/square-merge-proposals/{id}/fact-checks", proposalId)
                        .header("Authorization", bearer(teacher)).header("Idempotency-Key", "p08-mix")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(fact(revisionOf(teacher, proposalId), source.assignmentId(), false)))
                .andExpect(status().isUnprocessableEntity());
        int epoch = jdbcTemplate.queryForObject(
                "SELECT authorization_epoch FROM square WHERE id = ?", Integer.class, targetId);
        String cursor = "p08-cursor-token";
        jdbcTemplate.update("""
                INSERT INTO cursor_snapshot
                    (id, token_hash, user_id, subject_type, subject_key, scope_key, query_hash, epoch,
                     ids_json, total, position, expires_at)
                VALUES (?, ?, 'user_demo_teacher_01', 'USER', 'user_demo_teacher_01', ?, ?, ?,
                        '[]'::jsonb, 0, 0, '2026-10-31T01:00:00Z')
                """, UUID.randomUUID().toString(), Hashing.sha256Hex(cursor),
                "SQUARE_JOIN_REQUESTS:" + targetId,
                Hashing.sha256Hex("null|" + epoch), epoch);
        mockMvc.perform(post("/api/v1/square-merge-proposals/{id}/fact-checks", proposalId)
                        .header("Authorization", bearer(teacher)).header("Idempotency-Key", "p08-fact")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(fact(revisionOf(teacher, proposalId), source.assignmentId(), true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.canExecute").value(true));
        mockMvc.perform(post("/api/v1/square-merge-proposals/{id}/executions", proposalId)
                        .header("Authorization", bearer(teacher)).header("Idempotency-Key", "p08-exec")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":" + revisionOf(teacher, proposalId) + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));
        assertThat(jdbcTemplate.queryForObject("SELECT id FROM assignment WHERE id = ?", String.class, source.assignmentId()))
                .isEqualTo(source.assignmentId());
        assertThat(jdbcTemplate.queryForObject("SELECT square_id FROM assignment WHERE id = ?", String.class, source.assignmentId()))
                .isEqualTo(targetId);
        assertThat(jdbcTemplate.queryForObject("SELECT id FROM project WHERE id = ?", String.class, source.projectId()))
                .isEqualTo(source.projectId());
        assertThat(jdbcTemplate.queryForObject("SELECT square_id FROM project WHERE id = ?", String.class, source.projectId()))
                .isEqualTo(targetId);
        assertThat(jdbcTemplate.queryForObject("SELECT id FROM assignment WHERE id = ?", String.class, targetAssignment))
                .isEqualTo(targetAssignment);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM square WHERE id = ?", String.class, source.id()))
                .isEqualTo("MERGED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM course_code WHERE square_id = ?", String.class, source.id())).isEqualTo("REVOKED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM class_grant WHERE square_id = ? AND user_id = ?", Integer.class, targetId, ASSISTANT))
                .isZero();
        mockMvc.perform(get("/api/v1/projects/{id}/members", source.projectId()).header("Authorization", bearer(teacher)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/squares/{id}/join-requests", targetId)
                        .header("Authorization", bearer(teacher)).param("cursor", cursor))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CURSOR_EXPIRED"));
        assertThat(targetProject).isNotBlank();
        mockMvc.perform(get("/api/v1/squares/{id}/merge-proposals", targetId).header("Authorization", bearer(leader)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1));
    }

    @Test
    void electorateChangeCancelsTheVote() throws Exception {
        String leader = login("student_demo_01", "demo-password-01");
        String member = login("student_demo_02", "demo-password-02");
        Square source = square(leader, "p08-qual-source", "course_name_demo_mobile", 3, "period_demo_2");
        String targetId = cloneSquare(source.id());
        membership(source.id(), MEMBER);
        membership(targetId, LEADER);
        membership(targetId, MEMBER);
        teacherOf(targetId);
        JsonNode created = data(mockMvc.perform(post("/api/v1/square-merge-proposals")
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "p08-qual")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(proposal(source.id(), targetId, revision(source.id()), revision(targetId))))
                .andExpect(status().isOk()).andReturn());
        vote(leader, created.get("id").asText(), source.id(), "p08-qual-vote");
        jdbcTemplate.update("UPDATE course_membership SET status = 'LEFT' WHERE square_id = ? AND user_id = ?",
                source.id(), MEMBER);
        mockMvc.perform(get("/api/v1/square-merge-proposals/{id}", created.get("id").asText())
                        .header("Authorization", bearer(leader)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("QUALIFICATION_REVIEW"));
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM merge_ballot WHERE proposal_id = ?",
                Integer.class, created.get("id").asText())).isZero();
    }

    @Test
    void initiatorCanCancelAndOpenAnotherProposal() throws Exception {
        String leader = login("student_demo_01", "demo-password-01");
        String member = login("student_demo_02", "demo-password-02");
        Square source = square(leader, "p08-cancel-source", "course_name_demo_mobile", 3, "period_demo_2");
        String targetId = cloneSquare(source.id());
        membership(source.id(), MEMBER);
        membership(targetId, LEADER);
        membership(targetId, MEMBER);
        teacherOf(targetId);
        JsonNode created = data(mockMvc.perform(post("/api/v1/square-merge-proposals")
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "p08-cancel-create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(proposal(source.id(), targetId, revision(source.id()), revision(targetId))))
                .andExpect(status().isOk()).andReturn());
        String proposalId = created.get("id").asText();
        mockMvc.perform(post("/api/v1/square-merge-proposals/{id}/cancellations", proposalId)
                        .header("Authorization", bearer(member)).header("Idempotency-Key", "p08-cancel-other")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"不是发起人\",\"expectedRevision\":" + created.get("revision").asInt() + "}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/square-merge-proposals/{id}/cancellations", proposalId)
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "p08-cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"先不合并\",\"expectedRevision\":" + created.get("revision").asInt() + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));
        mockMvc.perform(post("/api/v1/square-merge-proposals")
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "p08-cancel-again")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(proposal(source.id(), targetId, revision(source.id()), revision(targetId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("VOTING"));
    }

    private JsonNode vote(String token, String proposalId, String squareId, String key) throws Exception {
        return data(mockMvc.perform(put("/api/v1/square-merge-proposals/{id}/me/ballots", proposalId)
                        .header("Authorization", bearer(token)).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"squareId\":\"" + squareId + "\",\"expectedRevision\":0,\"choice\":\"AGREE\"}"))
                .andExpect(status().isOk()).andReturn());
    }

    private String proposal(String sourceId, String targetId, int sourceRevision, int targetRevision) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "sourceSquareId", sourceId, "targetSquareId", targetId,
                "expectedSourceRevision", sourceRevision, "expectedTargetRevision", targetRevision,
                "reason", "同一教学班"));
    }

    private String fact(int revision, String assignmentId, boolean keepSeparate) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "decision", "CONFIRM", "reason", "作业分开保留", "expectedRevision", revision,
                "assignmentMappings", List.of(Map.of("fromAssignmentId", assignmentId, "keepSeparate", keepSeparate))));
    }

    private int revisionOf(String token, String proposalId) throws Exception {
        return data(mockMvc.perform(get("/api/v1/square-merge-proposals/{id}", proposalId)
                .header("Authorization", bearer(token))).andExpect(status().isOk()).andReturn()).get("revision").asInt();
    }

    private int revision(String squareId) {
        return jdbcTemplate.queryForObject("SELECT revision FROM square WHERE id = ?", Integer.class, squareId);
    }

    private String cloneSquare(String sourceId) {
        String id = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO square
                    (id, verified_course_id, school_id, term_id, name, teacher_name, slots_json,
                     canonical_key, square_no, status, authorization_epoch, revision)
                SELECT ?, verified_course_id, school_id, term_id, name || '副本', teacher_name, slots_json,
                       ?, ?, 'ACTIVE', 1, 1
                FROM square WHERE id = ?
                """, id, "b".repeat(64), "S-P08-" + id.substring(0, 8), sourceId);
        return id;
    }

    private void membership(String squareId, String userId) {
        jdbcTemplate.update("""
                INSERT INTO course_membership (id, square_id, user_id, status, joined_at, revision)
                VALUES (?, ?, ?, 'ACTIVE', '2026-10-31T00:00:00Z', 1)
                """, UUID.randomUUID().toString(), squareId, userId);
    }

    private void teacherOf(String squareId) {
        jdbcTemplate.update("""
                INSERT INTO teacher_assignment (id, square_id, user_id, active, started_at, revision)
                VALUES (?, ?, 'user_demo_teacher_01', true, '2026-10-31T00:00:00Z', 1)
                """, UUID.randomUUID().toString(), squareId);
    }

    private void grant(String squareId) {
        jdbcTemplate.update("""
                INSERT INTO class_grant
                    (id, square_id, user_id, capabilities_json, status, expires_at, granted_by, revision)
                VALUES (?, ?, ?, '["COURSE_READ"]'::jsonb, 'ACTIVE', '2026-12-31T00:00:00Z',
                        'user_demo_teacher_01', 1)
                """, UUID.randomUUID().toString(), squareId, ASSISTANT);
    }

    private void issueCode(String teacher, String squareId, String key) throws Exception {
        mockMvc.perform(post("/api/v1/squares/{id}/join-codes", squareId)
                        .header("Authorization", bearer(teacher)).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":" + revision(squareId) + "}"))
                .andExpect(status().isOk());
    }

    private Square square(String token, String key, String courseNameId, int weekday, String periodId) throws Exception {
        List<Map<String, Object>> slots = List.of(Map.of("weekday", weekday, "periodId", periodId,
                "weekPattern", "ALL", "startWeek", 1, "endWeek", 16, "customWeeks", List.of()));
        JsonNode match = data(mockMvc.perform(post("/api/v1/squares/matches")
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "schoolId", SCHOOL, "termId", TERM, "teacherName", "示例教师",
                                "slots", slots, "courseNameId", courseNameId))))
                .andExpect(status().isOk()).andReturn());
        JsonNode square = data(mockMvc.perform(post("/api/v1/squares")
                        .header("Authorization", bearer(token)).header("Idempotency-Key", key + "-square")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "schoolId", SCHOOL, "termId", TERM, "teacherName", "示例教师",
                                "slots", slots, "courseNameId", courseNameId,
                                "candidateCheckToken", match.get("candidateCheckToken").asText()))))
                .andExpect(status().isOk()).andReturn());
        String assignmentId = assignment(token, login("teacher_demo_01", "demo-password-03"),
                square.get("id").asText(), key + "-assignment");
        String projectId = project(token, square.get("id").asText(), assignmentId, key + "-project");
        return new Square(square.get("id").asText(), assignmentId, projectId);
    }

    private String assignment(String student, String teacher, String squareId, String key) throws Exception {
        JsonNode assignment = data(mockMvc.perform(post("/api/v1/squares/{id}/assignments", squareId)
                        .header("Authorization", bearer(student)).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "课程设计", "requirements", "完成", "hardDueAt", "2026-12-15T15:59:59Z",
                                "minMembers", 2, "maxMembers", 6, "sourceReferences", List.of()))))
                .andExpect(status().isOk()).andReturn());
        mockMvc.perform(post("/api/v1/assignments/{id}/confirmations", assignment.get("id").asText())
                        .header("Authorization", bearer(teacher)).header("Idempotency-Key", key + "-confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"decision\":\"CONFIRM_NEW\",\"reason\":\"ok\"}"))
                .andExpect(status().isOk());
        return assignment.get("id").asText();
    }

    private String project(String token, String squareId, String assignmentId, String key) throws Exception {
        return data(mockMvc.perform(post("/api/v1/projects")
                        .header("Authorization", bearer(token)).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "squareId", squareId, "assignmentId", assignmentId, "mode", "QUICK",
                                "name", "合并组", "capacity", 4, "discussionTime", "周三", "roles", List.of()))))
                .andExpect(status().isOk()).andReturn()).get("id").asText();
    }

    private void clearAll() {
        TestSchemaCleaner.deleteAll(jdbcTemplate, List.of(
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
                "course_code", "course_join_request", "merge_ballot", "square_merge", "cursor_snapshot",
                "assignment", "course_membership", "preview_token", "square",
                "verified_enrollment", "verified_course", "course_name", "period", "term",
                "operation_receipt", "access_token", "auth_session", "audit_event",
                "rate_window", "import_batch", "user_role", "user_profile", "app_user", "school"));
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

    private record Square(String id, String assignmentId, String projectId) {
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
