package cn.gulupai.server.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.gulupai.server.common.clock.MutableClock;
import cn.gulupai.server.common.security.Hashing;
import cn.gulupai.server.identity.cli.AdminImportService;
import cn.gulupai.server.it.TestSchemaCleaner;
import cn.gulupai.server.platform.application.AdminCursorCodec;
import cn.gulupai.server.platform.application.PlatformSuperAdminService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * R2 回归：平台列表的续页游标必须继承首页快照的绝对到期时间。
 *
 * <p>旧行为是每次续页都调用不带期限的 {@code CursorService.create}，把同一快照重新延长
 * 15 分钟，只要客户端连续翻页就能让过期快照一直可用。本类用固定时钟验证三件事：
 * 多次续页的 {@code expires_at} 精确等于首页快照的到期点；到达该时点后全部旧游标 409；
 * 到期后新发起的首页请求属于新快照，可以取得新的期限。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PlatformAdminTransactionIT.TestClockConfig.class)
class PlatformCursorExpiryP66IT {

    private static final Instant T0 = Instant.parse("2026-11-07T00:00:00Z");
    /** 与 CursorService 的快照 TTL 一致；这里只断言“首页期限是 T0+TTL”，不复制业务实现。 */
    private static final Duration SNAPSHOT_TTL = Duration.ofMinutes(15);

    private static final String ADMIN = "user_demo_teacher_01";
    private static final String ADMIN_LOGIN = "teacher_demo_01";
    private static final String ADMIN_PASSWORD = "demo-password-03";
    private static final String SCHOOL = "school_demo_01";
    /** p01 演示夹具里的账号数，全部属于 school_demo_01。 */
    private static final int USER_COUNT = 5;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MutableClock clock;
    @Autowired private AdminImportService importService;
    @Autowired private PlatformSuperAdminService superAdmins;

    @BeforeEach
    void setUp() {
        clearAll();
        clock.setInstant(T0);
        importService.importUsers(Path.of("admin/fixtures/p01-demo-users.json"), false);
        superAdmins.grantInitial(ADMIN, ADMIN, "隔离环境首位管理员", false);
    }

    @Test
    void continuationKeepsTheFirstPageExpiryAndExpiresExactlyAtThatInstant() throws Exception {
        String admin = login(ADMIN_LOGIN, ADMIN_PASSWORD);
        MvcResult first = usersPage(admin, "1", null);
        assertThat(data(first).get("total").asInt()).isEqualTo(USER_COUNT);
        String c1 = data(first).get("nextCursor").asText();
        assertThat(c1).isNotEmpty();

        // 首页游标拿到正常快照期限：T0 + 15 分钟。
        Instant expiresAt = expiresAt(c1);
        assertThat(expiresAt).isEqualTo(T0.plus(SNAPSHOT_TTL));

        // 快照临到期时续页：第二页游标必须复用同一个绝对到期点，而不是重新获得 15 分钟。
        clock.setInstant(T0.plus(Duration.ofMinutes(14)));
        String c2 = data(usersPage(admin, "1", c1)).get("nextCursor").asText();
        assertThat(expiresAt(c2)).isEqualTo(expiresAt);

        clock.setInstant(T0.plus(Duration.ofMinutes(14)).plusSeconds(30));
        String c3 = data(usersPage(admin, "1", c2)).get("nextCursor").asText();
        assertThat(expiresAt(c3)).isEqualTo(expiresAt);

        // 恰好到期：全部旧游标失效，不能靠继续翻页延长同一快照。
        clock.setInstant(expiresAt);
        for (String cursor : List.of(c1, c2, c3)) {
            mockMvc.perform(get("/api/v1/admin/users").param("limit", "1").param("cursor", cursor)
                            .header("Authorization", bearer(admin)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("CURSOR_EXPIRED"));
        }

        // 新发起的首页请求属于新快照，可以取得新的期限。
        MvcResult reread = usersPage(admin, "1", null);
        Instant renewed = expiresAt(data(reread).get("nextCursor").asText());
        assertThat(renewed).isEqualTo(expiresAt.plus(SNAPSHOT_TTL));
        assertThat(renewed).isAfter(expiresAt);
    }

    @Test
    void newRowsAfterTheFirstPageDoNotChangeTheFrozenSnapshotOrItsExpiry() throws Exception {
        String admin = login(ADMIN_LOGIN, ADMIN_PASSWORD);
        String c1 = data(usersPage(admin, "2", null)).get("nextCursor").asText();
        Instant expiresAt = expiresAt(c1);

        clock.setInstant(T0.plus(Duration.ofMinutes(2)));
        insertUser("user_p71_cursor_new", "p71_cursor_new");

        // 旧快照仍按冻结的 5 条与本来的 total 续页，期限继续精确继承原到期点。
        MvcResult second = usersPage(admin, "2", c1);
        assertThat(data(second).get("total").asInt()).isEqualTo(USER_COUNT);
        assertThat(data(second).get("items")).hasSize(2);
        assertThat(expiresAt(data(second).get("nextCursor").asText())).isEqualTo(expiresAt);

        // 新读到的首页才是包含新账号的新快照，期限按当前时钟重建。
        String freshCursor = data(usersPage(admin, "2", null)).get("nextCursor").asText();
        assertThat(expiresAt(freshCursor)).isEqualTo(T0.plus(Duration.ofMinutes(2)).plus(SNAPSHOT_TTL));
        assertThat(data(usersPage(admin, "2", null)).get("total").asInt()).isEqualTo(USER_COUNT + 1);
    }

    @Test
    void lastPageKeepsAnEmptyNextCursorWithoutIssuingANewSnapshot() throws Exception {
        String admin = login(ADMIN_LOGIN, ADMIN_PASSWORD);
        int snapshotsBefore = snapshotCount();
        MvcResult page = usersPage(admin, "50", null);
        assertThat(data(page).get("total").asInt()).isEqualTo(USER_COUNT);
        assertThat(data(page).get("items")).hasSize(USER_COUNT);
        assertThat(data(page).get("hasMore").asBoolean()).isFalse();
        assertThat(data(page).get("nextCursor").asText()).isEmpty();
        // 末页不需要 nextCursor，因此不应为它额外写入快照行。
        assertThat(snapshotCount()).isEqualTo(snapshotsBefore);
    }

    private MvcResult usersPage(String admin, String limit, String cursor) throws Exception {
        var request = get("/api/v1/admin/users").param("limit", limit).header("Authorization", bearer(admin));
        if (cursor != null) {
            request = request.param("cursor", cursor);
        }
        MvcResult result = mockMvc.perform(request).andExpect(status().isOk()).andReturn();
        // 所有断言都在访问令牌 30 分钟有效期之内，失败时不会掩盖成 SESSION_EXPIRED。
        assertThat(clock.instant()).isBefore(T0.plus(Duration.ofMinutes(30)));
        return result;
    }

    /** 游标 → cursor_snapshot.token_hash → 绝对到期时间。 */
    private Instant expiresAt(String cursor) {
        OffsetDateTime at = jdbc.queryForObject(
                "SELECT expires_at FROM cursor_snapshot WHERE token_hash = ?", OffsetDateTime.class,
                Hashing.sha256Hex(AdminCursorCodec.decode(cursor)));
        return at.toInstant();
    }

    private int snapshotCount() {
        return jdbc.queryForObject("SELECT count(*) FROM cursor_snapshot", Integer.class);
    }

    private void insertUser(String id, String loginId) {
        jdbc.update("""
                INSERT INTO app_user (id, school_id, login_id, display_name, password_hash, identity_status)
                VALUES (?, ?, ?, ?, ?, 'VERIFIED')
                """, id, SCHOOL, loginId, "游标新增账号", "not-a-real-hash");
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
