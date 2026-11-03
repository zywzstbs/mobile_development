package cn.gulupai.server.file;

import cn.gulupai.server.it.TestSchemaCleaner;
import cn.gulupai.server.common.clock.MutableClock;
import cn.gulupai.server.identity.cli.AcademicImportService;
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

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FileP05FIT.TestClockConfig.class)
class FileP05FIT {

    private static final Instant BASE = Instant.parse("2026-10-31T00:00:00Z");
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");
    private static final byte[] PDF = "%PDF-1.4\n1 0 obj<<>>endobj\ntrailer<<>>\n%%EOF\n"
            .getBytes(StandardCharsets.US_ASCII);
    private static final byte[] TEXT = "课程说明\n".getBytes(StandardCharsets.UTF_8);

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
    @Autowired
    private FileService fileService;
    @Autowired
    private CleanupAdminService cleanupAdminService;

    @BeforeEach
    void setUp() throws Exception {
        clearAll();
        clock.setInstant(BASE);
        deleteDirectory(Path.of("target/test-files"));
        userImportService.importUsers(Path.of("admin/fixtures/p01-demo-users.json"), false);
        academicImportService.importAcademic(Path.of("admin/fixtures/p02-demo-academic.json"), false);
    }

    @AfterEach
    void tearDown() throws Exception {
        clearAll();
        deleteDirectory(Path.of("target/test-files"));
    }

    private void clearAll() {
        TestSchemaCleaner.deleteAll(jdbcTemplate, List.of("file_reference", "file_authorization", "cleanup_plan", "stored_file",
                "personnel_handoff", "material_request", "personnel_record", "personnel_case_grant", "personnel_case",
                "removal_ballot", "handover_ack", "handover_item", "member_change_event",
                "member_change", "removal_vote",
                "teacher_summary_consent", "teacher_summary", "grade_appeal", "grade_snapshot",
                "grade_publication", "settlement", "ledger_correction", "grade_draft", "grade_rule",
                "contribution_confirmation", "unit_acceptance", "acceptance", "submission",
                "delivery_draft", "proposal_response", "unit_change_proposal",
                "reviewer_invitation", "reviewer_arrangement", "agreement_ack", "unit_share",
                "task_dependency", "criterion", "delivery_unit", "agreement", "task", "user_project_color", "role_ack", "role_negotiation",
                "announcement_reminder", "announcement_preview", "announcement_receipt",
                "announcement_recipient", "announcement_version", "help_article", "help_feedback", "support_route", "support_ticket",
                "support_reply", "support_history", "operator_grant", "content_report",
                "report_handling", "announcement",
                "notification_preference", "notification", "board_topic", "board_reply", "content_revision", "topic_follow",
                "chat_counter", "chat_message", "chat_change", "chat_read_state",
                "notification_outbox", "dice_round", "dice_roll", "dice_participant", "dice_session", "ai_reference", "peer_review_version", "peer_review",
                "work_version", "work", "project_code", "seat_reservation", "team_invitation", "team_application",
                "assignment_team_slot", "membership", "role_slot", "project_stage", "project",
                "teacher_transfer", "teacher_claim", "teacher_assignment", "class_grant", "course_code",
                "course_join_request", "cursor_snapshot", "assignment", "course_membership", "preview_token",
                "square", "verified_enrollment", "verified_course", "course_name", "period", "term",
                "operation_receipt", "access_token", "auth_session", "audit_event", "rate_window",
                "import_batch", "user_role", "user_profile", "app_user", "school"));
    }

    @Test
    void uploadsRealSamplesAndRejectsFalseCompletion() throws Exception {
        String leader = login("student_demo_01", "demo-password-01");
        String projectId = project(leader, "p05f-samples");
        Ready png = upload(leader, "SUPPORT_ATTACHMENT", "screenshot-test.png", "image/png", PNG, null, "p05f-png");
        assertThat(png.body().get("file").get("status").asText()).isEqualTo("UPLOADING");
        mockMvc.perform(post("/api/v1/files/{id}/completions", png.fileId())
                        .header("Authorization", bearer(leader))
                        .header("Idempotency-Key", "p05f-png-fake")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"uploadToken\":\"" + png.token() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FAILED"));
        assertThat(statusOf(png.fileId())).isEqualTo("FAILED");

        Ready realPng = upload(leader, "SUPPORT_ATTACHMENT", "screenshot-test.png", "image/png", PNG, null, "p05f-png-real");
        putBytes(realPng, PNG, "image/png");
        putBytes(realPng, PNG, "image/png");
        mockMvc.perform(put(tokenPath(realPng.url())).content(PDF).contentType("image/png"))
                .andExpect(status().isConflict());
        complete(leader, realPng, "p05f-png-done");
        assertDownloaded(leader, realPng.fileId(), PNG, "p05f-png-get");

        Ready pdf = upload(leader, "DELIVERY_ARTIFACT", "说明.pdf", "application/pdf", PDF, projectId, "p05f-pdf");
        putBytes(pdf, PDF, "application/pdf");
        complete(leader, pdf, "p05f-pdf-done");
        Ready text = upload(leader, "WORK_ARTIFACT", "说明.txt", "text/plain", TEXT, projectId, "p05f-txt");
        putBytes(text, TEXT, "text/plain");
        complete(leader, text, "p05f-txt-done");
        byte[] docx = docx();
        Ready word = upload(leader, "WORK_ARTIFACT", "说明.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", docx, null, "p05f-docx");
        putBytes(word, docx, "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        complete(leader, word, "p05f-docx-done");
        assertThat(statusOf(pdf.fileId())).isEqualTo("READY");
        assertThat(statusOf(text.fileId())).isEqualTo("READY");
        assertThat(statusOf(word.fileId())).isEqualTo("READY");
    }

    @Test
    void rejectsWrongSignatureTruncationScopeAndExpiredUrl() throws Exception {
        String leader = login("student_demo_01", "demo-password-01");
        String outsider = login("student_demo_02", "demo-password-02");
        String projectId = project(leader, "p05f-guard");
        int before = fileCount();
        mockMvc.perform(post("/api/v1/files/upload-intents")
                        .header("Authorization", bearer(leader))
                        .header("Idempotency-Key", "p05f-material")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(intent("MATERIAL_PROOF", "a.png", "image/png", PNG.length, null, "case_missing", null)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/files/upload-intents")
                        .header("Authorization", bearer(leader))
                        .header("Idempotency-Key", "p05f-ticket")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(intent("SUPPORT_ATTACHMENT", "a.png", "image/png", PNG.length, null, null, "ticket_missing")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/files/upload-intents")
                        .header("Authorization", bearer(outsider))
                        .header("Idempotency-Key", "p05f-outsider")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(intent("DELIVERY_ARTIFACT", "a.pdf", "application/pdf", PDF.length, projectId, null, null)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/files/upload-intents")
                        .header("Authorization", bearer(leader))
                        .header("Idempotency-Key", "p05f-pdf-support")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(intent("SUPPORT_ATTACHMENT", "a.pdf", "application/pdf", PDF.length, null, null, null)))
                .andExpect(status().isUnprocessableEntity());
        assertThat(fileCount()).isEqualTo(before);

        Ready mismatch = upload(leader, "SUPPORT_ATTACHMENT", "screenshot-test.png", "image/png", PDF, null, "p05f-mismatch");
        putBytes(mismatch, PDF, "image/png");
        complete(leader, mismatch, "p05f-mismatch-done");
        assertThat(statusOf(mismatch.fileId())).isEqualTo("FAILED");

        Ready truncated = upload(leader, "DELIVERY_ARTIFACT", "说明.pdf", "application/pdf",
                PDF.length + 20, projectId, PDF, "p05f-trunc");
        putBytes(truncated, PDF, "application/pdf");
        complete(leader, truncated, "p05f-trunc-done");
        assertThat(statusOf(truncated.fileId())).isEqualTo("FAILED");
        assertThatThrownBy(() -> fileService.attachReference(truncated.fileId(), "TASK", "task_demo", 1))
                .isInstanceOf(cn.gulupai.server.common.api.ApiException.class);

        Ready oversized = upload(leader, "SUPPORT_ATTACHMENT", "big.png", "image/png", 8, null, PNG, "p05f-big");
        mockMvc.perform(put(tokenPath(oversized.url())).content(PNG).contentType("image/png"))
                .andExpect(status().isPayloadTooLarge());
        assertThat(statusOf(oversized.fileId())).isEqualTo("UPLOADING");

        Ready expiring = upload(leader, "SUPPORT_ATTACHMENT", "late.png", "image/png", PNG, null, "p05f-expire");
        clock.advance(Duration.ofMinutes(11));
        mockMvc.perform(put(tokenPath(expiring.url())).content(PNG).contentType("image/png"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CODE_EXPIRED"));
    }

    @Test
    void downloadRechecksMembershipAndReplayDoesNotExtend() throws Exception {
        String leader = login("student_demo_01", "demo-password-01");
        String member = login("assistant_demo_01", "demo-password-04");
        String projectId = project(leader, "p05f-download");
        invite(leader, projectId, "user_demo_assistant_01", "p05f-invite");
        acceptLatestInvitation(member, projectId, "p05f-accept");
        Ready file = upload(member, "DELIVERY_ARTIFACT", "说明.pdf", "application/pdf", PDF, projectId, "p05f-dl");
        putBytes(file, PDF, "application/pdf");
        complete(member, file, "p05f-dl-done");
        fileService.attachReference(file.fileId(), "PROJECT", projectId, 1);
        JsonNode first = data(mockMvc.perform(post("/api/v1/files/{id}/download-authorizations", file.fileId())
                        .header("Authorization", bearer(member))
                        .header("Idempotency-Key", "p05f-dl-auth")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk()).andReturn());
        JsonNode replay = data(mockMvc.perform(post("/api/v1/files/{id}/download-authorizations", file.fileId())
                        .header("Authorization", bearer(member))
                        .header("Idempotency-Key", "p05f-dl-auth")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk()).andReturn());
        assertThat(replay.get("url").asText()).isEqualTo(first.get("url").asText());
        assertThat(replay.get("expiresAt").asText()).isEqualTo(first.get("expiresAt").asText());
        mockMvc.perform(get(tokenPath(first.get("url").asText())))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("filename")))
                .andExpect(content().bytes(PDF));
        jdbcTemplate.update("""
                UPDATE membership SET status = 'LEFT' WHERE project_id = ? AND user_id = 'user_demo_assistant_01'
                """, projectId);
        mockMvc.perform(get(tokenPath(first.get("url").asText())))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/files/{id}", file.fileId()).header("Authorization", bearer(member)))
                .andExpect(status().isForbidden());
        clock.advance(Duration.ofMinutes(6));
        mockMvc.perform(get(tokenPath(first.get("url").asText())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CODE_EXPIRED"));
    }

    @Test
    void reconcileRepairsMissingBytesAndCleansOrphans() throws Exception {
        String leader = login("student_demo_01", "demo-password-01");
        Ready file = upload(leader, "SUPPORT_ATTACHMENT", "screenshot-test.png", "image/png", PNG, null, "p05f-repair");
        putBytes(file, PNG, "image/png");
        Files.delete(Path.of("target/test-files/incoming").resolve(file.fileId()));
        fileService.reconcile();
        assertThat(statusOf(file.fileId())).isEqualTo("FAILED");

        Path stray = Path.of("target/test-files/incoming/stray-bytes");
        Files.createDirectories(stray.getParent());
        Files.write(stray, new byte[]{1, 2, 3});
        fileService.reconcile();
        assertThat(Files.exists(stray)).isFalse();

        Ready orphan = upload(leader, "WORK_ARTIFACT", "说明.txt", "text/plain", TEXT, null, "p05f-orphan");
        putBytes(orphan, TEXT, "text/plain");
        complete(leader, orphan, "p05f-orphan-done");
        clock.advance(Duration.ofHours(25));
        fileService.reconcile();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM cleanup_plan WHERE resource_id = ? AND status = 'PLANNED'
                """, Integer.class, orphan.fileId())).isEqualTo(1);
        assertThat(Files.exists(Path.of("target/test-files/ready").resolve(orphan.fileId()))).isTrue();
        clock.advance(Duration.ofDays(8));
        fileService.reconcile();
        assertThat(Files.exists(Path.of("target/test-files/ready").resolve(orphan.fileId()))).isFalse();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT status FROM cleanup_plan WHERE resource_id = ?
                """, String.class, orphan.fileId())).isEqualTo("EXECUTED");
    }

    @Test
    void cleanupDryRunDoesNotDeleteAndCancelStopsExecution() throws Exception {
        String leader = login("student_demo_01", "demo-password-01");
        Ready orphan = upload(leader, "WORK_ARTIFACT", "说明.txt", "text/plain", TEXT, null, "p16-dry-orphan");
        putBytes(orphan, TEXT, "text/plain");
        complete(leader, orphan, "p16-dry-done");
        clock.advance(Duration.ofHours(25));
        fileService.reconcile();

        var waiting = cleanupAdminService.dryRun();
        assertThat(waiting).hasSize(1);
        assertThat(waiting.getFirst().decision()).isEqualTo("WAITING");
        assertThat(waiting.getFirst().resourceId()).isEqualTo(orphan.fileId());
        assertThat(Files.exists(Path.of("target/test-files/ready").resolve(orphan.fileId()))).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM cleanup_plan WHERE id = ?", String.class, waiting.getFirst().id()))
                .isEqualTo("PLANNED");

        clock.advance(Duration.ofDays(8));
        var due = cleanupAdminService.dryRun();
        assertThat(due).singleElement().extracting(CleanupAdminService.PlanPreview::decision).isEqualTo("WOULD_DELETE");
        assertThat(Files.exists(Path.of("target/test-files/ready").resolve(orphan.fileId()))).isTrue();

        cleanupAdminService.cancel(due.getFirst().id(), "演示保留", "cli");
        assertThat(cleanupAdminService.dryRun()).isEmpty();
        fileService.reconcile();
        assertThat(Files.exists(Path.of("target/test-files/ready").resolve(orphan.fileId()))).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM cleanup_plan WHERE id = ?", String.class, due.getFirst().id()))
                .isEqualTo("CANCELLED");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT reason FROM audit_event WHERE action = 'CLEANUP_CANCEL' AND resource_id = ?
                """, String.class, due.getFirst().id())).isEqualTo("演示保留");
        assertThat(cleanupAdminService.dryRun()).singleElement()
                .extracting(CleanupAdminService.PlanPreview::decision).isEqualTo("WAITING");
    }

    private Ready upload(String token, String purpose, String name, String type, byte[] bytes,
                         String projectId, String key) throws Exception {
        return upload(token, purpose, name, type, bytes.length, projectId, bytes, key);
    }

    private Ready upload(String token, String purpose, String name, String type, int size, String projectId,
                         byte[] ignored, String key) throws Exception {
        JsonNode body = data(mockMvc.perform(post("/api/v1/files/upload-intents")
                        .header("Authorization", bearer(token))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(intent(purpose, name, type, size, projectId, null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.upload.method").value("PUT"))
                .andExpect(jsonPath("$.data.upload.url").value(org.hamcrest.Matchers.startsWith("https://")))
                .andReturn());
        return new Ready(body.get("file").get("id").asText(), body.get("uploadToken").asText(),
                body.get("upload").get("url").asText(), body);
    }

    private void putBytes(Ready file, byte[] bytes, String type) throws Exception {
        mockMvc.perform(put(tokenPath(file.url())).content(bytes).contentType(type))
                .andExpect(status().isNoContent());
    }

    private void complete(String token, Ready file, String key) throws Exception {
        mockMvc.perform(post("/api/v1/files/{id}/completions", file.fileId())
                        .header("Authorization", bearer(token))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"uploadToken\":\"" + file.token() + "\"}"))
                .andExpect(status().isOk());
    }

    private void assertDownloaded(String token, String fileId, byte[] bytes, String key) throws Exception {
        JsonNode download = data(mockMvc.perform(post("/api/v1/files/{id}/download-authorizations", fileId)
                        .header("Authorization", bearer(token))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk()).andReturn());
        mockMvc.perform(get(tokenPath(download.get("url").asText())))
                .andExpect(status().isOk())
                .andExpect(content().bytes(bytes));
    }

    private String intent(String purpose, String name, String type, int size, String projectId,
                          String caseId, String ticketId) throws Exception {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("purpose", purpose);
        body.put("fileName", name);
        body.put("contentType", type);
        body.put("sizeBytes", size);
        body.put("sha256", null);
        if (projectId != null) {
            body.put("projectId", projectId);
        }
        if (caseId != null) {
            body.put("caseId", caseId);
        }
        if (ticketId != null) {
            body.put("ticketId", ticketId);
        }
        return objectMapper.writeValueAsString(body);
    }

    private static byte[] docx() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zip.write("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"></Types>"
                    .getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"/>"
                    .getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return output.toByteArray();
    }

    private String project(String token, String key) throws Exception {
        String matchBody = objectMapper.writeValueAsString(Map.of(
                "schoolId", "school_demo_01", "termId", "term_demo_2026a", "teacherName", "示例教师",
                "slots", List.of(Map.of("weekday", 3, "periodId", "period_demo_2", "weekPattern", "ALL",
                        "startWeek", 1, "endWeek", 16, "customWeeks", List.of())),
                "courseNameId", "course_name_demo_mobile"));
        JsonNode match = data(mockMvc.perform(post("/api/v1/squares/matches")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(matchBody))
                .andExpect(status().isOk()).andReturn());
        String createBody = objectMapper.writeValueAsString(Map.of(
                "schoolId", "school_demo_01", "termId", "term_demo_2026a", "teacherName", "示例教师",
                "slots", List.of(Map.of("weekday", 3, "periodId", "period_demo_2", "weekPattern", "ALL",
                        "startWeek", 1, "endWeek", 16, "customWeeks", List.of())),
                "courseNameId", "course_name_demo_mobile",
                "candidateCheckToken", match.get("candidateCheckToken").asText()));
        JsonNode square = data(mockMvc.perform(post("/api/v1/squares")
                        .header("Authorization", bearer(token))
                        .header("Idempotency-Key", key + "-square")
                        .contentType(MediaType.APPLICATION_JSON).content(createBody))
                .andExpect(status().isOk()).andReturn());
        JsonNode assignment = data(mockMvc.perform(post("/api/v1/squares/{id}/assignments", square.get("id").asText())
                        .header("Authorization", bearer(token))
                        .header("Idempotency-Key", key + "-assignment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "课程设计", "requirements", "完成可演示应用",
                                "hardDueAt", "2026-12-15T15:59:59Z", "minMembers", 2, "maxMembers", 6,
                                "sourceReferences", List.of()))))
                .andExpect(status().isOk()).andReturn());
        String teacher = login("teacher_demo_01", "demo-password-03");
        mockMvc.perform(post("/api/v1/assignments/{id}/confirmations", assignment.get("id").asText())
                        .header("Authorization", bearer(teacher))
                        .header("Idempotency-Key", key + "-confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"decision\":\"CONFIRM_NEW\",\"reason\":\"ok\"}"))
                .andExpect(status().isOk());
        JsonNode project = data(mockMvc.perform(post("/api/v1/projects")
                        .header("Authorization", bearer(token))
                        .header("Idempotency-Key", key + "-project")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "squareId", square.get("id").asText(), "assignmentId", assignment.get("id").asText(),
                                "mode", "QUICK", "name", "文件组", "capacity", 3, "discussionTime", "周三",
                                "roles", List.of()))))
                .andExpect(status().isOk()).andReturn());
        return project.get("id").asText();
    }

    private void invite(String leader, String projectId, String userId, String key) throws Exception {
        int revision = data(mockMvc.perform(get("/api/v1/projects/{id}", projectId)
                        .header("Authorization", bearer(leader))).andExpect(status().isOk()).andReturn())
                .get("revision").asInt();
        mockMvc.perform(post("/api/v1/projects/{id}/invitations", projectId)
                        .header("Authorization", bearer(leader))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedProjectRevision\":" + revision
                                + ",\"recipientId\":\"" + userId + "\",\"message\":\"加入\"}"))
                .andExpect(status().isOk());
    }

    private void acceptLatestInvitation(String token, String projectId, String key) throws Exception {
        String invitationId = jdbcTemplate.queryForObject("""
                SELECT id FROM team_invitation WHERE project_id = ? AND status = 'PENDING'
                ORDER BY created_at DESC LIMIT 1
                """, String.class, projectId);
        mockMvc.perform(post("/api/v1/invitations/{id}/responses", invitationId)
                        .header("Authorization", bearer(token))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"decision\":\"ACCEPT\"}"))
                .andExpect(status().isOk());
    }

    private String statusOf(String fileId) {
        return jdbcTemplate.queryForObject("SELECT status FROM stored_file WHERE id = ?", String.class, fileId);
    }

    private int fileCount() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM stored_file", Integer.class);
    }

    private static String tokenPath(String url) {
        return url.substring(url.indexOf("/internal-file-transfer/"));
    }

    private String login(String loginId, String password) throws Exception {
        return data(mockMvc.perform(post("/api/v1/auth/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("loginId", loginId, "password", password))))
                .andExpect(status().isOk()).andReturn()).get("accessToken").asText();
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private JsonNode data(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
    }

    private static void deleteDirectory(Path root) throws Exception {
        if (!Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignored) {
                    // 测试目录清理失败不掩盖业务断言。
                }
            });
        }
    }

    private record Ready(String fileId, String token, String url, JsonNode body) {
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
