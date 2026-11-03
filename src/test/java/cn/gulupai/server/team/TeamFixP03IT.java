package cn.gulupai.server.team;

import cn.gulupai.server.it.TestSchemaCleaner;
import cn.gulupai.server.common.clock.MutableClock;
import cn.gulupai.server.identity.cli.AcademicImportService;
import cn.gulupai.server.governance.PersonnelCaseGrantAdminService;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F06/R03、F07/R04、F08/R08 的真实 PostgreSQL 回归。
 * 旧实现会固定返回空作品来源、截断候选并伪装完整列表、待办只覆盖邀请/申请。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TeamFixP03IT.TestClockConfig.class)
class TeamFixP03IT {

    private static final Instant BASE = Instant.parse("2026-10-31T00:00:00Z");
    private static final String SCHOOL = "school_demo_01";
    private static final String TERM = "term_demo_2026a";
    private static final String LEADER = "user_demo_student_01";
    private static final String MEMBER = "user_demo_student_02";
    private static final String TEACHER = "user_demo_teacher_01";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MutableClock clock;
    @Autowired private AdminImportService users;
    @Autowired private AcademicImportService academics;
    @Autowired private PersonnelCaseGrantAdminService personnelCaseGrantService;

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

    // ---- F06/R03 ----

    @Test
    void r03WorkSourceUsesRealEvidenceAndHistoricalMember() throws Exception {
        String leader = login("student_demo_01", "demo-password-01");
        String member = login("student_demo_02", "demo-password-02");
        String projectId = project(leader, member, "f06-source");

        mockMvc.perform(get("/api/v1/me/work-source-projects").header("Authorization", bearer(leader)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));

        acceptedUnit(projectId, LEADER, "2026-09-30T00:00:00Z", "2026-10-01T00:00:00Z", true);
        acceptedUnit(projectId, LEADER, "2026-10-02T00:00:00Z", "2026-10-01T00:00:00Z", false);
        reviewedUnit(projectId, MEMBER, LEADER);
        returnedUnit(projectId, LEADER);

        JsonNode sources = data(mockMvc.perform(get("/api/v1/me/work-source-projects")
                        .header("Authorization", bearer(leader)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(projectId))
                .andExpect(jsonPath("$.data.items[0].projectId").value(projectId))
                .andExpect(jsonPath("$.data.items[0].myEvidence.projectId").value(projectId))
                .andExpect(jsonPath("$.data.items[0].myEvidence.acceptedDeliveries").value(2))
                .andExpect(jsonPath("$.data.items[0].myEvidence.confirmedUnits").value(1))
                .andExpect(jsonPath("$.data.myEvidence").doesNotExist())
                .andReturn());
        assertThat(sources.path("items").get(0).path("myEvidence").path("independentReviews").asInt())
                .isEqualTo(1);
        assertThat(sources.path("items").get(0).path("myEvidence").path("onTimeDeliveries").asInt())
                .isEqualTo(1);
        assertThat(sources.path("items").get(0).path("myEvidence").path("lateDeliveries").asInt())
                .isEqualTo(1);

        // 本人证据不会枚举他人数据。
        String assistant = login("assistant_demo_01", "demo-password-04");
        mockMvc.perform(get("/api/v1/me/work-source-projects").header("Authorization", bearer(assistant)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));

        // 历史离组仍可选择并保存来源，但不恢复聊天读取权。
        acceptedUnit(projectId, MEMBER, "2026-09-30T00:00:00Z", "2026-10-01T00:00:00Z", true);
        jdbc.update("UPDATE membership SET status='LEFT' WHERE project_id=? AND user_id=?", projectId, MEMBER);
        jdbc.update("UPDATE project SET authorization_epoch=authorization_epoch+1 WHERE id=?", projectId);
        mockMvc.perform(get("/api/v1/me/work-source-projects").header("Authorization", bearer(member)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].myEvidence.acceptedDeliveries").value(2));
        mockMvc.perform(get("/api/v1/projects/{id}/chat-messages", projectId)
                        .header("Authorization", bearer(member)))
                .andExpect(status().isForbidden());
        JsonNode work = data(mockMvc.perform(post("/api/v1/me/works")
                        .header("Authorization", bearer(member)).header("Idempotency-Key", "f06-work")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "expectedRevision", 0, "title", "历史贡献作品", "description", "说明",
                                "role", "成员", "myContribution", "完成分项",
                                "sourceProjectId", projectId, "audience", "PRIVATE",
                                "artifacts", List.of(Map.of("kind", "LINK",
                                        "url", "https://example.test/work", "title", "仓库"))))))
                .andExpect(status().isOk()).andReturn());
        mockMvc.perform(get("/api/v1/works/{id}", work.path("id").asText())
                        .header("Authorization", bearer(member)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sourceProjectId").value(projectId));
    }

    // ---- F07/R04 ----

    @Test
    void r04InviteCandidatesPageBySnapshotWithFieldsAndVersionBinding() throws Exception {
        String leader = login("student_demo_01", "demo-password-01");
        String projectId = projectOnly(leader, "f07-candidates");
        String squareId = jdbc.queryForObject("SELECT square_id FROM project WHERE id=?", String.class, projectId);
        String verifiedCourseId = jdbc.queryForObject(
                "SELECT verified_course_id FROM square WHERE id=?", String.class, squareId);
        for (int i = 0; i < 21; i++) {
            String id = "user_f07_" + String.format("%02d", i);
            jdbc.update("""
                    INSERT INTO app_user(id, school_id, login_id, student_no, display_name,
                        password_hash, identity_status, skills_json)
                    VALUES (?, ?, ?, ?, ?, 'x', 'VERIFIED', '["Java"]'::jsonb)
                    """, id, SCHOOL, "f07_login_" + i, "F07-" + String.format("%02d", i),
                    "候选" + String.format("%02d", i));
            jdbc.update("""
                    INSERT INTO verified_enrollment(id, verified_course_id, user_id, valid)
                    VALUES (?, ?, ?, true)
                    """, UUID.randomUUID().toString(), verifiedCourseId, id);
        }
        int revision = projectRevision(projectId);
        JsonNode first = data(mockMvc.perform(get("/api/v1/projects/{id}/invite-candidates", projectId)
                        .param("expectedProjectRevision", String.valueOf(revision))
                        .param("q", "候选")
                        .header("Authorization", bearer(leader)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(20))
                .andExpect(jsonPath("$.data.total").value(21))
                .andExpect(jsonPath("$.data.hasMore").value(true))
                .andExpect(jsonPath("$.data.projectRevision").value(revision))
                .andExpect(jsonPath("$.data.items[0].userNo").value("F07-00"))
                .andExpect(jsonPath("$.data.items[0].source").value("COURSE"))
                .andExpect(jsonPath("$.data.items[0].eligible").value(true))
                .andReturn());
        String cursor = first.path("nextCursor").asText();
        assertThat(cursor).isNotBlank();
        mockMvc.perform(get("/api/v1/projects/{id}/invite-candidates", projectId)
                        .param("expectedProjectRevision", String.valueOf(revision))
                        .param("q", "候选").param("cursor", cursor)
                        .header("Authorization", bearer(leader)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.total").value(21))
                .andExpect(jsonPath("$.data.hasMore").value(false))
                .andExpect(jsonPath("$.data.nextCursor").value(nullValue()));

        // 同用户有有效申请时保留 APPLICATION 语境且不重复占位。
        jdbc.update("""
                INSERT INTO team_application(id, project_id, applicant_id, message, status, revision)
                VALUES (?, ?, 'user_f07_00', '申请', 'PENDING', 1)
                """, UUID.randomUUID().toString(), projectId);
        JsonNode dedup = data(mockMvc.perform(get("/api/v1/projects/{id}/invite-candidates", projectId)
                        .param("expectedProjectRevision", String.valueOf(revision))
                        .param("q", "候选")
                        .header("Authorization", bearer(leader)))
                .andExpect(status().isOk()).andReturn());
        assertThat(dedup.path("total").asInt()).isEqualTo(21);
        int appearances = 0;
        JsonNode applicant = null;
        for (JsonNode item : dedup.path("items")) {
            if ("user_f07_00".equals(item.path("userId").asText())) {
                appearances++;
                applicant = item;
            }
        }
        assertThat(appearances).isEqualTo(1);
        assertThat(applicant).isNotNull();
        assertThat(applicant.path("source").asText()).isEqualTo("APPLICATION");

        // LIKE 通配符按字面处理。
        mockMvc.perform(get("/api/v1/projects/{id}/invite-candidates", projectId)
                        .param("expectedProjectRevision", String.valueOf(revision))
                        .param("q", "%")
                        .header("Authorization", bearer(leader)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));

        // 项目版本变化使旧游标不可继续。
        jdbc.update("UPDATE project SET revision=revision+1 WHERE id=?", projectId);
        mockMvc.perform(get("/api/v1/projects/{id}/invite-candidates", projectId)
                        .param("expectedProjectRevision", String.valueOf(revision))
                        .param("q", "候选").param("cursor", cursor)
                        .header("Authorization", bearer(leader)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESOURCE_CHANGED"));

        // 续页快照 15 分钟到期，过期后要求重新加载。
        int freshRevision = projectRevision(projectId);
        JsonNode fresh = data(mockMvc.perform(get("/api/v1/projects/{id}/invite-candidates", projectId)
                        .param("expectedProjectRevision", String.valueOf(freshRevision))
                        .param("q", "候选")
                        .header("Authorization", bearer(leader)))
                .andExpect(status().isOk()).andReturn());
        clock.setInstant(BASE.plusSeconds(16 * 60));
        String relogin = login("student_demo_01", "demo-password-01");
        mockMvc.perform(get("/api/v1/projects/{id}/invite-candidates", projectId)
                        .param("expectedProjectRevision", String.valueOf(freshRevision))
                        .param("q", "候选").param("cursor", fresh.path("nextCursor").asText())
                        .header("Authorization", bearer(relogin)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CURSOR_EXPIRED"));
    }

    // ---- F08/R08 ----

    @Test
    void r08TodosCoverTeamDeliveryAndReviewerSources() throws Exception {
        String leader = login("student_demo_01", "demo-password-01");
        String member = login("student_demo_02", "demo-password-02");
        String projectId = project(leader, member, "f08-team");

        // 邀请：受邀人 ACTION，负责人 WAITING。另建项目只发邀请不接受。
        String inviteProject = projectOnly(leader, "f08-invite");
        sendInvite(leader, inviteProject, key("f08-invite-send"));

        // 申请：另一项目上申请人 WAITING，负责人 ACTION。
        String applyProject = projectOnly(leader, "f08-apply");
        jdbc.update("""
                INSERT INTO team_application(id, project_id, applicant_id, message, status, revision)
                VALUES (?, ?, ?, '申请', 'PENDING', 1)
                """, UUID.randomUUID().toString(), applyProject, MEMBER);

        // 约定：份额责任人 ACTION，负责人 WAITING。
        String agreementTask = agreementTask(projectId, "PENDING");
        // 验收邀请：受邀人 ACTION，负责人 WAITING。
        reviewerInvitation(agreementTask, MEMBER);
        // 任务：本人责任任务 ACTION；提交后转为 WAITING 提交验收。
        String task = ordinaryTask(projectId, MEMBER);
        String submission = pendingSubmission(task, MEMBER);

        Set<String> memberTypes = todoTypes(member);
        assertThat(memberTypes).contains("INVITATION", "APPLICATION", "AGREEMENT",
                "REVIEWER_INVITATION", "TASK");
        JsonNode memberTasks = todoByType(member, "TASK");
        assertThat(textValues(memberTasks.path("items"), "state")).contains("WAITING");
        assertThat(memberTasks.path("items").toString()).contains(task);
        assertThat(submission).isNotBlank();

        Set<String> leaderTypes = todoTypes(leader);
        assertThat(leaderTypes).contains("INVITATION", "APPLICATION", "AGREEMENT",
                "REVIEWER_INVITATION");
        // dashboard 与列表同一口径：计数等于同一快照过滤结果。
        JsonNode dashboard = data(mockMvc.perform(get("/api/v1/me/dashboard")
                        .header("Authorization", bearer(member)))
                .andExpect(status().isOk()).andReturn());
        JsonNode todos = data(mockMvc.perform(get("/api/v1/me/todos")
                        .header("Authorization", bearer(member)))
                .andExpect(status().isOk()).andReturn());
        long action = 0;
        long waiting = 0;
        for (JsonNode item : todos.path("items")) {
            if ("ACTION".equals(item.path("state").asText())) action++;
            if ("WAITING".equals(item.path("state").asText())) waiting++;
        }
        assertThat(dashboard.path("actionCount").asLong()).isEqualTo(action);
        assertThat(dashboard.path("waitingCount").asLong()).isEqualTo(waiting);
        assertThat(dashboard.path("items").size()).isEqualTo(todos.path("items").size());

        // 终态退出：约定确认后 ACTION 消失，但任务/申请等其他来源仍在。
        jdbc.update("UPDATE agreement SET status='ACTIVE' WHERE task_id=?", agreementTask);
        assertThat(todoTypes(member)).doesNotContain("AGREEMENT");
    }

    @Test
    void r08TodosCoverGovernanceMergeAndTerminalExit() throws Exception {
        String leader = login("student_demo_01", "demo-password-01");
        String member = login("student_demo_02", "demo-password-02");
        String projectId = project(leader, member, "f08-gov");
        String secondProject = projectOnly(leader, "f08-gov-2", "course_name_demo_db", 5, "period_demo_3");
        String squareId = jdbc.queryForObject("SELECT square_id FROM project WHERE id=?", String.class, projectId);
        String secondSquare = jdbc.queryForObject("SELECT square_id FROM project WHERE id=?", String.class, secondProject);
        String task = ordinaryTask(projectId, LEADER);

        String proposal = "proposal-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO unit_change_proposal(id, task_id, base_agreement_version, status, changes_json,
                    reason, affected_users_json, initiator_id, revision, created_at, updated_at)
                VALUES (?, ?, 1, 'PENDING', '[]'::jsonb, '调整', ?::jsonb, ?, 1, ?, ?)
                """, proposal, task, "[\"" + MEMBER + "\"]", LEADER, at(0), at(0));
        String role = "role-" + UUID.randomUUID();
        roleNegotiation(role, projectId, "PROPOSAL");
        roleNegotiation("role-swap-" + UUID.randomUUID(), projectId, "SWAP");
        roleNegotiation("role-plan-" + UUID.randomUUID(), projectId, "PLAN");
        String vote = "vote-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO removal_vote(id, project_id, target_user_id, initiator_id, status, facts,
                    communication, statement, statement_ends_at, voting_ends_at, electorate_json,
                    revision, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'VOTING', '事实', '沟通', '', ?, ?, ?::jsonb, 1, ?, ?)
                """, vote, projectId, MEMBER, LEADER, at(3600), at(7200),
                "[\"" + MEMBER + "\"]", at(0), at(0));
        String change = "change-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO member_change(id, project_id, target_user_id, initiator_id, kind, status,
                    plan_version, source_membership_revision, revision, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'EXIT', 'READY', 1, 1, 1, ?, ?)
                """, change, projectId, MEMBER, LEADER, at(0), at(0));
        jdbc.update("""
                INSERT INTO handover_item(id, change_id, plan_version, kind, task_id,
                    source_task_revision, recipient_id, responsibility)
                VALUES (?, ?, 1, 'TASK', ?, 1, ?, '实现')
                """, UUID.randomUUID().toString(), change, task, MEMBER);
        String caseId = "case-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO personnel_case(id, square_id, project_id, subject_user_id, title, status,
                    revision, created_at, updated_at)
                VALUES (?, ?, ?, ?, '人员事项', 'OPEN', 1, ?, ?)
                """, caseId, squareId, projectId, MEMBER, at(0), at(0));
        jdbc.update("""
                INSERT INTO material_request(id, case_id, recipient_id, status, requirements, due_at,
                    response_version, response_json, revision, created_at, updated_at)
                VALUES (?, ?, ?, 'OPEN', '提交材料', ?, 0, '{}'::jsonb, 1, ?, ?)
                """, UUID.randomUUID().toString(), caseId, MEMBER, at(3600), at(0), at(0));
        jdbc.update("""
                INSERT INTO square_merge(id, source_square_id, target_square_id, initiator_id, reason,
                    evidence_json, status, electorates_json, source_epochs_json, fact_check_json,
                    voting_ends_at, revision, created_at, updated_at)
                VALUES (?, ?, ?, ?, '合并', '[]'::jsonb, 'VOTING', ?::jsonb, '{}'::jsonb,
                    '{}'::jsonb, ?, 1, ?, ?)
                """, UUID.randomUUID().toString(), squareId, secondSquare, LEADER,
                "{\"source\":[\"" + MEMBER + "\"],\"target\":[]}", at(7200), at(0), at(0));

        Set<String> types = todoTypes(member);
        assertThat(types).contains("CHANGE_PROPOSAL", "ROLE_PROPOSAL", "ROLE_SWAP", "ROLE_PLAN",
                "REMOVAL_VOTE", "MEMBER_CHANGE", "MATERIAL_REQUEST", "SQUARE_MERGE");
        // S04：教师接续走真实 member-change → case → grant → handoff，学生不是处理人也不应是 ACTION。
        assertThat(types).doesNotContain("PERSONNEL_HANDOFF");
        String teacher = login("teacher_demo_01", "demo-password-03");
        int membershipRevision = jdbc.queryForObject("""
                SELECT revision FROM membership WHERE project_id=? AND user_id=? AND status='ACTIVE'
                """, Integer.class, projectId, LEADER);
        JsonNode realChange = data(mockMvc.perform(post("/api/v1/projects/{id}/member-changes", projectId)
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "s04-change")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"EXIT\",\"reason\":\"教师接续\","
                                + "\"expectedMembershipRevision\":" + membershipRevision + "}"))
                .andExpect(status().isOk()).andReturn());
        assertThat(todoTypes(teacher)).contains("MEMBER_CHANGE");
        String realCaseId = jdbc.queryForObject(
                "SELECT id FROM personnel_case WHERE member_change_id = ?",
                String.class, realChange.path("id").asText());
        mockMvc.perform(post("/api/v1/personnel-cases/{id}/acceptances", realCaseId)
                        .header("Authorization", bearer(teacher)).header("Idempotency-Key", "s04-accept")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedRevision\":1}"))
                .andExpect(status().isOk());
        String teacher2 = login("teacher_demo_02", "demo-password-05");
        // 仅有全局教师身份、无本 case 授权的 C 不得读取或代办。
        mockMvc.perform(get("/api/v1/personnel-cases/{id}/handoff-candidates", realCaseId)
                        .header("Authorization", bearer(teacher2)))
                .andExpect(status().isForbidden());
        personnelCaseGrantService.grant(realCaseId, "user_demo_teacher_02", "管理员授权接续",
                null, false, "test-admin");
        mockMvc.perform(get("/api/v1/personnel-cases/{id}/handoff-candidates", realCaseId)
                        .header("Authorization", bearer(teacher)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].userId").value("user_demo_teacher_02"));
        mockMvc.perform(post("/api/v1/personnel-cases/{id}/handoffs", realCaseId)
                        .header("Authorization", bearer(teacher)).header("Idempotency-Key", "s04-handoff")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedCaseRevision":2,"recipientTeacherId":"user_demo_teacher_02",
                                 "facts":"交接事实","unresolvedQuestions":"无","decisionsNeeded":"接续处理"}
                                """))
                .andExpect(status().isOk());
        JsonNode teacher2Handoffs = todoByType(teacher2, "PERSONNEL_HANDOFF");
        assertThat(textValues(teacher2Handoffs.path("items"), "state")).contains("ACTION");
        JsonNode teacher1Handoffs = todoByType(teacher, "PERSONNEL_HANDOFF");
        assertThat(textValues(teacher1Handoffs.path("items"), "state")).contains("WAITING");
        Set<String> leaderTypes = todoTypes(leader);
        assertThat(leaderTypes).contains("CHANGE_PROPOSAL", "ROLE_PROPOSAL", "SQUARE_MERGE");

        // 终态立即退出；资源被替换/撤销后不再显示可操作。
        jdbc.update("UPDATE unit_change_proposal SET status='APPLIED' WHERE id=?", proposal);
        jdbc.update("UPDATE role_negotiation SET status='DECLINED' WHERE id=?", role);
        Set<String> after = todoTypes(member);
        assertThat(after).doesNotContain("CHANGE_PROPOSAL");
        assertThat(after).doesNotContain("ROLE_PROPOSAL");
    }

    @Test
    void r08TodosCoverPeerReviewAndGradeAppeal() throws Exception {
        String leader = login("student_demo_01", "demo-password-01");
        String member = login("student_demo_02", "demo-password-02");
        String teacher = login("teacher_demo_01", "demo-password-03");
        String projectId = project(leader, member, "f08-peer");
        String assignmentId = jdbc.queryForObject(
                "SELECT assignment_id FROM project WHERE id=?", String.class, projectId);
        jdbc.update("UPDATE project SET status='ARCHIVED', archived_at=? WHERE id=?", at(0), projectId);
        assertThat(todoTypes(member)).contains("PEER_REVIEW");

        String publication = "pub-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO grade_publication(id, assignment_id, preview_id, published_by, published_at)
                VALUES (?, ?, ?, ?, ?)
                """, publication, assignmentId, "preview-" + UUID.randomUUID(), TEACHER, at(0));
        String snapshot = "snap-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO grade_snapshot(id, publication_id, assignment_id, student_id, version,
                    b, q, delta, g, basis_digest, rule_snapshot, ledger_snapshot, evidence_snapshot,
                    created_at)
                VALUES (?, ?, ?, ?, 1, 80, 0, 0, 80, ?, '{}'::jsonb, '{}'::jsonb, '[]'::jsonb, ?)
                """, snapshot, publication, assignmentId, MEMBER,
                "a".repeat(64), at(0));
        jdbc.update("""
                INSERT INTO grade_appeal(id, grade_snapshot_id, assignment_id, student_id, status,
                    issue, source_refs_json, evidence_refs_json, responses_json, revision, created_at)
                VALUES (?, ?, ?, ?, 'PENDING', '分数疑问', '[]'::jsonb, '[]'::jsonb, '[]'::jsonb, 1, ?)
                """, "appeal-" + UUID.randomUUID(), snapshot, assignmentId, MEMBER, at(0));
        assertThat(todoTypes(teacher)).contains("GRADE_APPEAL");
        assertThat(todoTypes(member)).contains("GRADE_APPEAL");
        JsonNode teacherTodos = todoByType(teacher, "GRADE_APPEAL");
        assertThat(textValues(teacherTodos.path("items"), "state")).contains("ACTION");
        JsonNode memberTodos = todoByType(member, "GRADE_APPEAL");
        assertThat(textValues(memberTodos.path("items"), "state")).contains("WAITING");

        // 稳定 ID：同一来源多次读取不变。
        String firstId = memberTodos.path("items").get(0).path("id").asText();
        String secondId = todoByType(member, "GRADE_APPEAL").path("items").get(0).path("id").asText();
        assertThat(firstId).isEqualTo(secondId);
    }


    @Test
    void s02FormalEvidenceUsesSubmissionAgreementVersion() throws Exception {
        String leader = login("student_demo_01", "demo-password-01");
        String member = login("student_demo_02", "demo-password-02");
        String projectId = project(leader, member, "s02-version");
        String task = formalTask(projectId);
        String unit = unit(task, "U1", "2026-10-01T00:00:00Z", 1, true);
        share(unit, LEADER, 1, true);
        newAgreementVersion(task, 2);
        share(unit, MEMBER, 2, true);
        String submission = submissionV(task, MEMBER, "REVIEWED", 2, "[\"" + unit + "\"]",
                "2026-09-30T00:00:00Z", "2026-10-01T00:00:00Z", "{}");
        acceptance(submission, TEACHER, "INDEPENDENT", "PASSED", true, unit);

        // A 只有 v1 未完成份额，不能分享 B 的 v2 成果。
        assertThat(sources(leader).path("total").asInt()).isZero();
        mockMvc.perform(post("/api/v1/me/works")
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "s02-a-work")
                        .contentType(MediaType.APPLICATION_JSON).content(workBody(projectId, "A 作品")))
                .andExpect(status().isForbidden());
        JsonNode b = sources(member);
        assertThat(b.path("total").asInt()).isEqualTo(1);
        assertThat(b.path("items").get(0).path("myEvidence").path("acceptedDeliveries").asInt()).isEqualTo(1);
        mockMvc.perform(post("/api/v1/me/works")
                        .header("Authorization", bearer(member)).header("Idempotency-Key", "s02-b-work")
                        .contentType(MediaType.APPLICATION_JSON).content(workBody(projectId, "B 作品")))
                .andExpect(status().isOk());

        // A 在 v1 已完成的交付仍保留；U2 转给 B 后不归 A，也不把 B 的成果算给 A。
        String completed = formalTask(projectId);
        String u1 = unit(completed, "U1", "2026-10-01T00:00:00Z", 1, true);
        share(u1, LEADER, 1, true);
        String aSubmission = submissionV(completed, LEADER, "REVIEWED", 1, "[\"" + u1 + "\"]",
                "2026-09-30T00:00:00Z", "2026-10-01T00:00:00Z", "{}");
        acceptance(aSubmission, TEACHER, "INDEPENDENT", "PASSED", true, u1);
        newAgreementVersion(completed, 2);
        String u2 = unit(completed, "U2", "2026-10-01T00:00:00Z", 2, false);
        share(u2, MEMBER, 2, true);
        String bSubmission = submissionV(completed, MEMBER, "REVIEWED", 2, "[\"" + u2 + "\"]",
                "2026-09-30T00:00:00Z", "2026-10-01T00:00:00Z", "{}");
        acceptance(bSubmission, TEACHER, "INDEPENDENT", "PASSED", true, u2);
        JsonNode a = sources(leader);
        assertThat(a.path("items").get(0).path("myEvidence").path("acceptedDeliveries").asInt()).isEqualTo(1);
        JsonNode b2 = sources(member);
        assertThat(b2.path("items").get(0).path("myEvidence").path("acceptedDeliveries").asInt()).isEqualTo(2);
    }

    @Test
    void s02MultiContributorAndCurrentVersionIndependentReview() throws Exception {
        String leader = login("student_demo_01", "demo-password-01");
        String member = login("student_demo_02", "demo-password-02");
        String projectId = project(leader, member, "s02-multi");
        String task = formalTask(projectId);
        String unit = unit(task, "U1", "2026-10-01T00:00:00Z", 1, true);
        share(unit, LEADER, 1, true);
        share(unit, MEMBER, 1, true);
        String submission = submissionV(task, LEADER, "REVIEWED", 1, "[\"" + unit + "\"]",
                "2026-09-30T00:00:00Z", "2026-10-01T00:00:00Z", "{}");
        acceptance(submission, TEACHER, "INDEPENDENT", "PASSED", true, unit);
        // 共同贡献者均取得 evidence，不仅 submitted_by。
        assertThat(sources(leader).path("items").get(0).path("myEvidence").path("acceptedDeliveries").asInt())
                .isEqualTo(1);
        assertThat(sources(member).path("items").get(0).path("myEvidence").path("acceptedDeliveries").asInt())
                .isEqualTo(1);

        // A 只有 v1 份额，但作为独立验收人验收 B 的 v2 提交：应计独立验收，而不是被旧份额永久排除。
        String reviewed = formalTask(projectId);
        String reviewedUnit = unit(reviewed, "U1", "2026-10-01T00:00:00Z", 1, true);
        share(reviewedUnit, LEADER, 1, true);
        newAgreementVersion(reviewed, 2);
        share(reviewedUnit, MEMBER, 2, true);
        String reviewedSubmission = submissionV(reviewed, MEMBER, "REVIEWED", 2,
                "[\"" + reviewedUnit + "\"]", "2026-09-30T00:00:00Z", "2026-10-01T00:00:00Z", "{}");
        acceptance(reviewedSubmission, LEADER, "INDEPENDENT", "PASSED", true, reviewedUnit);
        JsonNode a = sources(leader);
        assertThat(a.path("items").get(0).path("myEvidence").path("independentReviews").asInt())
                .isGreaterThanOrEqualTo(1);

        // 自验：本人对当前版本有份额的提交不能计独立验收。
        String selfTask = formalTask(projectId);
        String selfUnit = unit(selfTask, "U1", "2026-10-01T00:00:00Z", 1, true);
        share(selfUnit, LEADER, 1, true);
        String selfSubmission = submissionV(selfTask, LEADER, "REVIEWED", 1,
                "[\"" + selfUnit + "\"]", "2026-09-30T00:00:00Z", "2026-10-01T00:00:00Z", "{}");
        acceptance(selfSubmission, LEADER, "INDEPENDENT", "PASSED", true, selfUnit);
        assertThat(sources(leader).path("items").get(0).path("myEvidence").path("independentReviews").asInt())
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    void s03OrdinaryTasksFormRealEvidence() throws Exception {
        String leader = login("student_demo_01", "demo-password-01");
        String member = login("student_demo_02", "demo-password-02");
        String projectId = project(leader, member, "s03-ordinary");
        String task = ordinaryTask(projectId, LEADER);
        String submission = submissionV(task, LEADER, "REVIEWED", null, "[]",
                "2026-09-30T00:00:00Z", "2026-10-01T00:00:00Z", "{}");
        acceptance(submission, TEACHER, "INDEPENDENT", "PASSED", false, null);
        JsonNode a = sources(leader);
        assertThat(a.path("total").asInt()).isEqualTo(1);
        assertThat(a.path("items").get(0).path("myEvidence").path("acceptedDeliveries").asInt()).isEqualTo(1);
        assertThat(a.path("items").get(0).path("myEvidence").path("onTimeDeliveries").asInt()).isEqualTo(1);
        assertThat(a.path("items").get(0).path("myEvidence").path("confirmedUnits").asInt()).isZero();
        mockMvc.perform(post("/api/v1/me/works")
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "s03-work")
                        .contentType(MediaType.APPLICATION_JSON).content(workBody(projectId, "普通任务作品")))
                .andExpect(status().isOk());

        // B 只做独立验收，没有交付：独立验收可见，但不冒领 A 的交付。
        String second = ordinaryTask(projectId, LEADER);
        String secondSubmission = submissionV(second, LEADER, "REVIEWED", null, "[]",
                "2026-10-02T00:00:00Z", "2026-10-01T00:00:00Z", "{}");
        acceptance(secondSubmission, MEMBER, "INDEPENDENT", "PASSED", false, null);
        JsonNode b = sources(member);
        assertThat(b.path("items").get(0).path("myEvidence").path("acceptedDeliveries").asInt()).isZero();
        assertThat(b.path("items").get(0).path("myEvidence").path("independentReviews").asInt())
                .isGreaterThanOrEqualTo(1);

        // 普通任务负责人变更不改写历史完成者。
        jdbc.update("UPDATE task SET owner_id=? WHERE id=?", MEMBER, task);
        assertThat(sources(leader).path("items").get(0).path("myEvidence").path("acceptedDeliveries").asInt())
                .isEqualTo(2);
        assertThat(sources(member).path("items").get(0).path("myEvidence").path("acceptedDeliveries").asInt())
                .isZero();

        // 退回后重交通过只计一次有效完成。
        String retried = ordinaryTask(projectId, LEADER);
        String returned = submissionV(retried, LEADER, "REVIEWED", null, "[]",
                "2026-09-30T00:00:00Z", "2026-10-01T00:00:00Z", "{}");
        acceptance(returned, TEACHER, "INDEPENDENT", "RETURNED", false, null);
        String passed = submissionV(retried, LEADER, "REVIEWED", null, "[]",
                "2026-10-02T00:00:00Z", "2026-10-01T00:00:00Z", "{}");
        acceptance(passed, TEACHER, "INDEPENDENT", "PASSED", false, null);
        JsonNode retriedSources = sources(leader);
        assertThat(retriedSources.path("items").get(0).path("myEvidence").path("acceptedDeliveries").asInt())
                .isEqualTo(3);
        assertThat(retriedSources.path("items").get(0).path("myEvidence").path("lateDeliveries").asInt())
                .isEqualTo(2);

        // 普通 + 正式混合项目只形成一个来源项目，五项计数分别累加。
        String mixed = formalTask(projectId);
        String mixedUnit = unit(mixed, "U1", "2026-10-01T00:00:00Z", 1, true);
        share(mixedUnit, LEADER, 1, true);
        String mixedSubmission = submissionV(mixed, LEADER, "REVIEWED", 1,
                "[\"" + mixedUnit + "\"]", "2026-09-30T00:00:00Z", "2026-10-01T00:00:00Z", "{}");
        acceptance(mixedSubmission, TEACHER, "INDEPENDENT", "PASSED", true, mixedUnit);
        JsonNode mixedSources = sources(leader);
        assertThat(mixedSources.path("total").asInt()).isEqualTo(1);
        assertThat(mixedSources.path("items").get(0).path("myEvidence").path("acceptedDeliveries").asInt())
                .isEqualTo(4);
        assertThat(mixedSources.path("items").get(0).path("myEvidence").path("onTimeDeliveries").asInt())
                .isEqualTo(2);
        String assistant = login("assistant_demo_01", "demo-password-04");
        assertThat(sources(assistant).path("total").asInt()).isZero();
    }


    @Test
    void s02PartialReturnedPendingAndDueSnapshotRules() throws Exception {
        String leader = login("student_demo_01", "demo-password-01");
        String member = login("student_demo_02", "demo-password-02");
        String projectId = project(leader, member, "s02-invalid");
        String task = formalTask(projectId);
        String u1 = unit(task, "U1", "2026-10-01T00:00:00Z", 1, true);
        String u2 = unit(task, "U2", "2026-10-01T00:00:00Z", 2, false);
        share(u1, LEADER, 1, true);
        share(u2, LEADER, 1, true);
        String partial = submissionV(task, LEADER, "REVIEWED", 1,
                "[\"" + u1 + "\",\"" + u2 + "\"]", "2026-10-02T00:00:00Z",
                "2026-10-01T00:00:00Z", "{}");
        String acceptanceId = acceptanceId(partial, TEACHER, "PARTIAL", u1);

        String exact = formalTask(projectId);
        String u3 = unit(exact, "U1", "2026-10-01T00:00:00Z", 1, true);
        share(u3, LEADER, 1, true);
        String exactSubmission = submissionV(exact, LEADER, "REVIEWED", 1,
                "[\"" + u3 + "\"]", "2026-10-01T00:00:00Z", "2026-10-01T00:00:00Z",
                "{\"" + u3 + "\":\"2026-10-01T00:00:00Z\"}");
        acceptance(exactSubmission, TEACHER, "INDEPENDENT", "PASSED", true, u3);

        // PENDING / WITHDRAWN / RETURNED 均不产生有效交付。
        String pending = formalTask(projectId);
        String u4 = unit(pending, "U1", "2026-10-01T00:00:00Z", 1, false);
        share(u4, LEADER, 1, true);
        String pendingSubmission = submissionV(pending, LEADER, "PENDING", 1,
                "[\"" + u4 + "\"]", "2026-10-01T00:00:00Z", "2026-10-01T00:00:00Z", "{}");
        jdbc.update("UPDATE submission SET status='WITHDRAWN' WHERE id=?", pendingSubmission);
        String returnedTask = formalTask(projectId);
        String u5 = unit(returnedTask, "U1", "2026-10-01T00:00:00Z", 1, false);
        share(u5, LEADER, 1, true);
        String returnedSubmission = submissionV(returnedTask, LEADER, "REVIEWED", 1,
                "[\"" + u5 + "\"]", "2026-10-01T00:00:00Z", "2026-10-01T00:00:00Z", "{}");
        acceptance(returnedSubmission, TEACHER, "INDEPENDENT", "RETURNED", false, null);

        JsonNode sources = sources(leader);
        JsonNode evidence = sources.path("items").get(0).path("myEvidence");
        assertThat(evidence.path("acceptedDeliveries").asInt()).isEqualTo(2);
        assertThat(evidence.path("onTimeDeliveries").asInt()).isEqualTo(1);
        assertThat(evidence.path("lateDeliveries").asInt()).isEqualTo(1);
        assertThat(acceptanceId).isNotBlank();

        // 事后修改 task.due_at 不改写提交时截止快照。
        jdbc.update("UPDATE task SET due_at='2026-12-01T00:00:00Z' WHERE id IN (?, ?)", task, exact);
        JsonNode after = sources(leader).path("items").get(0).path("myEvidence");
        assertThat(after.path("onTimeDeliveries").asInt()).isEqualTo(1);
        assertThat(after.path("lateDeliveries").asInt()).isEqualTo(1);
    }

    @Test
    void s02WrongVersionConfirmationIsNotEvidence() throws Exception {
        String leader = login("student_demo_01", "demo-password-01");
        String member = login("student_demo_02", "demo-password-02");
        String projectId = project(leader, member, "s02-wrong-version");
        String task = formalTask(projectId);
        String unit = unit(task, "U1", "2026-10-01T00:00:00Z", 1, true);
        // A 只在 v2 有份额，却对 v1 提交做了确认：归属不可信，不得计证据。
        share(unit, LEADER, 2, true);
        String submission = submissionV(task, MEMBER, "REVIEWED", 1,
                "[\"" + unit + "\"]", "2026-09-30T00:00:00Z", "2026-10-01T00:00:00Z", "{}");
        String acceptanceId = acceptanceId(submission, TEACHER, "PASSED", unit);
        jdbc.update("""
                INSERT INTO contribution_confirmation(id, unit_id, user_id, acceptance_id,
                    submission_id, statement, at)
                VALUES (?, ?, ?, ?, ?, '确认', ?)
                """, "conf-" + UUID.randomUUID(), unit, LEADER, acceptanceId, submission, at(0));
        assertThat(sources(leader).path("total").asInt()).isZero();
        mockMvc.perform(post("/api/v1/me/works")
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "s02-wrong-work")
                        .contentType(MediaType.APPLICATION_JSON).content(workBody(projectId, "错版本作品")))
                .andExpect(status().isForbidden());
    }


    @Test
    void s04CaseGrantCandidatesReadAndAcceptChain() throws Exception {
        String leader = login("student_demo_01", "demo-password-01");
        String member = login("student_demo_02", "demo-password-02");
        String teacher = login("teacher_demo_01", "demo-password-03");
        String teacher2 = login("teacher_demo_02", "demo-password-05");
        String projectId = project(leader, member, "s04-chain");
        String caseId = openCase(projectId, leader, teacher, "s04-chain-case");

        // 全局教师身份不等于本 case 资格：未授权前不能读全案。
        mockMvc.perform(get("/api/v1/personnel-cases/{id}", caseId)
                        .header("Authorization", bearer(teacher2)))
                .andExpect(status().isForbidden());
        // 无合法候选时真实空列表。
        mockMvc.perform(get("/api/v1/personnel-cases/{id}/handoff-candidates", caseId)
                        .header("Authorization", bearer(teacher)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));

        personnelCaseGrantService.grant(caseId, "user_demo_teacher_02", "管理员授权接续",
                null, false, "test-admin");
        mockMvc.perform(get("/api/v1/personnel-cases/{id}/handoff-candidates", caseId)
                        .header("Authorization", bearer(teacher)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].userId").value("user_demo_teacher_02"));
        // 获得资格但尚未接受交接：仍不能读全案或材料。
        mockMvc.perform(get("/api/v1/personnel-cases/{id}", caseId)
                        .header("Authorization", bearer(teacher2)))
                .andExpect(status().isForbidden());

        int revision = caseRevision(caseId);
        JsonNode handoff = data(mockMvc.perform(post("/api/v1/personnel-cases/{id}/handoffs", caseId)
                        .header("Authorization", bearer(teacher)).header("Idempotency-Key", "s04-create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedCaseRevision\":" + revision
                                + ",\"recipientTeacherId\":\"user_demo_teacher_02\","
                                + "\"facts\":\"事实\",\"unresolvedQuestions\":\"无\","
                                + "\"decisionsNeeded\":\"接续\"}"))
                .andExpect(status().isOk()).andReturn());
        String handoffId = handoff.path("id").asText();
        assertThat(textValues(todoByType(teacher2, "PERSONNEL_HANDOFF").path("items"), "state"))
                .contains("ACTION");
        assertThat(textValues(todoByType(teacher, "PERSONNEL_HANDOFF").path("items"), "state"))
                .contains("WAITING");
        // 接续人可读取交接说明，但不能读取全案。
        mockMvc.perform(get("/api/v1/personnel-handoffs/{id}", handoffId)
                        .header("Authorization", bearer(teacher2)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/personnel-cases/{id}", caseId)
                        .header("Authorization", bearer(teacher2)))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/personnel-handoffs/{id}/responses", handoffId)
                        .header("Authorization", bearer(teacher2)).header("Idempotency-Key", "s04-accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"decision\":\"ACCEPT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACCEPTED"));
        // 同键同体重放不重复迁移；同键异体按统一协议 409。
        mockMvc.perform(post("/api/v1/personnel-handoffs/{id}/responses", handoffId)
                        .header("Authorization", bearer(teacher2)).header("Idempotency-Key", "s04-accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"decision\":\"ACCEPT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACCEPTED"));
        mockMvc.perform(post("/api/v1/personnel-handoffs/{id}/responses", handoffId)
                        .header("Authorization", bearer(teacher2)).header("Idempotency-Key", "s04-accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":2,\"decision\":\"DECLINE\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(jdbc.queryForObject("SELECT assignee_id FROM personnel_case WHERE id = ?",
                String.class, caseId)).isEqualTo("user_demo_teacher_02");
        mockMvc.perform(get("/api/v1/personnel-cases/{id}", caseId)
                        .header("Authorization", bearer(teacher2)))
                .andExpect(status().isOk());
        int next = caseRevision(caseId);
        mockMvc.perform(post("/api/v1/personnel-cases/{id}/updates", caseId)
                        .header("Authorization", bearer(teacher2)).header("Idempotency-Key", "s04-update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":" + next
                                + ",\"facts\":\"接续处理\",\"nextAction\":\"继续\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/personnel-cases/{id}/updates", caseId)
                        .header("Authorization", bearer(teacher)).header("Idempotency-Key", "s04-old-update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":" + (next + 1)
                                + ",\"facts\":\"原人代办\",\"nextAction\":\"继续\"}"))
                .andExpect(status().isForbidden());
        assertThat(todoTypes(teacher2)).doesNotContain("PERSONNEL_HANDOFF");
        assertThat(todoTypes(teacher)).doesNotContain("PERSONNEL_HANDOFF");
    }

    @Test
    void s04CancelRevokeAndRecoverRules() throws Exception {
        String leader = login("student_demo_01", "demo-password-01");
        String member = login("student_demo_02", "demo-password-02");
        String teacher = login("teacher_demo_01", "demo-password-03");
        String teacher2 = login("teacher_demo_02", "demo-password-05");
        String projectId = project(leader, member, "s04-cancel");
        String caseId = openCase(projectId, leader, teacher, "s04-cancel-case");
        personnelCaseGrantService.grant(caseId, "user_demo_teacher_02", "授权",
                null, false, "test-admin");

        String handoffId = createHandoff(caseId, teacher, "user_demo_teacher_02", "s04-cancel-h");
        mockMvc.perform(post("/api/v1/personnel-handoffs/{id}/cancellations", handoffId)
                        .header("Authorization", bearer(teacher)).header("Idempotency-Key", "s04-cancel-h-c")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"reason\":\"取消交接\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));
        assertThat(jdbc.queryForObject("SELECT assignee_id FROM personnel_case WHERE id = ?",
                String.class, caseId)).isEqualTo("user_demo_teacher_01");
        assertThat(todoTypes(teacher2)).doesNotContain("PERSONNEL_HANDOFF");
        assertThat(todoTypes(teacher)).doesNotContain("PERSONNEL_HANDOFF");

        // 撤销授权后，待回应交接不能继续接受，待办也不再显示可执行动作。
        String second = createHandoff(caseId, teacher, "user_demo_teacher_02", "s04-revoke-h");
        var firstGrant = personnelCaseGrantService.list(caseId).getFirst();
        personnelCaseGrantService.revoke(firstGrant.grantId(), firstGrant.revision(), "撤销授权",
                false, "test-admin");
        mockMvc.perform(post("/api/v1/personnel-handoffs/{id}/responses", second)
                        .header("Authorization", bearer(teacher2)).header("Idempotency-Key", "s04-revoked-accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"decision\":\"ACCEPT\"}"))
                .andExpect(status().isForbidden());
        assertThat(todoTypes(teacher2)).doesNotContain("PERSONNEL_HANDOFF");

        // 重新授权后可接受；随后撤销当前 assignee 的资格，写入立即失权。
        personnelCaseGrantService.grant(caseId, "user_demo_teacher_02", "重新授权",
                null, false, "test-admin");
        mockMvc.perform(post("/api/v1/personnel-handoffs/{id}/responses", second)
                        .header("Authorization", bearer(teacher2)).header("Idempotency-Key", "s04-accept-second")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"decision\":\"ACCEPT\"}"))
                .andExpect(status().isOk());
        // 先成功写入，再撤销资格，验证同键回放也会重新核权。
        int beforeRevoke = caseRevision(caseId);
        String updateBody = "{\"expectedRevision\":" + beforeRevoke
                + ",\"facts\":\"授权期间写入\",\"nextAction\":\"继续\"}";
        mockMvc.perform(post("/api/v1/personnel-cases/{id}/updates", caseId)
                        .header("Authorization", bearer(teacher2)).header("Idempotency-Key", "s04-b-update")
                        .contentType(MediaType.APPLICATION_JSON).content(updateBody))
                .andExpect(status().isOk());
        var accepted = personnelCaseGrantService.list(caseId).stream()
                .filter(grant -> "ACTIVE".equals(grant.status())).findFirst().orElseThrow();
        personnelCaseGrantService.revoke(accepted.grantId(), accepted.revision(), "接任人失权",
                false, "test-admin");
        mockMvc.perform(post("/api/v1/personnel-cases/{id}/updates", caseId)
                        .header("Authorization", bearer(teacher2)).header("Idempotency-Key", "s04-b-update")
                        .contentType(MediaType.APPLICATION_JSON).content(updateBody))
                .andExpect(status().isForbidden());
        int lost = caseRevision(caseId);
        mockMvc.perform(post("/api/v1/personnel-cases/{id}/updates", caseId)
                        .header("Authorization", bearer(teacher2)).header("Idempotency-Key", "s04-lost-update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":" + lost + ",\"facts\":\"失权后写入\","
                                + "\"nextAction\":\"继续\"}"))
                .andExpect(status().isForbidden());

        // 原处理人仍负责；当前教师可按规则收回重新指派。
        int revision = caseRevision(caseId);
        mockMvc.perform(post("/api/v1/personnel-cases/{id}/recoveries", caseId)
                        .header("Authorization", bearer(teacher)).header("Idempotency-Key", "s04-recover")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":" + revision + ",\"reason\":\"原接续人失权\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.assigneeId").value("user_demo_teacher_01"));
    }


    private String openCase(String projectId, String leader, String teacher, String key) throws Exception {
        int revision = jdbc.queryForObject("""
                SELECT revision FROM membership WHERE project_id=? AND user_id=? AND status='ACTIVE'
                """, Integer.class, projectId, LEADER);
        JsonNode change = data(mockMvc.perform(post("/api/v1/projects/{id}/member-changes", projectId)
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", key + "-change")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"EXIT\",\"reason\":\"教师接续\","
                                + "\"expectedMembershipRevision\":" + revision + "}"))
                .andExpect(status().isOk()).andReturn());
        String caseId = jdbc.queryForObject(
                "SELECT id FROM personnel_case WHERE member_change_id = ?",
                String.class, change.path("id").asText());
        mockMvc.perform(post("/api/v1/personnel-cases/{id}/acceptances", caseId)
                        .header("Authorization", bearer(teacher)).header("Idempotency-Key", key + "-accept")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedRevision\":1}"))
                .andExpect(status().isOk());
        return caseId;
    }

    private int caseRevision(String caseId) {
        Integer value = jdbc.queryForObject("SELECT revision FROM personnel_case WHERE id=?", Integer.class, caseId);
        return value == null ? 0 : value;
    }

    private String createHandoff(String caseId, String teacher, String recipient, String key) throws Exception {
        int revision = caseRevision(caseId);
        return data(mockMvc.perform(post("/api/v1/personnel-cases/{id}/handoffs", caseId)
                        .header("Authorization", bearer(teacher)).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedCaseRevision\":" + revision
                                + ",\"recipientTeacherId\":\"" + recipient + "\","
                                + "\"facts\":\"事实\",\"unresolvedQuestions\":\"无\","
                                + "\"decisionsNeeded\":\"接续\"}"))
                .andExpect(status().isOk()).andReturn()).path("id").asText();
    }


    @Test
    void s04GrantAdminRulesHorizontalAndConcurrentGrant() throws Exception {
        String leader = login("student_demo_01", "demo-password-01");
        String member = login("student_demo_02", "demo-password-02");
        String teacher = login("teacher_demo_01", "demo-password-03");
        String teacher2 = login("teacher_demo_02", "demo-password-05");
        String firstProject = project(leader, member, "s04-admin-1");
        String firstCase = openCase(firstProject, leader, teacher, "s04-admin-case1");
        String squareId = jdbc.queryForObject(
                "SELECT square_id FROM project WHERE id=?", String.class, firstProject);
        String secondCase = "case-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO personnel_case(id, square_id, project_id, subject_user_id, title,
                    status, revision, created_at, updated_at)
                VALUES (?, ?, ?, ?, '第二事项', 'OPEN', 1, ?, ?)
                """, secondCase, squareId, firstProject, MEMBER, at(0), at(0));

        var first = personnelCaseGrantService.grant(firstCase, "user_demo_teacher_02",
                "授权一", null, false, "test-admin");
        assertThat(first.action()).isEqualTo("CREATED");
        var duplicate = personnelCaseGrantService.grant(firstCase, "user_demo_teacher_02",
                "重复授权", null, false, "test-admin");
        assertThat(duplicate.action()).isEqualTo("EXISTS");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM personnel_case_grant
                WHERE case_id=? AND user_id='user_demo_teacher_02' AND status='ACTIVE'
                """, Integer.class, firstCase)).isEqualTo(1);

        // dry-run 不落库；真实授权后才成为候选，但仍不能读全案。
        var dry = personnelCaseGrantService.grant(secondCase, "user_demo_teacher_02",
                "dry-run", null, true, "test-admin");
        assertThat(dry.dryRun()).isTrue();
        assertThat(personnelCaseGrantService.list(secondCase)).isEmpty();
        mockMvc.perform(get("/api/v1/personnel-cases/{id}", secondCase)
                        .header("Authorization", bearer(teacher2)))
                .andExpect(status().isForbidden());
        personnelCaseGrantService.grant(secondCase, "user_demo_teacher_02",
                "授权二", null, false, "test-admin");
        mockMvc.perform(get("/api/v1/personnel-cases/{id}", secondCase)
                        .header("Authorization", bearer(teacher2)))
                .andExpect(status().isForbidden());
        // case X 的授权不能横向访问 case Y：teacher2 接受 case1 后只读 case1。
        String handoff = createHandoff(firstCase, teacher, "user_demo_teacher_02", "s04-admin-h");
        mockMvc.perform(post("/api/v1/personnel-handoffs/{id}/responses", handoff)
                        .header("Authorization", bearer(teacher2)).header("Idempotency-Key", "s04-admin-accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"decision\":\"ACCEPT\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/personnel-cases/{id}", firstCase)
                        .header("Authorization", bearer(teacher2)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/personnel-cases/{id}", secondCase)
                        .header("Authorization", bearer(teacher2)))
                .andExpect(status().isForbidden());

        // revision 保护：错误 revision 不能撤销；正确撤销后列表为 REVOKED。
        var active = personnelCaseGrantService.list(firstCase).stream()
                .filter(grant -> "ACTIVE".equals(grant.status())).findFirst().orElseThrow();
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        personnelCaseGrantService.revoke(active.grantId(), active.revision() + 9,
                                "错误版本", false, "test-admin"))
                .isInstanceOf(IllegalArgumentException.class);
        personnelCaseGrantService.revoke(active.grantId(), active.revision(), "撤销",
                false, "test-admin");
        assertThat(personnelCaseGrantService.list(firstCase).stream()
                .filter(grant -> "ACTIVE".equals(grant.status())).toList()).isEmpty();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM audit_event WHERE action IN
                    ('PERSONNEL_CASE_GRANT', 'PERSONNEL_CASE_GRANT_REVOKE')
                """, Integer.class)).isGreaterThanOrEqualTo(2);

        // 并发重复 grant：唯一有效约束生效，不产生第二条。
        var secondActive = personnelCaseGrantService.list(secondCase).stream()
                .filter(grant -> "ACTIVE".equals(grant.status())).findFirst().orElseThrow();
        personnelCaseGrantService.revoke(secondActive.grantId(), secondActive.revision(),
                "释放以便并发", false, "test-admin");
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var barrier = new java.util.concurrent.CountDownLatch(2);
            var start = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<PersonnelCaseGrantAdminService.GrantResult> call = () -> {
                barrier.countDown();
                start.await();
                return personnelCaseGrantService.grant(secondCase, "user_demo_teacher_02",
                        "并发授权", null, false, "test-admin");
            };
            var f1 = pool.submit(call);
            var f2 = pool.submit(call);
            barrier.await();
            start.countDown();
            var r1 = f1.get(10, java.util.concurrent.TimeUnit.SECONDS);
            var r2 = f2.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(java.util.Set.of(r1.action(), r2.action())).contains("CREATED", "EXISTS");
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM personnel_case_grant
                WHERE case_id=? AND status='ACTIVE'
                """, Integer.class, secondCase)).isEqualTo(1);
    }


    @Test
    void s04ConcurrentAcceptAndCancelYieldSingleTransition() throws Exception {
        String leader = login("student_demo_01", "demo-password-01");
        String member = login("student_demo_02", "demo-password-02");
        String teacher = login("teacher_demo_01", "demo-password-03");
        String teacher2 = login("teacher_demo_02", "demo-password-05");
        String projectId = project(leader, member, "s04-race");
        String caseId = openCase(projectId, leader, teacher, "s04-race-case");
        personnelCaseGrantService.grant(caseId, "user_demo_teacher_02",
                "并发测试", null, false, "test-admin");
        String handoffId = createHandoff(caseId, teacher, "user_demo_teacher_02", "s04-race-h");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        Callable<MvcResult> accept = () -> {
            ready.countDown();
            go.await();
            return mockMvc.perform(post("/api/v1/personnel-handoffs/{id}/responses", handoffId)
                    .header("Authorization", bearer(teacher2)).header("Idempotency-Key", "s04-race-accept")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"expectedRevision\":1,\"decision\":\"ACCEPT\"}")).andReturn();
        };
        Callable<MvcResult> cancel = () -> {
            ready.countDown();
            go.await();
            return mockMvc.perform(post("/api/v1/personnel-handoffs/{id}/cancellations", handoffId)
                    .header("Authorization", bearer(teacher)).header("Idempotency-Key", "s04-race-cancel")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"expectedRevision\":1,\"reason\":\"并发取消\"}")).andReturn();
        };
        List<MvcResult> results;
        try {
            Future<MvcResult> firstFuture = pool.submit(accept);
            Future<MvcResult> secondFuture = pool.submit(cancel);
            ready.await(5, TimeUnit.SECONDS);
            go.countDown();
            results = List.of(firstFuture.get(20, TimeUnit.SECONDS),
                    secondFuture.get(20, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        List<Integer> statuses = results.stream()
                .map(result -> result.getResponse().getStatus()).sorted().toList();
        // case 先于 handoff 的统一锁顺序下，竞争失败方可能是“已无资格”403，
        // 也可能是锁后重查发现 handoff 版本已前进 409；两者都必须只有一方提交。
        assertThat(statuses).hasSize(2);
        assertThat(statuses.get(0)).isEqualTo(200);
        assertThat(statuses.get(1)).isIn(403, 409);
        String handoffStatus = jdbc.queryForObject(
                "SELECT status FROM personnel_handoff WHERE id=?", String.class, handoffId);
        String assignee = jdbc.queryForObject(
                "SELECT assignee_id FROM personnel_case WHERE id=?", String.class, caseId);
        if ("ACCEPTED".equals(handoffStatus)) {
            assertThat(assignee).isEqualTo("user_demo_teacher_02");
        } else {
            assertThat(handoffStatus).isEqualTo("CANCELLED");
            assertThat(assignee).isEqualTo("user_demo_teacher_01");
        }
        assertThat(todoTypes(teacher2)).doesNotContain("PERSONNEL_HANDOFF");
        assertThat(todoTypes(teacher)).doesNotContain("PERSONNEL_HANDOFF");
    }

    // ---- seed helpers ----

    private void acceptedUnit(String projectId, String userId, String submittedAt, String due,
                              boolean confirmed) {
        String task = "task-" + UUID.randomUUID();
        String unit = "unit-" + UUID.randomUUID();
        String submission = "sub-" + UUID.randomUUID();
        String acceptance = "acc-" + UUID.randomUUID();
        taskWithAgreement(task, projectId, userId, due, "DONE");
        jdbc.update("""
                INSERT INTO delivery_unit(id, task_id, client_key, title, due_at, points, revision,
                    position, passed)
                VALUES (?, ?, 'u1', '交付项', ?::timestamptz, 10.00, 1, 1, true)
                """, unit, task, due);
        jdbc.update("""
                INSERT INTO unit_share(id, unit_id, user_id, points, responsibility, agreement_version, signed)
                VALUES (?, ?, ?, 10.00, '实现', 1, true)
                """, "share-" + UUID.randomUUID(), unit, userId);
        submission(submission, task, userId, "REVIEWED", submittedAt, "[\"" + unit + "\"]", due);
        jdbc.update("""
                INSERT INTO acceptance(id, submission_id, reviewer_id, kind, checks_json, decision,
                    reason, accepted_at)
                VALUES (?, ?, ?, 'INDEPENDENT', '[]'::jsonb, 'PASSED', '通过', ?::timestamptz)
                """, acceptance, submission, TEACHER, submittedAt);
        jdbc.update("INSERT INTO unit_acceptance(unit_id, submission_id, acceptance_id, passed) VALUES (?, ?, ?, true)",
                unit, submission, acceptance);
        if (confirmed) {
            jdbc.update("""
                    INSERT INTO contribution_confirmation(id, unit_id, user_id, acceptance_id,
                        submission_id, statement, at)
                    VALUES (?, ?, ?, ?, ?, '确认', ?::timestamptz)
                    """, "conf-" + UUID.randomUUID(), unit, userId, acceptance, submission, submittedAt);
        }
    }

    private void reviewedUnit(String projectId, String authorId, String reviewerId) {
        String task = "task-" + UUID.randomUUID();
        String unit = "unit-" + UUID.randomUUID();
        String submission = "sub-" + UUID.randomUUID();
        String acceptance = "acc-" + UUID.randomUUID();
        taskWithAgreement(task, projectId, authorId, "2026-10-01T00:00:00Z", "DONE");
        jdbc.update("""
                INSERT INTO delivery_unit(id, task_id, client_key, title, due_at, points, revision,
                    position, passed)
                VALUES (?, ?, 'u1', '被验收项', '2026-10-01T00:00:00Z', 10.00, 1, 1, true)
                """, unit, task);
        jdbc.update("""
                INSERT INTO unit_share(id, unit_id, user_id, points, responsibility, agreement_version, signed)
                VALUES (?, ?, ?, 10.00, '实现', 1, true)
                """, "share-" + UUID.randomUUID(), unit, authorId);
        submission(submission, task, authorId, "REVIEWED", "2026-09-30T00:00:00Z",
                "[\"" + unit + "\"]", "2026-10-01T00:00:00Z");
        jdbc.update("""
                INSERT INTO acceptance(id, submission_id, reviewer_id, kind, checks_json, decision,
                    reason, accepted_at)
                VALUES (?, ?, ?, 'INDEPENDENT', '[]'::jsonb, 'PASSED', '通过', '2026-09-30T00:00:00Z')
                """, acceptance, submission, reviewerId);
        jdbc.update("INSERT INTO unit_acceptance(unit_id, submission_id, acceptance_id, passed) VALUES (?, ?, ?, true)",
                unit, submission, acceptance);
    }

    private void returnedUnit(String projectId, String userId) {
        String task = "task-" + UUID.randomUUID();
        String unit = "unit-" + UUID.randomUUID();
        String submission = "sub-" + UUID.randomUUID();
        taskWithAgreement(task, projectId, userId, "2026-10-01T00:00:00Z", "IN_PROGRESS");
        jdbc.update("""
                INSERT INTO delivery_unit(id, task_id, client_key, title, due_at, points, revision,
                    position, passed)
                VALUES (?, ?, 'u1', '退回项', '2026-10-01T00:00:00Z', 10.00, 1, 1, false)
                """, unit, task);
        jdbc.update("""
                INSERT INTO unit_share(id, unit_id, user_id, points, responsibility, agreement_version, signed)
                VALUES (?, ?, ?, 10.00, '实现', 1, true)
                """, "share-" + UUID.randomUUID(), unit, userId);
        submission(submission, task, userId, "REVIEWED", "2026-10-02T00:00:00Z",
                "[\"" + unit + "\"]", "2026-10-01T00:00:00Z");
        jdbc.update("""
                INSERT INTO acceptance(id, submission_id, reviewer_id, kind, checks_json, decision,
                    reason, accepted_at)
                VALUES (?, ?, ?, 'INDEPENDENT', '[]'::jsonb, 'RETURNED', '退回', '2026-10-02T00:00:00Z')
                """, "acc-" + UUID.randomUUID(), submission, TEACHER);
    }

    private void taskWithAgreement(String taskId, String projectId, String ownerId,
                                   String due, String status) {
        String stage = jdbc.queryForObject(
                "SELECT id FROM project_stage WHERE project_id = ? ORDER BY position LIMIT 1",
                String.class, projectId);
        jdbc.update("""
                INSERT INTO task(id, project_id, stage_id, title, description, mode, owner_id, due_at,
                    status, agreement_version, revision, created_at, updated_at, handover_blocked)
                VALUES (?, ?, ?, '任务', '', 'ASSESSED', ?, ?::timestamptz, ?, 1, 1, ?, ?, false)
                """, taskId, projectId, stage, ownerId, due, status, at(0), at(0));
        jdbc.update("""
                INSERT INTO agreement(id, task_id, version, status, base_task_revision, plan_json,
                    revision, created_at, updated_at)
                VALUES (?, ?, 1, 'ACTIVE', 1, '{}'::jsonb, 1, ?, ?)
                """, "ag-" + UUID.randomUUID(), taskId, at(0), at(0));
    }

    private void submission(String id, String taskId, String userId, String status,
                            String submittedAt, String unitIdsJson, String due) {
        jdbc.update("""
                INSERT INTO submission(id, task_id, sequence, submitted_by, status, summary,
                    artifacts_json, unit_ids_json, criteria_snapshot, due_snapshot,
                    unit_due_snapshot_json, agreement_version, revision, submitted_at)
                VALUES (?, ?, 1, ?, ?, '提交', '[]'::jsonb, ?::jsonb, '[]'::jsonb, ?::timestamptz,
                    '{}'::jsonb, 1, 1, ?::timestamptz)
                """, id, taskId, userId, status, unitIdsJson, due, submittedAt);
    }

    private String agreementTask(String projectId, String status) {
        String task = "task-" + UUID.randomUUID();
        String stage = jdbc.queryForObject(
                "SELECT id FROM project_stage WHERE project_id = ? ORDER BY position LIMIT 1",
                String.class, projectId);
        jdbc.update("""
                INSERT INTO task(id, project_id, stage_id, title, description, mode, owner_id, due_at,
                    status, revision, created_at, updated_at, handover_blocked)
                VALUES (?, ?, ?, '待确认约定', '', 'ASSESSED', NULL, '2026-12-01T00:00:00Z', 'TODO',
                    1, ?, ?, false)
                """, task, projectId, stage, at(0), at(0));
        String unit = "unit-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO delivery_unit(id, task_id, client_key, title, due_at, points, revision,
                    position, passed)
                VALUES (?, ?, 'u1', '约定分项', '2026-12-01T00:00:00Z', 10.00, 1, 1, false)
                """, unit, task);
        jdbc.update("""
                INSERT INTO unit_share(id, unit_id, user_id, points, responsibility, agreement_version, signed)
                VALUES (?, ?, ?, 10.00, '实现', 1, false)
                """, "share-" + UUID.randomUUID(), unit, MEMBER);
        jdbc.update("""
                INSERT INTO agreement(id, task_id, version, status, base_task_revision, plan_json,
                    revision, created_at, updated_at)
                VALUES (?, ?, 1, ?, 1, '{}'::jsonb, 1, ?, ?)
                """, "ag-" + UUID.randomUUID(), task, status, at(0), at(0));
        return task;
    }

    private void reviewerInvitation(String taskId, String recipientId) {
        jdbc.update("""
                INSERT INTO reviewer_invitation(id, task_id, recipient_id, status, arrangement_version,
                    expires_at, revision, created_at)
                VALUES (?, ?, ?, 'PENDING', 1, ?, 1, ?)
                """, "ri-" + UUID.randomUUID(), taskId, recipientId, at(7200), at(0));
    }

    private String ordinaryTask(String projectId, String ownerId) {
        String task = "task-" + UUID.randomUUID();
        String stage = jdbc.queryForObject(
                "SELECT id FROM project_stage WHERE project_id = ? ORDER BY position LIMIT 1",
                String.class, projectId);
        jdbc.update("""
                INSERT INTO task(id, project_id, stage_id, title, description, mode, owner_id, due_at,
                    status, revision, created_at, updated_at, handover_blocked)
                VALUES (?, ?, ?, '本人责任任务', '', 'ORDINARY', ?, '2026-12-01T00:00:00Z',
                    'IN_PROGRESS', 1, ?, ?, false)
                """, task, projectId, stage, ownerId, at(0), at(0));
        return task;
    }

    private String pendingSubmission(String taskId, String userId) {
        String id = "sub-" + UUID.randomUUID();
        submission(id, taskId, userId, "PENDING", "2026-10-31T00:00:00Z", "[]", "2026-10-01T00:00:00Z");
        jdbc.update("UPDATE task SET status='REVIEW_PENDING' WHERE id=?", taskId);
        return id;
    }


    private String formalTask(String projectId) {
        String task = "task-" + UUID.randomUUID();
        String stage = jdbc.queryForObject(
                "SELECT id FROM project_stage WHERE project_id = ? ORDER BY position LIMIT 1",
                String.class, projectId);
        jdbc.update("""
                INSERT INTO task(id, project_id, stage_id, title, description, mode, owner_id, due_at,
                    status, agreement_version, revision, created_at, updated_at, handover_blocked)
                VALUES (?, ?, ?, '正式任务', '', 'ASSESSED', NULL, '2026-10-01T00:00:00Z',
                    'TODO', 1, 1, ?, ?, false)
                """, task, projectId, stage, at(0), at(0));
        jdbc.update("""
                INSERT INTO agreement(id, task_id, version, status, base_task_revision, plan_json,
                    revision, created_at, updated_at)
                VALUES (?, ?, 1, 'ACTIVE', 1, '{}'::jsonb, 1, ?, ?)
                """, "ag-" + UUID.randomUUID(), task, at(0), at(0));
        return task;
    }

    private void newAgreementVersion(String taskId, int version) {
        jdbc.update("UPDATE agreement SET status='SUPERSEDED' WHERE task_id=? AND status='ACTIVE'", taskId);
        jdbc.update("""
                INSERT INTO agreement(id, task_id, version, status, base_task_revision, plan_json,
                    revision, created_at, updated_at)
                VALUES (?, ?, ?, 'ACTIVE', 1, '{}'::jsonb, 1, ?, ?)
                """, "ag-" + UUID.randomUUID(), taskId, version, at(0), at(0));
    }

    private String unit(String taskId, String key, String due, int position, boolean passed) {
        String unit = "unit-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO delivery_unit(id, task_id, client_key, title, due_at, points, revision,
                    position, passed)
                VALUES (?, ?, ?, ?, ?::timestamptz, 10.00, 1, ?, ?)
                """, unit, taskId, key, key, due, position, passed);
        return unit;
    }

    private void share(String unitId, String userId, int version, boolean signed) {
        jdbc.update("""
                INSERT INTO unit_share(id, unit_id, user_id, points, responsibility, agreement_version, signed)
                VALUES (?, ?, ?, 10.00, '实现', ?, ?)
                """, "share-" + UUID.randomUUID(), unitId, userId, version, signed);
    }

    private String submissionV(String taskId, String userId, String status, Integer agreementVersion,
                               String unitIdsJson, String submittedAt, String due, String unitDueJson) {
        String id = "sub-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO submission(id, task_id, sequence, submitted_by, status, summary,
                    artifacts_json, unit_ids_json, criteria_snapshot, due_snapshot,
                    unit_due_snapshot_json, agreement_version, revision, submitted_at)
                VALUES (?, ?, (SELECT COALESCE(max(sequence),0)+1 FROM submission WHERE task_id = ?),
                    ?, ?, '提交', '[]'::jsonb, ?::jsonb, '[]'::jsonb, ?::timestamptz, ?::jsonb,
                    ?, 1, ?::timestamptz)
                """, id, taskId, taskId, userId, status, unitIdsJson, due, unitDueJson,
                agreementVersion, submittedAt);
        return id;
    }


    private String acceptanceId(String submissionId, String reviewerId, String decision, String unitId) {
        String id = "acc-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO acceptance(id, submission_id, reviewer_id, kind, checks_json, decision,
                    reason, accepted_at)
                VALUES (?, ?, ?, 'INDEPENDENT', '[]'::jsonb, ?, '验收', ?)
                """, id, submissionId, reviewerId, decision, at(0));
        if ("PASSED".equals(decision) || "PARTIAL".equals(decision)) {
            jdbc.update("""
                    INSERT INTO unit_acceptance(unit_id, submission_id, acceptance_id, passed)
                    VALUES (?, ?, ?, true)
                    """, unitId, submissionId, id);
        }
        return id;
    }

    private void acceptance(String submissionId, String reviewerId, String kind, String decision,
                            boolean passed, String unitId) {
        String acceptance = "acc-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO acceptance(id, submission_id, reviewer_id, kind, checks_json, decision,
                    reason, accepted_at)
                VALUES (?, ?, ?, ?, '[]'::jsonb, ?, '验收', ?)
                """, acceptance, submissionId, reviewerId, kind, decision, at(0));
        if (passed) {
            jdbc.update("""
                    INSERT INTO unit_acceptance(unit_id, submission_id, acceptance_id, passed)
                    VALUES (?, ?, ?, true)
                    """, unitId, submissionId, acceptance);
        }
    }

    private JsonNode sources(String token) throws Exception {
        return data(mockMvc.perform(get("/api/v1/me/work-source-projects")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andReturn());
    }

    private String workBody(String projectId, String title) throws Exception {
        return mapper.writeValueAsString(Map.of("expectedRevision", 0, "title", title,
                "description", "说明", "role", "成员", "myContribution", "完成分项",
                "sourceProjectId", projectId, "audience", "PRIVATE",
                "artifacts", List.of(Map.of("kind", "LINK",
                        "url", "https://example.test/work", "title", "仓库"))));
    }

    // ---- request helpers ----

    private Set<String> todoTypes(String token) throws Exception {
        Set<String> types = new HashSet<>();
        data(mockMvc.perform(get("/api/v1/me/todos").header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andReturn()).path("items")
                .forEach(item -> types.add(item.path("type").asText()));
        return types;
    }

    private JsonNode todoByType(String token, String type) throws Exception {
        JsonNode all = data(mockMvc.perform(get("/api/v1/me/todos")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andReturn());
        List<JsonNode> filtered = new ArrayList<>();
        all.path("items").forEach(item -> {
            if (type.equals(item.path("type").asText())) filtered.add(item);
        });
        return mapper.createObjectNode().set("items", mapper.valueToTree(filtered));
    }

    private int projectRevision(String projectId) {
        Integer value = jdbc.queryForObject("SELECT revision FROM project WHERE id=?", Integer.class, projectId);
        return value == null ? 0 : value;
    }

    private String project(String leader, String member, String key) throws Exception {
        String projectId = projectOnly(leader, key);
        invite(leader, projectId, MEMBER, key + "-inv");
        return projectId;
    }

    private String project(String leader, String member, String key, String courseName,
                           int weekday, String period) throws Exception {
        String projectId = projectOnly(leader, key, courseName, weekday, period);
        invite(leader, projectId, MEMBER, key + "-inv");
        return projectId;
    }

    private String key(String raw) {
        return raw;
    }

    private void sendInvite(String leader, String projectId, String key) throws Exception {
        JsonNode current = data(mockMvc.perform(get("/api/v1/projects/{id}", projectId)
                        .header("Authorization", bearer(leader))).andExpect(status().isOk()).andReturn());
        mockMvc.perform(post("/api/v1/projects/{id}/invitations", projectId)
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("expectedProjectRevision",
                                current.path("revision").asInt(), "recipientId", MEMBER,
                                "message", "加入"))))
                .andExpect(status().isOk());
    }

    private void invite(String leader, String projectId, String memberId, String key) throws Exception {
        JsonNode current = data(mockMvc.perform(get("/api/v1/projects/{id}", projectId)
                        .header("Authorization", bearer(leader))).andExpect(status().isOk()).andReturn());
        JsonNode invite = data(mockMvc.perform(post("/api/v1/projects/{id}/invitations", projectId)
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("expectedProjectRevision",
                                current.path("revision").asInt(), "recipientId", memberId,
                                "message", "加入"))))
                .andExpect(status().isOk()).andReturn());
        String member = login("student_demo_02", "demo-password-02");
        mockMvc.perform(post("/api/v1/invitations/{id}/responses",
                                invite.path("invitation").path("id").asText())
                        .header("Authorization", bearer(member)).header("Idempotency-Key", key + "-acc")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"decision\":\"ACCEPT\"}"))
                .andExpect(status().isOk());
    }

    private String projectOnly(String leader, String key) throws Exception {
        return projectOnly(leader, key, "course_name_demo_mobile", 3, "period_demo_2");
    }

    private String projectOnly(String leader, String key, String courseName,
                               int weekday, String period) throws Exception {
        var slots = List.of(Map.of("weekday", weekday, "periodId", period, "weekPattern", "ALL",
                "startWeek", 1, "endWeek", 16, "customWeeks", List.of()));
        JsonNode match = data(mockMvc.perform(post("/api/v1/squares/matches")
                        .header("Authorization", bearer(leader)).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("schoolId", SCHOOL, "termId", TERM,
                                "teacherName", "示例教师", "slots", slots,
                                "courseNameId", courseName))))
                .andExpect(status().isOk()).andReturn());
        JsonNode square = data(mockMvc.perform(post("/api/v1/squares")
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", key + "-sq")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("schoolId", SCHOOL, "termId", TERM,
                                "teacherName", "示例教师", "slots", slots, "courseNameId", courseName,
                                "candidateCheckToken", match.path("candidateCheckToken").asText()))))
                .andExpect(status().isOk()).andReturn());
        JsonNode assignment = data(mockMvc.perform(post("/api/v1/squares/{id}/assignments",
                                square.path("id").asText())
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", key + "-asg")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("name", "团队修复作业", "requirements", "演示",
                                "hardDueAt", "2026-12-15T15:59:59Z", "minMembers", 1, "maxMembers", 6,
                                "sourceReferences", List.of()))))
                .andExpect(status().isOk()).andReturn());
        String teacher = login("teacher_demo_01", "demo-password-03");
        mockMvc.perform(post("/api/v1/assignments/{id}/confirmations", assignment.path("id").asText())
                        .header("Authorization", bearer(teacher)).header("Idempotency-Key", key + "-conf")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"decision\":\"CONFIRM_NEW\",\"reason\":\"ok\"}"))
                .andExpect(status().isOk());
        return data(mockMvc.perform(post("/api/v1/projects")
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", key + "-project")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("squareId", square.path("id").asText(),
                                "assignmentId", assignment.path("id").asText(), "mode", "QUICK",
                                "name", "团队修复组", "capacity", 6, "discussionTime", "周三",
                                "roles", List.of()))))
                .andExpect(status().isOk()).andReturn()).path("id").asText();
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

    private java.time.OffsetDateTime at(int seconds) {
        return java.time.OffsetDateTime.ofInstant(clock.instant().plusSeconds(seconds), ZoneOffset.UTC);
    }

    private void clearAll() {
        TestSchemaCleaner.deleteAll(jdbc, List.of("chat_sync_cursor", "chat_delivery_range",
                "notification_outbox", "chat_change", "chat_message",
                "chat_read_state", "chat_counter", "topic_follow", "content_revision",
                "board_reply", "announcement_reminder", "announcement_preview", "announcement_receipt",
                "announcement_recipient", "announcement_version", "help_article", "help_feedback",
                "support_route", "support_ticket", "support_reply", "support_history", "operator_grant",
                "content_report", "report_handling", "announcement",
                "notification_preference", "notification", "board_topic", "dice_round", "dice_roll",
                "dice_participant", "dice_session", "report_snapshot", "delivery_release",
                "delivery_release_preview", "delivery_release_draft", "overall_reflection",
                "project_reflection", "file_reference", "file_authorization", "cleanup_plan",
                "stored_file", "personnel_case_grant", "personnel_handoff", "material_request", "personnel_record",
                "personnel_case", "removal_ballot", "handover_ack", "handover_item",
                "member_change_event", "member_change", "removal_vote", "teacher_summary_consent",
                "teacher_summary", "grade_appeal", "grade_snapshot", "grade_publication",
                "settlement", "ledger_correction", "grade_draft", "grade_rule",
                "contribution_confirmation", "unit_acceptance", "acceptance", "submission",
                "delivery_draft", "proposal_response", "unit_change_proposal", "reviewer_invitation",
                "reviewer_arrangement", "agreement_ack", "unit_share", "task_dependency", "criterion",
                "delivery_unit", "agreement", "task", "user_project_color", "role_ack",
                "role_negotiation", "ai_reference", "peer_review_version", "peer_review",
                "work_version", "work", "project_code", "seat_reservation", "team_invitation",
                "team_application", "assignment_team_slot", "membership", "role_slot", "project_stage",
                "project", "teacher_transfer", "teacher_claim", "teacher_assignment", "class_grant",
                "course_code", "course_join_request", "cursor_snapshot", "assignment",
                "course_membership", "merge_ballot", "square_merge", "preview_token", "square",
                "verified_enrollment", "verified_course", "course_name", "period", "term",
                "operation_receipt", "access_token", "auth_session", "audit_event", "rate_window",
                "import_batch", "user_role", "user_profile", "app_user", "school"));
    }

    @TestConfiguration
    static class TestClockConfig {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(BASE, ZoneOffset.UTC);
        }
    }
    private List<String> textValues(JsonNode array, String field) {
        List<String> values = new ArrayList<>();
        array.forEach(item -> values.add(item.path(field).asText()));
        return values;
    }

    private void roleNegotiation(String id, String projectId, String kind) {
        jdbc.update("""
                INSERT INTO role_negotiation(id, project_id, kind, status, source_project_revision,
                    leader_id, initiator_id, plan_json, reason, message, revision, created_at, updated_at)
                VALUES (?, ?, ?, 'PENDING', 1, ?, ?, ?::jsonb, '', '', 1, ?, ?)
                """, id, projectId, kind, LEADER, LEADER,
                "{\"parties\":[{\"userId\":\"" + MEMBER + "\"}]}", at(0), at(0));
    }

}
