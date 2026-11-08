package cn.gulupai.server.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.gulupai.server.common.security.Hashing;
import cn.gulupai.server.identity.cli.AdminImportService;
import cn.gulupai.server.it.TestSchemaCleaner;
import cn.gulupai.server.platform.application.PlatformSuperAdminService;
import cn.gulupai.server.platform.domain.PlatformCapability;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * R3 / R6 回归：平台审计只投影显式登记的管理事件，理由按分类给出；
 * 总览按字段检查能力，缺任一计数能力明确拒绝，缺审计能力时
 * {@code recentOperations} 为空数组而不是伪造“全平台没有事件”。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PlatformReadPrivacyP66IT {

    private static final Path FIXTURE = Path.of("admin/fixtures/p01-demo-users.json");
    private static final String ADMIN = "user_demo_teacher_01";
    private static final String ADMIN_LOGIN = "teacher_demo_01";
    private static final String ADMIN_PASSWORD = "demo-password-03";
    private static final String LIMITED = "user_demo_student_02";
    private static final String LIMITED_LOGIN = "student_demo_02";
    private static final String LIMITED_PASSWORD = "demo-password-02";
    private static final String TARGET = "user_demo_student_01";
    private static final String SCHOOL_A = "school_demo_01";
    private static final String SCHOOL_B = "school_p66_privacy_b";
    private static final String USER_B = "user_p66_privacy_b";
    private static final String USER_B_LOGIN = "p66_privacy_b";
    private static final String USER_B_PASSWORD = "demo-password-07";

    /** 教师申请里的核验材料，属于不能被普通审计读取原文的敏感内容。 */
    private static final String SENSITIVE = "身份证 110101199001011234 与离职证明扫描件";
    /** 事项类事件的内部理由，未获准不应出现在管理审计里。 */
    private static final String PERSONNEL = "家庭困难材料：低保编号 XXX-0001";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AdminImportService importService;

    @Autowired
    private PlatformSuperAdminService superAdmins;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() {
        clearAll();
        importService.importUsers(FIXTURE, false);
        jdbc.update("UPDATE school SET registration_enabled = true WHERE id = ?", SCHOOL_A);
        superAdmins.grantInitial(ADMIN, ADMIN, "隔离环境首位管理员", false);
        jdbc.update("INSERT INTO school (id, name, timezone) VALUES (?, ?, ?)", SCHOOL_B, "P66 隔离第二学校",
                "Asia/Shanghai");
        jdbc.update("""
                INSERT INTO app_user (id, school_id, login_id, display_name, password_hash, identity_status)
                VALUES (?, ?, ?, ?, ?, 'VERIFIED')
                """, USER_B, SCHOOL_B, USER_B_LOGIN, "第二学校管理员", passwordEncoder.encode(USER_B_PASSWORD));
    }

    @Test
    void dashboardChecksEachCountCapabilityBeforeItOffersRecentOperations() throws Exception {
        audit("audit_p66_privacy_dashboard", "ACCOUNT_DISABLED", "USER", TARGET, ADMIN, "账号处置：疑似共享");
        // 入口能力先给足，才能验证缺少计数能力时返回的是专门的总览拒绝，而不是通用 403。
        regrant(LIMITED, "PLATFORM", null, List.of(PlatformCapability.PLATFORM_ACCOUNT_READ.name()));
        String limited = login(LIMITED_LOGIN, LIMITED_PASSWORD);

        MvcResult refused = mockMvc.perform(get("/api/v1/admin/dashboard").header("Authorization", bearer(limited)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value(containsString("总览统计权限不足")))
                .andReturn();
        assertThat(refused.getResponse().getContentAsString()).doesNotContain("counts");
        assertThat(refused.getResponse().getContentAsString()).doesNotContain("recentOperations");

        // 缺计数能力只影响总览，已授权的账号目录照常可用；审计仍按各自能力拒绝。
        mockMvc.perform(get("/api/v1/admin/users").param("limit", "5").header("Authorization", bearer(limited)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isArray());
        mockMvc.perform(get("/api/v1/admin/audit-logs").header("Authorization", bearer(limited)))
                .andExpect(status().isForbidden());

        regrant(LIMITED, "PLATFORM", null, List.of(PlatformCapability.PLATFORM_ACCOUNT_READ.name(),
                PlatformCapability.PLATFORM_TEACHER_REVIEW.name(), PlatformCapability.PLATFORM_COURSE_GOVERNANCE.name()));
        mockMvc.perform(get("/api/v1/admin/dashboard").header("Authorization", bearer(limited)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.counts.teacherApplicationsPending").value(0))
                .andExpect(jsonPath("$.data.counts.courseClaimsPending").value(0))
                .andExpect(jsonPath("$.data.counts.exceptionsPending").value(0))
                .andExpect(jsonPath("$.data.recentOperations").isEmpty());

        regrant(LIMITED, "PLATFORM", null, List.of(PlatformCapability.PLATFORM_ACCOUNT_READ.name(),
                PlatformCapability.PLATFORM_TEACHER_REVIEW.name(), PlatformCapability.PLATFORM_COURSE_GOVERNANCE.name(),
                PlatformCapability.PLATFORM_AUDIT_READ.name()));
        mockMvc.perform(get("/api/v1/admin/dashboard").header("Authorization", bearer(limited)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.recentOperations[0].action").value("ACCOUNT_DISABLED"))
                .andExpect(jsonPath("$.data.recentOperations[0].targetId").value(TARGET))
                .andExpect(jsonPath("$.data.recentOperations[0].result").value("SUCCEEDED"));
    }

    @Test
    void auditListOnlyProjectsClassifiedManagementEventsAndWithholdsRawReasons() throws Exception {
        String admin = login(ADMIN_LOGIN, ADMIN_PASSWORD);
        String applicationId = pendingTeacherApplication();
        String grantId = grantIdOf(ADMIN);
        String grantAuditId = jdbc.queryForObject(
                "SELECT id FROM audit_event WHERE action = 'SUPER_ADMIN_GRANTED' AND resource_id = ?",
                String.class, grantId);

        audit("audit_p66_privacy_1", "ACCOUNT_DISABLED", "USER", TARGET, ADMIN, "账号处置：疑似共享");
        audit("audit_p66_privacy_2", "REGISTRATION_APPROVE", "REGISTRATION", applicationId, ADMIN, SENSITIVE);
        audit("audit_p66_privacy_3", "SUPPORT_VIEW", "SUPPORT_TICKET", "ticket_p66_privacy", ADMIN, SENSITIVE);
        audit("audit_p66_privacy_4", "PERSONNEL_CASE_GRANT", "PERSONNEL_CASE", "case_p66_privacy", ADMIN, PERSONNEL);
        audit("audit_p66_privacy_5", "AUTH_LOGIN", "USER", TARGET, TARGET, "");
        audit("audit_p66_privacy_6", "UNKNOWN_ADMIN_ACTION", "UNKNOWN", "unknown_p66_privacy", ADMIN, SENSITIVE);

        MvcResult result = mockMvc.perform(get("/api/v1/admin/audit-logs").header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(3))
                .andReturn();
        JsonNode items = data(result).get("items");
        assertThat(items).hasSize(3);
        List<String> ids = new ArrayList<>();
        items.forEach(item -> ids.add(item.get("id").asText()));
        assertThat(ids).containsExactlyInAnyOrder("audit_p66_privacy_1", "audit_p66_privacy_2", grantAuditId);

        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain(SENSITIVE).doesNotContain(PERSONNEL)
                .doesNotContain("SUPPORT_VIEW").doesNotContain("SUPPORT_TICKET")
                .doesNotContain("PERSONNEL_CASE").doesNotContain("AUTH_LOGIN")
                .doesNotContain("UNKNOWN_ADMIN_ACTION").doesNotContain("ticket_p66_privacy");

        JsonNode account = item(items, "audit_p66_privacy_1");
        assertThat(account.get("action").asText()).isEqualTo("ACCOUNT_DISABLED");
        assertThat(account.get("result").asText()).isEqualTo("SUCCEEDED");
        assertThat(account.get("reason").asText()).isEqualTo("账号处置：疑似共享");
        assertThat(account.get("scope").asText()).isEqualTo(SCHOOL_A);
        assertThat(account.get("actorId").asText()).isEqualTo(ADMIN);
        assertThat(account.get("targetType").asText()).isEqualTo("USER");
        assertThat(account.get("targetId").asText()).isEqualTo(TARGET);
        assertThat(account.get("createdAt").asText()).isNotEmpty();

        // 已登记的教师申请事件保留真实动作映射与真实范围，但理由默认不返回原文。
        JsonNode application = item(items, "audit_p66_privacy_2");
        assertThat(application.get("action").asText()).isEqualTo("TEACHER_APPLICATION_APPROVED");
        assertThat(application.get("targetType").asText()).isEqualTo("TEACHER_APPLICATION");
        assertThat(application.get("scope").asText()).isEqualTo(SCHOOL_A);
        assertThat(application.get("reason").asText()).isEmpty();

        JsonNode grant = item(items, grantAuditId);
        assertThat(grant.get("scope").asText()).isEqualTo("PLATFORM");
        assertThat(grant.get("actorId").asText()).isEqualTo(ADMIN);
        assertThat(grant.get("actorRole").asText()).isEqualTo("SUPER_ADMIN");
    }

    @Test
    void auditScopeFollowsTheRealTargetInsteadOfTheActorSchool() throws Exception {
        regrant(LIMITED, "SCHOOL", SCHOOL_A, PlatformCapability.ALL);
        regrant(USER_B, "SCHOOL", SCHOOL_B, PlatformCapability.ALL);
        String grantId = grantIdOf(ADMIN);
        String grantAuditId = jdbc.queryForObject(
                "SELECT id FROM audit_event WHERE action = 'SUPER_ADMIN_GRANTED' AND resource_id = ?",
                String.class, grantId);

        // 目标在第二学校，但操作者属于第一学校：第一学校管理员不应因为“同校操作者”而看到它。
        audit("audit_p66_scope_target_b", "ACCOUNT_DISABLED", "USER", USER_B, LIMITED, "跨校账号处置");
        // 目标在第一学校，操作者来自第二学校：第二学校管理员按真实目标范围可见。
        audit("audit_p66_scope_target_a", "ACCOUNT_DISABLED", "USER", TARGET, USER_B, "第二学校处置");

        MvcResult firstSchool = mockMvc.perform(get("/api/v1/admin/audit-logs")
                        .header("Authorization", bearer(login(LIMITED_LOGIN, LIMITED_PASSWORD))))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(ids(firstSchool)).containsExactly("audit_p66_scope_target_a");
        assertThat(data(firstSchool).get("total").asInt()).isEqualTo(1);

        MvcResult secondSchool = mockMvc.perform(get("/api/v1/admin/audit-logs")
                        .header("Authorization", bearer(login(USER_B_LOGIN, USER_B_PASSWORD))))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(ids(secondSchool)).containsExactly("audit_p66_scope_target_b");
        assertThat(data(secondSchool).get("total").asInt()).isEqualTo(1);

        // 全平台管理员既看到全部真实范围事件，也看到全平台授权事件本身。
        MvcResult platform = mockMvc.perform(get("/api/v1/admin/audit-logs")
                        .header("Authorization", bearer(login(ADMIN_LOGIN, ADMIN_PASSWORD))))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(ids(platform)).containsExactlyInAnyOrder(
                "audit_p66_scope_target_a", "audit_p66_scope_target_b", grantAuditId);
    }

    @Test
    void accountDetailOnlyShowsManagementEventsOfThatAccount() throws Exception {
        String admin = login(ADMIN_LOGIN, ADMIN_PASSWORD);
        audit("audit_p66_detail_login", "AUTH_LOGIN", "USER", TARGET, TARGET, "");
        audit("audit_p66_detail_disable", "ACCOUNT_DISABLED", "USER", TARGET, ADMIN, "账号处置：停用");

        MvcResult result = mockMvc.perform(get("/api/v1/admin/users/{userId}", TARGET)
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode records = data(result).get("managementRecords");
        assertThat(records).hasSize(1);
        assertThat(records.get(0).get("id").asText()).isEqualTo("audit_p66_detail_disable");
        assertThat(records.get(0).get("action").asText()).isEqualTo("ACCOUNT_DISABLED");
        assertThat(records.get(0).get("result").asText()).isEqualTo("SUCCEEDED");
    }

    @Test
    void auditPagingCountsOnlyVisibleEventsAndForeignCursorsCannotBypassTheProjection() throws Exception {
        String admin = login(ADMIN_LOGIN, ADMIN_PASSWORD);
        for (int index = 1; index <= 3; index++) {
            audit("audit_p66_page_" + index, "ACCOUNT_RESTORED", "USER", TARGET, ADMIN, "第" + index + "次恢复");
        }
        audit("audit_p66_page_hidden", "SUPPORT_VIEW", "SUPPORT_TICKET", "ticket_p66_page", ADMIN, SENSITIVE);

        MvcResult first = mockMvc.perform(get("/api/v1/admin/audit-logs").param("limit", "2")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(4))
                .andExpect(jsonPath("$.data.hasMore").value(true))
                .andReturn();
        String cursor = data(first).get("nextCursor").asText();
        assertThat(cursor).isNotEmpty();

        MvcResult second = mockMvc.perform(get("/api/v1/admin/audit-logs").param("limit", "2")
                        .param("cursor", cursor).header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(4))
                .andReturn();
        assertThat(data(second).get("items")).hasSize(2);
        List<String> seen = new ArrayList<>(ids(first));
        seen.addAll(ids(second));
        assertThat(seen).hasSize(4).doesNotContain("audit_p66_page_hidden");

        // 其他列表的游标作用域不同，不能拿来按 ID 加载绕过审计投影。
        MvcResult users = mockMvc.perform(get("/api/v1/admin/users").param("limit", "1")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andReturn();
        String foreign = data(users).get("nextCursor").asText();
        assertThat(foreign).isNotEmpty();
        mockMvc.perform(get("/api/v1/admin/audit-logs").param("cursor", foreign)
                        .header("Authorization", bearer(admin)))
                .andExpect(status().is4xxClientError());
    }

    /**
     * S1：A03 的恢复资格必须是目标事实，且与 A04 的写入口径一致。
     * 只有“管理停用 + disabled + ADMIN_DISABLED”为 true，其它停用来源与待审/驳回均 false。
     */
    @Test
    void accountDetailExposesRestorationEligibilityFromTheSameWritePredicate() throws Exception {
        String admin = login(ADMIN_LOGIN, ADMIN_PASSWORD);

        setAccount(TARGET, "DISABLED", true, "ADMIN_DISABLED");
        assertEligibility(admin, true, "DISABLED");

        setAccount(TARGET, "DISABLED", true, "IMPORT_DISABLED");
        assertEligibility(admin, false, "DISABLED");

        setAccount(TARGET, "DISABLED", true, "LEGACY_DISABLED_UNCLEAR");
        assertEligibility(admin, false, "DISABLED");

        setAccount(TARGET, "PENDING_REVIEW", false, "VERIFIED_ACTIVE");
        assertEligibility(admin, false, "PENDING_REVIEW");

        setAccount(TARGET, "REJECTED", false, "REGISTRATION_REJECTED");
        assertEligibility(admin, false, "REJECTED");
    }

    /**
     * S1：账号详情里的 grants/teachingCourses 和摘要计数都按课程真实 school_id 过滤。
     * 账号属于学校 A、但课程关系跨到学校 B 时，学校范围操作者不能借详情读到 B 的课程。
     */
    @Test
    void accountDetailScopesCourseRelationsToVisibleSchools() throws Exception {
        seedSquare(SCHOOL_A, "term_scope_a", "square_scope_a", "SCOPE-A", "范围内课程", 971);
        seedSquare(SCHOOL_B, "term_scope_b", "square_scope_b", "SCOPE-B", "跨校隐藏课程", 972);
        assignTeacher(TARGET, "square_scope_a");
        assignTeacher(TARGET, "square_scope_b");
        grantAssistant(TARGET, "square_scope_a");
        grantAssistant(TARGET, "square_scope_b");

        String platform = login(ADMIN_LOGIN, ADMIN_PASSWORD);
        JsonNode all = accountDetail(platform, TARGET);
        assertThat(squareIds(all.get("grants"))).containsExactlyInAnyOrder("square_scope_a", "square_scope_b");
        assertThat(squareIds(all.get("teachingCourses")))
                .containsExactlyInAnyOrder("square_scope_a", "square_scope_b");
        assertThat(all.get("user").get("summary").asText()).contains("2门课程有助理授权").contains("负责2门课程");

        regrant(LIMITED, "SCHOOL", SCHOOL_A, List.of(PlatformCapability.PLATFORM_ACCOUNT_READ.name()));
        String scoped = login(LIMITED_LOGIN, LIMITED_PASSWORD);
        MvcResult scopedResult = mockMvc.perform(get("/api/v1/admin/users/{userId}", TARGET)
                        .header("Authorization", bearer(scoped)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode scopedDetail = data(scopedResult);
        assertThat(squareIds(scopedDetail.get("grants"))).containsExactly("square_scope_a");
        assertThat(squareIds(scopedDetail.get("teachingCourses"))).containsExactly("square_scope_a");
        assertThat(scopedDetail.get("user").get("summary").asText()).isEqualTo("1门课程有助理授权，负责1门课程");
        assertThat(scopedResult.getResponse().getContentAsString())
                .doesNotContain("跨校隐藏课程").doesNotContain("SCOPE-B");
    }

    /**
     * S1：A14 目录只对全平台范围授权开放；同一用户的多条历史授权只出现一次，
     * 优先当前 ACTIVE 授权，无当前授权时保留最近一条撤销记录。
     *
     * <p>本轮更正：正向夹具必须是真正完整的授权（角色、能力、范围、账号资格齐全），
     * 只插一条 ACTIVE 授权头不再是合法的一档。
     */
    @Test
    void superAdminDirectoryRequiresPlatformScopeAndCollapsesGrantHistory() throws Exception {
        regrant(LIMITED, "SCHOOL", SCHOOL_A, List.of(PlatformCapability.PLATFORM_SUPER_ADMIN_MANAGE.name()));
        String scoped = login(LIMITED_LOGIN, LIMITED_PASSWORD);
        mockMvc.perform(get("/api/v1/admin/super-admins").header("Authorization", bearer(scoped)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value(containsString("全平台范围")));

        // 同一用户两条撤销记录：目录只显示最近一条。
        insertRevokedGrant("grant_privacy_revoked_old", USER_B, "2026-01-01T00:00:00Z",
                "2026-01-02T00:00:00Z");
        insertRevokedGrant("grant_privacy_revoked_new", USER_B, "2026-02-01T00:00:00Z",
                "2026-02-02T00:00:00Z");
        // 同一用户先撤销后重授：目录只显示当前 ACTIVE 授权，不重复出现。
        insertRevokedGrant("grant_privacy_target_old", TARGET, "2026-01-05T00:00:00Z",
                "2026-01-06T00:00:00Z");
        superAdmins.grantInitial(ADMIN, TARGET, "目录重授完整授权夹具", false);
        // 只调整测试库中的 granted_at 以稳定排序断言，角色/能力/范围保持完整授权创建时的状态。
        jdbc.update("UPDATE platform_admin_grant SET granted_at = '2026-03-01T00:00:00Z' "
                + "WHERE user_id = ? AND status = 'ACTIVE'", TARGET);

        String admin = login(ADMIN_LOGIN, ADMIN_PASSWORD);
        MvcResult result = mockMvc.perform(get("/api/v1/admin/super-admins")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode items = data(result).get("items");
        // 目录是“所有当前/历史授权”的现态视图：全平台和学校范围授权都列出，但每用户一条。
        assertThat(items).hasSize(4);
        assertThat(countDirectoryRows(items, TARGET)).isEqualTo(1);
        JsonNode target = itemByUserId(items, TARGET);
        assertThat(target.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(target.get("grantedAt").asText()).startsWith("2026-03-01");
        // 版本来自本次授权，而不是历史撤销记录。
        assertThat(target.get("revision").asInt()).isEqualTo(1);
        JsonNode revoked = itemByUserId(items, USER_B);
        assertThat(revoked.get("status").asText()).isEqualTo("REVOKED");
        assertThat(revoked.get("grantedAt").asText()).startsWith("2026-02-01");
        assertThat(itemByUserId(items, ADMIN).get("status").asText()).isEqualTo("ACTIVE");
        JsonNode schoolScoped = itemByUserId(items, LIMITED);
        assertThat(schoolScoped.get("status").asText()).isEqualTo("ACTIVE");
    }

    /**
     * R1：保留 ACTIVE 授权但缺 SUPER_ADMIN 角色的残缺记录不再投影成 ACTIVE；
     * 整份目录明确不可就绪，而不是输出一份看似完整的成员列表。同账号用自己的普通会话
     * 访问管理接口仍然 403，目录状态与真实治理资格保持一致。
     */
    @Test
    void superAdminDirectoryRefusesActiveGrantWithoutTheSuperAdminRole() throws Exception {
        grantToTarget();
        jdbc.update("DELETE FROM user_role WHERE user_id = ? AND role = 'SUPER_ADMIN'", TARGET);

        assertDirectoryUnavailable();

        String target = login("student_demo_01", "demo-password-01");
        mockMvc.perform(get("/api/v1/admin/users").header("Authorization", bearer(target)))
                .andExpect(status().isForbidden());
    }

    /**
     * R1：能力与范围必须来自当前选中的同一授权行。历史撤销授权上的残留能力/范围
     * 不能替当前残缺授权补足资格。
     */
    @Test
    void superAdminDirectoryChecksCapabilityAndScopeOnTheSelectedGrantOnly() throws Exception {
        grantToTarget();
        String activeGrant = activeGrantId(TARGET);
        jdbc.update("INSERT INTO platform_admin_capability (grant_id, capability) "
                + "VALUES ('grant_privacy_target_old', 'PLATFORM_SUPER_ADMIN_MANAGE') ON CONFLICT DO NOTHING");
        jdbc.update("INSERT INTO platform_admin_scope (grant_id, scope_type, school_id) "
                + "VALUES ('grant_privacy_target_old', 'PLATFORM', NULL) ON CONFLICT DO NOTHING");

        // 当前授权没有能力：历史授权的能力不能借过来。
        jdbc.update("DELETE FROM platform_admin_capability WHERE grant_id = ?", activeGrant);
        assertDirectoryUnavailable();

        // 恢复能力，只保留“缺范围”的残缺。
        jdbc.update("INSERT INTO platform_admin_capability (grant_id, capability) VALUES (?, ?)",
                activeGrant, PlatformCapability.PLATFORM_SUPER_ADMIN_MANAGE.name());
        jdbc.update("DELETE FROM platform_admin_scope WHERE grant_id = ?", activeGrant);
        assertDirectoryUnavailable();
    }

    /**
     * R1：身份、资格、凭据与账号状态的异常都不能被投影成 ACTIVE。待审/驳回不是“停用”，
     * 同属资格异常，本方案统一返回不可就绪。
     */
    @ParameterizedTest
    @EnumSource(IneligibleAccountFact.class)
    void superAdminDirectoryRefusesActiveGrantWithDegradedAccountFacts(IneligibleAccountFact fact)
            throws Exception {
        grantToTarget();
        fact.apply(jdbc, TARGET);
        assertDirectoryUnavailable();
    }

    /** R1：真实停用仍显示 DISABLED，并保持原有登录拒绝行为。 */
    @Test
    void superAdminDirectoryShowsTrulyDisabledAccountAsDisabled() throws Exception {
        grantToTarget();
        jdbc.update("UPDATE app_user SET account_status = 'DISABLED', disabled = true WHERE id = ?", TARGET);

        String admin = login(ADMIN_LOGIN, ADMIN_PASSWORD);
        JsonNode items = data(mockMvc.perform(get("/api/v1/admin/super-admins")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andReturn()).get("items");
        assertThat(itemByUserId(items, TARGET).get("status").asText()).isEqualTo("DISABLED");
        assertThat(itemByUserId(items, ADMIN).get("status").asText()).isEqualTo("ACTIVE");

        mockMvc.perform(post("/api/v1/auth/sessions").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "loginId", "student_demo_01", "password", "demo-password-01"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_DISABLED"));
    }

    /** R1：合法撤销本来就可能移除角色，历史行仍按真实撤销状态显示 REVOKED。 */
    @Test
    void superAdminDirectoryKeepsRevokedHistoryWhenTheRoleIsAlreadyGone() throws Exception {
        insertRevokedGrant("grant_privacy_target_old", TARGET, "2026-01-05T00:00:00Z",
                "2026-01-06T00:00:00Z");
        jdbc.update("DELETE FROM user_role WHERE user_id = ? AND role = 'SUPER_ADMIN'", TARGET);

        String admin = login(ADMIN_LOGIN, ADMIN_PASSWORD);
        JsonNode items = data(mockMvc.perform(get("/api/v1/admin/super-admins")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andReturn()).get("items");
        assertThat(itemByUserId(items, TARGET).get("status").asText()).isEqualTo("REVOKED");
        assertThat(itemByUserId(items, TARGET).get("grantedAt").asText()).startsWith("2026-01-05");
    }

    /** R1：目录规模限制保持原错误行为：不截断，也不把残缺记录粉饰成 ACTIVE。 */
    @Test
    void superAdminDirectoryKeepsTheScaleLimitWithoutTruncatingOrFakingActive() throws Exception {
        insertBulkActiveGrants(201);

        String admin = login(ADMIN_LOGIN, ADMIN_PASSWORD);
        mockMvc.perform(get("/api/v1/admin/super-admins").header("Authorization", bearer(admin)))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("COLLECTION_TOO_LARGE"));
    }

    /** 给 TARGET 建立一条完整授权，并保留一条历史撤销记录用于“历史元数据不能补当前授权”。 */
    private void grantToTarget() {
        insertRevokedGrant("grant_privacy_target_old", TARGET, "2026-01-05T00:00:00Z",
                "2026-01-06T00:00:00Z");
        superAdmins.grantInitial(ADMIN, TARGET, "目录完整授权夹具", false);
    }

    private String activeGrantId(String userId) {
        return jdbc.queryForObject("""
                SELECT id FROM platform_admin_grant WHERE user_id = ? AND status = 'ACTIVE'
                """, String.class, userId);
    }

    /** 残缺授权让整份目录不可就绪：503、标准错误外壳、不返回成员列表。 */
    private void assertDirectoryUnavailable() throws Exception {
        String admin = login(ADMIN_LOGIN, ADMIN_PASSWORD);
        MvcResult result = mockMvc.perform(get("/api/v1/admin/super-admins")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value(containsString("待核对")))
                .andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("items");
    }

    private void insertBulkActiveGrants(int count) {
        jdbc.update("""
                INSERT INTO app_user (id, school_id, login_id, display_name, password_hash, identity_status)
                SELECT 'user_p71_bulk_' || g, ?, 'p71_bulk_' || g, '批量账号' || g, 'x', 'VERIFIED'
                FROM generate_series(1, ?) AS g
                """, SCHOOL_A, count);
        jdbc.update("""
                INSERT INTO platform_admin_grant (id, user_id, status, granted_by, granted_at, revision)
                SELECT 'grant_p71_bulk_' || g, 'user_p71_bulk_' || g, 'ACTIVE', 'cli', now(), 1
                FROM generate_series(1, ?) AS g
                """, count);
    }

    private static long countDirectoryRows(JsonNode items, String userId) {
        long count = 0;
        for (JsonNode item : items) {
            if (userId.equals(item.get("userId").asText())) {
                count++;
            }
        }
        return count;
    }

    /** 只保留 ACTIVE 授权、但账号侧资格事实被破坏的几类形态。 */
    enum IneligibleAccountFact {
        IDENTITY_PENDING("UPDATE app_user SET identity_status = 'PENDING' WHERE id = ?"),
        IDENTITY_REJECTED("UPDATE app_user SET identity_status = 'REJECTED' WHERE id = ?"),
        ELIGIBILITY_ADMIN_DISABLED("UPDATE app_user SET eligibility_source = 'ADMIN_DISABLED' WHERE id = ?"),
        ELIGIBILITY_REGISTRATION_PENDING(
                "UPDATE app_user SET eligibility_source = 'REGISTRATION_PENDING' WHERE id = ?"),
        CREDENTIAL_MISSING("UPDATE app_user SET password_hash = '' WHERE id = ?"),
        ACCOUNT_PENDING_REVIEW("UPDATE app_user SET account_status = 'PENDING_REVIEW' WHERE id = ?"),
        ACCOUNT_REJECTED("UPDATE app_user SET account_status = 'REJECTED' WHERE id = ?");

        private final String sql;

        IneligibleAccountFact(String sql) {
            this.sql = sql;
        }

        void apply(JdbcTemplate jdbc, String userId) {
            jdbc.update(sql, userId);
        }
    }

    /**
     * S1：续页必须按当前范围复核。学校范围操作者的旧游标在账号转出本范围后
     * 不能继续返回旧 total 或越权行，返回 CURSOR_EXPIRED 让客户端重读。
     */
    @Test
    void staleUserCursorExpiresWhenAccountLeavesTheCurrentScope() throws Exception {
        regrant(LIMITED, "SCHOOL", SCHOOL_A, List.of(PlatformCapability.PLATFORM_ACCOUNT_READ.name()));
        String scoped = login(LIMITED_LOGIN, LIMITED_PASSWORD);
        MvcResult first = mockMvc.perform(get("/api/v1/admin/users").param("limit", "2")
                        .header("Authorization", bearer(scoped)))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(data(first).get("total").asInt()).isEqualTo(5);
        String cursor = data(first).get("nextCursor").asText();
        assertThat(cursor).isNotEmpty();

        jdbc.update("UPDATE app_user SET school_id = ? WHERE id = ?", SCHOOL_B, TARGET);
        mockMvc.perform(get("/api/v1/admin/users").param("limit", "2").param("cursor", cursor)
                        .header("Authorization", bearer(scoped)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CURSOR_EXPIRED"));

        MvcResult reread = mockMvc.perform(get("/api/v1/admin/users").param("limit", "50")
                        .header("Authorization", bearer(scoped)))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(data(reread).get("total").asInt()).isEqualTo(4);
        assertThat(ids(reread)).doesNotContain(TARGET);
    }

    private void assertEligibility(String token, boolean expected, String status) throws Exception {
        mockMvc.perform(get("/api/v1/admin/users/{userId}", TARGET)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user.accountStatus").value(status))
                .andExpect(jsonPath("$.data.user.restorationEligible").value(expected));
    }

    private JsonNode accountDetail(String token, String userId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/admin/users/{userId}", userId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn();
        return data(result);
    }

    private void setAccount(String userId, String status, boolean disabled, String eligibility) {
        jdbc.update("UPDATE app_user SET account_status = ?, disabled = ?, eligibility_source = ? WHERE id = ?",
                status, disabled, eligibility, userId);
    }

    private void seedSquare(String schoolId, String termId, String squareId, String squareNo, String name,
                            int ordinal) {
        jdbc.update("""
                INSERT INTO term (id, school_id, name, start_date, end_date, ordinal)
                VALUES (?, ?, ?, DATE '2026-09-01', DATE '2027-01-15', ?)
                """, termId, schoolId, "P66 范围学期 " + ordinal, ordinal);
        jdbc.update("""
                INSERT INTO square (id, school_id, term_id, name, teacher_name, slots_json, canonical_key,
                    square_no, status)
                VALUES (?, ?, ?, ?, '教师', '[]'::jsonb, ?, ?, 'ACTIVE')
                """, squareId, schoolId, termId, name, Hashing.sha256Hex(squareId), squareNo);
    }

    private void assignTeacher(String userId, String squareId) {
        jdbc.update("""
                INSERT INTO teacher_assignment (id, square_id, user_id, active, started_at)
                VALUES (?, ?, ?, true, CURRENT_TIMESTAMP)
                """, "ta_" + squareId, squareId, userId);
    }

    private void grantAssistant(String userId, String squareId) {
        jdbc.update("""
                INSERT INTO class_grant (id, square_id, user_id, capabilities_json, status, expires_at, granted_by)
                VALUES (?, ?, ?, '["ANNOUNCEMENT_WRITE"]'::jsonb, 'ACTIVE', '2099-01-01T00:00:00Z', ?)
                """, "grant_" + squareId, squareId, userId, ADMIN);
    }

    private void insertRevokedGrant(String id, String userId, String grantedAt, String revokedAt) {
        jdbc.update("""
                INSERT INTO platform_admin_grant
                    (id, user_id, status, granted_by, granted_at, revoked_by, revoked_at, revision)
                VALUES (?, ?, 'REVOKED', 'cli', ?::timestamptz, 'cli', ?::timestamptz, 2)
                """, id, userId, grantedAt, revokedAt);
    }

    private static JsonNode itemByUserId(JsonNode items, String userId) {
        for (JsonNode item : items) {
            if (userId.equals(item.get("userId").asText())) {
                return item;
            }
        }
        throw new AssertionError("响应里没有 userId=" + userId + " 的目录项：" + items);
    }

    private static List<String> squareIds(JsonNode rows) {
        List<String> ids = new ArrayList<>();
        rows.forEach(row -> ids.add(row.get("squareId").asText()));
        return ids;
    }

    private String pendingTeacherApplication() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/auth/registrations")
                        .header("Idempotency-Key", "p66-privacy-application")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "loginId", "p66_privacy_applicant",
                                "password", "example-password-123",
                                "displayName", "隐私回归申请人",
                                "schoolId", SCHOOL_A,
                                "requestedRole", "TEACHER",
                                "teacherApplication", SENSITIVE))))
                .andExpect(status().isOk())
                .andReturn();
        return data(created).get("id").asText();
    }

    /**
     * 直接构造平台授权，用于把某个账号放进“只有部分能力”的状态。平台授权服务本身只提供
     * 全平台授予，这里绕开它只是为了覆盖读取侧的按字段检查。
     */
    private void regrant(String userId, String scopeType, String schoolId, List<String> capabilities) {
        jdbc.update("DELETE FROM platform_admin_capability WHERE grant_id IN "
                + "(SELECT id FROM platform_admin_grant WHERE user_id = ?)", userId);
        jdbc.update("DELETE FROM platform_admin_scope WHERE grant_id IN "
                + "(SELECT id FROM platform_admin_grant WHERE user_id = ?)", userId);
        jdbc.update("DELETE FROM platform_admin_grant WHERE user_id = ?", userId);
        String grantId = "grant_p66_privacy_" + userId;
        jdbc.update("""
                INSERT INTO platform_admin_grant (id, user_id, status, granted_by, granted_at, revision)
                VALUES (?, ?, 'ACTIVE', 'cli', CURRENT_TIMESTAMP, 1)
                """, grantId, userId);
        for (String capability : capabilities) {
            jdbc.update("INSERT INTO platform_admin_capability (grant_id, capability) VALUES (?, ?)",
                    grantId, capability);
        }
        if ("PLATFORM".equals(scopeType)) {
            jdbc.update("INSERT INTO platform_admin_scope (grant_id, scope_type, school_id) "
                    + "VALUES (?, 'PLATFORM', NULL)", grantId);
        } else {
            jdbc.update("INSERT INTO platform_admin_scope (grant_id, scope_type, school_id) "
                    + "VALUES (?, 'SCHOOL', ?)", grantId, schoolId);
        }
        jdbc.update("INSERT INTO user_role (user_id, role) VALUES (?, 'SUPER_ADMIN') ON CONFLICT DO NOTHING",
                userId);
    }

    private void audit(String id, String action, String resourceType, String resourceId, String actorId,
                       String reason) {
        jdbc.update("""
                INSERT INTO audit_event (id, actor_id, action, resource_type, resource_id, reason, at)
                VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """, id, actorId, action, resourceType, resourceId, reason);
    }

    private String grantIdOf(String userId) {
        return jdbc.queryForObject("SELECT id FROM platform_admin_grant WHERE user_id = ?", String.class, userId);
    }

    private static JsonNode item(JsonNode items, String id) {
        for (JsonNode item : items) {
            if (id.equals(item.get("id").asText())) {
                return item;
            }
        }
        throw new AssertionError("响应里没有 id=" + id + " 的审计项：" + items);
    }

    private List<String> ids(MvcResult result) throws Exception {
        List<String> ids = new ArrayList<>();
        data(result).get("items").forEach(item -> ids.add(item.get("id").asText()));
        return ids;
    }

    private String login(String loginId, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/sessions").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("loginId", loginId, "password", password))))
                .andExpect(status().isOk()).andReturn();
        return data(result).get("accessToken").asText();
    }

    private JsonNode data(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private void clearAll() {
        TestSchemaCleaner.deleteAll(jdbc, List.of(
                "operation_receipt", "access_token", "auth_session", "audit_event", "rate_window",
                "import_batch", "registration_receipt", "registration_application", "cursor_snapshot",
                "teacher_transfer", "teacher_claim", "teacher_assignment", "class_grant", "course_code",
                "course_join_request", "assignment", "course_membership", "square", "verified_enrollment",
                "verified_course", "course_name", "period", "term", "user_role", "user_profile", "app_user",
                "school"));
    }
}
