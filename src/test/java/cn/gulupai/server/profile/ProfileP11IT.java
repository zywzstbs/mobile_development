package cn.gulupai.server.profile;

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
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(ProfileP11IT.TestClockConfig.class)
class ProfileP11IT {

    private static final Instant BASE = Instant.parse("2026-10-31T00:00:00Z");
    private static final String SCHOOL = "school_demo_01";
    private static final String TERM = "term_demo_2026a";

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

    private void clearAll() {
        for (String table : List.of("chat_sync_cursor", "chat_delivery_range", "report_snapshot", "delivery_release", "delivery_release_preview",
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
                "merge_ballot", "square_merge", "preview_token", "square", "verified_enrollment",
                "verified_course", "course_name", "period", "term", "operation_receipt", "access_token",
                "auth_session", "audit_event", "rate_window", "import_batch", "user_role", "user_profile",
                "app_user", "school")) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    @Test
    void workLifecycleAndAudienceIntersection() throws Exception {
        String leader = login("student_demo_01", "demo-password-01");
        String member = login("student_demo_02", "demo-password-02");
        Project project = project(leader, "p11-work");
        workProjectId = project.id();
        seedAcceptedDelivery(project.id(), "user_demo_student_01");
        addMember(leader, project.id(), member, "user_demo_student_02");

        JsonNode work = data(mockMvc.perform(post("/api/v1/me/works")
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "p11-work-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(workBody(0, "登录页面", "PRIVATE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.versions.length()").value(1))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andReturn());
        String workId = work.path("id").asText();
        JsonNode replay = data(mockMvc.perform(post("/api/v1/me/works")
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "p11-work-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(workBody(0, "登录页面", "PRIVATE")))
                .andExpect(status().isOk()).andReturn());
        assertThat(replay.path("id").asText()).isEqualTo(workId);
        mockMvc.perform(get("/api/v1/works/{id}", workId).header("Authorization", bearer(member)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/users/user_demo_student_01/profile")
                        .header("Authorization", bearer(member)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.works.length()").value(0))
                .andExpect(jsonPath("$.data.isSelf").value(false));

        mockMvc.perform(patch("/api/v1/me/works/{id}", workId)
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "p11-work-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(workBody(1, "登录页面", "TEAM")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.audience").value("TEAM"));
        mockMvc.perform(get("/api/v1/works/{id}", workId).header("Authorization", bearer(member)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.versions.length()").value(0));
        mockMvc.perform(get("/api/v1/me/works").header("Authorization", bearer(leader)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].versions.length()").value(2));

        mockMvc.perform(post("/api/v1/me/works/{id}/withdrawals", workId)
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "p11-work-3")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedRevision\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("WITHDRAWN"));
        mockMvc.perform(patch("/api/v1/me/works/{id}", workId)
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "p11-work-4")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(workBody(3, "重新提交", "TEAM")))
                .andExpect(status().isConflict());
        mockMvc.perform(get("/api/v1/works/{id}", workId).header("Authorization", bearer(member)))
                .andExpect(status().isForbidden());
    }

    @Test
    void peerReviewLifecycleAndRawPrivacy() throws Exception {
        String leader = login("student_demo_01", "demo-password-01");
        String member = login("student_demo_02", "demo-password-02");
        Project project = project(leader, "p11-peer");
        addMember(leader, project.id(), member, "user_demo_student_02");
        archive(project.id());

        mockMvc.perform(get("/api/v1/projects/{id}/peer-review-context", project.id())
                        .header("Authorization", bearer(leader)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.targets.length()").value(1))
                .andExpect(jsonPath("$.data.targets[0].canReview").value(true));
        JsonNode review = data(mockMvc.perform(post("/api/v1/projects/{id}/peer-reviews", project.id())
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "p11-peer-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(peerBody("user_demo_student_02", 0, "交付稳定")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.observations.length()").value(3))
                .andExpect(jsonPath("$.data.canEdit").value(true))
                .andReturn());
        String reviewId = review.path("id").asText();
        mockMvc.perform(get("/api/v1/peer-reviews/{id}", reviewId)
                        .header("Authorization", bearer(member)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/peer-reviews/{id}", reviewId)
                        .header("Authorization", bearer(leader)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.targetUserId").value("user_demo_student_02"));
        mockMvc.perform(put("/api/v1/peer-reviews/{id}", reviewId)
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "p11-peer-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(peerBody("user_demo_student_02", 1, "沟通及时")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revision").value(2))
                .andExpect(jsonPath("$.data.versions.length()").value(2));
        mockMvc.perform(post("/api/v1/projects/{id}/peer-reviews", project.id())
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "p11-peer-dup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(peerBody("user_demo_student_02", 0, "重复")))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/api/v1/peer-reviews/{id}/withdrawals", reviewId)
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "p11-peer-3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":2,\"reason\":\"撤回\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("WITHDRAWN"));
    }

    @Test
    void cooperationWindowAndProfileContext() throws Exception {
        String leader = login("student_demo_01", "demo-password-01");
        String member = login("student_demo_02", "demo-password-02");
        String assistant = login("assistant_demo_01", "demo-password-04");
        Project project = project(leader, "p11-coop");
        addMember(leader, project.id(), member, "user_demo_student_02");
        mockMvc.perform(get("/api/v1/me/cooperations").header("Authorization", bearer(leader)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
        archive(project.id());
        mockMvc.perform(get("/api/v1/me/cooperations").header("Authorization", bearer(leader)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].canReview").value(true))
                .andExpect(jsonPath("$.data.items[0].members.length()").value(2));
        mockMvc.perform(get("/api/v1/users/user_demo_student_01/profile")
                        .param("contextProjectId", project.id())
                        .header("Authorization", bearer(member)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isSelf").value(false))
                .andExpect(jsonPath("$.data.cooperationSummaryVisible").value(false));

        clock.advance(Duration.ofDays(15));
        leader = login("student_demo_01", "demo-password-01");
        assistant = login("assistant_demo_01", "demo-password-04");
        mockMvc.perform(get("/api/v1/me/cooperations").header("Authorization", bearer(leader)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].canReview").value(false));
        mockMvc.perform(post("/api/v1/projects/{id}/peer-reviews", project.id())
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "p11-peer-expired")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(peerBody("user_demo_student_02", 0, "过期")))
                .andExpect(status().isConflict());
        mockMvc.perform(get("/api/v1/users/user_demo_student_01/profile")
                        .header("Authorization", bearer(assistant)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isSelf").value(false));
    }

    @Test
    void classWorkVisibleToCourseMemberButTeamWorkIsNot() throws Exception {
        String leader = login("student_demo_01", "demo-password-01");
        String assistant = login("assistant_demo_01", "demo-password-04");
        Project project = project(leader, "p11-audience");
        workProjectId = project.id();
        seedAcceptedDelivery(project.id(), "user_demo_student_01");
        JsonNode classWork = data(mockMvc.perform(post("/api/v1/me/works")
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "p11-class-work")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(workBody(0, "课程作品", "CLASS")))
                .andExpect(status().isOk()).andReturn());
        JsonNode teamWork = data(mockMvc.perform(post("/api/v1/me/works")
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "p11-team-work")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(workBody(0, "组内作品", "TEAM")))
                .andExpect(status().isOk()).andReturn());
        mockMvc.perform(get("/api/v1/works/{id}", classWork.path("id").asText())
                        .header("Authorization", bearer(assistant)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/works/{id}", teamWork.path("id").asText())
                        .header("Authorization", bearer(assistant)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/users/user_demo_student_01/profile")
                        .header("Authorization", bearer(assistant)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.works.length()").value(1))
                .andExpect(jsonPath("$.data.works[0].title").value("课程作品"));
        assertThat(project.id()).isNotBlank();
    }

    @Test
    void aiReferenceLeaderOnlyAndDuplicateRules() throws Exception {
        String leader = login("student_demo_01", "demo-password-01");
        String member = login("student_demo_02", "demo-password-02");
        Project project = project(leader, "p11-ai");
        addMember(leader, project.id(), member, "user_demo_student_02");
        int revision = projectRevision(leader, project.id());
        JsonNode ai = data(mockMvc.perform(post("/api/v1/projects/{id}/client-ai-references", project.id())
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "p11-ai-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "expectedProjectRevision", revision,
                                "sourceProjectId", project.id(),
                                "clientRecordId", "client-1",
                                "scene", "TOPIC_ANALYSIS",
                                "model", "local-model",
                                "title", "选题参考",
                                "text", "组长确认后的AI参考文字"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.origin").value("CLIENT_BYOK_LEADER_CONFIRMED"))
                .andReturn());
        mockMvc.perform(get("/api/v1/projects/{id}/ai-references", project.id())
                        .header("Authorization", bearer(member)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1));
        mockMvc.perform(post("/api/v1/projects/{id}/client-ai-references", project.id())
                        .header("Authorization", bearer(member)).header("Idempotency-Key", "p11-ai-member")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "expectedProjectRevision", revision,
                                "sourceProjectId", project.id(), "clientRecordId", "client-2",
                                "scene", "TASK_PLAN", "model", "m", "title", "t", "text", "x"))))
                .andExpect(status().isForbidden());
        JsonNode replay = data(mockMvc.perform(post("/api/v1/projects/{id}/client-ai-references", project.id())
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "p11-ai-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "expectedProjectRevision", revision,
                                "sourceProjectId", project.id(), "clientRecordId", "client-1",
                                "scene", "TOPIC_ANALYSIS", "model", "local-model",
                                "title", "选题参考", "text", "组长确认后的AI参考文字"))))
                .andExpect(status().isOk()).andReturn());
        assertThat(replay.path("id").asText()).isEqualTo(ai.path("id").asText());
        mockMvc.perform(post("/api/v1/projects/{id}/client-ai-references", project.id())
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "p11-ai-diff")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "expectedProjectRevision", revision,
                                "sourceProjectId", project.id(), "clientRecordId", "client-1",
                                "scene", "TOPIC_ANALYSIS", "model", "local-model",
                                "title", "不同标题", "text", "不同正文"))))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/api/v1/projects/{id}/client-ai-references", project.id())
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "p11-ai-cross")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "expectedProjectRevision", revision,
                                "sourceProjectId", "other-project", "clientRecordId", "client-3",
                                "scene", "TASK_PLAN", "model", "m", "title", "t", "text", "x"))))
                .andExpect(status().isUnprocessableEntity());
    }

    private Project project(String token, String key) throws Exception {
        String matchBody = mapper.writeValueAsString(Map.of("schoolId", SCHOOL, "termId", TERM,
                "teacherName", "示例教师", "slots", slots(), "courseNameId", "course_name_demo_mobile"));
        JsonNode match = data(mockMvc.perform(post("/api/v1/squares/matches")
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content(matchBody)).andExpect(status().isOk()).andReturn());
        String squareBody = mapper.writeValueAsString(Map.of("schoolId", SCHOOL, "termId", TERM,
                "teacherName", "示例教师", "slots", slots(), "courseNameId", "course_name_demo_mobile",
                "candidateCheckToken", match.path("candidateCheckToken").asText()));
        JsonNode square = data(mockMvc.perform(post("/api/v1/squares")
                        .header("Authorization", bearer(token)).header("Idempotency-Key", key + "-square")
                        .contentType(MediaType.APPLICATION_JSON).content(squareBody))
                .andExpect(status().isOk()).andReturn());
        String assignmentBody = mapper.writeValueAsString(Map.of("name", "课程设计",
                "requirements", "完成可演示应用", "hardDueAt", "2026-12-15T15:59:59Z",
                "minMembers", 2, "maxMembers", 6, "sourceReferences", List.of()));
        JsonNode assignment = data(mockMvc.perform(post("/api/v1/squares/{id}/assignments",
                                square.path("id").asText())
                        .header("Authorization", bearer(token)).header("Idempotency-Key", key + "-asg")
                        .contentType(MediaType.APPLICATION_JSON).content(assignmentBody))
                .andExpect(status().isOk()).andReturn());
        String teacher = login("teacher_demo_01", "demo-password-03");
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
                                "name", "作品组", "capacity", 3, "discussionTime", "周三",
                                "roles", List.of()))))
                .andExpect(status().isOk()).andReturn());
        return new Project(square.path("id").asText(), project.path("id").asText());
    }

    private void addMember(String leaderToken, String projectId, String memberToken, String userId) throws Exception {
        int revision = projectRevision(leaderToken, projectId);
        JsonNode invite = data(mockMvc.perform(post("/api/v1/projects/{id}/invitations", projectId)
                        .header("Authorization", bearer(leaderToken)).header("Idempotency-Key", "p11-invite-" + userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("expectedProjectRevision", revision,
                                "recipientId", userId, "message", "加入作品组"))))
                .andExpect(status().isOk()).andReturn());
        mockMvc.perform(post("/api/v1/invitations/{id}/responses", invite.path("invitation").path("id").asText())
                        .header("Authorization", bearer(memberToken)).header("Idempotency-Key", "p11-accept-" + userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"decision\":\"ACCEPT\"}"))
                .andExpect(status().isOk());
    }

    private void archive(String projectId) {
        jdbc.update("""
                UPDATE project SET status = 'ARCHIVED', archived_at = ?, revision = revision + 1,
                    updated_at = ? WHERE id = ?
                """, java.time.OffsetDateTime.ofInstant(BASE, ZoneOffset.UTC),
                java.time.OffsetDateTime.ofInstant(BASE, ZoneOffset.UTC), projectId);
    }

    private String workBody(int revision, String title, String audience) throws Exception {
        return mapper.writeValueAsString(Map.of("expectedRevision", revision, "title", title,
                "description", "作品说明", "role", "客户端", "myContribution", "完成页面",
                "sourceProjectId", workProjectId, "audience", audience,
                "artifacts", List.of(Map.of("kind", "LINK", "url", "https://example.test/work",
                        "title", "仓库"))));
    }

    private String workProjectId;

    private String peerBody(String target, int revision, String comment) throws Exception {
        return mapper.writeValueAsString(Map.of("targetUserId", target, "expectedRevision", revision,
                "observedEnough", true, "observations", List.of(
                        Map.of("dimension", "DELIVERY", "level", "POSITIVE", "evidenceText", "按时交付"),
                        Map.of("dimension", "COMMUNICATION", "level", "POSITIVE", "evidenceText", "沟通清晰"),
                        Map.of("dimension", "REWORK", "level", "NEEDS_IMPROVEMENT", "evidenceText", "返工较少")),
                "comment", comment));
    }

    private int projectRevision(String token, String projectId) throws Exception {
        return data(mockMvc.perform(get("/api/v1/projects/{id}", projectId)
                        .header("Authorization", bearer(token))).andExpect(status().isOk()).andReturn())
                .path("revision").asInt();
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

    private record Project(String squareId, String id) {
    }

    @TestConfiguration
    static class TestClockConfig {
        @Bean @Primary
        MutableClock testClock() {
            return new MutableClock(BASE, ZoneOffset.UTC);
        }
    }
    /** 作品来源现在要求本人真实有效贡献证据；测试用最小已验收交付建立该证据。 */
    private void seedAcceptedDelivery(String projectId, String userId) {
        String stage = jdbc.queryForObject(
                "SELECT id FROM project_stage WHERE project_id = ? ORDER BY position LIMIT 1",
                String.class, projectId);
        String task = java.util.UUID.randomUUID().toString();
        String unit = java.util.UUID.randomUUID().toString();
        String submission = java.util.UUID.randomUUID().toString();
        String acceptance = java.util.UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO task(id, project_id, stage_id, title, description, mode, owner_id, due_at,
                    status, agreement_version, revision, created_at, updated_at)
                VALUES (?, ?, ?, '作品来源任务', '', 'ASSESSED', ?, '2026-10-01T00:00:00Z', 'DONE',
                    1, 1, '2026-10-31T00:00:00Z', '2026-10-31T00:00:00Z')
                """, task, projectId, stage, userId);
        jdbc.update("""
                INSERT INTO agreement(id, task_id, version, status, base_task_revision, plan_json,
                    revision, created_at, updated_at)
                VALUES (?, ?, 1, 'ACTIVE', 1, '{}'::jsonb, 1, '2026-10-31T00:00:00Z',
                    '2026-10-31T00:00:00Z')
                """, java.util.UUID.randomUUID().toString(), task);
        jdbc.update("""
                INSERT INTO delivery_unit(id, task_id, client_key, title, due_at, points, revision,
                    position, passed)
                VALUES (?, ?, 'u1', '作品来源分项', '2026-10-01T00:00:00Z', 10.00, 1, 1, true)
                """, unit, task);
        jdbc.update("""
                INSERT INTO unit_share(id, unit_id, user_id, points, responsibility, agreement_version, signed)
                VALUES (?, ?, ?, 10.00, '实现', 1, true)
                """, java.util.UUID.randomUUID().toString(), unit, userId);
        jdbc.update("""
                INSERT INTO submission(id, task_id, sequence, submitted_by, status, summary,
                    artifacts_json, unit_ids_json, criteria_snapshot, due_snapshot,
                    unit_due_snapshot_json, agreement_version, revision, submitted_at)
                VALUES (?, ?, 1, ?, 'REVIEWED', '提交', '[]'::jsonb, ?::jsonb, '[]'::jsonb,
                    '2026-10-01T00:00:00Z', '{}'::jsonb, 1, 1, '2026-10-01T00:00:00Z')
                """, submission, task, userId, "[\"" + unit + "\"]");
        jdbc.update("""
                INSERT INTO acceptance(id, submission_id, reviewer_id, kind, checks_json, decision,
                    reason, accepted_at)
                VALUES (?, ?, 'user_demo_teacher_01', 'INDEPENDENT', '[]'::jsonb, 'PASSED', '通过',
                    '2026-10-01T00:00:00Z')
                """, acceptance, submission);
        jdbc.update("""
                INSERT INTO unit_acceptance(unit_id, submission_id, acceptance_id, passed)
                VALUES (?, ?, ?, true)
                """, unit, submission, acceptance);
    }

}
