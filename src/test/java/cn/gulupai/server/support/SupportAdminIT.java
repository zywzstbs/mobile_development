package cn.gulupai.server.support;

import cn.gulupai.server.it.TestSchemaCleaner;
import cn.gulupai.server.common.clock.MutableClock;
import cn.gulupai.server.identity.cli.AcademicImportService;
import cn.gulupai.server.identity.cli.AdminImportService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(SupportAdminIT.TestClockConfig.class)
class SupportAdminIT {

    private static final Instant BASE = Instant.parse("2026-10-31T00:00:00Z");
    private static final String SQUARE = "square_demo_help";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MutableClock clock;
    @Autowired
    private AdminImportService users;
    @Autowired
    private AcademicImportService academics;
    @Autowired
    private HelpCatalogImportService helpImport;
    @Autowired
    private OperatorGrantAdminService grants;

    @BeforeEach
    void setUp() throws Exception {
        clearAll();
        clock.setInstant(BASE);
        users.importUsers(Path.of("admin/fixtures/p01-demo-users.json"), false);
        academics.importAcademic(Path.of("admin/fixtures/p02-demo-academic.json"), false);
        jdbc.update("""
                INSERT INTO square
                    (id, school_id, term_id, name, teacher_name, slots_json, canonical_key, square_no, status)
                VALUES (?, 'school_demo_01', 'term_demo_2026a', '帮助演示班', '示例教师', '[]'::jsonb,
                        'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', 'H1', 'ACTIVE')
                """, SQUARE);
    }

    @AfterEach
    void tearDown() {
        clearAll();
    }

    @Test
    void helpImportAndGlobalGrantDriveMaintenanceEntry() throws Exception {
        Path help = Path.of("admin/fixtures/p16-help.json");
        HelpCatalogImportService.ImportSummary dry = helpImport.importArticles(help, true, "cli");
        assertThat(dry.dryRun()).isTrue();
        assertThat(dry.created()).isEqualTo(1);
        assertThat(countArticles()).isZero();

        HelpCatalogImportService.ImportSummary applied = helpImport.importArticles(help, false, "cli");
        assertThat(applied.created()).isEqualTo(1);
        assertThat(helpImport.importArticles(help, false, "cli").unchanged()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT revision FROM help_article WHERE id='help_general_1'", Integer.class))
                .isEqualTo(1);
        mockMvc.perform(get("/api/v1/help/content-pack"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.articles[0].id").value("help_general_1"));

        OperatorGrantAdminService.GrantResult preview = grants.grant(
                "user_demo_teacher_02", "HELP_CONTENT_WRITE", "GLOBAL", null, true, true, "cli");
        assertThat(preview.dryRun()).isTrue();
        assertThat(countGrants()).isZero();
        String teacher = login("teacher_demo_02", "demo-password-05");
        mockMvc.perform(get("/api/v1/me").header("Authorization", bearer(teacher)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.capabilities").isEmpty());

        grants.grant("user_demo_teacher_02", "HELP_CONTENT_WRITE", "GLOBAL", null, true, false, "cli");
        grants.grant(SQUARE_USER(), "HELP_CONTENT_WRITE", "SQUARE", SQUARE, true, false, "cli");
        teacher = login("teacher_demo_02", "demo-password-05");
        mockMvc.perform(get("/api/v1/me").header("Authorization", bearer(teacher)))
                .andExpect(jsonPath("$.data.capabilities[0]").value("HELP_CONTENT_WRITE"))
                .andExpect(jsonPath("$.data.capabilities.length()").value(1));
        String student = login("student_demo_01", "demo-password-01");
        mockMvc.perform(get("/api/v1/me").header("Authorization", bearer(student)))
                .andExpect(jsonPath("$.data.capabilities").isEmpty());

        grants.grant("user_demo_teacher_02", "HELP_ADMIN", "GLOBAL", null, true, false, "cli");
        teacher = login("teacher_demo_02", "demo-password-05");
        mockMvc.perform(put("/api/v1/help/articles/help_general_1")
                        .header("Authorization", bearer(student))
                        .header("Idempotency-Key", "p16-student")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(articleUpdate(1, "学生改", "不行")))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/v1/help/articles/help_general_1")
                        .header("Authorization", bearer(teacher))
                        .header("Idempotency-Key", "p16-teacher")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(articleUpdate(1, "教师修订", "按新说明提交")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("教师修订"));

        grants.grant("user_demo_teacher_02", "HELP_CONTENT_WRITE", "GLOBAL", null, false, false, "cli");
        teacher = login("teacher_demo_02", "demo-password-05");
        mockMvc.perform(get("/api/v1/me").header("Authorization", bearer(teacher)))
                .andExpect(jsonPath("$.data.capabilities").isEmpty());
    }

    @Test
    void invalidHelpAndLocalGrantAreRejected(@TempDir Path temp) throws Exception {
        Path bad = temp.resolve("bad-help.json");
        Files.writeString(bad, """
                {"articles":[{"id":"help_bad","audience":"GENERAL","title":"坏版本","summary":"",
                "body":"正文","category":"GENERAL","appVersionRange":"1.0","published":true}]}
                """);
        assertThatThrownBy(() -> helpImport.importArticles(bad, false, "cli"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("版本范围");
        assertThat(countArticles()).isZero();

        Path unknown = temp.resolve("unknown-help.json");
        Files.writeString(unknown, """
                {"articles":[{"id":"help_x","audience":"GENERAL","title":"题","summary":"",
                "body":"正文","category":"GENERAL","appVersionRange":"1.0.0","published":true,
                "extra":true}]}
                """);
        assertThatThrownBy(() -> helpImport.importArticles(unknown, true, "cli"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知字段");

        assertThatThrownBy(() -> grants.grant(
                "user_demo_teacher_02", "ADMIN", "GLOBAL", null, true, false, "cli"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(countGrants()).isZero();

        Path course = temp.resolve("course-help.json");
        Files.writeString(course, """
                {"articles":[{"id":"help_course_1","audience":"COURSE","squareId":"%s","title":"班内帮助",
                "category":"COURSE","roles":[],"appVersionRange":"1.2.3","prerequisites":[],
                "steps":["课程说明"],"outcomes":[],"failureAction":"只给本班",
                "entry":{"type":"COURSE","id":null},"published":true}]}
                """.formatted(SQUARE));
        assertThat(helpImport.importArticles(course, false, "cli").created()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT square_id FROM help_article WHERE id='help_course_1'", String.class)).isEqualTo(SQUARE);
    }

    private static String articleUpdate(int revision, String title, String step) {
        return "{\"expectedRevision\":" + revision + ",\"article\":{\"title\":\"" + title
                + "\",\"category\":\"GENERAL\",\"roles\":[\"STUDENT\"],\"scope\":\"GENERAL\",\"squareId\":null,"
                + "\"appVersionRange\":\"1.0.*\",\"prerequisites\":[],\"steps\":[\"" + step + "\"],"
                + "\"outcomes\":[\"完成\"],\"failureAction\":\"重试\",\"entry\":{\"type\":\"COURSE\",\"id\":null}}}";
    }

    private static String SQUARE_USER() {
        return "user_demo_student_01";
    }

    private int countArticles() {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM help_article", Integer.class);
        return count == null ? 0 : count;
    }

    private int countGrants() {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM operator_grant", Integer.class);
        return count == null ? 0 : count;
    }

    private String login(String loginId, String password) throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"" + loginId + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return body.replaceAll("(?s).*\"accessToken\":\"([^\"]+)\".*", "$1");
    }

    private static String bearer(String token) {
        return "Bearer " + token;
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

    @TestConfiguration
    static class TestClockConfig {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(BASE, ZoneOffset.UTC);
        }
    }
}
