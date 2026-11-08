package cn.gulupai.server.platform;

import cn.gulupai.server.common.api.ApiException;
import cn.gulupai.server.identity.cli.AdminImportService;
import cn.gulupai.server.it.TestSchemaCleaner;
import cn.gulupai.server.platform.application.PlatformSuperAdminService;
import cn.gulupai.server.platform.persistence.PlatformRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * R2 回归：用户导入只能重建普通业务角色，不能成为删除平台角色、绕过停用或隐式换密的入口；
 * 初始化遇到残缺授权必须显式冲突，不能假报 already-active。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PlatformImportP66IT {

    private static final String ADMIN = "user_demo_teacher_01";
    private static final String ADMIN_LOGIN = "teacher_demo_01";
    private static final String ADMIN_PASSWORD = "demo-password-03";
    private static final Path FIXTURE = Path.of("admin/fixtures/p01-demo-users.json");

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AdminImportService importService;
    @Autowired private PlatformSuperAdminService superAdmins;
    @Autowired private PlatformRepository repository;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        clearAll();
        importService.importUsers(FIXTURE, false);
        superAdmins.grantInitial(ADMIN, ADMIN, "隔离环境首位管理员", false);
        // 断言只关心“失败导入有没有新增批次”，先移除 setUp 自己产生的批次记录。
        jdbc.update("DELETE FROM import_batch");
    }

    @Test
    void reimportOfTheOnlyPlatformAdminKeepsRoleAndExistingSession() throws Exception {
        String token = login(ADMIN_LOGIN, ADMIN_PASSWORD);
        String grantId = repository.findActiveGrant(ADMIN).orElseThrow().id();

        importService.importUsers(mutate(body -> user(body, ADMIN).put("displayName", "示例教师（改名）")), false);

        assertThat(roles(ADMIN)).contains("SUPER_ADMIN", "TEACHER");
        assertThat(text("SELECT display_name FROM app_user WHERE id = ?", ADMIN)).isEqualTo("示例教师（改名）");
        assertThat(number("SELECT count(*) FROM platform_admin_grant WHERE user_id = ? AND status = 'ACTIVE'",
                ADMIN)).isEqualTo(1);
        assertThat(repository.findActiveGrant(ADMIN).orElseThrow().id()).isEqualTo(grantId);
        // 没有发生凭据或资格变化，导入不应无故撤销既有会话。
        mockMvc.perform(get("/api/v1/admin/dashboard").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/admin/super-admins").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void importFileCannotDeclareSuperAdmin() {
        Path file = mutate(body -> {
            ObjectNode student = user(body, "user_demo_student_01");
            ArrayNode declared = student.putArray("roles");
            declared.add("STUDENT");
            declared.add("SUPER_ADMIN");
        });

        assertThatThrownBy(() -> importService.importUsers(file, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能通过导入获得 SUPER_ADMIN");
        assertThat(number("SELECT count(*) FROM import_batch")).isZero();
    }

    @Test
    void importCannotDisableTheLastPlatformAdmin() {
        Path file = mutate(body -> user(body, ADMIN).put("disabled", true));

        assertThatThrownBy(() -> importService.importUsers(file, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能改变停用状态");
        assertThat(text("SELECT account_status FROM app_user WHERE id = ?", ADMIN)).isEqualTo("ACTIVE");
        assertThat(booleanValue("SELECT disabled FROM app_user WHERE id = ?", ADMIN)).isFalse();
        assertThat(roles(ADMIN)).contains("SUPER_ADMIN");
        assertThat(number("SELECT count(*) FROM import_batch")).isZero();
    }

    @Test
    void importCannotChangeThePasswordOfAPlatformAdmin() throws Exception {
        Path file = mutate(body -> user(body, ADMIN).put("password", "another-password-99"));

        assertThatThrownBy(() -> importService.importUsers(file, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能修改口令");
        assertThat(login(ADMIN_LOGIN, ADMIN_PASSWORD)).isNotBlank();
        assertThat(number("SELECT count(*) FROM import_batch")).isZero();
    }

    @Test
    void importCannotFlipTheIdentityStatusOfAPlatformAdmin() {
        Path file = mutate(body -> user(body, ADMIN).put("identityStatus", "PENDING"));

        assertThatThrownBy(() -> importService.importUsers(file, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能改变身份核验状态");
        assertThat(text("SELECT identity_status FROM app_user WHERE id = ?", ADMIN)).isEqualTo("VERIFIED");
    }

    @Test
    void dryRunReportsTheSameSensitiveRejectionWithoutWriting() {
        Path file = mutate(body -> user(body, ADMIN).put("displayName", "不应写入").put("disabled", true));

        assertThatThrownBy(() -> importService.importUsers(file, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能改变停用状态");
        assertThat(text("SELECT display_name FROM app_user WHERE id = ?", ADMIN)).isEqualTo("示例教师");
        assertThat(number("SELECT count(*) FROM import_batch")).isZero();
    }

    @Test
    void grantInitialReportsConflictWhenTheActiveGrantLostItsRole() {
        jdbc.update("DELETE FROM user_role WHERE user_id = ? AND role = 'SUPER_ADMIN'", ADMIN);
        assertThat(repository.countPlatformRecoveryAdmins()).isZero();

        assertThatThrownBy(() -> superAdmins.grantInitial(ADMIN, ADMIN, "重试初始化", false))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("人工受控修复");
        assertThat(number("SELECT count(*) FROM platform_admin_grant WHERE user_id = ? AND status = 'ACTIVE'",
                ADMIN)).isEqualTo(1);
        assertThat(number("SELECT count(*) FROM platform_admin_capability")).isEqualTo(7);
    }

    @Test
    void orphanSuperAdminRoleIsNotRecoverableAndInitializationMakesItConsistent() {
        clearAll();
        importService.importUsers(FIXTURE, false);
        // 隔离夹具：模拟 V031 之前遗留、还没有平台授权的 SUPER_ADMIN 角色。
        jdbc.update("INSERT INTO user_role (user_id, role) VALUES (?, 'SUPER_ADMIN')", ADMIN);
        assertThat(repository.countPlatformRecoveryAdmins()).isZero();

        var result = superAdmins.grantInitial(ADMIN, ADMIN, "遗留角色补齐平台授权", false);

        assertThat(result.alreadyActive()).isFalse();
        assertThat(result.grantId()).isNotBlank();
        assertThat(number("SELECT count(*) FROM user_role WHERE user_id = ? AND role = 'SUPER_ADMIN'", ADMIN))
                .isEqualTo(1);
        assertThat(repository.countPlatformRecoveryAdmins()).isEqualTo(1);
    }

    @Test
    void repeatedInitializationKeepsTheSameGrantId() {
        var first = superAdmins.grantInitial(ADMIN, ADMIN, "重复初始化", false);
        var second = superAdmins.grantInitial(ADMIN, ADMIN, "重复初始化", false);

        assertThat(second.alreadyActive()).isTrue();
        assertThat(second.grantId()).isEqualTo(first.grantId());
        assertThat(number("SELECT count(*) FROM platform_admin_grant")).isEqualTo(1);
        assertThat(number("SELECT count(*) FROM platform_admin_capability")).isEqualTo(7);
    }

    private Path mutate(Consumer<ObjectNode> change) {
        try {
            ObjectNode body = (ObjectNode) objectMapper.readTree(Files.readAllBytes(FIXTURE));
            change.accept(body);
            Path file = Files.createTempFile(tempDir, "p66-import-", ".json");
            Files.writeString(file, objectMapper.writeValueAsString(body), StandardCharsets.UTF_8);
            return file;
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static ObjectNode user(ObjectNode body, String userId) {
        for (var node : body.withArray("users")) {
            if (userId.equals(node.get("id").asText())) {
                return (ObjectNode) node;
            }
        }
        throw new IllegalArgumentException("夹具里没有用户 " + userId);
    }

    private List<String> roles(String userId) {
        return repository.rolesOf(userId);
    }

    private String text(String sql, Object... args) {
        return jdbc.queryForObject(sql, String.class, args);
    }

    private boolean booleanValue(String sql, Object... args) {
        Boolean value = jdbc.queryForObject(sql, Boolean.class, args);
        return Boolean.TRUE.equals(value);
    }

    private int number(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private String login(String loginId, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/sessions").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("loginId", loginId, "password", password))))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("data").get("accessToken").asText();
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
