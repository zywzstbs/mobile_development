package cn.gulupai.server.report;

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

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(ReportP10IT.TestClockConfig.class)
class ReportP10IT {

    private static final Instant BASE = Instant.parse("2026-10-31T00:00:00Z");
    private static final String SCHOOL = "school_demo_01";
    private static final String TERM = "term_demo_2026a";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MutableClock clock;
    @Autowired private AdminImportService users;
    @Autowired private AcademicImportService academics;

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

    @Test
    void deliveryPreviewBindsCurrentSourcesAndFinalReportArchivesOnlyCurrentSnapshot() throws Exception {
        String leader = login("student_demo_01", "demo-password-01");
        Project project = createProject(leader, "p10-final");
        String taskId = addCompletedTask(project, "已完成任务");

        lifecycle(leader, project.id(), 1, "ENTER_CLOSING", null, null, "p10-close");
        assertThat(jdbc.queryForObject("SELECT closing_cycle FROM project WHERE id = ?", Integer.class, project.id()))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT recruitment_open FROM project WHERE id = ?", Boolean.class, project.id()))
                .isFalse();

        mockMvc.perform(get("/api/v1/projects/{id}/delivery-release-draft", project.id())
                        .header("Authorization", bearer(leader)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.draft").isEmpty());

        String draftBody = deliveryDraftBody(0, "首版交付");
        JsonNode firstDraft = data(mockMvc.perform(put("/api/v1/projects/{id}/delivery-release-draft", project.id())
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "p10-draft-1")
                        .contentType(MediaType.APPLICATION_JSON).content(draftBody))
                .andExpect(status().isOk()).andReturn());
        assertThat(firstDraft.path("draft").path("revision").asInt()).isEqualTo(1);

        String firstSource = firstDraft.path("sourceKey").asText();
        JsonNode preview = data(mockMvc.perform(post("/api/v1/projects/{id}/delivery-release-previews", project.id())
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "p10-preview-stale")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("expectedSourceKey", firstSource))))
                .andExpect(status().isOk()).andReturn());
        assertThat(preview.path("expiresAt").asText()).isEqualTo("2026-10-31T00:10:00Z");
        assertThat(preview.path("sources").isArray()).isTrue();

        data(mockMvc.perform(put("/api/v1/projects/{id}/delivery-release-draft", project.id())
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "p10-draft-2")
                        .contentType(MediaType.APPLICATION_JSON).content(deliveryDraftBody(1, "更新后的交付")))
                .andExpect(status().isOk()).andReturn());
        mockMvc.perform(post("/api/v1/projects/{id}/delivery-releases", project.id())
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "p10-publish-stale")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("previewToken", preview.path("previewToken").asText()))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PREVIEW_STALE"));

        JsonNode currentDraft = data(mockMvc.perform(get("/api/v1/projects/{id}/delivery-release-draft", project.id())
                        .header("Authorization", bearer(leader)))
                .andExpect(status().isOk()).andReturn());
        JsonNode currentPreview = preview(leader, project.id(), currentDraft.path("sourceKey").asText(), "p10-preview-current");
        JsonNode release = data(mockMvc.perform(post("/api/v1/projects/{id}/delivery-releases", project.id())
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "p10-publish-current")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("previewToken", currentPreview.path("previewToken").asText()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.confirmedBy").value("user_demo_student_01"))
                .andReturn());

        mockMvc.perform(get("/api/v1/projects/{id}/delivery-releases", project.id())
                        .header("Authorization", bearer(leader)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1));

        JsonNode reflection = data(mockMvc.perform(put("/api/v1/projects/{id}/me/reflection", project.id())
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "p10-reflection")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"expectedRevision":0,"workSummary":"完成接口","learning":"掌握事务",
                                 "problems":"处理时序","improvements":"增加并发场景"}
                                """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.revision").value(1)).andReturn());
        assertThat(reflection.path("userId").asText()).isEqualTo("user_demo_student_01");
        data(mockMvc.perform(put("/api/v1/projects/{id}/overall-reflection", project.id())
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "p10-overall")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"expectedRevision":0,"summary":"完成课程设计","coordination":"按职责协作",
                                 "improvements":"提前检查交付"}
                                """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.revision").value(1)).andReturn());

        JsonNode report = data(mockMvc.perform(get("/api/v1/projects/{id}/report", project.id())
                        .header("Authorization", bearer(leader)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.data.overview.taskCount").value(1))
                .andExpect(jsonPath("$.data.data.divisionAndProcess[0].taskId").value(taskId))
                .andExpect(jsonPath("$.data.data.retrospective.members[0].missing").value(false))
                .andExpect(jsonPath("$.data.data.retrospective.overall.summary").value("完成课程设计"))
                .andReturn());
        JsonNode finalSnapshot = createSnapshot(leader, project.id(), report.path("sourceKey").asText(),
                "FINAL", "最终报告", 1, "", "p10-final-report-1");
        JsonNode replay = createSnapshot(leader, project.id(), report.path("sourceKey").asText(),
                "FINAL", "最终报告", 1, "", "p10-final-report-1");
        assertThat(replay.path("id").asText()).isEqualTo(finalSnapshot.path("id").asText());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM report_snapshot WHERE project_id = ?", Integer.class,
                project.id())).isEqualTo(1);

        jdbc.update("UPDATE task SET status = 'TODO' WHERE id = ?", taskId);
        mockMvc.perform(post("/api/v1/projects/{id}/lifecycle-actions", project.id())
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "p10-archive-unfinished")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("expectedRevision", 2,
                                "action", "ARCHIVE", "expectedReportId", finalSnapshot.path("id").asText(),
                                "expectedReleaseId", release.path("id").asText()))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        assertThat(jdbc.queryForObject("SELECT status FROM project WHERE id = ?", String.class, project.id()))
                .isEqualTo("CLOSING");

        jdbc.update("UPDATE task SET status = 'DONE' WHERE id = ?", taskId);
        jdbc.update("UPDATE task SET title = ? WHERE id = ?", "任务资料已更新", taskId);
        mockMvc.perform(post("/api/v1/projects/{id}/lifecycle-actions", project.id())
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "p10-archive-stale")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("expectedRevision", 2,
                                "action", "ARCHIVE", "expectedReportId", finalSnapshot.path("id").asText(),
                                "expectedReleaseId", release.path("id").asText()))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SOURCE_CHANGED"));
        assertThat(jdbc.queryForObject("SELECT status FROM project WHERE id = ?", String.class, project.id()))
                .isEqualTo("CLOSING");

        JsonNode refreshedDraft = data(mockMvc.perform(get("/api/v1/projects/{id}/delivery-release-draft", project.id())
                        .header("Authorization", bearer(leader)))
                .andExpect(status().isOk()).andReturn());
        JsonNode refreshedPreview = preview(leader, project.id(), refreshedDraft.path("sourceKey").asText(),
                "p10-preview-refreshed");
        JsonNode refreshedRelease = data(mockMvc.perform(post("/api/v1/projects/{id}/delivery-releases", project.id())
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "p10-publish-refreshed")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("previewToken", refreshedPreview.path("previewToken").asText()))))
                .andExpect(status().isOk()).andReturn());
        JsonNode refreshedReport = data(mockMvc.perform(get("/api/v1/projects/{id}/report", project.id())
                        .header("Authorization", bearer(leader)))
                .andExpect(status().isOk()).andReturn());
        JsonNode refreshedSnapshot = createSnapshot(leader, project.id(), refreshedReport.path("sourceKey").asText(),
                "FINAL", "修订后的最终报告", 1, "", "p10-final-report-2");
        lifecycle(leader, project.id(), 2, "ARCHIVE", refreshedSnapshot.path("id").asText(),
                refreshedRelease.path("id").asText(), "p10-archive-current");
        assertThat(jdbc.queryForObject("SELECT status FROM project WHERE id = ?", String.class, project.id()))
                .isEqualTo("ARCHIVED");
        mockMvc.perform(get("/api/v1/report-snapshots/{id}", refreshedSnapshot.path("id").asText())
                        .header("Authorization", bearer(leader)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.type").value("FINAL"));
        mockMvc.perform(get("/api/v1/projects/{id}/report-snapshots", project.id())
                        .header("Authorization", bearer(leader)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(2));
        mockMvc.perform(put("/api/v1/projects/{id}/delivery-release-draft", project.id())
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "p10-post-archive-draft")
                        .contentType(MediaType.APPLICATION_JSON).content(deliveryDraftBody(2, "不允许的改动")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PROJECT_READ_ONLY"));
    }

    @Test
    void emptyProjectUsesEmptySnapshotAndArchivesWithoutDeliveryRelease() throws Exception {
        String leader = login("student_demo_01", "demo-password-01");
        Project project = createProject(leader, "p10-empty");
        lifecycle(leader, project.id(), 1, "ENTER_CLOSING", null, null, "p10-empty-close");
        JsonNode report = data(mockMvc.perform(get("/api/v1/projects/{id}/report", project.id())
                        .header("Authorization", bearer(leader)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.data.overview.taskCount").value(0)).andReturn());
        JsonNode empty = createSnapshot(leader, project.id(), report.path("sourceKey").asText(),
                "EMPTY", "空项目说明", 1, "项目未创建任务或正式分工", "p10-empty-report");
        lifecycle(leader, project.id(), 2, "ARCHIVE", empty.path("id").asText(), null, "p10-empty-archive");
        assertThat(jdbc.queryForObject("SELECT status FROM project WHERE id = ?", String.class, project.id()))
                .isEqualTo("ARCHIVED");
        assertThat(jdbc.queryForObject("SELECT release_id FROM report_snapshot WHERE id = ?", String.class,
                empty.path("id").asText())).isNull();
        assertThat(jdbc.queryForObject("SELECT empty_reason FROM report_snapshot WHERE id = ?", String.class,
                empty.path("id").asText())).isEqualTo("项目未创建任务或正式分工");
        mockMvc.perform(post("/api/v1/projects/{id}/lifecycle-actions", project.id())
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "p10-unarchive")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":3,\"action\":\"RESTORE_ACTIVE\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PROJECT_READ_ONLY"));
    }

    @Test
    void emptyCannotStandInForAProjectWithTaskHistory() throws Exception {
        String leader = login("student_demo_01", "demo-password-01");
        Project project = createProject(leader, "p10-not-empty");
        addCompletedTask(project, "曾经建立的任务");
        lifecycle(leader, project.id(), 1, "ENTER_CLOSING", null, null, "p10-not-empty-close");
        JsonNode report = data(mockMvc.perform(get("/api/v1/projects/{id}/report", project.id())
                        .header("Authorization", bearer(leader)))
                .andExpect(status().isOk()).andReturn());
        mockMvc.perform(post("/api/v1/projects/{id}/report-snapshots", project.id())
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "p10-invalid-empty")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(snapshotBody("EMPTY", "误用空报告", report.path("sourceKey").asText(), 1,
                                "这里不应创建EMPTY")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM report_snapshot WHERE project_id = ?", Integer.class,
                project.id())).isZero();
    }

    @Test
    void restoreAndReenterClosingStartsANewCycle() throws Exception {
        String leader = login("student_demo_01", "demo-password-01");
        Project project = createProject(leader, "p10-cycle");
        lifecycle(leader, project.id(), 1, "ENTER_CLOSING", null, null, "p10-cycle-close-1");
        lifecycle(leader, project.id(), 2, "RESTORE_ACTIVE", null, null, "p10-cycle-restore");
        assertThat(jdbc.queryForObject("SELECT status FROM project WHERE id = ?", String.class, project.id()))
                .isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT closing_cycle FROM project WHERE id = ?", Integer.class, project.id()))
                .isEqualTo(1);

        lifecycle(leader, project.id(), 3, "ENTER_CLOSING", null, null, "p10-cycle-close-2");
        assertThat(jdbc.queryForObject("SELECT closing_cycle FROM project WHERE id = ?", Integer.class, project.id()))
                .isEqualTo(2);
    }

    @Test
    void unconfirmedAcceptedContributionBlocksArchive() throws Exception {
        String leader = login("student_demo_01", "demo-password-01");
        Project project = createProject(leader, "p10-unconfirmed");
        String unitId = addUnconfirmedContribution(project);
        mockMvc.perform(get("/api/v1/projects/{id}/report", project.id())
                        .header("Authorization", bearer(leader)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.data.deliveriesAndAcceptance[0].criteria").isArray())
                .andExpect(jsonPath("$.data.data.deliveriesAndAcceptance[0].criteria.length()").value(0))
                .andExpect(jsonPath("$.data.data.deliveriesAndAcceptance[0].unitsSnapshot[0].id")
                        .value(unitId))
                .andExpect(jsonPath("$.data.data.deliveriesAndAcceptance[0].unitsSnapshot[0].shares[0].accepted")
                        .value(true))
                .andExpect(jsonPath("$.data.data.deliveriesAndAcceptance[0].unitsSnapshot[0].contributionConfirmations.length()")
                        .value(0));
        lifecycle(leader, project.id(), 1, "ENTER_CLOSING", null, null, "p10-unconfirmed-close");

        mockMvc.perform(post("/api/v1/projects/{id}/lifecycle-actions", project.id())
                        .header("Authorization", bearer(leader)).header("Idempotency-Key", "p10-unconfirmed-archive")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":2,\"action\":\"ARCHIVE\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        assertThat(jdbc.queryForObject("SELECT status FROM project WHERE id = ?", String.class, project.id()))
                .isEqualTo("CLOSING");
    }

    private JsonNode preview(String token, String projectId, String sourceKey, String key) throws Exception {
        return data(mockMvc.perform(post("/api/v1/projects/{id}/delivery-release-previews", projectId)
                        .header("Authorization", bearer(token)).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("expectedSourceKey", sourceKey))))
                .andExpect(status().isOk()).andReturn());
    }

    private JsonNode createSnapshot(String token, String projectId, String sourceKey, String type,
                                    String name, int cycle, String reason, String key) throws Exception {
        String body = snapshotBody(type, name, sourceKey, cycle, reason);
        return data(mockMvc.perform(post("/api/v1/projects/{id}/report-snapshots", projectId)
                        .header("Authorization", bearer(token)).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn());
    }

    private String snapshotBody(String type, String name, String sourceKey, int cycle, String reason)
            throws Exception {
        return mapper.writeValueAsString(Map.of("type", type, "name", name,
                "expectedSourceKey", sourceKey, "closingCycle", cycle, "emptyReason", reason));
    }

    private String deliveryDraftBody(int revision, String title) throws Exception {
        Map<String, Object> material = Map.of("kind", "LINK", "url", "https://example.test/deliverable",
                "title", title);
        return mapper.writeValueAsString(Map.of("expectedRevision", revision,
                "work", List.of(material), "report", List.of(), "demo", List.of(), "notes", "演示交付"));
    }

    private void lifecycle(String token, String projectId, int revision, String action,
                           String reportId, String releaseId, String key) throws Exception {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("expectedRevision", revision);
        body.put("action", action);
        if (reportId != null) body.put("expectedReportId", reportId);
        if (releaseId != null) body.put("expectedReleaseId", releaseId);
        mockMvc.perform(post("/api/v1/projects/{id}/lifecycle-actions", projectId)
                        .header("Authorization", bearer(token)).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)))
                .andExpect(status().isOk());
    }

    private Project createProject(String token, String key) throws Exception {
        String matchBody = mapper.writeValueAsString(Map.of("schoolId", SCHOOL, "termId", TERM,
                "teacherName", "示例教师", "slots", slots(), "courseNameId", "course_name_demo_mobile"));
        JsonNode match = data(mockMvc.perform(post("/api/v1/squares/matches")
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON).content(matchBody))
                .andExpect(status().isOk()).andReturn());
        String squareBody = mapper.writeValueAsString(Map.of("schoolId", SCHOOL, "termId", TERM,
                "teacherName", "示例教师", "slots", slots(), "courseNameId", "course_name_demo_mobile",
                "candidateCheckToken", match.path("candidateCheckToken").asText()));
        JsonNode square = data(mockMvc.perform(post("/api/v1/squares")
                        .header("Authorization", bearer(token)).header("Idempotency-Key", key + "-square")
                        .contentType(MediaType.APPLICATION_JSON).content(squareBody))
                .andExpect(status().isOk()).andReturn());
        String assignmentBody = mapper.writeValueAsString(Map.of("name", "课程设计", "requirements", "完成可演示应用",
                "hardDueAt", "2026-12-15T15:59:59Z", "minMembers", 2, "maxMembers", 6,
                "sourceReferences", List.of()));
        JsonNode assignment = data(mockMvc.perform(post("/api/v1/squares/{id}/assignments", square.path("id").asText())
                        .header("Authorization", bearer(token)).header("Idempotency-Key", key + "-assignment")
                        .contentType(MediaType.APPLICATION_JSON).content(assignmentBody))
                .andExpect(status().isOk()).andReturn());
        String teacher = login("teacher_demo_01", "demo-password-03");
        mockMvc.perform(post("/api/v1/assignments/{id}/confirmations", assignment.path("id").asText())
                        .header("Authorization", bearer(teacher)).header("Idempotency-Key", key + "-confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"decision\":\"CONFIRM_NEW\",\"reason\":\"ok\"}"))
                .andExpect(status().isOk());
        String createBody = mapper.writeValueAsString(Map.of("squareId", square.path("id").asText(),
                "assignmentId", assignment.path("id").asText(), "mode", "QUICK", "name", "任务组",
                "capacity", 3, "discussionTime", "周三", "roles", List.of()));
        JsonNode project = data(mockMvc.perform(post("/api/v1/projects")
                        .header("Authorization", bearer(token)).header("Idempotency-Key", key + "-project")
                        .contentType(MediaType.APPLICATION_JSON).content(createBody))
                .andExpect(status().isOk()).andReturn());
        return new Project(project.path("id").asText(), project.path("stages").get(0).path("id").asText());
    }

    private List<Map<String, Object>> slots() {
        return List.of(Map.of("weekday", 3, "periodId", "period_demo_2", "weekPattern", "ALL",
                "startWeek", 1, "endWeek", 16, "customWeeks", List.of()));
    }

    private String addCompletedTask(Project project, String title) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO task(id, project_id, stage_id, title, description, mode, owner_id,
                                 due_at, status, revision, created_at, updated_at)
                VALUES (?, ?, ?, ?, '', 'ORDINARY', NULL, NULL, 'DONE', 1, ?, ?)
                """, id, project.id(), project.stageId(), title,
                java.time.OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC),
                java.time.OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
        return id;
    }

    private String addUnconfirmedContribution(Project project) throws Exception {
        String userId = jdbc.queryForObject("SELECT user_id FROM membership WHERE project_id = ? AND status = 'ACTIVE'",
                String.class, project.id());
        String taskId = addCompletedTask(project, "待本人确认贡献的任务");
        String agreementId = UUID.randomUUID().toString();
        String unitId = UUID.randomUUID().toString();
        String submissionId = UUID.randomUUID().toString();
        String acceptanceId = UUID.randomUUID().toString();
        java.time.OffsetDateTime at = java.time.OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        String unitDueSnapshot = mapper.writeValueAsString(Map.of(unitId, at.toInstant().toString()));
        String agreementSnapshot = mapper.writeValueAsString(Map.of(
                "ownerId", userId,
                "dueAt", at.toInstant().toString(),
                "units", List.of(Map.of("id", unitId, "title", "交付项", "points", "100.00",
                        "shares", List.of(Map.of("userId", userId, "points", "100.00",
                                "responsibility", "完成交付项")), "criteria", List.of())),
                "reviewerPolicy", Map.of("userId", userId, "kind", "SELF_CHECK")));

        jdbc.update("""
                INSERT INTO agreement(id, task_id, version, status, base_task_revision, plan_json,
                                      revision, created_at, updated_at)
                VALUES (?, ?, 1, 'ACTIVE', 1, '{}'::jsonb, 1, ?, ?)
                """, agreementId, taskId, at, at);
        jdbc.update("""
                INSERT INTO delivery_unit(id, task_id, client_key, title, due_at, points, revision, position)
                VALUES (?, ?, 'p10-unit', '交付项', ?, 100, 1, 1)
                """, unitId, taskId, at);
        jdbc.update("""
                INSERT INTO unit_share(id, unit_id, user_id, points, responsibility, agreement_version, signed)
                VALUES (?, ?, ?, 100, '完成交付项', 1, true)
                """, UUID.randomUUID().toString(), unitId, userId);
        jdbc.update("""
                INSERT INTO submission(id, task_id, sequence, submitted_by, status, summary,
                    artifacts_json, unit_ids_json, criteria_snapshot, due_snapshot, unit_due_snapshot_json,
                    agreement_snapshot, agreement_version, submitted_at, revision)
                VALUES (?, ?, 1, ?, 'REVIEWED', '已完成', '[]'::jsonb, ?::jsonb, '[]'::jsonb,
                    NULL, ?::jsonb, ?::jsonb, 1, ?, 1)
                """, submissionId, taskId, userId, mapper.writeValueAsString(List.of(unitId)),
                unitDueSnapshot, agreementSnapshot, at);
        jdbc.update("""
                INSERT INTO acceptance(id, submission_id, reviewer_id, kind, checks_json, decision, accepted_at)
                VALUES (?, ?, ?, 'SELF_CHECK', '{}'::jsonb, 'PASSED', ?)
                """, acceptanceId, submissionId, userId, at);
        jdbc.update("""
                INSERT INTO unit_acceptance(unit_id, submission_id, acceptance_id, passed)
                VALUES (?, ?, ?, true)
                """, unitId, submissionId, acceptanceId);
        return unitId;
    }

    private String login(String loginId, String password) throws Exception {
        return data(mockMvc.perform(post("/api/v1/auth/sessions").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("loginId", loginId, "password", password))))
                .andExpect(status().isOk()).andReturn()).path("accessToken").asText();
    }

    private JsonNode data(MvcResult result) throws Exception {
        return mapper.readTree(result.getResponse().getContentAsString()).path("data");
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private void clearAll() {
        for (String table : List.of("report_snapshot", "delivery_release", "delivery_release_preview",
                "delivery_release_draft", "overall_reflection", "project_reflection",
                "file_reference", "file_authorization", "cleanup_plan", "stored_file",
                "personnel_handoff", "material_request", "personnel_record", "personnel_case_grant", "personnel_case",
                "removal_ballot", "handover_ack", "handover_item", "member_change_event",
                "member_change", "removal_vote", "teacher_summary_consent", "teacher_summary",
                "grade_appeal", "grade_snapshot", "grade_publication", "settlement", "ledger_correction",
                "grade_draft", "grade_rule", "contribution_confirmation", "unit_acceptance", "acceptance",
                "submission", "delivery_draft", "proposal_response", "unit_change_proposal",
                "reviewer_invitation", "reviewer_arrangement", "agreement_ack", "unit_share",
                "task_dependency", "criterion", "delivery_unit", "agreement", "task", "user_project_color",
                "role_ack", "role_negotiation", "announcement_reminder", "announcement_preview", "announcement_receipt",
                "announcement_recipient", "announcement_version", "help_article", "help_feedback", "support_route", "support_ticket",
                "support_reply", "support_history", "operator_grant", "content_report",
                "report_handling", "announcement",
                "notification_preference", "notification", "board_topic", "board_reply", "content_revision", "topic_follow",
                "chat_counter", "chat_message", "chat_change", "chat_read_state",
                "notification_outbox", "dice_round", "dice_roll", "dice_participant", "dice_session", "ai_reference", "peer_review_version", "peer_review",
                "work_version", "work", "project_code", "seat_reservation", "team_invitation",
                "team_application", "assignment_team_slot", "membership", "role_slot", "project_stage",
                "project", "teacher_transfer", "teacher_claim", "teacher_assignment", "class_grant",
                "course_code", "course_join_request", "cursor_snapshot", "assignment", "course_membership",
                "merge_ballot", "square_merge", "preview_token", "square", "verified_enrollment",
                "verified_course", "course_name", "period", "term", "operation_receipt", "access_token",
                "auth_session", "audit_event", "rate_window", "import_batch", "user_role", "user_profile",
                "app_user", "school")) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    record Project(String id, String stageId) {
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
