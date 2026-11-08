package cn.gulupai.server.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.gulupai.server.it.TestSchemaCleaner;
import cn.gulupai.server.platform.domain.PlatformCapability;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
 * R5 回归：两条回放入口（同幂等键的 authorizationCheck、换键但同 actor+operationId 的
 * existing 分支）都必须按**当前**目标范围核权，并且只核对当前资格与可见范围，
 * 不重放新执行的业务前置条件（原 revision、PENDING/ACTIVE、旧令牌未消费）。
 *
 * <p>授权范围的变化一律用隔离夹具里的 SQL 构造，仓库没有在线改 scope 的接口；
 * 这里只声明它是测试夹具，不宣称存在产品能力。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PlatformReplayScopeP66IT {

    private static final Path FIXTURE = Path.of("admin/fixtures/p01-demo-users.json");
    private static final String SCHOOL_A = "school_demo_01";
    private static final String SCHOOL_B = "school_p66_replay_b";
    private static final String ACTOR = "user_p66_replay_admin";
    private static final String ACTOR_LOGIN = "p66_replay_admin";
    private static final String ACTOR_PASSWORD = "demo-password-07";
    private static final String TARGET = "user_p66_replay_target";
    private static final String OTHER = "user_p66_replay_other";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private cn.gulupai.server.identity.cli.AdminImportService importService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() {
        clearAll();
        importService.importUsers(FIXTURE, false);
        jdbc.update("INSERT INTO school (id, name, timezone) VALUES (?, ?, ?)", SCHOOL_B, "P66 回放隔离学校",
                "Asia/Shanghai");
        user(TARGET, "p66_replay_target", "回放目标");
        user(OTHER, "p66_replay_other", "回放目标二");
        user(ACTOR, ACTOR_LOGIN, "回放操作者");
        regrant(ACTOR, "PLATFORM", null, PlatformCapability.ALL);
        // 目标账号带一个学校范围的授权，撤销后仍可追溯原操作对应的授权记录。
        grant(TARGET, "SCHOOL", SCHOOL_B, PlatformCapability.ALL, 1);
    }

    @Test
    void sameKeyReplayIsRefusedAfterTheOperatorScopeShrinksAndStillWorksWhenRestored() throws Exception {
        String actor = login(ACTOR_LOGIN, ACTOR_PASSWORD);
        String operationId = "op_p66_replay_revoke";
        String key = "p66-replay-revoke";
        String reauth = reauth(actor, operationId, "SUPER_ADMIN_REVOKE", "USER", TARGET, 1, null, null,
                "回放范围回归", null, null, null);
        Map<String, Object> body = Map.of("reason", "回放范围回归", "expectedRevision", 1, "reauthToken", reauth);
        String path = "/api/v1/admin/super-admins/" + TARGET + "/revocations";
        MvcResult committed = write(actor, path, operationId, key, body);
        String receipt = data(committed).toString();
        int audits = auditCount();
        int operations = operationCount();

        // 同键回放：只是读取已提交回执，不重复写业务、审计或操作行。
        MvcResult replay = write(actor, path, operationId, key, body);
        assertThat(data(replay).toString()).isEqualTo(receipt);
        assertThat(auditCount()).isEqualTo(audits);
        assertThat(operationCount()).isEqualTo(operations);

        // 操作者范围收缩到第一学校后，原目标（第二学校）不再可见：两条入口都必须拒绝。
        jdbc.update("UPDATE platform_admin_scope SET scope_type = 'SCHOOL', school_id = ? WHERE grant_id IN "
                + "(SELECT id FROM platform_admin_grant WHERE user_id = ?)", SCHOOL_A, ACTOR);
        mockMvc.perform(post(path)
                        .header("Authorization", bearer(actor))
                        .header("Idempotency-Key", key)
                        .header("X-Operation-Id", operationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        MvcResult otherKey = mockMvc.perform(post(path)
                        .header("Authorization", bearer(actor))
                        .header("Idempotency-Key", key + "-other")
                        .header("X-Operation-Id", operationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isForbidden())
                .andReturn();
        assertThat(otherKey.getResponse().getContentAsString()).doesNotContain("SUPER_ADMIN_REVOKED");

        // 范围恢复后合法回放仍然可读，说明拒绝的原因是当前范围而不是历史状态。
        jdbc.update("UPDATE platform_admin_scope SET school_id = NULL, scope_type = 'PLATFORM' WHERE grant_id IN "
                + "(SELECT id FROM platform_admin_grant WHERE user_id = ?)", ACTOR);
        assertThat(data(write(actor, path, operationId, key, body)).toString()).isEqualTo(receipt);
    }

    @Test
    void revokeReceiptStaysReadableAfterTheTargetIsGrantedAgain() throws Exception {
        String actor = login(ACTOR_LOGIN, ACTOR_PASSWORD);
        String operationId = "op_p66_replay_revoke_twice";
        String key = "p66-replay-revoke-twice";
        String reauth = reauth(actor, operationId, "SUPER_ADMIN_REVOKE", "USER", TARGET, 1, null, null,
                "撤销后重新授予", null, null, null);
        Map<String, Object> body = Map.of("reason", "撤销后重新授予", "expectedRevision", 1, "reauthToken", reauth);
        String path = "/api/v1/admin/super-admins/" + TARGET + "/revocations";
        String receipt = data(write(actor, path, operationId, key, body)).toString();

        // 重新授予一个新授权：旧回执必须仍然按原授权追溯，不能被 findActiveGrant 套到新授权上。
        grant(TARGET, "SCHOOL", SCHOOL_B, PlatformCapability.ALL, 1);
        assertThat(data(write(actor, path, operationId, key, body)).toString()).isEqualTo(receipt);

        // 反向：把操作者的范围也收缩，重新授予不会让旧回执变成可见。
        jdbc.update("UPDATE platform_admin_scope SET scope_type = 'SCHOOL', school_id = ? WHERE grant_id IN "
                + "(SELECT id FROM platform_admin_grant WHERE user_id = ?)", SCHOOL_A, ACTOR);
        mockMvc.perform(post(path)
                        .header("Authorization", bearer(actor))
                        .header("Idempotency-Key", key)
                        .header("X-Operation-Id", operationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isForbidden());
    }

    @Test
    void replayAfterTheTargetRevisionChangedReturnsTheOriginalReceipt() throws Exception {
        String actor = login(ACTOR_LOGIN, ACTOR_PASSWORD);
        String operationId = "op_p66_replay_disable";
        String key = "p66-replay-disable";
        int revision = revision(OTHER);
        String reauth = reauth(actor, operationId, "ACCOUNT_STATUS_CHANGE", "USER", OTHER, revision, null, null,
                "回放版本回归", null, "DISABLED", null);
        Map<String, Object> body = Map.of("status", "DISABLED", "reason", "回放版本回归",
                "expectedRevision", revision, "reauthToken", reauth);
        String path = "/api/v1/admin/users/" + OTHER + "/status-changes";
        MvcResult committed = write(actor, path, operationId, key, body);
        int resultingRevision = data(committed).get("operation").get("resultingRevision").asInt();
        assertThat(data(committed).get("resource").get("status").asText()).isEqualTo("DISABLED");

        // 目标后来又被别的事实改变版本：回执返回的是原操作结果，不是伪造的当前版本。
        jdbc.update("UPDATE app_user SET display_name = ?, revision = revision + 1 WHERE id = ?", "回放目标二已改名", OTHER);
        MvcResult replay = write(actor, path, operationId, key, body);
        assertThat(data(replay).get("resource").get("revision").asInt()).isEqualTo(resultingRevision);
        assertThat(data(replay).get("resource").get("status").asText()).isEqualTo("DISABLED");
        assertThat(data(replay).get("operation").get("status").asText()).isEqualTo("COMMITTED");
    }

    @Test
    void replayWithADifferentBusinessBodyIsRejectedAsKeyReuse() throws Exception {
        String actor = login(ACTOR_LOGIN, ACTOR_PASSWORD);
        String operationId = "op_p66_replay_conflict";
        String key = "p66-replay-conflict";
        int revision = revision(OTHER);
        String reauth = reauth(actor, operationId, "ACCOUNT_STATUS_CHANGE", "USER", OTHER, revision, null, null,
                "摘要回归甲", null, "DISABLED", null);
        String path = "/api/v1/admin/users/" + OTHER + "/status-changes";
        write(actor, path, operationId, key, Map.of("status", "DISABLED", "reason", "摘要回归甲",
                "expectedRevision", revision, "reauthToken", reauth));

        mockMvc.perform(post(path)
                        .header("Authorization", bearer(actor))
                        .header("Idempotency-Key", key)
                        .header("X-Operation-Id", operationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("status", "DISABLED", "reason", "摘要回归乙",
                                "expectedRevision", revision, "reauthToken", reauth))))
                .andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void disabledOperatorCannotReplayACommittedOperation() throws Exception {
        String actor = login(ACTOR_LOGIN, ACTOR_PASSWORD);
        String operationId = "op_p66_replay_disabled_operator";
        String key = "p66-replay-disabled-operator";
        int revision = revision(OTHER);
        String reauth = reauth(actor, operationId, "ACCOUNT_STATUS_CHANGE", "USER", OTHER, revision, null, null,
                "停用后回放", null, "DISABLED", null);
        String path = "/api/v1/admin/users/" + OTHER + "/status-changes";
        Map<String, Object> body = Map.of("status", "DISABLED", "reason", "停用后回放",
                "expectedRevision", revision, "reauthToken", reauth);
        write(actor, path, operationId, key, body);

        jdbc.update("UPDATE app_user SET disabled = true, account_status = 'DISABLED', "
                + "eligibility_source = 'ADMIN_DISABLED' WHERE id = ?", ACTOR);
        mockMvc.perform(post(path)
                        .header("Authorization", bearer(actor))
                        .header("Idempotency-Key", key)
                        .header("X-Operation-Id", operationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().is4xxClientError());
    }

    /**
     * 第二轮 S1：首次授予时操作记录为空，不能因为没有历史 admin_operation 就 404。
     * 首执只按当前目标关系核范围；回放才追溯原授权。
     */
    @Test
    void firstSuperAdminGrantCommitsWithoutAPriorOperationAndReplaysOnce() throws Exception {
        String actor = login(ACTOR_LOGIN, ACTOR_PASSWORD);
        String operationId = "op_p66_first_grant";
        String key = "p66-first-grant";
        String reauth = reauth(actor, operationId, "SUPER_ADMIN_GRANT", "USER", OTHER, null,
                "NO_ACTIVE_GRANT", null, "首位平台管理员", null, null, OTHER);
        Map<String, Object> body = Map.of(
                "targetUserId", OTHER, "reason", "首位平台管理员", "reauthToken", reauth);

        MvcResult committed = write(actor, "/api/v1/admin/super-admins", operationId, key, body);
        String receipt = data(committed).toString();
        assertThat(data(committed).get("operation").get("status").asText()).isEqualTo("COMMITTED");
        assertThat(data(committed).get("operation").get("auditId").asText()).isNotBlank();
        assertThat(data(committed).get("resource").get("id").asText()).isEqualTo(OTHER);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM platform_admin_grant WHERE user_id = ? AND status = 'ACTIVE'",
                Integer.class, OTHER)).isEqualTo(1);
        int audits = auditCount();
        int operations = operationCount();

        // 同键回放只读回执；换键但同 operationId 命中原操作，同样不重复授权、审计或回执写入。
        assertThat(data(write(actor, "/api/v1/admin/super-admins", operationId, key, body)).toString())
                .isEqualTo(receipt);
        assertThat(data(write(actor, "/api/v1/admin/super-admins", operationId, key + "-alias", body)).toString())
                .isEqualTo(receipt);
        assertThat(auditCount()).isEqualTo(audits);
        assertThat(operationCount()).isEqualTo(operations);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM platform_admin_grant WHERE user_id = ?", Integer.class, OTHER)).isEqualTo(1);
    }

    /**
     * 第二轮 S1：首次撤销同样不能要求“原操作已存在”；回放仍按原授权追溯。
     */
    @Test
    void firstSuperAdminRevokeCommitsWithoutAPriorOperation() throws Exception {
        String actor = login(ACTOR_LOGIN, ACTOR_PASSWORD);
        String operationId = "op_p66_first_revoke";
        String key = "p66-first-revoke";
        String path = "/api/v1/admin/super-admins/" + TARGET + "/revocations";
        String reauth = reauth(actor, operationId, "SUPER_ADMIN_REVOKE", "USER", TARGET, 1, null, null,
                "首次撤销不追溯旧操作", null, null, null);
        Map<String, Object> body = Map.of(
                "reason", "首次撤销不追溯旧操作", "expectedRevision", 1, "reauthToken", reauth);

        MvcResult committed = write(actor, path, operationId, key, body);
        String receipt = data(committed).toString();
        assertThat(data(committed).get("operation").get("status").asText()).isEqualTo("COMMITTED");
        assertThat(data(committed).get("resource").get("status").asText()).isEqualTo("REVOKED");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM platform_admin_grant WHERE id = ?", String.class,
                "grant_p66_replay_" + TARGET + "_1")).isEqualTo("REVOKED");

        // 回放仍要找到原 PLATFORM_ADMIN_GRANT；撤销后该行仍存在，范围解析不能因此失效。
        assertThat(data(write(actor, path, operationId, key, body)).toString()).isEqualTo(receipt);
    }

    private void user(String id, String loginId, String displayName) {
        jdbc.update("""
                INSERT INTO app_user (id, school_id, login_id, display_name, password_hash, identity_status)
                VALUES (?, ?, ?, ?, ?, 'VERIFIED')
                """, id, SCHOOL_B, loginId, displayName, passwordEncoder.encode(ACTOR_PASSWORD));
    }

    private void grant(String userId, String scopeType, String schoolId, List<String> capabilities, int revision) {
        String grantId = "grant_p66_replay_" + userId + "_" + revision;
        // 撤销后重新授予时必须产生新的授权行：旧授权行仍要留给原操作追溯。
        Integer existing = jdbc.queryForObject(
                "SELECT count(*) FROM platform_admin_grant WHERE id = ?", Integer.class, grantId);
        if (existing != null && existing > 0) {
            grantId = grantId + "_" + java.util.UUID.randomUUID().toString().substring(0, 8);
        }
        jdbc.update("""
                INSERT INTO platform_admin_grant (id, user_id, status, granted_by, granted_at, revision)
                VALUES (?, ?, 'ACTIVE', 'cli', CURRENT_TIMESTAMP, ?)
                """, grantId, userId, revision);
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

    private void regrant(String userId, String scopeType, String schoolId, List<String> capabilities) {
        jdbc.update("DELETE FROM platform_admin_capability WHERE grant_id IN "
                + "(SELECT id FROM platform_admin_grant WHERE user_id = ?)", userId);
        jdbc.update("DELETE FROM platform_admin_scope WHERE grant_id IN "
                + "(SELECT id FROM platform_admin_grant WHERE user_id = ?)", userId);
        jdbc.update("DELETE FROM platform_admin_grant WHERE user_id = ?", userId);
        grant(userId, scopeType, schoolId, capabilities, 1);
    }

    private int revision(String userId) {
        return jdbc.queryForObject("SELECT revision FROM app_user WHERE id = ?", Integer.class, userId);
    }

    private int auditCount() {
        return jdbc.queryForObject("SELECT count(*) FROM audit_event", Integer.class);
    }

    private int operationCount() {
        return jdbc.queryForObject("SELECT count(*) FROM admin_operation", Integer.class);
    }

    private String login(String loginId, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/sessions").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("loginId", loginId, "password", password))))
                .andExpect(status().isOk()).andReturn();
        return data(result).get("accessToken").asText();
    }

    private String reauth(String token, String operationId, String action, String targetType, String targetId,
                          Integer expectedRevision, String precondition, String decision, String reason, String note,
                          String status, String targetUserId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/reauthentications")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reauthBody(operationId, action, targetType,
                                targetId, expectedRevision, precondition, decision, reason, note, status,
                                targetUserId))))
                .andExpect(status().isOk()).andReturn();
        return data(result).get("reauthToken").asText();
    }

    private Map<String, Object> reauthBody(String operationId, String action, String targetType, String targetId,
                                          Integer expectedRevision, String precondition, String decision,
                                          String reason, String note, String status, String targetUserId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("password", ACTOR_PASSWORD);
        body.put("operationId", operationId);
        body.put("action", action);
        body.put("targetType", targetType);
        body.put("targetId", targetId);
        if (expectedRevision != null) {
            body.put("expectedRevision", expectedRevision);
        }
        if (precondition != null) {
            body.put("precondition", precondition);
        }
        if (decision != null) {
            body.put("decision", decision);
        }
        if (reason != null) {
            body.put("reason", reason);
        }
        if (note != null) {
            body.put("note", note);
        }
        if (status != null) {
            body.put("status", status);
        }
        if (targetUserId != null) {
            body.put("targetUserId", targetUserId);
        }
        return body;
    }

    private MvcResult write(String token, String path, String operationId, String key, Map<String, Object> body)
            throws Exception {
        return mockMvc.perform(post(path)
                        .header("Authorization", bearer(token))
                        .header("Idempotency-Key", key)
                        .header("X-Operation-Id", operationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk()).andReturn();
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
