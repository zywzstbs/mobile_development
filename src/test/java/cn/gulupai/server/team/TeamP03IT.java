package cn.gulupai.server.team;

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
@Import(TeamP03IT.TestClockConfig.class)
class TeamP03IT {

    private static final Instant BASE = Instant.parse("2026-10-31T00:00:00Z");
    private static final String SCHOOL = "school_demo_01";
    private static final String TERM = "term_demo_2026a";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private MutableClock clock;
    @Autowired
    private AdminImportService userImportService;
    @Autowired
    private AcademicImportService academicImportService;

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
                "work_version", "work", "project_code", "seat_reservation",
                "team_invitation",
                "team_application", "assignment_team_slot", "membership", "role_slot",
                "project_stage", "project", "teacher_transfer", "teacher_claim",
                "teacher_assignment", "class_grant", "course_code", "course_join_request",
                "cursor_snapshot", "assignment", "course_membership", "merge_ballot", "square_merge", "preview_token", "square",
                "verified_enrollment", "verified_course", "course_name", "period", "term",
                "operation_receipt", "access_token", "auth_session", "audit_event",
                "rate_window", "import_batch", "user_role", "user_profile", "app_user", "school"));
    }

    @Test
    void quickProjectApplyApproveAcceptAndCode() throws Exception {
        String student1 = login("student_demo_01", "demo-password-01");
        String student2 = login("student_demo_02", "demo-password-02");
        Bootstrap boot = bootstrap(student1);
        JsonNode project = createQuickProject(student1, boot, 3, "甲组", "p03-quick-1");
        String projectId = project.get("id").asText();
        assertThat(project.get("myProjectRole").asText()).isEqualTo("LEADER");
        mockMvc.perform(get("/api/v1/projects/{id}", projectId).header("Authorization", bearer(student1)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.memberCount").value(1));
        mockMvc.perform(get("/api/v1/projects/{id}/members", projectId)
                        .header("Authorization", bearer(student1)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1));
        mockMvc.perform(get("/api/v1/me/projects").header("Authorization", bearer(student1)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1));

        JsonNode application = apply(student2, projectId, "加入", null, "p03-apply-1");
        assertThat(application.get("application").get("status").asText()).isEqualTo("PENDING");
        JsonNode approved = data(mockMvc.perform(post("/api/v1/applications/{id}/decisions",
                                application.get("application").get("id").asText())
                        .header("Authorization", bearer(student1))
                        .header("Idempotency-Key", "p03-approve-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"decision\":\"APPROVE\",\"reason\":\"合适\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.application.status").value("APPROVED"))
                .andReturn());
        String invitationId = approved.get("application").get("invitationId").asText();
        mockMvc.perform(post("/api/v1/invitations/{id}/responses", invitationId)
                        .header("Authorization", bearer(student2))
                        .header("Idempotency-Key", "p03-accept-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"decision\":\"ACCEPT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.invitation.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.data.membership.status").value("ACTIVE"));
        mockMvc.perform(get("/api/v1/projects/{id}/members", projectId)
                        .header("Authorization", bearer(student1)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(2));
        mockMvc.perform(get("/api/v1/me/projects").header("Authorization", bearer(student2)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1));

        JsonNode code = data(mockMvc.perform(get("/api/v1/projects/{id}/join-code", projectId)
                        .header("Authorization", bearer(student1)))
                .andExpect(status().isOk()).andReturn());
        mockMvc.perform(post("/api/v1/project-code-resolutions")
                        .header("Authorization", bearer(student2))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code.get("code").asText() + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.canApply").value(false));
    }

    @Test
    void detailedProjectRecruitmentPatchAndCandidates() throws Exception {
        String student1 = login("student_demo_01", "demo-password-01");
        String student2 = login("student_demo_02", "demo-password-02");
        Bootstrap boot = bootstrap(student1);
        String body = objectMapper.writeValueAsString(Map.of(
                "squareId", boot.squareId(), "assignmentId", boot.assignmentId(),
                "mode", "DETAILED", "name", "详细组", "intro", "详细项目",
                "capacity", 4, "discussionTime", "周三",
                "roles", List.of(
                        Map.of("clientKey", "a", "roleName", "开发", "duty", "编码", "total", 2),
                        Map.of("clientKey", "b", "roleName", "测试", "duty", "验收", "total", 2)),
                "leaderRoleKey", "a"));
        JsonNode project = data(mockMvc.perform(post("/api/v1/projects")
                        .header("Authorization", bearer(student1))
                        .header("Idempotency-Key", "p03-detailed-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn());
        String projectId = project.get("id").asText();
        assertThat(project.get("roles").size()).isEqualTo(2);

        String bad = objectMapper.writeValueAsString(Map.of(
                "squareId", boot.squareId(), "assignmentId", boot.assignmentId(),
                "mode", "DETAILED", "name", "坏组", "intro", "坏",
                "capacity", 4,
                "roles", List.of(Map.of("clientKey", "a", "roleName", "开发", "duty", "编码", "total", 3)),
                "leaderRoleKey", "a"));
        mockMvc.perform(post("/api/v1/projects").header("Authorization", bearer(student2))
                        .header("Idempotency-Key", "p03-detailed-bad")
                        .contentType(MediaType.APPLICATION_JSON).content(bad))
                .andExpect(status().isUnprocessableEntity());

        mockMvc.perform(put("/api/v1/projects/{id}/recruitment", projectId)
                        .header("Authorization", bearer(student1))
                        .header("Idempotency-Key", "p03-recruit-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"open\":true,\"description\":\"招募\"," +
                                "\"professionalDirection\":\"软件开发\",\"weeklyHours\":6," +
                                "\"acceptBeginners\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.recruitment.weeklyHours").value(6));

        mockMvc.perform(get("/api/v1/projects/{id}/recruitment", projectId)
                        .header("Authorization", bearer(student2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.recruitment.professionalDirection").value("软件开发"));
        mockMvc.perform(get("/api/v1/assignments/{id}/recruitments", boot.assignmentId())
                        .param("professionalDirection", "软件")
                        .header("Authorization", bearer(student2)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1));

        int revision = projectRevision(student1, projectId);
        mockMvc.perform(get("/api/v1/projects/{id}/invite-candidates", projectId)
                        .param("expectedProjectRevision", String.valueOf(revision))
                        .param("q", "示例")
                        .header("Authorization", bearer(student1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].source").value("COURSE"));

        mockMvc.perform(patch("/api/v1/projects/{id}", projectId)
                        .header("Authorization", bearer(student1))
                        .header("Idempotency-Key", "p03-patch-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":" + revision
                                + ",\"name\":\"新详细组\",\"intro\":\"新简介\","
                                + "\"discussionTime\":\"周四\",\"capacity\":4}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.displayName").value("新详细组"));
        mockMvc.perform(patch("/api/v1/projects/{id}", projectId)
                        .header("Authorization", bearer(student1))
                        .header("Idempotency-Key", "p03-patch-bad")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":" + (revision + 1)
                                + ",\"name\":\"错\",\"intro\":\"错\",\"capacity\":5}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void directInvitationDeclineAndRevoke() throws Exception {
        String student1 = login("student_demo_01", "demo-password-01");
        String student2 = login("student_demo_02", "demo-password-02");
        Bootstrap boot = bootstrap(student1);
        JsonNode project = createQuickProject(student1, boot, 3, "邀请组", "p03-invite-project");
        String projectId = project.get("id").asText();
        JsonNode invite = data(mockMvc.perform(post("/api/v1/projects/{id}/invitations", projectId)
                        .header("Authorization", bearer(student1))
                        .header("Idempotency-Key", "p03-invite-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedProjectRevision\":1,\"recipientId\":\"user_demo_student_02\"," +
                                "\"message\":\"加入我们\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.invitation.status").value("PENDING"))
                .andReturn());
        String invitationId = invite.get("invitation").get("id").asText();
        mockMvc.perform(get("/api/v1/me/invitations").header("Authorization", bearer(student2)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1));
        mockMvc.perform(get("/api/v1/invitations/{id}", invitationId)
                        .header("Authorization", bearer(student2)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.canRespond").value(true));
        mockMvc.perform(get("/api/v1/projects/{id}/invitations", projectId)
                        .header("Authorization", bearer(student1)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1));
        mockMvc.perform(post("/api/v1/invitations/{id}/responses", invitationId)
                        .header("Authorization", bearer(student2))
                        .header("Idempotency-Key", "p03-decline-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"decision\":\"DECLINE\",\"reason\":\"不加入\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.invitation.status").value("DECLINED"));
        mockMvc.perform(post("/api/v1/invitations/{id}/responses", invitationId)
                        .header("Authorization", bearer(student2))
                        .header("Idempotency-Key", "p03-decline-again")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":2,\"decision\":\"ACCEPT\"}"))
                .andExpect(status().isConflict());

        int revision2 = projectRevision(student1, projectId);
        JsonNode invite2 = data(mockMvc.perform(post("/api/v1/projects/{id}/invitations", projectId)
                        .header("Authorization", bearer(student1))
                        .header("Idempotency-Key", "p03-invite-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedProjectRevision\":" + revision2
                                + ",\"recipientId\":\"user_demo_student_02\",\"message\":\"再次邀请\"}"))
                .andExpect(status().isOk()).andReturn());
        mockMvc.perform(post("/api/v1/invitations/{id}/revocations",
                                invite2.get("invitation").get("id").asText())
                        .header("Authorization", bearer(student1))
                        .header("Idempotency-Key", "p03-revoke-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"reason\":\"不再需要\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.invitation.status").value("REVOKED"));
    }

    @Test
    void capacityApprovalWithdrawalAndMultipleTeams() throws Exception {
        String student1 = login("student_demo_01", "demo-password-01");
        String student2 = login("student_demo_02", "demo-password-02");
        String assistant = login("assistant_demo_01", "demo-password-04");
        Bootstrap boot = bootstrap(student1);
        JsonNode project = createQuickProject(student1, boot, 2, "两人组", "p03-capacity");
        String projectId = project.get("id").asText();
        JsonNode app2 = apply(student2, projectId, "学生2", null, "p03-cap-apply-1");
        JsonNode app3 = apply(assistant, projectId, "助理", null, "p03-cap-apply-2");
        mockMvc.perform(post("/api/v1/applications/{id}/decisions", app2.get("application").get("id").asText())
                        .header("Authorization", bearer(student1))
                        .header("Idempotency-Key", "p03-cap-approve-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"decision\":\"APPROVE\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/applications/{id}/decisions", app3.get("application").get("id").asText())
                        .header("Authorization", bearer(student1))
                        .header("Idempotency-Key", "p03-cap-approve-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"decision\":\"APPROVE\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ROLE_FULL"));
        mockMvc.perform(post("/api/v1/applications/{id}/withdrawals", app3.get("application").get("id").asText())
                        .header("Authorization", bearer(assistant))
                        .header("Idempotency-Key", "p03-withdraw-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"reason\":\"不申请了\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.application.status").value("WITHDRAWN"));

        JsonNode project2 = createQuickProject(student2, boot, 3, "乙组", "p03-second-team");
        assertThat(project2.get("id").asText()).isNotEqualTo(projectId);
        mockMvc.perform(post("/api/v1/projects/{id}/applications", projectId)
                        .header("Authorization", bearer(student2))
                        .header("Idempotency-Key", "p03-cross-team-apply")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"跨组申请\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_IN_ASSIGNMENT_TEAM"));
    }

    @Test
    void codeActionsDashboardTodosAndPermissions() throws Exception {
        String student1 = login("student_demo_01", "demo-password-01");
        String student2 = login("student_demo_02", "demo-password-02");
        String teacher = login("teacher_demo_01", "demo-password-03");
        Bootstrap boot = bootstrap(student1);
        JsonNode project = createQuickProject(student1, boot, 3, "状态组", "p03-status");
        String projectId = project.get("id").asText();
        String oldCode = data(mockMvc.perform(get("/api/v1/projects/{id}/join-code", projectId)
                        .header("Authorization", bearer(student1))).andExpect(status().isOk()).andReturn())
                .get("code").asText();
        int revision = projectRevision(student1, projectId);
        mockMvc.perform(post("/api/v1/projects/{id}/code-actions", projectId)
                        .header("Authorization", bearer(student1))
                        .header("Idempotency-Key", "p03-disable")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":" + revision + ",\"action\":\"DISABLE\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.enabled").value(false));
        mockMvc.perform(post("/api/v1/project-code-resolutions")
                        .header("Authorization", bearer(student2))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + oldCode + "\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CODE_DISABLED"));
        int revision2 = projectRevision(student1, projectId);
        mockMvc.perform(post("/api/v1/projects/{id}/code-actions", projectId)
                        .header("Authorization", bearer(student1))
                        .header("Idempotency-Key", "p03-enable")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":" + revision2 + ",\"action\":\"ENABLE\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.enabled").value(true));
        int revision3 = projectRevision(student1, projectId);
        JsonNode rotated = data(mockMvc.perform(post("/api/v1/projects/{id}/code-actions", projectId)
                        .header("Authorization", bearer(student1))
                        .header("Idempotency-Key", "p03-rotate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":" + revision3 + ",\"action\":\"ROTATE\"}"))
                .andExpect(status().isOk()).andReturn());
        assertThat(rotated.get("code").asText()).isNotEqualTo(oldCode);

        apply(student2, projectId, "申请", null, "p03-status-apply");
        mockMvc.perform(get("/api/v1/me/dashboard").header("Authorization", bearer(student2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pendingApplicationCount").value(1));
        mockMvc.perform(get("/api/v1/me/todos").param("state", "WAITING")
                        .header("Authorization", bearer(student2)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1));
        mockMvc.perform(get("/api/v1/me/work-source-projects").header("Authorization", bearer(student2)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));

        mockMvc.perform(patch("/api/v1/projects/{id}", projectId)
                        .header("Authorization", bearer(student2))
                        .header("Idempotency-Key", "p03-forbidden-patch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"name\":\"篡改\",\"intro\":\"x\",\"capacity\":3}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/projects")
                        .header("Authorization", bearer(teacher))
                        .header("Idempotency-Key", "p03-teacher-project")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "squareId", boot.squareId(), "assignmentId", boot.assignmentId(),
                                "mode", "QUICK", "name", "教师组", "capacity", 3, "roles", List.of()))))
                .andExpect(status().isForbidden());
    }

    @Test
    void concurrentApprovalForLastSeatCreatesOnlyOneReservation() throws Exception {
        String student1 = login("student_demo_01", "demo-password-01");
        String student2 = login("student_demo_02", "demo-password-02");
        String assistant = login("assistant_demo_01", "demo-password-04");
        Bootstrap boot = bootstrap(student1);
        JsonNode project = createQuickProject(student1, boot, 2, "末位席位组", "p03-last-seat");
        String projectId = project.get("id").asText();
        JsonNode app2 = apply(student2, projectId, "抢最后席位", null, "p03-last-a2");
        JsonNode app3 = apply(assistant, projectId, "抢最后席位", null, "p03-last-a3");
        java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(2);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        List<java.util.concurrent.Future<Integer>> futures = new java.util.ArrayList<>();
        try {
            String[][] calls = {
                    {app2.path("application").path("id").asText(), "p03-last-d2"},
                    {app3.path("application").path("id").asText(), "p03-last-d3"}};
            for (String[] call : calls) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await(5, java.util.concurrent.TimeUnit.SECONDS);
                    return mockMvc.perform(post("/api/v1/applications/{id}/decisions", call[0])
                                    .header("Authorization", bearer(student1))
                                    .header("Idempotency-Key", call[1])
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"expectedRevision\":1,\"decision\":\"APPROVE\"}"))
                            .andReturn().getResponse().getStatus();
                }));
            }
            ready.await(5, java.util.concurrent.TimeUnit.SECONDS);
            start.countDown();
            List<Integer> statuses = new java.util.ArrayList<>();
            for (var future : futures) statuses.add(future.get(10, java.util.concurrent.TimeUnit.SECONDS));
            assertThat(statuses).contains(200);
            assertThat(statuses).allMatch(status -> status == 200 || status == 409);
        } finally {
            executor.shutdownNow();
        }
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM seat_reservation WHERE project_id = ? AND status = 'ACTIVE'
                """, Integer.class, projectId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM team_invitation WHERE project_id = ? AND status = 'PENDING'
                """, Integer.class, projectId)).isEqualTo(1);
    }

    @Test
    void concurrentSameInvitationAcceptHasOneMembership() throws Exception {
        String student1 = login("student_demo_01", "demo-password-01");
        String student2 = login("student_demo_02", "demo-password-02");
        Bootstrap boot = bootstrap(student1);
        JsonNode project = createQuickProject(student1, boot, 3, "并发组", "p03-concurrent");
        String projectId = project.get("id").asText();
        JsonNode invite = data(mockMvc.perform(post("/api/v1/projects/{id}/invitations", projectId)
                        .header("Authorization", bearer(student1))
                        .header("Idempotency-Key", "p03-concurrent-invite")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedProjectRevision\":1,"
                                + "\"recipientId\":\"user_demo_student_02\",\"message\":\"并发邀请\"}"))
                .andExpect(status().isOk()).andReturn());
        String invitationId = invite.get("invitation").get("id").asText();
        java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(2);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        List<java.util.concurrent.Future<Integer>> futures = new java.util.ArrayList<>();
        try {
            for (int i = 0; i < 2; i++) {
                String key = "p03-concurrent-accept-" + i;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await(5, java.util.concurrent.TimeUnit.SECONDS);
                    return mockMvc.perform(post("/api/v1/invitations/{id}/responses", invitationId)
                                    .header("Authorization", bearer(student2))
                                    .header("Idempotency-Key", key)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"expectedRevision\":1,\"decision\":\"ACCEPT\"}"))
                            .andReturn().getResponse().getStatus();
                }));
            }
            assertThat(ready.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Integer> statuses = new java.util.ArrayList<>();
            for (var future : futures) {
                statuses.add(future.get(10, java.util.concurrent.TimeUnit.SECONDS));
            }
            assertThat(statuses).contains(200);
            assertThat(statuses).allMatch(s -> s == 200 || s == 409);
        } finally {
            executor.shutdownNow();
        }
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM membership WHERE project_id = ? AND status = 'ACTIVE'",
                Integer.class, projectId)).isEqualTo(2);
    }

    @Test
    void concurrentAcceptOfTwoTeamsKeepsOneAssignmentMembership() throws Exception {
        String student1 = login("student_demo_01", "demo-password-01");
        String student2 = login("student_demo_02", "demo-password-02");
        String assistant = login("assistant_demo_01", "demo-password-04");
        Bootstrap boot = bootstrap(student1);
        JsonNode first = createQuickProject(student1, boot, 3, "甲组", "p03-it04-a");
        JsonNode second = createQuickProject(student2, boot, 3, "乙组", "p03-it04-b");
        String firstInvite = invite(student1, first, "user_demo_assistant_01", "p03-it04-invite-a");
        String secondInvite = invite(student2, second, "user_demo_assistant_01", "p03-it04-invite-b");

        java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(2);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        List<java.util.concurrent.Future<Integer>> futures = new java.util.ArrayList<>();
        try {
            String[] invitations = {firstInvite, secondInvite};
            for (int i = 0; i < invitations.length; i++) {
                String invitationId = invitations[i];
                String key = "p03-it04-accept-" + i;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await(5, java.util.concurrent.TimeUnit.SECONDS);
                    return mockMvc.perform(post("/api/v1/invitations/{id}/responses", invitationId)
                                    .header("Authorization", bearer(assistant))
                                    .header("Idempotency-Key", key)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"expectedRevision\":1,\"decision\":\"ACCEPT\"}"))
                            .andReturn().getResponse().getStatus();
                }));
            }
            assertThat(ready.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Integer> statuses = new java.util.ArrayList<>();
            for (var future : futures) {
                statuses.add(future.get(10, java.util.concurrent.TimeUnit.SECONDS));
            }
            assertThat(statuses).contains(200);
            assertThat(statuses).allMatch(code -> code == 200 || code == 409);
        } finally {
            executor.shutdownNow();
        }
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM assignment_team_slot
                WHERE assignment_id = ? AND user_id = 'user_demo_assistant_01'
                """, Integer.class, boot.assignmentId())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM membership
                WHERE user_id = 'user_demo_assistant_01' AND status = 'ACTIVE'
                """, Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM seat_reservation
                WHERE user_id = 'user_demo_assistant_01' AND status = 'ACTIVE'
                """, Integer.class)).isZero();
    }

    @Test
    void expiredInvitationCannotBeAccepted() throws Exception {
        String student1 = login("student_demo_01", "demo-password-01");
        String assistant = login("assistant_demo_01", "demo-password-04");
        Bootstrap boot = bootstrap(student1);
        JsonNode project = createQuickProject(student1, boot, 3, "过期组", "p03-expire");
        String projectId = project.get("id").asText();
        JsonNode invite = data(mockMvc.perform(post("/api/v1/projects/{id}/invitations", projectId)
                        .header("Authorization", bearer(student1))
                        .header("Idempotency-Key", "p03-expire-invite")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedProjectRevision\":1,"
                                + "\"recipientId\":\"user_demo_assistant_01\",\"message\":\"邀请\"}"))
                .andExpect(status().isOk()).andReturn());
        clock.advance(java.time.Duration.ofHours(25));
        assistant = login("assistant_demo_01", "demo-password-04");
        mockMvc.perform(post("/api/v1/invitations/{id}/responses", invite.get("invitation").get("id").asText())
                        .header("Authorization", bearer(assistant))
                        .header("Idempotency-Key", "p03-expire-accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"decision\":\"ACCEPT\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVITATION_EXPIRED"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM membership WHERE project_id = ? AND user_id = 'user_demo_assistant_01'",
                Integer.class, projectId)).isZero();
    }

    private Bootstrap bootstrap(String studentToken) throws Exception {
        String matchBody = objectMapper.writeValueAsString(Map.of(
                "schoolId", SCHOOL, "termId", TERM, "teacherName", "示例教师",
                "slots", List.of(Map.of("weekday", 3, "periodId", "period_demo_2",
                        "weekPattern", "ALL", "startWeek", 1, "endWeek", 16, "customWeeks", List.of())),
                "courseNameId", "course_name_demo_mobile"));
        JsonNode match = data(mockMvc.perform(post("/api/v1/squares/matches")
                        .header("Authorization", bearer(studentToken))
                        .contentType(MediaType.APPLICATION_JSON).content(matchBody))
                .andExpect(status().isOk()).andReturn());
        String createBody = objectMapper.writeValueAsString(Map.of(
                "schoolId", SCHOOL, "termId", TERM, "teacherName", "示例教师",
                "slots", List.of(Map.of("weekday", 3, "periodId", "period_demo_2",
                        "weekPattern", "ALL", "startWeek", 1, "endWeek", 16, "customWeeks", List.of())),
                "courseNameId", "course_name_demo_mobile",
                "candidateCheckToken", match.get("candidateCheckToken").asText()));
        JsonNode square = data(mockMvc.perform(post("/api/v1/squares")
                        .header("Authorization", bearer(studentToken))
                        .header("Idempotency-Key", "p03-bootstrap-square")
                        .contentType(MediaType.APPLICATION_JSON).content(createBody))
                .andExpect(status().isOk()).andReturn());
        String assignmentBody = objectMapper.writeValueAsString(Map.of(
                "name", "课程设计", "requirements", "完成可演示应用",
                "hardDueAt", "2026-12-15T15:59:59Z", "minMembers", 2, "maxMembers", 6,
                "sourceReferences", List.of()));
        JsonNode assignment = data(mockMvc.perform(post("/api/v1/squares/{id}/assignments",
                                square.get("id").asText())
                        .header("Authorization", bearer(studentToken))
                        .header("Idempotency-Key", "p03-bootstrap-assignment")
                        .contentType(MediaType.APPLICATION_JSON).content(assignmentBody))
                .andExpect(status().isOk()).andReturn());
        String teacher = login("teacher_demo_01", "demo-password-03");
        mockMvc.perform(post("/api/v1/assignments/{id}/confirmations", assignment.get("id").asText())
                        .header("Authorization", bearer(teacher))
                        .header("Idempotency-Key", "p03-bootstrap-confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"decision\":\"CONFIRM_NEW\",\"reason\":\"ok\"}"))
                .andExpect(status().isOk());
        return new Bootstrap(square.get("id").asText(), assignment.get("id").asText());
    }

    private JsonNode createQuickProject(String token, Bootstrap boot, int capacity, String name,
                                        String key) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "squareId", boot.squareId(), "assignmentId", boot.assignmentId(),
                "mode", "QUICK", "name", name, "capacity", capacity,
                "discussionTime", "周三", "roles", List.of()));
        return data(mockMvc.perform(post("/api/v1/projects")
                        .header("Authorization", bearer(token))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn());
    }

    private JsonNode apply(String token, String projectId, String message, String roleSlotId,
                           String key) throws Exception {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("message", message);
        if (roleSlotId != null) {
            body.put("roleSlotId", roleSlotId);
        }
        return data(mockMvc.perform(post("/api/v1/projects/{id}/applications", projectId)
                        .header("Authorization", bearer(token))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk()).andReturn());
    }

    private String invite(String leader, JsonNode project, String recipientId, String key) throws Exception {
        JsonNode invite = data(mockMvc.perform(post("/api/v1/projects/{id}/invitations", project.get("id").asText())
                        .header("Authorization", bearer(leader))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedProjectRevision\":" + project.get("revision").asInt()
                                + ",\"recipientId\":\"" + recipientId + "\",\"message\":\"同时邀请\"}"))
                .andExpect(status().isOk()).andReturn());
        return invite.get("invitation").get("id").asText();
    }

    private int projectRevision(String token, String projectId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/projects/{id}", projectId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andReturn();
        return data(result).get("revision").asInt();
    }

    private String login(String loginId, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "loginId", loginId, "password", password))))
                .andExpect(status().isOk()).andReturn();
        return data(result).get("accessToken").asText();
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private JsonNode data(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
    }

    private record Bootstrap(String squareId, String assignmentId) {
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
