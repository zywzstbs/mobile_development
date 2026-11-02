package cn.gulupai.server.role;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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
@Import(RoleP04IT.TestClockConfig.class)
class RoleP04IT {

    private static final Instant BASE = Instant.parse("2026-10-31T00:00:00Z");
    private static final String SCHOOL = "school_demo_01";
    private static final String TERM = "term_demo_2026a";
    private static final String STUDENT_2 = "user_demo_student_02";
    private static final String ASSISTANT = "user_demo_assistant_01";

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
                "work_version", "work", "project_code", "seat_reservation",
                "team_invitation", "team_application", "assignment_team_slot", "membership", "role_slot",
                "project_stage", "project", "teacher_transfer", "teacher_claim", "teacher_assignment",
                "class_grant", "course_code", "course_join_request", "cursor_snapshot", "assignment",
                "course_membership", "merge_ballot", "square_merge", "preview_token", "square", "verified_enrollment", "verified_course",
                "course_name", "period", "term", "operation_receipt", "access_token", "auth_session",
                "audit_event", "rate_window", "import_batch", "user_role", "user_profile", "app_user",
                "school")) {
            jdbcTemplate.update("DELETE FROM " + table);
        }
    }

    @Test
    void roleSlotsKeepIdentityAndProtectOccupancy() throws Exception {
        String leader = login("student_demo_01", "demo-password-01");
        String member = login("student_demo_02", "demo-password-02");
        String assistant = login("assistant_demo_01", "demo-password-04");
        String teacher = login("teacher_demo_01", "demo-password-03");
        Bootstrap boot = bootstrap(leader);
        JsonNode project = createDetailed(leader, boot, "p04-c02-project", 5, List.of(
                Map.of("clientKey", "a", "roleName", "开发", "duty", "编码", "total", 3),
                Map.of("clientKey", "b", "roleName", "测试", "duty", "验收", "total", 2)));
        String projectId = project.get("id").asText();
        String roleA = project.get("roles").get(0).get("id").asText();
        String roleB = project.get("roles").get(1).get("id").asText();

        invite(leader, projectId, STUDENT_2, roleA, "p04-c02-reserve");
        mockMvc.perform(put("/api/v1/projects/{id}/role-slots", projectId)
                        .header("Authorization", bearer(leader))
                        .header("Idempotency-Key", "p04-c02-shrink")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(slotsBody(projectRevision(leader, projectId), List.of(
                                roleInput(roleA, "old_row", "开发", "编码", 1),
                                roleInput(roleB, null, "测试", "验收", 2)))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        assertThat(roleTotal(roleA)).isEqualTo(3);

        acceptLatestInvitation(member, projectId, "p04-c02-join");
        int revision = projectRevision(leader, projectId);
        JsonNode replaced = data(mockMvc.perform(put("/api/v1/projects/{id}/role-slots", projectId)
                        .header("Authorization", bearer(leader))
                        .header("Idempotency-Key", "p04-c02-replace")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(slotsBody(revision, List.of(
                                roleInput(roleA, "old_row", "开发", "开发功能", 2),
                                roleInput("", "new_row", "测试", "验证功能", 1)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.project.capacity").value(3))
                .andExpect(jsonPath("$.data.project.mode").value("DETAILED"))
                .andReturn());
        JsonNode roles = replaced.get("roles");
        assertThat(roles).hasSize(2);
        assertThat(roles.get(0).get("id").asText()).isEqualTo(roleA);
        String createdId = roles.get(1).get("id").asText();
        assertThat(createdId).isNotBlank().isNotEqualTo("new_row").isNotEqualTo(roleB);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM role_slot WHERE id = ?",
                Integer.class, roleB)).isZero();

        mockMvc.perform(put("/api/v1/projects/{id}/role-slots", projectId)
                        .header("Authorization", bearer(leader))
                        .header("Idempotency-Key", "p04-c02-stranger")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(slotsBody(projectRevision(leader, projectId), List.of(
                                roleInput("role_missing", "old_row", "开发", "开发功能", 2),
                                roleInput("", "another", "测试", "验证功能", 1)))))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(put("/api/v1/projects/{id}/role-slots", projectId)
                        .header("Authorization", bearer(leader))
                        .header("Idempotency-Key", "p04-c02-duplicate-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(slotsBody(projectRevision(leader, projectId), List.of(
                                roleInput(roleA, "same", "开发", "开发功能", 2),
                                roleInput("", "same", "测试", "验证功能", 1)))))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(put("/api/v1/projects/{id}/role-slots", projectId)
                        .header("Authorization", bearer(leader))
                        .header("Idempotency-Key", "p04-c02-hijack")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(slotsBody(projectRevision(leader, projectId), List.of(
                                roleInput(roleA, "keep", "开发", "开发功能", 2),
                                roleInput("", roleA, "劫持", "不能覆盖", 1)))))
                .andExpect(status().isUnprocessableEntity());
        assertThat(jdbcTemplate.queryForObject("SELECT role_name FROM role_slot WHERE id = ?",
                String.class, roleA)).isEqualTo("开发");

        JsonNode other = createDetailed(assistant, boot, "p04-c02-other", 2, List.of(
                Map.of("clientKey", "a", "roleName", "外部", "duty", "外部职责", "total", 2)));
        String foreignRole = other.get("roles").get(0).get("id").asText();
        mockMvc.perform(put("/api/v1/projects/{id}/role-slots", projectId)
                        .header("Authorization", bearer(leader))
                        .header("Idempotency-Key", "p04-c02-foreign")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(slotsBody(projectRevision(leader, projectId), List.of(
                                roleInput(foreignRole, "foreign", "开发", "开发功能", 2),
                                roleInput("", "new2", "测试", "验证功能", 1)))))
                .andExpect(status().isUnprocessableEntity());

        mockMvc.perform(put("/api/v1/projects/{id}/role-slots", projectId)
                        .header("Authorization", bearer(leader))
                        .header("Idempotency-Key", "p04-c02-below")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(slotsBody(projectRevision(leader, projectId), List.of(
                                roleInput(roleA, "old_row", "开发", "开发功能", 1),
                                roleInput(createdId, "new_row", "测试", "验证功能", 1)))))
                .andExpect(status().isUnprocessableEntity());
        assertThat(roleTotal(roleA)).isEqualTo(2);
        mockMvc.perform(put("/api/v1/projects/{id}/role-slots", projectId)
                        .header("Authorization", bearer(leader))
                        .header("Idempotency-Key", "p04-c02-delete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(slotsBody(projectRevision(leader, projectId), List.of(
                                roleInput(createdId, "new_row", "测试", "验证功能", 2)))))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(put("/api/v1/projects/{id}/role-slots", projectId)
                        .header("Authorization", bearer(leader))
                        .header("Idempotency-Key", "p04-c02-unknown")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"extra\":true,\"roles\":[]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("FIELD_NOT_ALLOWED"));
        mockMvc.perform(put("/api/v1/projects/{id}/role-slots", projectId)
                        .header("Authorization", bearer(member))
                        .header("Idempotency-Key", "p04-c02-member")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(slotsBody(projectRevision(leader, projectId), List.of(
                                roleInput(roleA, "old_row", "开发", "开发功能", 2),
                                roleInput(createdId, "new_row", "测试", "验证功能", 1)))))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/v1/projects/{id}/role-slots", projectId)
                        .header("Authorization", bearer(teacher))
                        .header("Idempotency-Key", "p04-c02-teacher")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(slotsBody(projectRevision(leader, projectId), List.of(
                                roleInput(roleA, "old_row", "开发", "开发功能", 2)))))
                .andExpect(status().isForbidden());

        int current = projectRevision(leader, projectId);
        mockMvc.perform(patch("/api/v1/projects/{id}", projectId)
                        .header("Authorization", bearer(leader))
                        .header("Idempotency-Key", "p04-c02-patch-mismatch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":" + current
                                + ",\"name\":\"详细组\",\"intro\":\"详细项目\",\"discussionTime\":\"周三\",\"capacity\":4}"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(patch("/api/v1/projects/{id}", projectId)
                        .header("Authorization", bearer(leader))
                        .header("Idempotency-Key", "p04-c02-patch-match")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":" + current
                                + ",\"name\":\"详细组\",\"intro\":\"详细项目\",\"discussionTime\":\"周三\",\"capacity\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.capacity").value(3));
        mockMvc.perform(get("/api/v1/projects/{id}/role-slots", projectId)
                        .header("Authorization", bearer(member)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roles[0].filled").value(2))
                .andExpect(jsonPath("$.data.conversionBlockers").isArray());
    }

    @Test
    void quickUpgradeAssignsLeaderAndLeavesOthersUnselected() throws Exception {
        String leader = login("student_demo_01", "demo-password-01");
        String assistant = login("assistant_demo_01", "demo-password-04");
        Bootstrap boot = bootstrap(leader);
        JsonNode project = createQuick(leader, boot, 3, "快速组", "p04-upgrade-project");
        String projectId = project.get("id").asText();
        mockMvc.perform(put("/api/v1/projects/{id}/role-slots", projectId)
                        .header("Authorization", bearer(leader))
                        .header("Idempotency-Key", "p04-upgrade-missing")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "expectedRevision", project.get("revision").asInt(),
                                "roles", List.of(Map.of("id", "", "clientKey", "lead",
                                        "roleName", "统筹", "duty", "组织", "total", 3)),
                                "completeDetails", Map.of("name", "不完整", "intro", "缺少组长岗位")))))
                .andExpect(status().isUnprocessableEntity());
        assertThat(jdbcTemplate.queryForObject("SELECT mode FROM project WHERE id = ?",
                String.class, projectId)).isEqualTo("QUICK");

        invite(leader, projectId, ASSISTANT, null, "p04-upgrade-invite");
        acceptLatestInvitation(assistant, projectId, "p04-upgrade-accept");
        JsonNode upgraded = data(mockMvc.perform(put("/api/v1/projects/{id}/role-slots", projectId)
                        .header("Authorization", bearer(leader))
                        .header("Idempotency-Key", "p04-upgrade-ok")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "expectedRevision", projectRevision(leader, projectId),
                                "roles", List.of(
                                        Map.of("id", "", "clientKey", "lead", "roleName", "统筹",
                                                "duty", "组织", "total", 2),
                                        Map.of("id", "", "clientKey", "dev", "roleName", "开发",
                                                "duty", "编码", "total", 1)),
                                "completeDetails", Map.of("name", "升级后的项目", "intro", "补充说明"),
                                "leaderRoleKey", "lead"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.project.mode").value("DETAILED"))
                .andExpect(jsonPath("$.data.project.name").value("升级后的项目"))
                .andReturn());
        String leaderRole = upgraded.get("project").get("myMembership").get("roleSlotId").asText();
        assertThat(leaderRole).isEqualTo(upgraded.get("roles").get(0).get("id").asText());
        JsonNode assistantView = data(mockMvc.perform(get("/api/v1/projects/{id}", projectId)
                        .header("Authorization", bearer(assistant)))
                .andExpect(status().isOk()).andReturn());
        assertThat(assistantView.get("myMembership").get("roleSlotId").isNull()).isTrue();
        mockMvc.perform(put("/api/v1/projects/{id}/role-slots", projectId)
                        .header("Authorization", bearer(leader))
                        .header("Idempotency-Key", "p04-upgrade-again")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "expectedRevision", upgraded.get("project").get("revision").asInt(),
                                "roles", List.of(Map.of("id", leaderRole, "roleName", "统筹",
                                        "duty", "组织", "total", 3)),
                                "completeDetails", Map.of("name", "不能再传", "intro", "已是详细项目")))))
                .andExpect(status().isUnprocessableEntity());
        assertThat(jdbcTemplate.queryForObject("SELECT name FROM project WHERE id = ?",
                String.class, projectId)).isEqualTo("升级后的项目");
    }

    @Test
    void proposalListsAcceptDeclineWithdrawAndIdempotency() throws Exception {
        String leader = login("student_demo_01", "demo-password-01");
        String member = login("student_demo_02", "demo-password-02");
        String assistant = login("assistant_demo_01", "demo-password-04");
        String teacher = login("teacher_demo_01", "demo-password-03");
        Fixture fixture = detailedTrio(leader, member, assistant, "p04-proposal");
        mockMvc.perform(get("/api/v1/me/role-proposals").header("Authorization", bearer(teacher)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/me/role-proposals").header("Authorization", bearer(member)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));

        MemberRow target = member(leader, fixture.projectId, STUDENT_2);
        String body = proposalBody(fixture.revision, target, fixture.roleA);
        JsonNode created = data(mockMvc.perform(post("/api/v1/projects/{id}/role-proposals", fixture.projectId)
                        .header("Authorization", bearer(leader))
                        .header("Idempotency-Key", "p04-proposal-create")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.proposal.status").value("PENDING"))
                .andExpect(jsonPath("$.data.canWithdraw").value(true))
                .andExpect(jsonPath("$.data.canAccept").value(false))
                .andReturn());
        String proposalId = created.get("proposal").get("id").asText();
        JsonNode replay = data(mockMvc.perform(post("/api/v1/projects/{id}/role-proposals", fixture.projectId)
                        .header("Authorization", bearer(leader))
                        .header("Idempotency-Key", "p04-proposal-create")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn());
        assertThat(replay.get("proposal").get("id").asText()).isEqualTo(proposalId);
        mockMvc.perform(post("/api/v1/projects/{id}/role-proposals", fixture.projectId)
                        .header("Authorization", bearer(leader))
                        .header("Idempotency-Key", "p04-proposal-create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(proposalBody(fixture.revision, target, fixture.roleA).replace("调整到另一个岗位", "另一段说明")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        mockMvc.perform(get("/api/v1/me/role-proposals").header("Authorization", bearer(member)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].canAccept").value(true));
        mockMvc.perform(get("/api/v1/projects/{id}/role-proposals", fixture.projectId)
                        .header("Authorization", bearer(leader)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1));
        mockMvc.perform(get("/api/v1/projects/{id}/role-proposals", fixture.projectId)
                        .header("Authorization", bearer(assistant)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
        mockMvc.perform(get("/api/v1/role-proposals/{id}", proposalId)
                        .header("Authorization", bearer(assistant)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/role-proposals/{id}/responses", proposalId)
                        .header("Authorization", bearer(leader))
                        .header("Idempotency-Key", "p04-proposal-leader-accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(responseBody(1, "ACCEPT", "", fixture.revision)))
                .andExpect(status().isForbidden());

        int slotsBefore = slotCount(fixture.projectId);
        mockMvc.perform(post("/api/v1/role-proposals/{id}/responses", proposalId)
                        .header("Authorization", bearer(member))
                        .header("Idempotency-Key", "p04-proposal-accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(responseBody(1, "ACCEPT", "", projectRevision(leader, fixture.projectId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.proposal.status").value("APPLIED"));
        assertThat(roleOf(fixture.projectId, STUDENT_2)).isEqualTo(fixture.roleA);
        assertThat(slotCount(fixture.projectId)).isEqualTo(slotsBefore);
        assertThat(taskTableCount()).isZero();

        MemberRow assistantRow = member(leader, fixture.projectId, ASSISTANT);
        JsonNode declined = data(mockMvc.perform(post("/api/v1/projects/{id}/role-proposals", fixture.projectId)
                        .header("Authorization", bearer(leader))
                        .header("Idempotency-Key", "p04-proposal-decline-create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(proposalBody(projectRevision(leader, fixture.projectId), assistantRow, fixture.roleB)))
                .andExpect(status().isOk()).andReturn());
        String declinedId = declined.get("proposal").get("id").asText();
        mockMvc.perform(post("/api/v1/role-proposals/{id}/responses", declinedId)
                        .header("Authorization", bearer(assistant))
                        .header("Idempotency-Key", "p04-proposal-decline")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(responseBody(1, "DECLINE", "", projectRevision(leader, fixture.projectId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.proposal.status").value("DECLINED"));
        assertThat(roleOf(fixture.projectId, ASSISTANT)).isEqualTo(fixture.roleA);

        JsonNode withdrawn = data(mockMvc.perform(post("/api/v1/projects/{id}/role-proposals", fixture.projectId)
                        .header("Authorization", bearer(leader))
                        .header("Idempotency-Key", "p04-proposal-withdraw-create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(proposalBody(projectRevision(leader, fixture.projectId), assistantRow, fixture.roleB)))
                .andExpect(status().isOk()).andReturn());
        mockMvc.perform(post("/api/v1/role-proposals/{id}/withdrawals",
                        withdrawn.get("proposal").get("id").asText())
                        .header("Authorization", bearer(leader))
                        .header("Idempotency-Key", "p04-proposal-withdraw")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"reason\":\"不再调整\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.proposal.status").value("WITHDRAWN"));
        mockMvc.perform(post("/api/v1/role-proposals/{id}/withdrawals",
                        withdrawn.get("proposal").get("id").asText())
                        .header("Authorization", bearer(member))
                        .header("Idempotency-Key", "p04-proposal-withdraw-member")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":2,\"reason\":\"成员不能撤回\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void swapAppliesOnlyAfterBothAccept() throws Exception {
        String leader = login("student_demo_01", "demo-password-01");
        String member = login("student_demo_02", "demo-password-02");
        String assistant = login("assistant_demo_01", "demo-password-04");
        Fixture fixture = detailedTrio(leader, member, assistant, "p04-swap");
        MemberRow first = member(leader, fixture.projectId, STUDENT_2);
        MemberRow second = member(leader, fixture.projectId, ASSISTANT);
        JsonNode swap = data(mockMvc.perform(post("/api/v1/projects/{id}/role-swaps", fixture.projectId)
                        .header("Authorization", bearer(leader))
                        .header("Idempotency-Key", "p04-swap-create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(swapBody(fixture.revision, first, second)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.swap.parties").isArray())
                .andReturn());
        String swapId = swap.get("swap").get("id").asText();
        mockMvc.perform(get("/api/v1/me/role-swaps").header("Authorization", bearer(member)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1));
        mockMvc.perform(get("/api/v1/role-swaps/{id}", swapId).header("Authorization", bearer(leader)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.canWithdraw").value(true));
        mockMvc.perform(post("/api/v1/role-swaps/{id}/responses", swapId)
                        .header("Authorization", bearer(member))
                        .header("Idempotency-Key", "p04-swap-first")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(acceptBody(1, fixture.revision)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.swap.status").value("PENDING"))
                .andExpect(jsonPath("$.data.myConfirmed").value(true));
        assertThat(roleOf(fixture.projectId, STUDENT_2)).isEqualTo(fixture.roleB);
        mockMvc.perform(post("/api/v1/role-swaps/{id}/responses", swapId)
                        .header("Authorization", bearer(assistant))
                        .header("Idempotency-Key", "p04-swap-second")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(acceptBody(1, fixture.revision)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.swap.status").value("APPLIED"));
        assertThat(roleOf(fixture.projectId, STUDENT_2)).isEqualTo(fixture.roleA);
        assertThat(roleOf(fixture.projectId, ASSISTANT)).isEqualTo(fixture.roleB);
        assertThat(slotCount(fixture.projectId)).isEqualTo(3);
        assertThat(taskTableCount()).isZero();
    }

    @Test
    void concurrentLastTwoPlanAcceptsApplyOnce() throws Exception {
        String leader = login("student_demo_01", "demo-password-01");
        String member = login("student_demo_02", "demo-password-02");
        String assistant = login("assistant_demo_01", "demo-password-04");
        Fixture fixture = detailedTrio(leader, member, assistant, "p04-plan");
        MemberRow first = member(leader, fixture.projectId, STUDENT_2);
        MemberRow second = member(leader, fixture.projectId, ASSISTANT);
        JsonNode plan = data(mockMvc.perform(post("/api/v1/projects/{id}/role-plans", fixture.projectId)
                        .header("Authorization", bearer(leader))
                        .header("Idempotency-Key", "p04-plan-create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(planBody(fixture.revision, List.of(
                                Map.of("membershipId", first.id(), "expectedMembershipRevision", first.revision(),
                                        "targetRoleSlotId", fixture.roleA),
                                Map.of("membershipId", second.id(), "expectedMembershipRevision", second.revision(),
                                        "targetRoleSlotId", fixture.roleB)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.canConfirm").value(false))
                .andReturn());
        String planId = plan.get("plan").get("id").asText();
        mockMvc.perform(get("/api/v1/projects/{id}/role-plans", fixture.projectId)
                        .header("Authorization", bearer(member)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1));
        mockMvc.perform(get("/api/v1/me/role-plans").header("Authorization", bearer(assistant)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].canConfirm").value(true));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            List<String> tokens = List.of(member, assistant);
            for (int i = 0; i < 2; i++) {
                String token = tokens.get(i);
                String key = "p04-plan-accept-" + i;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await(5, java.util.concurrent.TimeUnit.SECONDS);
                    return mockMvc.perform(post("/api/v1/role-plans/{id}/responses", planId)
                                    .header("Authorization", bearer(token))
                                    .header("Idempotency-Key", key)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(acceptBody(1, fixture.revision)))
                            .andReturn().getResponse().getStatus();
                }));
            }
            assertThat(ready.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get(15, java.util.concurrent.TimeUnit.SECONDS));
            }
            assertThat(statuses).containsOnly(200);
        } finally {
            executor.shutdownNow();
        }
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM role_negotiation WHERE id = ?", String.class, planId)).isEqualTo("APPLIED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM role_ack WHERE negotiation_id = ? AND decision = 'ACCEPT'",
                Integer.class, planId)).isEqualTo(2);
        assertThat(roleOf(fixture.projectId, STUDENT_2)).isEqualTo(fixture.roleA);
        assertThat(roleOf(fixture.projectId, ASSISTANT)).isEqualTo(fixture.roleB);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM membership WHERE project_id = ? AND status = 'ACTIVE'",
                Integer.class, fixture.projectId)).isEqualTo(3);
    }

    @Test
    void stalePlanCannotApplyAndNewMemberKeepsRole() throws Exception {
        String leader = login("student_demo_01", "demo-password-01");
        String member = login("student_demo_02", "demo-password-02");
        String assistant = login("assistant_demo_01", "demo-password-04");
        Bootstrap boot = bootstrap(leader);
        JsonNode project = createDetailed(leader, boot, "p04-stale-project", 6, List.of(
                Map.of("clientKey", "a", "roleName", "开发", "duty", "编码", "total", 4),
                Map.of("clientKey", "b", "roleName", "测试", "duty", "验收", "total", 2)));
        String projectId = project.get("id").asText();
        String roleA = project.get("roles").get(0).get("id").asText();
        String roleB = project.get("roles").get(1).get("id").asText();
        invite(leader, projectId, STUDENT_2, roleA, "p04-stale-invite-member");
        acceptLatestInvitation(member, projectId, "p04-stale-accept-member");
        MemberRow target = member(leader, projectId, STUDENT_2);
        MemberRow leaderMember = member(leader, projectId, "user_demo_student_01");
        int revision = projectRevision(leader, projectId);
        JsonNode plan = data(mockMvc.perform(post("/api/v1/projects/{id}/role-plans", projectId)
                        .header("Authorization", bearer(leader))
                        .header("Idempotency-Key", "p04-stale-plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(planBody(revision, List.of(
                                Map.of("membershipId", target.id(), "expectedMembershipRevision", target.revision(),
                                        "targetRoleSlotId", roleB),
                                Map.of("membershipId", leaderMember.id(),
                                        "expectedMembershipRevision", leaderMember.revision(),
                                        "targetRoleSlotId", roleB)))))
                .andExpect(status().isOk()).andReturn());
        String planId = plan.get("plan").get("id").asText();

        invite(leader, projectId, ASSISTANT, roleA, "p04-stale-invite-new");
        acceptLatestInvitation(assistant, projectId, "p04-stale-accept-new");
        assertThat(roleOf(projectId, ASSISTANT)).isEqualTo(roleA);
        mockMvc.perform(post("/api/v1/role-plans/{id}/responses", planId)
                        .header("Authorization", bearer(member))
                        .header("Idempotency-Key", "p04-stale-member-accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(acceptBody(1, projectRevision(leader, projectId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.plan.status").value("PENDING"));
        mockMvc.perform(post("/api/v1/role-plans/{id}/responses", planId)
                        .header("Authorization", bearer(leader))
                        .header("Idempotency-Key", "p04-stale-leader-accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(acceptBody(1, projectRevision(leader, projectId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.plan.status").value("APPLIED"));
        assertThat(roleOf(projectId, ASSISTANT)).isEqualTo(roleA);
        assertThat(roleOf(projectId, STUDENT_2)).isEqualTo(roleB);
        assertThat(roleOf(projectId, "user_demo_student_01")).isEqualTo(roleB);

        MemberRow assistantNow = member(leader, projectId, ASSISTANT);
        mockMvc.perform(put("/api/v1/projects/{id}/me/role", projectId)
                        .header("Authorization", bearer(assistant))
                        .header("Idempotency-Key", "p04-role-full")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":" + assistantNow.revision()
                                + ",\"expectedProjectRevision\":" + projectRevision(leader, projectId)
                                + ",\"roleSlotId\":\"" + roleB + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ROLE_FULL"));
        assertThat(roleOf(projectId, ASSISTANT)).isEqualTo(roleA);

        MemberRow moved = member(leader, projectId, STUDENT_2);
        mockMvc.perform(put("/api/v1/projects/{id}/me/role", projectId)
                        .header("Authorization", bearer(member))
                        .header("Idempotency-Key", "p04-self-role")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":" + moved.revision()
                                + ",\"expectedProjectRevision\":" + projectRevision(leader, projectId)
                                + ",\"roleSlotId\":\"" + roleA + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.myMembership.roleSlotId").value(roleA));
        MemberRow studentNow = member(leader, projectId, STUDENT_2);
        MemberRow leaderNow = member(leader, projectId, "user_demo_student_01");
        JsonNode blocked = data(mockMvc.perform(post("/api/v1/projects/{id}/role-plans", projectId)
                        .header("Authorization", bearer(leader))
                        .header("Idempotency-Key", "p04-blocked-plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(planBody(projectRevision(leader, projectId), List.of(
                                Map.of("membershipId", studentNow.id(),
                                        "expectedMembershipRevision", studentNow.revision(),
                                        "targetRoleSlotId", roleB),
                                Map.of("membershipId", leaderNow.id(),
                                        "expectedMembershipRevision", leaderNow.revision(),
                                        "targetRoleSlotId", roleA)))))
                .andExpect(status().isOk()).andReturn());
        String blockedId = blocked.get("plan").get("id").asText();
        jdbcTemplate.update("UPDATE project SET owner_id = ? WHERE id = ?", STUDENT_2, projectId);
        mockMvc.perform(get("/api/v1/role-plans/{id}", blockedId).header("Authorization", bearer(member)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.availability").value("LEADER_CHANGED"))
                .andExpect(jsonPath("$.data.canConfirm").value(false));
        mockMvc.perform(post("/api/v1/role-plans/{id}/responses", blockedId)
                        .header("Authorization", bearer(member))
                        .header("Idempotency-Key", "p04-leader-changed-accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(acceptBody(1, projectRevision(leader, projectId))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SOURCE_CHANGED"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM role_negotiation WHERE id = ?", String.class, blockedId)).isEqualTo("PENDING");
        assertThat(roleOf(projectId, STUDENT_2)).isEqualTo(roleA);
    }

    private Fixture detailedTrio(String leader, String member, String assistant, String key) throws Exception {
        Bootstrap boot = bootstrap(leader);
        JsonNode project = createDetailed(leader, boot, key + "-project", 5, List.of(
                Map.of("clientKey", "a", "roleName", "开发", "duty", "编码", "total", 3),
                Map.of("clientKey", "b", "roleName", "测试", "duty", "验收", "total", 2)));
        String projectId = project.get("id").asText();
        String roleA = project.get("roles").get(0).get("id").asText();
        String roleB = project.get("roles").get(1).get("id").asText();
        invite(leader, projectId, STUDENT_2, roleB, key + "-invite-member");
        acceptLatestInvitation(member, projectId, key + "-accept-member");
        invite(leader, projectId, ASSISTANT, roleA, key + "-invite-assistant");
        acceptLatestInvitation(assistant, projectId, key + "-accept-assistant");
        return new Fixture(boot, projectId, roleA, roleB, projectRevision(leader, projectId));
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
                        .header("Idempotency-Key", "p04-bootstrap-square")
                        .contentType(MediaType.APPLICATION_JSON).content(createBody))
                .andExpect(status().isOk()).andReturn());
        String assignmentBody = objectMapper.writeValueAsString(Map.of(
                "name", "课程设计", "requirements", "完成可演示应用",
                "hardDueAt", "2026-12-15T15:59:59Z", "minMembers", 2, "maxMembers", 6,
                "sourceReferences", List.of()));
        JsonNode assignment = data(mockMvc.perform(post("/api/v1/squares/{id}/assignments",
                                square.get("id").asText())
                        .header("Authorization", bearer(studentToken))
                        .header("Idempotency-Key", "p04-bootstrap-assignment")
                        .contentType(MediaType.APPLICATION_JSON).content(assignmentBody))
                .andExpect(status().isOk()).andReturn());
        String teacher = login("teacher_demo_01", "demo-password-03");
        mockMvc.perform(post("/api/v1/assignments/{id}/confirmations", assignment.get("id").asText())
                        .header("Authorization", bearer(teacher))
                        .header("Idempotency-Key", "p04-bootstrap-confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"decision\":\"CONFIRM_NEW\",\"reason\":\"ok\"}"))
                .andExpect(status().isOk());
        return new Bootstrap(square.get("id").asText(), assignment.get("id").asText());
    }

    private JsonNode createDetailed(String token, Bootstrap boot, String key, int capacity,
                                    List<Map<String, Object>> roles) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "squareId", boot.squareId(), "assignmentId", boot.assignmentId(),
                "mode", "DETAILED", "name", "详细组", "intro", "详细项目",
                "capacity", capacity, "discussionTime", "周三",
                "roles", roles, "leaderRoleKey", "a"));
        return data(mockMvc.perform(post("/api/v1/projects")
                        .header("Authorization", bearer(token))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn());
    }

    private JsonNode createQuick(String token, Bootstrap boot, int capacity, String name, String key)
            throws Exception {
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

    private void invite(String leader, String projectId, String userId, String roleId, String key)
            throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("expectedProjectRevision", projectRevision(leader, projectId));
        body.put("recipientId", userId);
        body.put("message", "加入项目");
        if (roleId != null) {
            body.put("roleSlotId", roleId);
        }
        mockMvc.perform(post("/api/v1/projects/{id}/invitations", projectId)
                        .header("Authorization", bearer(leader))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk());
    }

    private void acceptLatestInvitation(String token, String projectId, String key) throws Exception {
        String invitationId = jdbcTemplate.queryForObject("""
                SELECT id FROM team_invitation
                WHERE project_id = ? AND status = 'PENDING'
                ORDER BY created_at DESC LIMIT 1
                """, String.class, projectId);
        mockMvc.perform(post("/api/v1/invitations/{id}/responses", invitationId)
                        .header("Authorization", bearer(token))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"decision\":\"ACCEPT\"}"))
                .andExpect(status().isOk());
    }

    private String slotsBody(int revision, List<Map<String, Object>> roles) throws Exception {
        return objectMapper.writeValueAsString(Map.of("expectedRevision", revision, "roles", roles));
    }

    private Map<String, Object> roleInput(String id, String clientKey, String name, String duty, int total) {
        Map<String, Object> role = new java.util.LinkedHashMap<>();
        role.put("id", id);
        if (clientKey != null) {
            role.put("clientKey", clientKey);
        }
        role.put("roleName", name);
        role.put("duty", duty);
        role.put("total", total);
        return role;
    }

    private String proposalBody(int revision, MemberRow member, String roleId) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "expectedRevision", revision,
                "membershipId", member.id(),
                "expectedMembershipRevision", member.revision(),
                "roleSlotId", roleId,
                "message", "调整到另一个岗位"));
    }

    private String swapBody(int revision, MemberRow first, MemberRow second) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "expectedRevision", revision,
                "firstMembershipId", first.id(),
                "secondMembershipId", second.id(),
                "expectedFirstMembershipRevision", first.revision(),
                "expectedSecondMembershipRevision", second.revision(),
                "reason", "交换岗位"));
    }

    private String planBody(int revision, List<Map<String, Object>> assignments) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "expectedRevision", revision, "assignments", assignments, "reason", "多人调岗"));
    }

    private String acceptBody(int revision, int projectRevision) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "expectedRevision", revision, "decision", "ACCEPT", "comment", "",
                "expectedProjectRevision", projectRevision));
    }

    private String responseBody(int revision, String decision, String reason, int projectRevision)
            throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "expectedRevision", revision, "decision", decision, "reason", reason,
                "expectedProjectRevision", projectRevision));
    }

    private MemberRow member(String token, String projectId, String userId) throws Exception {
        JsonNode items = data(mockMvc.perform(get("/api/v1/projects/{id}/members", projectId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andReturn()).get("items");
        String roleA = null;
        String roleB = null;
        MemberRow found = null;
        for (JsonNode item : items) {
            String roleId = item.get("roleSlotId").isNull() ? null : item.get("roleSlotId").asText();
            if (roleA == null && roleId != null) {
                roleA = roleId;
            } else if (roleId != null && !roleId.equals(roleA)) {
                roleB = roleId;
            }
            if (userId.equals(item.get("userId").asText())) {
                found = new MemberRow(item.get("id").asText(), item.get("revision").asInt(), roleId);
            }
        }
        assertThat(found).isNotNull();
        String other = found.roleId() == null ? roleA : found.roleId().equals(roleA) ? roleB : roleA;
        return new MemberRow(found.id(), found.revision(), other == null ? found.roleId() : other);
    }

    private int projectRevision(String token, String projectId) throws Exception {
        return data(mockMvc.perform(get("/api/v1/projects/{id}", projectId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andReturn()).get("revision").asInt();
    }

    private int roleTotal(String roleId) {
        return jdbcTemplate.queryForObject("SELECT total FROM role_slot WHERE id = ?", Integer.class, roleId);
    }

    private String roleOf(String projectId, String userId) {
        return jdbcTemplate.queryForObject("""
                SELECT role_slot_id FROM membership
                WHERE project_id = ? AND user_id = ? AND status = 'ACTIVE'
                """, String.class, projectId, userId);
    }

    private int slotCount(String projectId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM assignment_team_slot WHERE project_id = ?", Integer.class, projectId);
    }

    private int taskTableCount() {
        Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM task", Integer.class);
        return count == null ? 0 : count;
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

    private record Fixture(Bootstrap boot, String projectId, String roleA, String roleB, int revision) {
    }

    private record MemberRow(String id, int revision, String otherRole) {
        String roleId() {
            return otherRole;
        }
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
