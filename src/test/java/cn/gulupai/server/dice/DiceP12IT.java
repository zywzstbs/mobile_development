package cn.gulupai.server.dice;

import cn.gulupai.server.common.clock.MutableClock;
import cn.gulupai.server.dice.application.RandomSource;
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
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(DiceP12IT.TestClockConfig.class)
class DiceP12IT {

    private static final Instant BASE = Instant.parse("2026-10-31T00:00:00Z");
    private static final String SCHOOL = "school_demo_01";
    private static final String TERM = "term_demo_2026a";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MutableClock clock;
    @Autowired private AdminImportService users;
    @Autowired private AcademicImportService academics;
    @Autowired private RandomQueue random;

    @BeforeEach
    void setUp() throws Exception {
        clearAll();
        clock.setInstant(BASE);
        random.clear();
        users.importUsers(Path.of("admin/fixtures/p01-demo-users.json"), false);
        academics.importAcademic(Path.of("admin/fixtures/p02-demo-academic.json"), false);
    }

    @AfterEach
    void tearDown() {
        clearAll();
    }

    private void clearAll() {
        for (String table : List.of("announcement_reminder", "announcement_preview", "announcement_receipt",
                "announcement_recipient", "announcement_version", "help_article", "help_feedback", "support_route", "support_ticket",
                "support_reply", "support_history", "operator_grant", "content_report",
                "report_handling", "announcement",
                "notification_preference", "notification", "board_topic", "board_reply", "content_revision", "topic_follow",
                "chat_counter", "chat_message", "chat_change", "chat_read_state",
                "notification_outbox", "dice_round", "dice_roll", "dice_participant", "dice_session",
                "report_snapshot", "delivery_release", "delivery_release_preview",
                "delivery_release_draft", "overall_reflection", "project_reflection",
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
                "app_user", "school")) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    @Test
    void uniqueMaxHidesOtherRollsUntilAllRolledAndClaimCompletesSession() throws Exception {
        String leader = login("student_demo_01", "demo-password-01");
        String member = login("student_demo_02", "demo-password-02");
        Setup setup = setup(leader, member, "p12-max");
        random.add(2, 4);
        JsonNode session = create(leader, setup.taskId(), "MAX", null, "p12-session-max");
        String sessionId = session.path("id").asText();
        assertThat(session.path("status").asText()).isEqualTo("INVITING");
        participate(leader, sessionId, 1, "ACCEPT", "p12-a1");
        JsonNode rolling = participate(member, sessionId, 1, "ACCEPT", "p12-a2");
        assertThat(rolling.path("status").asText()).isEqualTo("ROLLING");
        assertThat(rolling.path("revision").asInt()).isEqualTo(2);

        JsonNode leaderRoll = roll(leader, sessionId, 2, 1, "p12-r1");
        assertThat(leaderRoll.path("myRollValue").asInt()).isEqualTo(3);
        assertThat(leaderRoll.path("rounds").size()).isZero();
        JsonNode memberBefore = data(mockMvc.perform(get("/api/v1/dice-sessions/{id}", sessionId)
                        .header("Authorization", bearer(member))).andExpect(status().isOk()).andReturn());
        assertThat(memberBefore.path("rounds").size()).isZero();
        assertThat(memberBefore.path("participants").get(0).path("hasRolled").asBoolean()).isTrue();

        JsonNode complete = roll(member, sessionId, 2, 1, "p12-r2");
        assertThat(complete.path("status").asText()).isEqualTo("AWAITING_CLAIM");
        assertThat(complete.path("proposedOwnerId").asText()).isEqualTo("user_demo_student_02");
        assertThat(complete.path("rounds").get(0).path("rolls").size()).isEqualTo(2);
        assertThat(complete.path("rounds").get(0).path("winnerId").asText())
                .isEqualTo("user_demo_student_02");

        mockMvc.perform(post("/api/v1/dice-sessions/{id}/outcome-decisions", sessionId)
                        .header("Authorization", bearer(member)).header("Idempotency-Key", "p12-outcome")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":3,\"decision\":\"ACCEPT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("AWAITING_CLAIM"))
                .andExpect(jsonPath("$.data.claimStatus").value("AWAITING"));
        mockMvc.perform(post("/api/v1/tasks/{id}/claims", setup.taskId())
                        .header("Authorization", bearer(member)).header("Idempotency-Key", "p12-claim")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/tasks/{id}/claims", setup.taskId())
                        .header("Authorization", bearer(member)).header("Idempotency-Key", "p12-claim")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/dice-sessions/{id}", sessionId)
                        .header("Authorization", bearer(leader)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CLAIMED"))
                .andExpect(jsonPath("$.data.claimStatus").value("CLAIMED"));
    }

    @Test
    void threeTiedRoundsEndUnresolved() throws Exception {
        String leader = login("student_demo_01", "demo-password-01");
        String member = login("student_demo_02", "demo-password-02");
        Setup setup = setup(leader, member, "p12-tie");
        random.add(3, 3, 1, 1, 5, 5);
        String sessionId = create(leader, setup.taskId(), "MAX", null, "p12-tie-session").path("id").asText();
        participate(leader, sessionId, 1, "ACCEPT", "p12-tie-a1");
        JsonNode accepted = participate(member, sessionId, 1, "ACCEPT", "p12-tie-a2");
        int revision = accepted.path("revision").asInt();
        for (int round = 1; round <= 3; round++) {
            roll(leader, sessionId, revision, round, "p12-tie-l" + round);
            JsonNode after = roll(member, sessionId, revision, round, "p12-tie-m" + round);
            revision = after.path("revision").asInt();
            if (round < 3) {
                assertThat(after.path("status").asText()).isEqualTo("TIE_ROUND");
                assertThat(after.path("rounds").size()).isEqualTo(round);
            } else {
                assertThat(after.path("status").asText()).isEqualTo("UNRESOLVED");
            }
        }
        mockMvc.perform(get("/api/v1/dice-sessions/{id}", sessionId)
                        .header("Authorization", bearer(leader)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rounds.length()").value(3))
                .andExpect(jsonPath("$.data.proposedOwnerId").doesNotExist());
    }

    @Test
    void targetWithoutHitKeepsCandidateSetAndParticipationCanDecline() throws Exception {
        String leader = login("student_demo_01", "demo-password-01");
        String member = login("student_demo_02", "demo-password-02");
        Setup setup = setup(leader, member, "p12-target");
        random.add(2, 3);
        String sessionId = create(leader, setup.taskId(), "TARGET", 6, "p12-target-session").path("id").asText();
        participate(leader, sessionId, 1, "ACCEPT", "p12-target-a1");
        JsonNode after = participate(member, sessionId, 1, "ACCEPT", "p12-target-a2");
        roll(leader, sessionId, after.path("revision").asInt(), 1, "p12-target-l1");
        JsonNode tied = roll(member, sessionId, after.path("revision").asInt(), 1, "p12-target-m1");
        assertThat(tied.path("status").asText()).isEqualTo("TIE_ROUND");
        assertThat(tied.path("rounds").get(0).path("candidateIds").size()).isEqualTo(2);

        Setup declineSetup = setup(leader, member, "p12-decline");
        String declineId = create(leader, declineSetup.taskId(), "MIN", null, "p12-decline-session")
                .path("id").asText();
        participate(leader, declineId, 1, "ACCEPT", "p12-decline-a1");
        JsonNode declined = participate(member, declineId, 1, "DECLINE", "p12-decline-a2");
        assertThat(declined.path("status").asText()).isEqualTo("DECLINED");
    }

    @Test
    void minTieAndTargetMultiHitStayTied() throws Exception {
        String leader = login("student_demo_01", "demo-password-01");
        String member = login("student_demo_02", "demo-password-02");
        Setup minSetup = setup(leader, member, "p12-min-tie");
        random.add(0, 0);
        String minId = create(leader, minSetup.taskId(), "MIN", null, "p12-min-tie-session")
                .path("id").asText();
        participate(leader, minId, 1, "ACCEPT", "p12-min-a1");
        int revision = participate(member, minId, 1, "ACCEPT", "p12-min-a2").path("revision").asInt();
        roll(leader, minId, revision, 1, "p12-min-l1");
        JsonNode minTie = roll(member, minId, revision, 1, "p12-min-m1");
        assertThat(minTie.path("status").asText()).isEqualTo("TIE_ROUND");
        assertThat(minTie.path("rounds").get(0).path("winnerId").isNull()).isTrue();

        Setup targetSetup = setup(leader, member, "p12-target-multi");
        random.add(3, 3);
        String targetId = create(leader, targetSetup.taskId(), "TARGET", 4,
                "p12-target-multi-session").path("id").asText();
        participate(leader, targetId, 1, "ACCEPT", "p12-target-m-a1");
        int targetRevision = participate(member, targetId, 1, "ACCEPT", "p12-target-m-a2")
                .path("revision").asInt();
        roll(leader, targetId, targetRevision, 1, "p12-target-m-l1");
        JsonNode multi = roll(member, targetId, targetRevision, 1, "p12-target-m-m1");
        assertThat(multi.path("status").asText()).isEqualTo("TIE_ROUND");
        assertThat(multi.path("rounds").get(0).path("candidateIds").size()).isEqualTo(2);
    }

    @Test
    void anotherMemberClaimInvalidatesAwaitingSession() throws Exception {
        String leader = login("student_demo_01", "demo-password-01");
        String member = login("student_demo_02", "demo-password-02");
        Setup setup = setup(leader, member, "p12-race");
        random.add(5, 0);
        String sessionId = create(leader, setup.taskId(), "MIN", null, "p12-race-session").path("id").asText();
        participate(leader, sessionId, 1, "ACCEPT", "p12-race-a1");
        int revision = participate(member, sessionId, 1, "ACCEPT", "p12-race-a2").path("revision").asInt();
        roll(leader, sessionId, revision, 1, "p12-race-l");
        JsonNode complete = roll(member, sessionId, revision, 1, "p12-race-m");
        assertThat(complete.path("proposedOwnerId").asText()).isEqualTo("user_demo_student_02");
        mockMvc.perform(post("/api/v1/dice-sessions/{id}/outcome-decisions", sessionId)
                        .header("Authorization", bearer(member)).header("Idempotency-Key", "p12-race-outcome")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":" + complete.path("revision").asInt()
                                + ",\"decision\":\"ACCEPT\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/tasks/{id}/claims", setup.taskId())
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "p12-race-claim")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/dice-sessions/{id}", sessionId)
                        .header("Authorization", bearer(member)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("INVALIDATED"));
    }

    private Setup setup(String leaderToken, String memberToken, String key) throws Exception {
        JsonNode match = data(mockMvc.perform(post("/api/v1/squares/matches")
                        .header("Authorization", bearer(leaderToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("schoolId", SCHOOL, "termId", TERM,
                                "teacherName", "示例教师", "slots", slots(),
                                "courseNameId", "course_name_demo_mobile"))))
                .andExpect(status().isOk()).andReturn());
        JsonNode square = data(mockMvc.perform(post("/api/v1/squares")
                        .header("Authorization", bearer(leaderToken)).header("Idempotency-Key", key + "-sq")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("schoolId", SCHOOL, "termId", TERM,
                                "teacherName", "示例教师", "slots", slots(),
                                "courseNameId", "course_name_demo_mobile",
                                "candidateCheckToken", match.path("candidateCheckToken").asText()))))
                .andExpect(status().isOk()).andReturn());
        JsonNode assignment = data(mockMvc.perform(post("/api/v1/squares/{id}/assignments",
                                square.path("id").asText())
                        .header("Authorization", bearer(leaderToken)).header("Idempotency-Key", key + "-asg")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("name", "骰子作业",
                                "requirements", "演示", "hardDueAt", "2026-12-15T15:59:59Z",
                                "minMembers", 2, "maxMembers", 6, "sourceReferences", List.of()))))
                .andExpect(status().isOk()).andReturn());
        String teacher = login("teacher_demo_01", "demo-password-03");
        mockMvc.perform(post("/api/v1/assignments/{id}/confirmations", assignment.path("id").asText())
                        .header("Authorization", bearer(teacher)).header("Idempotency-Key", key + "-confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"decision\":\"CONFIRM_NEW\",\"reason\":\"ok\"}"))
                .andExpect(status().isOk());
        JsonNode project = data(mockMvc.perform(post("/api/v1/projects")
                        .header("Authorization", bearer(leaderToken)).header("Idempotency-Key", key + "-project")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("squareId", square.path("id").asText(),
                                "assignmentId", assignment.path("id").asText(), "mode", "QUICK",
                                "name", "骰子组", "capacity", 3, "discussionTime", "周三",
                                "roles", List.of()))))
                .andExpect(status().isOk()).andReturn());
        inviteAndAccept(leaderToken, memberToken, project.path("id").asText(), key);
        JsonNode board = data(mockMvc.perform(get("/api/v1/projects/{id}/board", project.path("id").asText())
                        .header("Authorization", bearer(leaderToken))).andExpect(status().isOk()).andReturn());
        String stageId = board.path("stages").get(0).path("id").asText();
        JsonNode task = data(mockMvc.perform(post("/api/v1/projects/{id}/tasks", project.path("id").asText())
                        .header("Authorization", bearer(leaderToken)).header("Idempotency-Key", key + "-task")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("title", "待投骰任务",
                                "description", "无主任务", "mode", "ORDINARY", "stageId", stageId,
                                "criteria", List.of(Map.of("text", "完成")),
                                "dependencies", List.of(), "dueAt", "2026-12-15T15:59:59Z"))))
                .andExpect(status().isOk()).andReturn());
        return new Setup(project.path("id").asText(), task.path("id").asText());
    }

    private void inviteAndAccept(String leaderToken, String memberToken, String projectId,
                                 String key) throws Exception {
        int revision = data(mockMvc.perform(get("/api/v1/projects/{id}", projectId)
                        .header("Authorization", bearer(leaderToken))).andExpect(status().isOk()).andReturn())
                .path("revision").asInt();
        JsonNode invite = data(mockMvc.perform(post("/api/v1/projects/{id}/invitations", projectId)
                        .header("Authorization", bearer(leaderToken)).header("Idempotency-Key", key + "-inv")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("expectedProjectRevision", revision,
                                "recipientId", "user_demo_student_02", "message", "参加骰子"))))
                .andExpect(status().isOk()).andReturn());
        mockMvc.perform(post("/api/v1/invitations/{id}/responses", invite.path("invitation").path("id").asText())
                        .header("Authorization", bearer(memberToken)).header("Idempotency-Key", key + "-acc")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"decision\":\"ACCEPT\"}"))
                .andExpect(status().isOk());
    }

    private JsonNode create(String token, String taskId, String rule, Integer target, String key)
            throws Exception {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("expectedTaskRevision", 1);
        body.put("participantIds", List.of("user_demo_student_01", "user_demo_student_02"));
        body.put("rule", rule);
        if ("TARGET".equals(rule)) body.put("targetValue", target);
        else body.put("targetValue", null);
        body.put("maxRounds", 3);
        return data(mockMvc.perform(post("/api/v1/tasks/{id}/dice-sessions", taskId)
                        .header("Authorization", bearer(token)).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)))
                .andExpect(status().isOk()).andReturn());
    }

    private JsonNode participate(String token, String sessionId, int revision, String decision, String key)
            throws Exception {
        return data(mockMvc.perform(post("/api/v1/dice-sessions/{id}/participations", sessionId)
                        .header("Authorization", bearer(token)).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("expectedRevision", revision,
                                "decision", decision))))
                .andExpect(status().isOk()).andReturn());
    }

    private JsonNode roll(String token, String sessionId, int revision, int round, String key)
            throws Exception {
        return data(mockMvc.perform(post("/api/v1/dice-sessions/{id}/rolls", sessionId)
                        .header("Authorization", bearer(token)).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("expectedRevision", revision,
                                "roundNo", round))))
                .andExpect(status().isOk()).andReturn());
    }

    private List<Map<String, Object>> slots() {
        return List.of(Map.of("weekday", 3, "periodId", "period_demo_2", "weekPattern", "ALL",
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

    private record Setup(String projectId, String taskId) {
    }

    static class RandomQueue implements RandomSource {
        private final Deque<Integer> values = new ArrayDeque<>();

        void add(int... next) {
            for (int value : next) values.add(value);
        }

        void clear() {
            values.clear();
        }

        @Override
        public int nextInt(int bound) {
            Integer next = values.poll();
            return next == null ? 1 : next;
        }
    }

    @TestConfiguration
    static class TestClockConfig {
        @Bean @Primary
        MutableClock testClock() {
            return new MutableClock(BASE, ZoneOffset.UTC);
        }

        @Bean @Primary
        RandomQueue randomQueue() {
            return new RandomQueue();
        }
    }
}
