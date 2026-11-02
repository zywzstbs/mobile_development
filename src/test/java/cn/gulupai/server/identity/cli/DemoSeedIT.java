package cn.gulupai.server.identity.cli;

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

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;

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
        assertThat(Files.getPosixFilePermissions(credentials)).containsExactlyInAnyOrder(OWNER_READ, OWNER_WRITE);

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
        assertThat(count("square")).isEqualTo(4);
        assertThat(count("project")).isEqualTo(2);
        assertThat(count("app_user")).isEqualTo(21);
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
        for (String table : List.of("help_article", "help_feedback", "support_history", "support_reply",
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
                "import_batch", "user_role", "user_profile", "app_user", "school")) {
            jdbc.update("DELETE FROM " + table);
        }
    }
}
