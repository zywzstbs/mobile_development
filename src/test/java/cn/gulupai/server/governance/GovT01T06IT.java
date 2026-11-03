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

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 第三轮 T01～T06：有效教师资格、材料正文投影、当前处理人办理、交接读取、
 * 收回和 case→子资源锁顺序的真实 PG 回归。
 *
 * <p>所有业务动作走真实 HTTP + PostgreSQL；仅“制造失权/并发条件”的数据库状态用 JDBC 设置。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(GovT01T06IT.TestClockConfig.class)
class GovT01T06IT {

    private static final Instant BASE = Instant.parse("2026-10-31T00:00:00Z");
    private static final String SCHOOL = "school_demo_01";
    private static final String TERM = "term_demo_2026a";
    private static final String LEADER = "user_demo_student_01";
    private static final String MEMBER = "user_demo_student_02";
    private static final String ASSISTANT = "user_demo_assistant_01";
    private static final String TEACHER_A = "user_demo_teacher_01";
    private static final String TEACHER_B = "user_demo_teacher_02";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private MutableClock clock;
    @Autowired private AdminImportService userImportService;
    @Autowired private AcademicImportService academicImportService;
    @Autowired private PersonnelCaseGrantAdminService grants;

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

    // ---- T01-A/B/D + T02-D/E + T03（撤权） ----

    @Test
    void t01AuthorizedHandlerReadsBodyWhileUnacceptedCandidateCannot() throws Exception {
        String leader = login("student_demo_01", "demo-password-01");
        String member = login("student_demo_02", "demo-password-02");
        String teacherA = login("teacher_demo_01", "demo-password-03");
        String teacherB = login("teacher_demo_02", "demo-password-05");
        Project project = project(leader, "t01-body");
        invite(leader, project.id(), MEMBER);
        acceptInvite(member, project.id());
        String changeId = createExit(member, project.id(), MEMBER);
        String caseId = caseIdOfChange(changeId);
        acceptCase(teacherA, caseId);
        String materialId = requestMaterial(teacherA, caseId, MEMBER);

        respondMaterial(member, materialId, "明确的学生正文 v1", 1, "t01-respond");
        mockMvc.perform(get("/api/v1/material-requests/{id}", materialId).header("Authorization", bearer(teacherA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.responseVersion").value(1))
                .andExpect(jsonPath("$.data.response.content").value("明确的学生正文 v1"))
                .andExpect(jsonPath("$.data.responses[0].version").value(1))
                .andExpect(jsonPath("$.data.responses[0].content").value("明确的学生正文 v1"))
                .andExpect(jsonPath("$.data.responses[0].evidenceRefs[0].kind").value("LINK"))
                .andExpect(jsonPath("$.data.reviews").isArray())
                .andExpect(jsonPath("$.data.caseTitle").isNotEmpty())
                .andExpect(jsonPath("$.data.recipientName").isNotEmpty())
                .andExpect(jsonPath("$.data.canReview").value(true))
                .andExpect(jsonPath("$.data.canRespond").value(false));
        mockMvc.perform(get("/api/v1/personnel-cases/{id}", caseId).header("Authorization", bearer(teacherA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.materials[0].response.content").value("明确的学生正文 v1"))
                .andExpect(jsonPath("$.data.materials[0].responses[0].content").value("明确的学生正文 v1"))
                .andExpect(jsonPath("$.data.staffRecords").exists());

        // B 已获得单 case 授权，但未接受交接：可以成为候选，不能读全案/材料正文。
        grantB(caseId);
        mockMvc.perform(get("/api/v1/personnel-cases/{id}", caseId).header("Authorization", bearer(teacherB)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/material-requests/{id}", materialId).header("Authorization", bearer(teacherB)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/personnel-cases/{id}/handoff-candidates", caseId)
                        .header("Authorization", bearer(teacherA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].userId").value(TEACHER_B));

        String handoffId = createHandoff(teacherA, caseId, TEACHER_B);
        mockMvc.perform(get("/api/v1/personnel-handoffs/{id}", handoffId).header("Authorization", bearer(teacherB)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.facts").value("交接事实"))
                .andExpect(jsonPath("$.data.unresolvedQuestions").value("未决问题"))
                .andExpect(jsonPath("$.data.decisionsNeeded").value("待决定"))
                .andExpect(jsonPath("$.data.canRespond").value(true));
        mockMvc.perform(get("/api/v1/personnel-cases/{id}", caseId).header("Authorization", bearer(teacherB)))
                .andExpect(status().isForbidden());
        acceptHandoff(teacherB, handoffId);
        mockMvc.perform(get("/api/v1/personnel-cases/{id}", caseId).header("Authorization", bearer(teacherB)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.assigneeId").value(TEACHER_B));
        mockMvc.perform(get("/api/v1/material-requests/{id}", materialId).header("Authorization", bearer(teacherB)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.response.content").value("明确的学生正文 v1"))
                .andExpect(jsonPath("$.data.canReview").value(true));

        int materialRevision = materialRevision(materialId);
        mockMvc.perform(post("/api/v1/material-requests/{id}/reviews", materialId)
                        .header("Authorization", bearer(teacherB)).header("Idempotency-Key", "t01-b-review")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"responseVersion\":1,\"decision\":\"ACCEPT\",\"reason\":\"已核对\","
                                + "\"expectedRevision\":" + materialRevision + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.data.reviews[0].reviewerId").value(TEACHER_B))
                .andExpect(jsonPath("$.data.reviews[0].responseVersion").value(1));

        // 先成功写入，再撤销教师角色：读取、办理和同键回放都立即失权。
        int caseRevision = caseRevision(caseId);
        String updateBody = "{\"expectedRevision\":" + caseRevision + ",\"facts\":\"授权期间写入\","
                + "\"nextAction\":\"继续\"}";
        mockMvc.perform(post("/api/v1/personnel-cases/{id}/updates", caseId)
                        .header("Authorization", bearer(teacherB)).header("Idempotency-Key", "t01-b-update")
                        .contentType(MediaType.APPLICATION_JSON).content(updateBody))
                .andExpect(status().isOk());
        jdbc.update("DELETE FROM user_role WHERE user_id = ? AND role = 'TEACHER'", TEACHER_B);
        mockMvc.perform(get("/api/v1/personnel-cases/{id}", caseId).header("Authorization", bearer(teacherB)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/material-requests/{id}", materialId).header("Authorization", bearer(teacherB)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/personnel-cases/{id}/updates", caseId)
                        .header("Authorization", bearer(teacherB)).header("Idempotency-Key", "t01-b-update")
                        .contentType(MediaType.APPLICATION_JSON).content(updateBody))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/personnel-cases/{id}/material-requests", caseId)
                        .header("Authorization", bearer(teacherB)).header("Idempotency-Key", "t01-b-request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(materialCreate("t01-b-request", caseRevision + 1, MEMBER)))
                .andExpect(status().isForbidden());
        // 关联人员决定同样不能由失权接任人办理。
        mockMvc.perform(post("/api/v1/member-changes/{id}/personnel-decisions", changeId)
                        .header("Authorization", bearer(teacherB)).header("Idempotency-Key", "t01-b-decide")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(decision(changeRevision(changeId), "RETURN")))
                .andExpect(status().isForbidden());
        assertThat(todoTypes(teacherB)).doesNotContain("PERSONNEL_HANDOFF");

        // 当前任课教师依法收回，授权历史与材料正文都保留。
        mockMvc.perform(post("/api/v1/personnel-cases/{id}/recoveries", caseId)
                        .header("Authorization", bearer(teacherA)).header("Idempotency-Key", "t01-recover")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":" + (caseRevision + 1) + ",\"reason\":\"接任人失权\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.assigneeId").value(TEACHER_A));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM personnel_case_grant WHERE case_id = ?",
                Integer.class, caseId)).isEqualTo(1);
        mockMvc.perform(get("/api/v1/material-requests/{id}", materialId).header("Authorization", bearer(teacherA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.response.content").value("明确的学生正文 v1"));
    }

    // ---- T01-E/F/G：版本冲突、撤回占位、具体 JSON 字段 ----

    @Test
    void t01VersionConflictAndWithdrawalKeepTruthfulProjection() throws Exception {
        String leader = login("student_demo_01", "demo-password-01");
        String member = login("student_demo_02", "demo-password-02");
        String teacherA = login("teacher_demo_01", "demo-password-03");
        Project project = project(leader, "t01-version");
        invite(leader, project.id(), MEMBER);
        acceptInvite(member, project.id());
        String changeId = createExit(member, project.id(), MEMBER);
        String caseId = caseIdOfChange(changeId);
        acceptCase(teacherA, caseId);
        String materialId = requestMaterial(teacherA, caseId, MEMBER);

        respondMaterial(member, materialId, "第一版正文", 1, "t01-v1");
        // 教师读到 v1 之后学生提交 v2；按 v1 审核必须冲突，不能误审新正文。
        respondMaterial(member, materialId, "第二版正文", 2, "t01-v2");
        mockMvc.perform(post("/api/v1/material-requests/{id}/reviews", materialId)
                        .header("Authorization", bearer(teacherA)).header("Idempotency-Key", "t01-review-v1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"responseVersion\":1,\"decision\":\"ACCEPT\",\"reason\":\"按旧版本\","
                                + "\"expectedRevision\":" + materialRevision(materialId) + "}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SOURCE_CHANGED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM material_review WHERE material_id = ?",
                Integer.class, materialId)).isZero();

        mockMvc.perform(get("/api/v1/material-requests/{id}", materialId).header("Authorization", bearer(teacherA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.responseVersion").value(2))
                .andExpect(jsonPath("$.data.responses.length()").value(2))
                .andExpect(jsonPath("$.data.responses[0].content").value("第一版正文"))
                .andExpect(jsonPath("$.data.responses[1].content").value("第二版正文"))
                .andExpect(jsonPath("$.data.responses[1].createdAt").isNotEmpty());

        // 撤回当前版本：版本号/作者/时间保留，正文只占位；历史 v1 不被删除或改写。
        mockMvc.perform(post("/api/v1/material-requests/{id}/withdrawals", materialId)
                        .header("Authorization", bearer(member)).header("Idempotency-Key", "t01-withdraw")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":" + materialRevision(materialId) + ",\"reason\":\"撤回最新\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("WITHDRAWN"))
                .andExpect(jsonPath("$.data.response.content").doesNotExist())
                .andExpect(jsonPath("$.data.responses[1].withdrawn").value(true))
                .andExpect(jsonPath("$.data.responses[1].content").doesNotExist())
                .andExpect(jsonPath("$.data.responses[0].content").value("第一版正文"));
        mockMvc.perform(get("/api/v1/personnel-cases/{id}", caseId).header("Authorization", bearer(teacherA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.materials[0].responses[1].withdrawn").value(true))
                .andExpect(jsonPath("$.data.materials[0].responses[1].content").doesNotExist());
        mockMvc.perform(get("/api/v1/me/material-requests").header("Authorization", bearer(member)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].responses[1].content").doesNotExist());
        // 同键回放仍按当前撤回状态投影，不能返回首次响应的正文。
        mockMvc.perform(post("/api/v1/material-requests/{id}/withdrawals", materialId)
                        .header("Authorization", bearer(member)).header("Idempotency-Key", "t01-withdraw")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":" + (materialRevision(materialId) - 1)
                                + ",\"reason\":\"撤回最新\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.response.content").doesNotExist());
    }

    // ---- T01-C：同校无关教师、其他 case 授权、学生项目成员、App 管理员身份 ----

    @Test
    void t01UnrelatedTeacherOtherCaseGrantAndAdminIdentityCannotReadBody() throws Exception {
        String leader = login("student_demo_01", "demo-password-01");
        String member = login("student_demo_02", "demo-password-02");
        String teacherA = login("teacher_demo_01", "demo-password-03");
        String teacherB = login("teacher_demo_02", "demo-password-05");
        String assistant = login("assistant_demo_01", "demo-password-04");
        Project project = project(leader, "t01-unrelated");
        invite(leader, project.id(), MEMBER);
        acceptInvite(member, project.id());
        String changeId = createExit(member, project.id(), MEMBER);
        String caseId = caseIdOfChange(changeId);
        acceptCase(teacherA, caseId);
        String materialId = requestMaterial(teacherA, caseId, MEMBER);
        respondMaterial(member, materialId, "私密正文", 1, "t01-private");

        // 同校无关教师：没有本 case 资格。
        mockMvc.perform(get("/api/v1/material-requests/{id}", materialId).header("Authorization", bearer(teacherB)))
                .andExpect(status().isForbidden());
        // 其他 case 的有效授权不能跨 case 读取。
        Project other = project(leader, "t01-other-owner");
        invite(leader, other.id(), MEMBER);
        acceptInvite(member, other.id());
        String otherChange = createExit(member, other.id(), MEMBER);
        grantB(caseIdOfChange(otherChange));
        mockMvc.perform(get("/api/v1/material-requests/{id}", materialId).header("Authorization", bearer(teacherB)))
                .andExpect(status().isForbidden());
        // 同一学生项目的其他学生不能读他人材料。
        mockMvc.perform(get("/api/v1/material-requests/{id}", materialId).header("Authorization", bearer(leader)))
                .andExpect(status().isForbidden());
        // CLASS_ADMIN 不是教师处理人，也不能读人员事项正文。
        mockMvc.perform(get("/api/v1/material-requests/{id}", materialId).header("Authorization", bearer(assistant)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/personnel-cases/{id}", caseId).header("Authorization", bearer(assistant)))
                .andExpect(status().isForbidden());
    }

    // ---- T02-B：保留旧 grant 但移除 TEACHER 角色后，候选/待办/详情全部消失 ----

    @Test
    void t02TeacherRoleRemovalRemovesCandidatesTodosAndHandoffRead() throws Exception {
        String leader = login("student_demo_01", "demo-password-01");
        String member = login("student_demo_02", "demo-password-02");
        String teacherA = login("teacher_demo_01", "demo-password-03");
        String teacherB = login("teacher_demo_02", "demo-password-05");
        Project project = project(leader, "t02-role");
        invite(leader, project.id(), MEMBER);
        acceptInvite(member, project.id());
        String caseId = caseIdOfChange(createExit(member, project.id(), MEMBER));
        acceptCase(teacherA, caseId);
        grantB(caseId);
        String handoffId = createHandoff(teacherA, caseId, TEACHER_B);

        assertThat(todoTypes(teacherB)).contains("PERSONNEL_HANDOFF");
        mockMvc.perform(get("/api/v1/me/personnel-handoffs").header("Authorization", bearer(teacherB)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1));

        jdbc.update("DELETE FROM user_role WHERE user_id = ? AND role = 'TEACHER'", TEACHER_B);
        // grant 仍 ACTIVE，但资格必须在使用时重查角色。
        assertThat(jdbc.queryForObject("SELECT status FROM personnel_case_grant WHERE case_id = ?",
                String.class, caseId)).isEqualTo("ACTIVE");
        // 旧 token 与新登录 token 都不能恢复已失效的教师处理权。
        for (String token : List.of(teacherB, login("teacher_demo_02", "demo-password-05"))) {
            mockMvc.perform(get("/api/v1/personnel-cases/{id}", caseId).header("Authorization", bearer(token)))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get("/api/v1/personnel-handoffs/{id}", handoffId)
                            .header("Authorization", bearer(token)))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get("/api/v1/me/personnel-handoffs").header("Authorization", bearer(token)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.total").value(0));
            mockMvc.perform(post("/api/v1/personnel-handoffs/{id}/responses", handoffId)
                            .header("Authorization", bearer(token)).header("Idempotency-Key", "t02-respond")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"expectedRevision\":1,\"decision\":\"ACCEPT\"}"))
                    .andExpect(status().isForbidden());
            assertThat(todoTypes(token)).doesNotContain("PERSONNEL_HANDOFF");
        }
        mockMvc.perform(get("/api/v1/personnel-cases/{id}/handoff-candidates", caseId)
                        .header("Authorization", bearer(teacherA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
        // 恢复角色后，仍然有效的 grant 可恢复资格（不默默生成新授权）。
        jdbc.update("INSERT INTO user_role (user_id, role) VALUES (?, 'TEACHER')", TEACHER_B);
        mockMvc.perform(get("/api/v1/personnel-handoffs/{id}", handoffId).header("Authorization", bearer(teacherB)))
                .andExpect(status().isOk());
    }

    // ---- T02-C：未核验、停用、不同学校、回避分别拒绝 ----

    @Test
    void t02EligibilityChecksVerificationStatusSchoolAndRecusal() throws Exception {
        String leader = login("student_demo_01", "demo-password-01");
        String member = login("student_demo_02", "demo-password-02");
        String teacherA = login("teacher_demo_01", "demo-password-03");
        String teacherB = login("teacher_demo_02", "demo-password-05");
        Project project = project(leader, "t02-eligible");
        invite(leader, project.id(), MEMBER);
        acceptInvite(member, project.id());
        String caseId = caseIdOfChange(createExit(member, project.id(), MEMBER));
        acceptCase(teacherA, caseId);
        grantB(caseId);

        jdbc.update("UPDATE app_user SET identity_status = 'PENDING' WHERE id = ?", TEACHER_B);
        mockMvc.perform(get("/api/v1/personnel-cases/{id}", caseId).header("Authorization", bearer(teacherB)))
                .andExpect(status().isForbidden());
        jdbc.update("UPDATE app_user SET identity_status = 'VERIFIED' WHERE id = ?", TEACHER_B);

        jdbc.update("UPDATE app_user SET disabled = true WHERE id = ?", TEACHER_B);
        mockMvc.perform(get("/api/v1/personnel-cases/{id}", caseId).header("Authorization", bearer(teacherB)))
                .andExpect(status().is4xxClientError());
        jdbc.update("UPDATE app_user SET disabled = false WHERE id = ?", TEACHER_B);

        jdbc.update("""
                INSERT INTO school (id, name, timezone) VALUES ('school_other', '其他学校', 'Asia/Shanghai')
                ON CONFLICT (id) DO NOTHING
                """);
        jdbc.update("UPDATE app_user SET school_id = 'school_other' WHERE id = ?", TEACHER_B);
        mockMvc.perform(get("/api/v1/personnel-cases/{id}", caseId).header("Authorization", bearer(teacherB)))
                .andExpect(status().isForbidden());
        jdbc.update("UPDATE app_user SET school_id = ? WHERE id = ?", SCHOOL, TEACHER_B);

        jdbc.update("""
                INSERT INTO membership (id, project_id, user_id, project_role, status, color, joined_at, revision)
                VALUES (?, ?, ?, 'MEMBER', 'ACTIVE', '#236B56', ?, 1)
                """, "member-" + UUID.randomUUID(), project.id(), TEACHER_B, at(0));
        mockMvc.perform(get("/api/v1/personnel-cases/{id}", caseId).header("Authorization", bearer(teacherB)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/personnel-cases/{id}/handoff-candidates", caseId)
                        .header("Authorization", bearer(teacherA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
        // 合法授权教师虽不是当前任课教师，仍可接任并正常办理（D01 正向）。
        jdbc.update("DELETE FROM membership WHERE project_id = ? AND user_id = ?", project.id(), TEACHER_B);
        String handoffId = createHandoff(teacherA, caseId, TEACHER_B);
        acceptHandoff(teacherB, handoffId);
        mockMvc.perform(get("/api/v1/personnel-cases/{id}", caseId).header("Authorization", bearer(teacherB)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.assigneeId").value(TEACHER_B));
    }

    // ---- T03：旧处理人失权矩阵的可收回性 ----

    @Test
    void t03RecoverMatrixDistinguishesEveryLossOfQualification() throws Exception {
        Scenario valid = caseWithBAsAssignee("t03-valid");
        mockMvc.perform(post("/api/v1/personnel-cases/{id}/recoveries", valid.caseId())
                        .header("Authorization", bearer(valid.teacherA())).header("Idempotency-Key", "t03-valid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":" + caseRevision(valid.caseId())
                                + ",\"reason\":\"无理由抢回\"}"))
                .andExpect(status().isUnprocessableEntity());
        assertThat(caseAssignee(valid.caseId())).isEqualTo(TEACHER_B);

        Scenario revoked = caseWithBAsAssignee("t03-revoked");
        var active = grants.list(revoked.caseId()).stream().filter(g -> "ACTIVE".equals(g.status()))
                .findFirst().orElseThrow();
        grants.revoke(active.grantId(), active.revision(), "撤权", false, "test-admin");
        recover(revoked.teacherA(), revoked.caseId(), "t03-revoked");

        Scenario disabled = caseWithBAsAssignee("t03-disabled");
        jdbc.update("UPDATE app_user SET disabled = true WHERE id = ?", TEACHER_B);
        recover(disabled.teacherA(), disabled.caseId(), "t03-disabled");
        jdbc.update("UPDATE app_user SET disabled = false WHERE id = ?", TEACHER_B);

        Scenario roleLost = caseWithBAsAssignee("t03-role");
        jdbc.update("DELETE FROM user_role WHERE user_id = ? AND role = 'TEACHER'", TEACHER_B);
        recover(roleLost.teacherA(), roleLost.caseId(), "t03-role");
        jdbc.update("INSERT INTO user_role (user_id, role) VALUES (?, 'TEACHER')", TEACHER_B);

        Scenario recused = caseWithBAsAssignee("t03-recused");
        jdbc.update("""
                INSERT INTO membership (id, project_id, user_id, project_role, status, color, joined_at, revision)
                VALUES (?, ?, ?, 'MEMBER', 'ACTIVE', '#236B56', ?, 1)
                """, "member-" + UUID.randomUUID(), recused.projectId(), TEACHER_B, at(0));
        recover(recused.teacherA(), recused.caseId(), "t03-recused");

        Scenario expired = caseWithBAsAssignee("t03-expired");
        jdbc.update("DELETE FROM personnel_case_grant WHERE case_id = ?", expired.caseId());
        grants.grant(expired.caseId(), TEACHER_B, "短时授权", clock.instant().plusSeconds(3600),
                false, "test-admin");
        clock.advance(Duration.ofSeconds(3600));
        String teacherA = login("teacher_demo_01", "demo-password-03");
        recover(teacherA, expired.caseId(), "t03-expired");

        Scenario closed = caseWithBAsAssignee("t03-closed");
        mockMvc.perform(post("/api/v1/personnel-cases/{id}/closures", closed.caseId())
                        .header("Authorization", bearer(closed.teacherB())).header("Idempotency-Key", "t03-close")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":" + caseRevision(closed.caseId())
                                + ",\"reason\":\"结案\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CLOSED"));
        mockMvc.perform(post("/api/v1/personnel-cases/{id}/recoveries", closed.caseId())
                        .header("Authorization", bearer(closed.teacherA())).header("Idempotency-Key", "t03-closed")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":" + caseRevision(closed.caseId())
                                + ",\"reason\":\"不能重开\"}"))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT status FROM personnel_case WHERE id = ?",
                String.class, closed.caseId())).isEqualTo("CLOSED");
    }

    // ---- T04-A/B/C/D/E：关联人员决定只由本 case 当前处理人办理 ----

    @Test
    void t04LinkedDecisionRequiresCurrentHandlerOfTheLinkedCase() throws Exception {
        String leader = login("student_demo_01", "demo-password-01");
        String member = login("student_demo_02", "demo-password-02");
        String teacherA = login("teacher_demo_01", "demo-password-03");
        String teacherB = login("teacher_demo_02", "demo-password-05");
        Project project = project(leader, "t04-handler");
        invite(leader, project.id(), MEMBER);
        acceptInvite(member, project.id());
        String changeId = createExit(member, project.id(), MEMBER);
        String caseId = caseIdOfChange(changeId);
        acceptCase(teacherA, caseId);

        // A 是当前 assignee：可以决定。
        mockMvc.perform(post("/api/v1/member-changes/{id}/personnel-decisions", changeId)
                        .header("Authorization", bearer(teacherA)).header("Idempotency-Key", "t04-a-allow")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(decision(changeRevision(changeId), "ALLOW_HANDOVER")))
                .andExpect(status().isOk());
        grantB(caseId);
        String handoffId = createHandoff(teacherA, caseId, TEACHER_B);
        acceptHandoff(teacherB, handoffId);

        // B 接任后可以继续决定；A 不能以任课教师身份绕过接任人。
        int revision = changeRevision(changeId);
        mockMvc.perform(post("/api/v1/member-changes/{id}/personnel-decisions", changeId)
                        .header("Authorization", bearer(teacherB)).header("Idempotency-Key", "t04-b-return")
                        .contentType(MediaType.APPLICATION_JSON).content(decision(revision, "RETURN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RETURNED"));
        mockMvc.perform(post("/api/v1/member-changes/{id}/personnel-decisions", changeId)
                        .header("Authorization", bearer(teacherA)).header("Idempotency-Key", "t04-a-bypass")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(decision(changeRevision(changeId), "ALLOW_HANDOVER")))
                .andExpect(status().isForbidden());
        // 同键回放也要重核当前办理资格。
        jdbc.update("DELETE FROM user_role WHERE user_id = ? AND role = 'TEACHER'", TEACHER_B);
        mockMvc.perform(post("/api/v1/member-changes/{id}/personnel-decisions", changeId)
                        .header("Authorization", bearer(teacherB)).header("Idempotency-Key", "t04-b-return")
                        .contentType(MediaType.APPLICATION_JSON).content(decision(revision, "RETURN")))
                .andExpect(status().isForbidden());
        jdbc.update("INSERT INTO user_role (user_id, role) VALUES (?, 'TEACHER')", TEACHER_B);

        // 版本过期不写半份决定。
        mockMvc.perform(post("/api/v1/member-changes/{id}/personnel-decisions", changeId)
                        .header("Authorization", bearer(teacherB)).header("Idempotency-Key", "t04-stale")
                        .contentType(MediaType.APPLICATION_JSON).content(decision(revision - 1, "ALLOW_HANDOVER")))
                .andExpect(status().isConflict());

        // B 对另一个 case 的 change 不能办理。
        Project other = project(leader, "t04-other");
        invite(leader, other.id(), MEMBER);
        acceptInvite(member, other.id());
        String otherChange = createExit(member, other.id(), MEMBER);
        mockMvc.perform(post("/api/v1/member-changes/{id}/personnel-decisions", otherChange)
                        .header("Authorization", bearer(teacherB)).header("Idempotency-Key", "t04-other")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(decision(changeRevision(otherChange), "ALLOW_HANDOVER")))
                .andExpect(status().isForbidden());

        // 没有关联 case 的孤立成员变更不能回退成任意教师可办。
        String orphan = "change-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO member_change (id, project_id, target_user_id, initiator_id, kind, status,
                    plan_version, source_membership_revision, revision, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'EXIT', 'WAIT_PERSONNEL', 0, 1, 1, ?, ?)
                """, orphan, project.id(), ASSISTANT, LEADER, at(0), at(0));
        mockMvc.perform(post("/api/v1/member-changes/{id}/personnel-decisions", orphan)
                        .header("Authorization", bearer(teacherA)).header("Idempotency-Key", "t04-orphan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(decision(1, "ALLOW_HANDOVER")))
                .andExpect(status().isForbidden());
    }

    // ---- T04-F：removal-vote 真实状态流程产生合法 case 后由接任人办理 ----

    @Test
    void t04RemovalVoteRealFlowCreatesCaseAndAllowsCurrentHandlerDecision() throws Exception {
        String leader = login("student_demo_01", "demo-password-01");
        String member = login("student_demo_02", "demo-password-02");
        String assistant = login("assistant_demo_01", "demo-password-04");
        Project project = project(leader, "t04-vote");
        invite(leader, project.id(), MEMBER);
        acceptInvite(member, project.id());
        invite(leader, project.id(), ASSISTANT);
        acceptInvite(assistant, project.id());
        JsonNode vote = data(mockMvc.perform(post("/api/v1/projects/{id}/removal-votes", project.id())
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "t04-vote")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "targetUserId", MEMBER, "facts", "长期未参与", "communicationHistory", "已沟通"))))
                .andExpect(status().isOk()).andReturn());
        String voteId = vote.get("id").asText();
        clock.advance(Duration.ofHours(48));
        leader = login("student_demo_01", "demo-password-01");
        assistant = login("assistant_demo_01", "demo-password-04");
        ballot(leader, voteId, "AGREE", 0, "t04-vote-l");
        ballot(assistant, voteId, "AGREE", 0, "t04-vote-a");
        clock.advance(Duration.ofHours(72));
        leader = login("student_demo_01", "demo-password-01");
        mockMvc.perform(get("/api/v1/removal-votes/{id}", voteId).header("Authorization", bearer(leader)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PASSED"))
                .andExpect(jsonPath("$.data.memberChangeId").isNotEmpty());
        String changeId = jdbc.queryForObject(
                "SELECT member_change_id FROM removal_vote WHERE id = ?", String.class, voteId);
        String caseId = caseIdOfChange(changeId);
        assertThat(caseId).isNotBlank();

        String teacherA = login("teacher_demo_01", "demo-password-03");
        String teacherB = login("teacher_demo_02", "demo-password-05");
        acceptCase(teacherA, caseId);
        grantB(caseId);
        String handoffId = createHandoff(teacherA, caseId, TEACHER_B);
        acceptHandoff(teacherB, handoffId);
        int voteRevision = voteRevision(voteId);
        mockMvc.perform(post("/api/v1/removal-votes/{id}/personnel-decisions", voteId)
                        .header("Authorization", bearer(teacherB)).header("Idempotency-Key", "t04-vote-b")
                        .contentType(MediaType.APPLICATION_JSON).content(decision(voteRevision, "ALLOW_HANDOVER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.memberChangeId").value(changeId));
        mockMvc.perform(post("/api/v1/removal-votes/{id}/personnel-decisions", voteId)
                        .header("Authorization", bearer(teacherA)).header("Idempotency-Key", "t04-vote-a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(decision(voteRevision(voteId), "ALLOW_HANDOVER")))
                .andExpect(status().isForbidden());
    }

    // ---- T05：详情/列表按当前资格过滤，不泄漏被过滤 total ----

    @Test
    void t05HandoffDetailAndListFollowCurrentQualification() throws Exception {
        String leader = login("student_demo_01", "demo-password-01");
        String member = login("student_demo_02", "demo-password-02");
        String teacherA = login("teacher_demo_01", "demo-password-03");
        String teacherB = login("teacher_demo_02", "demo-password-05");
        Project project = project(leader, "t05-list");
        invite(leader, project.id(), MEMBER);
        acceptInvite(member, project.id());
        String caseId = caseIdOfChange(createExit(member, project.id(), MEMBER));
        acceptCase(teacherA, caseId);
        grantB(caseId);

        // 终态交接曾经属于 B，但 B 没有当前读取资格：列表过滤，不因无权项整体 500。
        String cancelled = createHandoff(teacherA, caseId, TEACHER_B);
        mockMvc.perform(post("/api/v1/personnel-handoffs/{id}/cancellations", cancelled)
                        .header("Authorization", bearer(teacherA)).header("Idempotency-Key", "t05-cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"reason\":\"重发\"}"))
                .andExpect(status().isOk());
        String pending = createHandoff(teacherA, caseId, TEACHER_B);
        mockMvc.perform(get("/api/v1/me/personnel-handoffs").header("Authorization", bearer(teacherB)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(pending))
                .andExpect(jsonPath("$.data.items[0].sourceCaseRevision").isNumber());
        mockMvc.perform(get("/api/v1/personnel-handoffs/{id}", cancelled).header("Authorization", bearer(teacherB)))
                .andExpect(status().isForbidden());

        jdbc.update("DELETE FROM personnel_case_grant WHERE case_id = ?", caseId);
        mockMvc.perform(get("/api/v1/me/personnel-handoffs").header("Authorization", bearer(teacherB)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
        mockMvc.perform(get("/api/v1/personnel-handoffs/{id}", pending).header("Authorization", bearer(teacherB)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/personnel-handoffs/{id}/responses", pending)
                        .header("Authorization", bearer(teacherB)).header("Idempotency-Key", "t05-respond")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"decision\":\"ACCEPT\"}"))
                .andExpect(status().isForbidden());

        grantB(caseId);
        acceptHandoff(teacherB, pending);
        // B 已把事项再交给当前任课教师 A：旧的 from/to 历史身份不能获得超出当前资格的全案访问。
        String back = createHandoff(teacherB, caseId, TEACHER_A);
        acceptHandoff(teacherA, back);
        mockMvc.perform(get("/api/v1/personnel-handoffs/{id}", back).header("Authorization", bearer(teacherB)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/me/personnel-handoffs").header("Authorization", bearer(teacherB)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
        // GET 不会推进状态。
        mockMvc.perform(get("/api/v1/personnel-handoffs/{id}", pending).header("Authorization", bearer(teacherB)))
                .andExpect(status().isForbidden());
    }

    // ---- T06-A/C：case 永远先于 handoff；双连接栅栏验证无反向等待 ----

    @Test
    void t06CaseLockPrecedesAcceptWithoutReverseWait() throws Exception {
        Scenario pending = caseWithPendingHandoff("t06-close");
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            holder.createStatement().execute("SET statement_timeout = '5s'");
            lockCase(holder, pending.caseId());
            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                Future<MvcResult> accept = pool.submit(() -> respondHandoff(pending.teacherB(),
                        pending.handoffId(), 1, "ACCEPT", "t06-accept-close"));
                // 确定接受事务已经阻塞在 case 锁上，而不是尚未开始。
                awaitLockWaitingOnCase();
                // 新顺序下接受尚未触碰 handoff；若仍先锁 handoff，这里会形成反向等待并超时。
                holder.createStatement().executeUpdate(
                        "UPDATE personnel_handoff SET status = 'CANCELLED', revision = revision + 1 "
                                + "WHERE case_id = '" + pending.caseId() + "' AND status = 'PENDING'");
                holder.commit();
                MvcResult result = accept.get(10, TimeUnit.SECONDS);
                assertThat(result.getResponse().getStatus()).isIn(403, 409);
            } finally {
                pool.shutdownNow();
            }
        }
        assertThat(handoffStatus(pending.handoffId())).isEqualTo("CANCELLED");
        assertThat(caseAssignee(pending.caseId())).isEqualTo(TEACHER_A);

        // 撤权完成后不能新接受，原处理人可确定性取消失效交接（T06-D 的顺序断言）。
        Scenario revoked = caseWithPendingHandoff("t06-revoked");
        var active = grants.list(revoked.caseId()).stream().filter(g -> "ACTIVE".equals(g.status()))
                .findFirst().orElseThrow();
        grants.revoke(active.grantId(), active.revision(), "撤权", false, "test-admin");
        mockMvc.perform(post("/api/v1/personnel-handoffs/{id}/responses", revoked.handoffId())
                        .header("Authorization", bearer(revoked.teacherB())).header("Idempotency-Key", "t06-late-accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"decision\":\"ACCEPT\"}"))
                .andExpect(status().is4xxClientError());
        mockMvc.perform(post("/api/v1/personnel-handoffs/{id}/cancellations", revoked.handoffId())
                        .header("Authorization", bearer(revoked.teacherA())).header("Idempotency-Key", "t06-cancel-revoked")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"reason\":\"接续人已撤权\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));
        assertThat(caseAssignee(revoked.caseId())).isEqualTo(TEACHER_A);
        assertThat(handoffStatus(revoked.handoffId())).isEqualTo("CANCELLED");
    }

    // ---- T06-B/E：接受先完成后的结案不能使用旧负责人资格 ----

    @Test
    void t06StaleCloseAfterAcceptanceIsRejected() throws Exception {
        Scenario scenario = caseWithBAsAssignee("t06-stale-close");
        int beforeAcceptRevision = caseRevision(scenario.caseId()) - 1;
        assertThat(caseAssignee(scenario.caseId())).isEqualTo(TEACHER_B);
        // 旧负责人 A 仍持接受前 revision：锁后重验必须冲突，不能结掉新负责人的事项。
        mockMvc.perform(post("/api/v1/personnel-cases/{id}/closures", scenario.caseId())
                        .header("Authorization", bearer(scenario.teacherA()))
                        .header("Idempotency-Key", "t06-stale-close")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":" + beforeAcceptRevision + ",\"reason\":\"旧负责人结案\"}"))
                .andExpect(status().is4xxClientError());
        assertThat(jdbc.queryForObject("SELECT status FROM personnel_case WHERE id = ?",
                String.class, scenario.caseId())).isEqualTo("IN_PROGRESS");
        // 取消不能在被并发接受后用 0 行更新返回虚假成功。
        mockMvc.perform(post("/api/v1/personnel-handoffs/{id}/cancellations", scenario.handoffId())
                        .header("Authorization", bearer(scenario.teacherA()))
                        .header("Idempotency-Key", "t06-cancel-after-accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"reason\":\"已接受后取消\"}"))
                .andExpect(status().is4xxClientError());
        assertThat(handoffStatus(scenario.handoffId())).isEqualTo("ACCEPTED");
    }

    // ---- helpers ----

    private Scenario caseWithPendingHandoff(String prefix) throws Exception {
        String leader = login("student_demo_01", "demo-password-01");
        String member = login("student_demo_02", "demo-password-02");
        String teacherA = login("teacher_demo_01", "demo-password-03");
        String teacherB = login("teacher_demo_02", "demo-password-05");
        Project project = project(leader, prefix);
        invite(leader, project.id(), MEMBER);
        acceptInvite(member, project.id());
        String caseId = caseIdOfChange(createExit(member, project.id(), MEMBER));
        acceptCase(teacherA, caseId);
        grantB(caseId);
        String handoffId = createHandoff(teacherA, caseId, TEACHER_B);
        return new Scenario(project.id(), caseId, handoffId, teacherA, teacherB);
    }

    private void awaitLockWaitingOnCase() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbc.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity
                    WHERE datname = current_database() AND state = 'active'
                      AND wait_event_type = 'Lock' AND query ILIKE '%personnel_case%'
                    """, Integer.class);
            if (waiting != null && waiting > 0) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("接受事务没有先等待 case 锁");
    }

    private Scenario caseWithBAsAssignee(String prefix) throws Exception {
        String leader = login("student_demo_01", "demo-password-01");
        String member = login("student_demo_02", "demo-password-02");
        String teacherA = login("teacher_demo_01", "demo-password-03");
        String teacherB = login("teacher_demo_02", "demo-password-05");
        Project project = project(leader, prefix);
        invite(leader, project.id(), MEMBER);
        acceptInvite(member, project.id());
        String caseId = caseIdOfChange(createExit(member, project.id(), MEMBER));
        acceptCase(teacherA, caseId);
        grantB(caseId);
        String handoffId = createHandoff(teacherA, caseId, TEACHER_B);
        acceptHandoff(teacherB, handoffId);
        return new Scenario(project.id(), caseId, handoffId, teacherA, teacherB);
    }

    private void recover(String teacherA, String caseId, String key) throws Exception {
        mockMvc.perform(post("/api/v1/personnel-cases/{id}/recoveries", caseId)
                        .header("Authorization", bearer(teacherA)).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":" + caseRevision(caseId) + ",\"reason\":\"依法收回\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.assigneeId").value(TEACHER_A));
    }

    private MvcResult respondHandoff(String token, String handoffId, int revision, String decision, String key)
            throws Exception {
        return mockMvc.perform(post("/api/v1/personnel-handoffs/{id}/responses", handoffId)
                .header("Authorization", bearer(token)).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedRevision\":" + revision + ",\"decision\":\"" + decision + "\"}")).andReturn();
    }

    private MvcResult recoverRequest(String token, String caseId, int revision, String key) throws Exception {
        return mockMvc.perform(post("/api/v1/personnel-cases/{id}/recoveries", caseId)
                .header("Authorization", bearer(token)).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedRevision\":" + revision + ",\"reason\":\"并发收回\"}")).andReturn();
    }

    private void lockCase(Connection connection, String caseId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM personnel_case WHERE id = ? FOR UPDATE")) {
            statement.setString(1, caseId);
            statement.executeQuery().close();
        }
    }

    private void grantB(String caseId) {
        grants.grant(caseId, TEACHER_B, "管理员授权接续", null, false, "test-admin");
    }

    private void acceptCase(String token, String caseId) throws Exception {
        mockMvc.perform(post("/api/v1/personnel-cases/{id}/acceptances", caseId)
                        .header("Authorization", bearer(token)).header("Idempotency-Key", "accept-" + caseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":" + caseRevision(caseId) + "}"))
                .andExpect(status().isOk());
    }

    private void acceptHandoff(String token, String handoffId) throws Exception {
        mockMvc.perform(post("/api/v1/personnel-handoffs/{id}/responses", handoffId)
                        .header("Authorization", bearer(token)).header("Idempotency-Key", "accept-h-" + handoffId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"decision\":\"ACCEPT\"}"))
                .andExpect(status().isOk());
    }

    private String createHandoff(String token, String caseId, String recipient) throws Exception {
        return data(mockMvc.perform(post("/api/v1/personnel-cases/{id}/handoffs", caseId)
                        .header("Authorization", bearer(token)).header("Idempotency-Key",
                                "handoff-" + caseId + "-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedCaseRevision\":" + caseRevision(caseId)
                                + ",\"recipientTeacherId\":\"" + recipient + "\","
                                + "\"facts\":\"交接事实\",\"unresolvedQuestions\":\"未决问题\","
                                + "\"decisionsNeeded\":\"待决定\"}"))
                .andExpect(status().isOk()).andReturn()).get("id").asText();
    }

    private String requestMaterial(String token, String caseId, String recipient) throws Exception {
        return data(mockMvc.perform(post("/api/v1/personnel-cases/{id}/material-requests", caseId)
                        .header("Authorization", bearer(token)).header("Idempotency-Key", "material-" + caseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(materialCreate("material-" + caseId, caseRevision(caseId), recipient)))
                .andExpect(status().isOk()).andReturn()).get("id").asText();
    }

    private String materialCreate(String key, int caseRevision, String recipient) {
        return "{\"expectedCaseRevision\":" + caseRevision + ",\"recipientId\":\"" + recipient + "\","
                + "\"requirements\":\"提交明确正文\",\"dueAt\":\"2026-11-10T15:59:59Z\"}";
    }

    private void respondMaterial(String token, String materialId, String content, int expectedRevision, String key)
            throws Exception {
        mockMvc.perform(post("/api/v1/material-requests/{id}/responses", materialId)
                        .header("Authorization", bearer(token)).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "content", content,
                                "expectedRevision", expectedRevision,
                                "evidenceRefs", List.of(Map.of("kind", "LINK",
                                        "url", "https://example.test/evidence",
                                        "title", "证据"))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.response.content").value(content));
    }

    private String createExit(String token, String projectId, String userId) throws Exception {
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

    private void ballot(String token, String voteId, String choice, int revision, String key) throws Exception {
        mockMvc.perform(put("/api/v1/removal-votes/{id}/me/ballot", voteId)
                        .header("Authorization", bearer(token)).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":" + revision + ",\"choice\":\"" + choice + "\"}"))
                .andExpect(status().isOk());
    }

    private String decision(int revision, String value) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "expectedRevision", revision, "decision", value, "placementPlan", "由组内接任",
                "reason", "人员核对", "allowBelowMinimum", false));
    }

    private String caseIdOfChange(String changeId) {
        return jdbc.queryForObject("SELECT id FROM personnel_case WHERE member_change_id = ?", String.class, changeId);
    }

    private int caseRevision(String caseId) {
        return jdbc.queryForObject("SELECT revision FROM personnel_case WHERE id = ?", Integer.class, caseId);
    }

    private String caseAssignee(String caseId) {
        return jdbc.queryForObject("SELECT assignee_id FROM personnel_case WHERE id = ?", String.class, caseId);
    }

    private int changeRevision(String changeId) {
        return jdbc.queryForObject("SELECT revision FROM member_change WHERE id = ?", Integer.class, changeId);
    }

    private int voteRevision(String voteId) {
        return jdbc.queryForObject("SELECT revision FROM removal_vote WHERE id = ?", Integer.class, voteId);
    }

    private int materialRevision(String materialId) {
        return jdbc.queryForObject("SELECT revision FROM material_request WHERE id = ?", Integer.class, materialId);
    }

    private String handoffStatus(String handoffId) {
        return jdbc.queryForObject("SELECT status FROM personnel_handoff WHERE id = ?", String.class, handoffId);
    }

    private Set<String> todoTypes(String token) throws Exception {
        Set<String> types = new java.util.HashSet<>();
        data(mockMvc.perform(get("/api/v1/me/todos").header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andReturn()).path("items")
                .forEach(item -> types.add(item.path("type").asText()));
        return types;
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
                                "mode", "QUICK", "name", "治理组", "capacity", 4, "discussionTime", "周三",
                                "roles", List.of()))))
                .andExpect(status().isOk()).andReturn());
        return new Project(created.get("id").asText(), created.get("stages").get(0).get("id").asText());
    }

    private void invite(String leader, String projectId, String userId) throws Exception {
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

    private void acceptInvite(String token, String projectId) throws Exception {
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

    private java.time.OffsetDateTime at(int seconds) {
        return java.time.OffsetDateTime.ofInstant(clock.instant().plusSeconds(seconds), ZoneOffset.UTC);
    }

    private void clearAll() {
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

    private record Project(String id, String stageId) {
    }

    private record Scenario(String projectId, String caseId, String handoffId, String teacherA, String teacherB) {
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
