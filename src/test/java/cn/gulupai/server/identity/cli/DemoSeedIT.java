package cn.gulupai.server.identity.cli;

import cn.gulupai.server.it.TestSchemaCleaner;
import org.junit.jupiter.api.AfterEach;
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

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static java.nio.file.attribute.PosixFilePermission.OWNER_READ;
import static java.nio.file.attribute.PosixFilePermission.OWNER_WRITE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DemoSeedIT {

    @Autowired
    private DemoSeedService seedService;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        clearAll();
    }

    @AfterEach
    void tearDown() {
        clearAll();
    }

    @Test
    void fullScenarioWritesRosterWithoutPublishingPasswords(@TempDir Path temp) throws Exception {
        Path credentials = temp.resolve("demo-credentials.txt");
        DemoSeedService.SeedSummary preview = seedService.seed("full", true, credentials, "cli");
        assertThat(preview.describe()).isEqualTo("演示种子：full 用户21 真实班2 选课18 广场4 dryRun=true");
        assertThat(Files.exists(credentials)).isFalse();
        assertThat(count("app_user")).isZero();

        DemoSeedService.SeedSummary applied = seedService.seed("full", false, credentials, "cli");
        String password = passwordOf(credentials, "operator_full_01");
        assertThat(applied.describe()).isEqualTo("演示种子：full 用户21 真实班2 选课18 广场4 dryRun=false");
        assertThat(applied.describe()).doesNotContain(password);
        assertPrivateCredentialFile(credentials);
        assertThat(temporaryJsonDirectories()).isEmpty();

        assertThat(count("app_user")).isEqualTo(21);
        assertThat(count("verified_course")).isEqualTo(2);
        assertThat(count("verified_enrollment")).isEqualTo(18);
        assertThat(count("square")).isEqualTo(4);
        assertThat(count("project")).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM task WHERE mode = 'ORDINARY' AND status = 'TODO'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM task WHERE mode = 'ORDINARY' AND status = 'DONE'", Integer.class))
                .isEqualTo(1);
        assertThat(count("project_reflection")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM project WHERE name = '演示快速组'", String.class))
                .isEqualTo("CLOSING");
        assertThat(jdbc.queryForObject("SELECT status FROM agreement", String.class)).isEqualTo("PENDING");
        assertThat(count("chat_message")).isEqualTo(51);
        assertThat(count("square_merge")).isEqualTo(2);
        assertThat(count("support_ticket")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM stored_file", String.class)).isEqualTo("READY");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM square WHERE verified_course_id='vc_demo_full_mobile'
                """, Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM verified_enrollment
                WHERE verified_course_id='vc_demo_full_mobile' AND user_id='user_demo_full_student_09'
                """, Integer.class)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM operator_grant
                WHERE user_id='user_demo_full_operator' AND active=true AND scope_type='GLOBAL'
                """, Integer.class)).isEqualTo(4);

        mockMvc.perform(post("/api/v1/auth/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"operator_full_01\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user.capabilities[0]").value("HELP_CONTENT_WRITE"));

        String firstPassword = passwordOf(credentials, "student_full_01");
        seedService.seed("full", false, credentials, "cli");
        assertThat(passwordOf(credentials, "student_full_01")).isEqualTo(firstPassword);
        assertPrivateCredentialFile(credentials);
        assertThat(count("square")).isEqualTo(4);
        assertThat(count("project")).isEqualTo(2);
        assertThat(count("app_user")).isEqualTo(21);
    }

    /**
     * 上一轮遗留的宽权限凭据文件必须在复用前收紧：既保留既有口令，也不再把明文暴露给其他主体。
     */
    @Test
    void existingWideCredentialFileIsTightenedBeforeReuse(@TempDir Path temp) throws Exception {
        Path credentials = temp.resolve("legacy-credentials.txt");
        Files.writeString(credentials, "# 既有凭据\nstudent_full_01 ExistingPassw0rdValue\n");
        widenCredentialFile(credentials, temp);
        assertThat(readableByForeignPrincipals(credentials))
                .as("前置条件：文件在收紧前对其他主体可读").isTrue();

        seedService.seed("full", false, credentials, "cli");

        assertPrivateCredentialFile(credentials);
        assertThat(readableByForeignPrincipals(credentials)).isFalse();
        assertThat(passwordOf(credentials, "student_full_01")).isEqualTo("ExistingPassw0rdValue");
        assertThat(count("app_user")).isEqualTo(21);
    }

    /** 平台相关的私有权限断言：POSIX 检查 0600，Windows 检查 DACL 只剩当前可信主体一条允许规则。 */
    private static void assertPrivateCredentialFile(Path file) throws Exception {
        if (Files.getFileAttributeView(file, PosixFileAttributeView.class) != null) {
            assertThat(Files.getPosixFilePermissions(file)).containsExactlyInAnyOrder(OWNER_READ, OWNER_WRITE);
            return;
        }
        AclFileAttributeView view = Files.getFileAttributeView(file, AclFileAttributeView.class);
        assertThat(view).as("Windows 上凭据文件必须有 ACL 视图").isNotNull();
        List<AclEntry> acl = view.getAcl();
        assertThat(acl).as("凭据文件 ACL 只能保留当前用户一条允许规则").hasSize(1);
        AclEntry entry = acl.getFirst();
        assertThat(entry.type()).isEqualTo(AclEntryType.ALLOW);
        assertThat(entry.flags()).isEmpty();
        assertThat(entry.principal()).isEqualTo(trustedPrincipal());
        assertThat(entry.permissions())
                .contains(AclEntryPermission.READ_DATA, AclEntryPermission.WRITE_DATA);
        // 生产实现的独立复核：条目数、主体、继承标志、权限集合都必须符合私有策略。
        PrivateCredentialFiles.verifyPrivateFile(file);
    }

    private static void widenCredentialFile(Path file, Path parent) throws IOException {
        if (Files.getFileAttributeView(file, PosixFileAttributeView.class) != null) {
            Files.setPosixFilePermissions(file, Set.of(OWNER_READ, OWNER_WRITE,
                    PosixFilePermission.GROUP_READ, PosixFilePermission.OTHERS_READ));
            return;
        }
        Files.getFileAttributeView(file, AclFileAttributeView.class)
                .setAcl(Files.getFileAttributeView(parent, AclFileAttributeView.class).getAcl());
    }

    private static boolean readableByForeignPrincipals(Path file) throws IOException {
        if (Files.getFileAttributeView(file, PosixFileAttributeView.class) != null) {
            return Files.getPosixFilePermissions(file).stream()
                    .anyMatch(permission -> permission == PosixFilePermission.GROUP_READ
                            || permission == PosixFilePermission.OTHERS_READ);
        }
        UserPrincipal trusted = trustedPrincipal();
        return Files.getFileAttributeView(file, AclFileAttributeView.class).getAcl().stream()
                .filter(entry -> entry.type() == AclEntryType.ALLOW)
                .filter(entry -> !entry.principal().equals(trusted))
                .anyMatch(entry -> entry.permissions().contains(AclEntryPermission.READ_DATA));
    }

    private static UserPrincipal trustedPrincipal() throws IOException {
        return FileSystems.getDefault().getUserPrincipalLookupService()
                .lookupPrincipalByName(TrustedOsIdentity.windowsAccountName());
    }

    /** 播种过程中创建的私有临时目录必须全部清理。 */
    private static List<Path> temporaryJsonDirectories() throws IOException {
        Path base = Path.of(System.getProperty("java.io.tmpdir"));
        try (Stream<Path> children = Files.list(base)) {
            return children.filter(path -> path.getFileName().toString().startsWith("gulupai-private-"))
                    .toList();
        }
    }

    private static String passwordOf(Path file, String loginId) throws Exception {
        return Files.readAllLines(file).stream()
                .filter(line -> line.startsWith(loginId + " "))
                .map(line -> line.substring(loginId.length() + 1))
                .findFirst()
                .orElseThrow();
    }

    private int count(String table) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
        return count == null ? 0 : count;
    }

    private void clearAll() {
        TestSchemaCleaner.deleteAll(jdbc, List.of("help_article", "help_feedback", "support_history", "support_reply",
                "support_ticket", "support_route", "operator_grant", "report_handling", "content_report",
                "announcement_reminder", "announcement_preview", "announcement_receipt",
                "announcement_recipient", "announcement_version", "announcement",
                "notification_preference", "notification", "notification_outbox", "chat_change",
                "chat_message", "chat_read_state", "chat_counter", "topic_follow", "content_revision",
                "board_reply", "board_topic", "dice_round", "dice_roll", "dice_participant",
                "dice_session", "report_snapshot", "delivery_release", "delivery_release_preview",
                "delivery_release_draft", "overall_reflection", "project_reflection", "file_reference",
                "file_authorization", "cleanup_plan", "stored_file", "personnel_handoff",
                "material_request", "personnel_record", "personnel_case_grant", "personnel_case", "removal_ballot",
                "handover_ack", "handover_item", "member_change_event", "member_change",
                "removal_vote", "teacher_summary_consent", "teacher_summary", "grade_appeal",
                "grade_snapshot", "grade_publication", "settlement", "ledger_correction", "grade_draft",
                "grade_rule", "contribution_confirmation", "unit_acceptance", "acceptance",
                "submission", "delivery_draft", "proposal_response", "unit_change_proposal",
                "reviewer_invitation", "reviewer_arrangement", "agreement_ack", "unit_share",
                "task_dependency", "criterion", "delivery_unit", "agreement", "task", "user_project_color",
                "role_ack", "role_negotiation", "ai_reference", "peer_review_version", "peer_review",
                "work_version", "work", "project_code", "seat_reservation", "team_invitation",
                "team_application", "assignment_team_slot", "membership", "role_slot", "project_stage",
                "project", "teacher_transfer", "teacher_claim", "teacher_assignment", "class_grant",
                "course_code", "course_join_request", "cursor_snapshot", "assignment",
                "course_membership", "merge_ballot", "square_merge", "preview_token", "square",
                "verified_enrollment", "verified_course", "course_name", "period", "term",
                "operation_receipt", "access_token", "auth_session", "audit_event", "rate_window",
                "import_batch", "user_role", "user_profile", "app_user", "school"));
    }
}
