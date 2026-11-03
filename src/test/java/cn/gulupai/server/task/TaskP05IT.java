package cn.gulupai.server.task;

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

import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TaskP05IT.TestClockConfig.class)
class TaskP05IT {

    private static final Instant BASE = Instant.parse("2026-10-31T00:00:00Z");
    private static final String SCHOOL = "school_demo_01";
    private static final String TERM = "term_demo_2026a";
    private static final String ASSISTANT = "user_demo_assistant_01";

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
                "merge_ballot", "square_merge", "preview_token", "square", "verified_enrollment", "verified_course", "course_name",
                "period", "term", "operation_receipt", "access_token", "auth_session", "audit_event",
                "rate_window", "import_batch", "user_role", "user_profile", "app_user", "school"));
    }

    @Test
    void ordinaryTaskBoardAndRejectsFormalCreate() throws Exception {
        String leader = login("student_demo_01", "demo-password-01");
        String member = login("assistant_demo_01", "demo-password-04");
        String teacher = login("teacher_demo_01", "demo-password-03");
        Project project = project(leader, "p05-create");
        invite(leader, project.id(), ASSISTANT, "p05-create-invite");
        acceptLatestInvitation(member, project.id(), "p05-create-accept");
        String body = taskBody(project.stageId(), "登录页面", List.of());
        JsonNode created = data(mockMvc.perform(post("/api/v1/projects/{id}/tasks", project.id())
                        .header("Authorization", bearer(leader))
                        .header("Idempotency-Key", "p05-task")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mode").value("ORDINARY"))
                .andExpect(jsonPath("$.data.status").value("TODO"))
                .andExpect(jsonPath("$.data.ownerId").isEmpty())
                .andExpect(jsonPath("$.data.ownerName").value("未认领"))
                .andExpect(jsonPath("$.data.units").isEmpty())
                .andExpect(jsonPath("$.data.claimable").value(true))
                .andExpect(jsonPath("$.data.criteria[0].id").isNotEmpty())
                .andReturn());
        String taskId = created.get("id").asText();
        assertThat(created.get("reviewerAssignment").isObject()).isTrue();
        assertThat(created.get("reviewerAssignment").size()).isZero();
        JsonNode replay = data(mockMvc.perform(post("/api/v1/projects/{id}/tasks", project.id())
                        .header("Authorization", bearer(leader))
                        .header("Idempotency-Key", "p05-task")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn());
        assertThat(replay.get("id").asText()).isEqualTo(taskId);
        mockMvc.perform(get("/api/v1/projects/{id}/board", project.id()).header("Authorization", bearer(member)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tasks[0].id").value(taskId))
                .andExpect(jsonPath("$.data.members.length()").value(2));
        mockMvc.perform(get("/api/v1/projects/{id}/tasks", project.id()).header("Authorization", bearer(leader)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1));
        mockMvc.perform(get("/api/v1/tasks/{id}", taskId).header("Authorization", bearer(member)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.title").value("登录页面"));
        mockMvc.perform(post("/api/v1/projects/{id}/tasks", project.id())
                        .header("Authorization", bearer(member))
                        .header("Idempotency-Key", "p05-member-create")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/projects/{id}/tasks", project.id())
                        .header("Authorization", bearer(teacher))
                        .header("Idempotency-Key", "p05-teacher-create")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        int before = taskCount(project.id());
        mockMvc.perform(post("/api/v1/projects/{id}/tasks", project.id())
                        .header("Authorization", bearer(leader))
                        .header("Idempotency-Key", "p05-assessed")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "title", "正式任务", "description", "", "mode", "ASSESSED",
                                "stageId", project.stageId(), "criteria", List.of(), "dependencies", List.of(),
                                "assessedPlan", Map.of("ownerId", "user_demo_student_01",
                                        "dueAt", "2026-11-06T15:59:59Z", "units", List.of())))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        assertThat(taskCount(project.id())).isEqualTo(before);
        mockMvc.perform(post("/api/v1/projects/{id}/tasks", project.id())
                        .header("Authorization", bearer(leader))
                        .header("Idempotency-Key", "p05-unknown")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"x\",\"mode\":\"ORDINARY\",\"stageId\":\"" + project.stageId()
                                + "\",\"criteria\":[{\"text\":\"标准\"}],\"dependencies\":[],"
                                + "\"dueAt\":\"2026-11-06T15:59:59Z\",\"extra\":1}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("FIELD_NOT_ALLOWED"));
        jdbcTemplate.update("UPDATE project SET status = 'CLOSING' WHERE id = ?", project.id());
        mockMvc.perform(post("/api/v1/projects/{id}/tasks", project.id())
                        .header("Authorization", bearer(leader))
                        .header("Idempotency-Key", "p05-closing-create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(taskBody(project.stageId(), "收尾后不能新建", List.of())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROJECT_READ_ONLY"));
    }

    @Test
    void stagesKeepIdentityAndFollowOrder() throws Exception {
        String leader = login("student_demo_01", "demo-password-01");
        Project project = project(leader, "p05-stage");
        JsonNode task = createTask(leader, project, "看板任务", "p05-stage-task");
        int revision = projectRevision(leader, project.id());
        JsonNode replaced = data(mockMvc.perform(put("/api/v1/projects/{id}/stages", project.id())
                        .header("Authorization", bearer(leader))
                        .header("Idempotency-Key", "p05-c03")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "expectedRevision", revision,
                                "stages", List.of(
                                        Map.of("id", "", "name", "测试", "order", 1),
                                        Map.of("id", project.stageId(), "clientKey", "old", "name", "开发", "order", 0))))))
                .andExpect(status().isOk()).andReturn());
        assertThat(replaced.get("stages").get(0).get("id").asText()).isEqualTo(project.stageId());
        assertThat(replaced.get("stages").get(0).get("name").asText()).isEqualTo("开发");
        String createdId = replaced.get("stages").get(1).get("id").asText();
        assertThat(createdId).isNotBlank().isNotEqualTo("").isNotEqualTo(project.stageId());
        assertThat(jdbcTemplate.queryForObject("SELECT stage_id FROM task WHERE id = ?",
                String.class, task.get("id").asText())).isEqualTo(project.stageId());
        int current = projectRevision(leader, project.id());
        mockMvc.perform(put("/api/v1/projects/{id}/stages", project.id())
                        .header("Authorization", bearer(leader))
                        .header("Idempotency-Key", "p05-c03-stranger")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "expectedRevision", current,
                                "stages", List.of(Map.of("id", "stage_missing", "name", "开发"))))))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(put("/api/v1/projects/{id}/stages", project.id())
                        .header("Authorization", bearer(leader))
                        .header("Idempotency-Key", "p05-c03-order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "expectedRevision", current,
                                "stages", List.of(
                                        Map.of("id", project.stageId(), "name", "开发", "order", 0),
                                        Map.of("id", createdId, "name", "测试", "order", 0))))))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(put("/api/v1/projects/{id}/stages", project.id())
                        .header("Authorization", bearer(leader))
                        .header("Idempotency-Key", "p05-c03-delete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "expectedRevision", current,
                                "stages", List.of(Map.of("id", createdId, "name", "测试"))))))
                .andExpect(status().isUnprocessableEntity());
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM project_stage WHERE id = ?",
                Integer.class, project.stageId())).isEqualTo(1);
        List<Map<String, String>> tooMany = new ArrayList<>();
        for (int i = 0; i < 13; i++) {
            tooMany.add(Map.of("id", "", "name", "阶段" + i));
        }
        mockMvc.perform(put("/api/v1/projects/{id}/stages", project.id())
                        .header("Authorization", bearer(leader))
                        .header("Idempotency-Key", "p05-c03-limit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("expectedRevision", current, "stages", tooMany))))
                .andExpect(status().isUnprocessableEntity());
        JsonNode sequential = data(mockMvc.perform(put("/api/v1/projects/{id}/stages", project.id())
                        .header("Authorization", bearer(leader))
                        .header("Idempotency-Key", "p05-c03-array-order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "expectedRevision", current,
                                "stages", List.of(
                                        Map.of("id", project.stageId(), "name", " 开发 "),
                                        Map.of("name", "验收"))))))
                .andExpect(status().isOk()).andReturn());
        assertThat(sequential.get("stages").get(0).get("name").asText()).isEqualTo("开发");
        assertThat(sequential.get("stages").get(1).get("name").asText()).isEqualTo("验收");
    }

    @Test
    void claimStartStageMoveAndColorDoNotCancel() throws Exception {
        String leader = login("student_demo_01", "demo-password-01");
        String member = login("assistant_demo_01", "demo-password-04");
        Project project = project(leader, "p05-flow");
        invite(leader, project.id(), ASSISTANT, "p05-flow-invite");
        acceptLatestInvitation(member, project.id(), "p05-flow-accept");
        JsonNode task = createTask(leader, project, "待认领", "p05-flow-task");
        String taskId = task.get("id").asText();
        mockMvc.perform(post("/api/v1/tasks/{id}/claims", taskId)
                        .header("Authorization", bearer(member))
                        .header("Idempotency-Key", "p05-claim")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("TODO"))
                .andExpect(jsonPath("$.data.ownerId").value(ASSISTANT));
        mockMvc.perform(post("/api/v1/tasks/{id}/starts", taskId)
                        .header("Authorization", bearer(leader))
                        .header("Idempotency-Key", "p05-start-other")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":2}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/tasks/{id}/starts", taskId)
                        .header("Authorization", bearer(member))
                        .header("Idempotency-Key", "p05-start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"));
        String extraStage = data(mockMvc.perform(put("/api/v1/projects/{id}/stages", project.id())
                        .header("Authorization", bearer(leader))
                        .header("Idempotency-Key", "p05-flow-stage")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "expectedRevision", projectRevision(leader, project.id()),
                                "stages", List.of(
                                        Map.of("id", project.stageId(), "name", "默认阶段"),
                                        Map.of("name", "开发"))))))
                .andExpect(status().isOk()).andReturn()).get("stages").get(1).get("id").asText();
        mockMvc.perform(patch("/api/v1/tasks/{id}", taskId)
                        .header("Authorization", bearer(leader))
                        .header("Idempotency-Key", "p05-move")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":3,\"expectedProjectRevision\":"
                                + projectRevision(leader, project.id()) + ",\"stageId\":\"" + extraStage + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stageId").value(extraStage))
                .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"));
        mockMvc.perform(patch("/api/v1/tasks/{id}", taskId)
                        .header("Authorization", bearer(leader))
                        .header("Idempotency-Key", "p05-cancel-field")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":4,\"expectedProjectRevision\":1,\"stageId\":\""
                                + extraStage + "\",\"status\":\"CANCELLED\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("FIELD_NOT_ALLOWED"));
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM task WHERE id = ?", String.class, taskId))
                .isEqualTo("IN_PROGRESS");
        mockMvc.perform(put("/api/v1/projects/{id}/me/color", project.id())
                        .header("Authorization", bearer(member))
                        .header("Idempotency-Key", "p05-color")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":0,\"color\":\"#236b56\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.color").value("#236B56"))
                .andExpect(jsonPath("$.data.revision").value(1));
        mockMvc.perform(put("/api/v1/projects/{id}/me/color", project.id())
                        .header("Authorization", bearer(member))
                        .header("Idempotency-Key", "p05-color-bad")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"color\":\"#000000\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void dependenciesBlockStartAndRejectCycles() throws Exception {
        String leader = login("student_demo_01", "demo-password-01");
        String otherLeader = login("student_demo_02", "demo-password-02");
        Project project = project(leader, "p05-dep");
        Project other = anotherProject(otherLeader, project, "p05-dep-other");
        JsonNode first = createTask(leader, project, "前置", "p05-dep-a");
        JsonNode second = createTask(leader, project, "后续", "p05-dep-b");
        JsonNode foreign = createTask(otherLeader, other, "外项目", "p05-dep-foreign");
        String firstId = first.get("id").asText();
        String secondId = second.get("id").asText();
        mockMvc.perform(put("/api/v1/tasks/{id}/dependencies", secondId)
                        .header("Authorization", bearer(leader))
                        .header("Idempotency-Key", "p05-dep-submit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(dependencyBody(1, firstId, "BEFORE_SUBMIT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dependencies[0].gate").value("BEFORE_SUBMIT"));
        mockMvc.perform(put("/api/v1/tasks/{id}/dependencies", secondId)
                        .header("Authorization", bearer(leader))
                        .header("Idempotency-Key", "p05-dep-start-gate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(dependencyBody(2, firstId, "BEFORE_START")))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/tasks/{id}/claims", secondId)
                        .header("Authorization", bearer(leader))
                        .header("Idempotency-Key", "p05-dep-claim")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":3,\"startNow\":false}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEPENDENCY_BLOCKED"));
        assertThat(jdbcTemplate.queryForObject("SELECT owner_id FROM task WHERE id = ?", String.class, secondId))
                .isNull();
        mockMvc.perform(put("/api/v1/tasks/{id}/dependencies", secondId)
                        .header("Authorization", bearer(leader))
                        .header("Idempotency-Key", "p05-dep-clear")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":3,\"dependencies\":[]}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/tasks/{id}/claims", secondId)
                        .header("Authorization", bearer(leader))
                        .header("Idempotency-Key", "p05-dep-claim-ok")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":4,\"startNow\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"));
        mockMvc.perform(put("/api/v1/tasks/{id}/dependencies", secondId)
                        .header("Authorization", bearer(leader))
                        .header("Idempotency-Key", "p05-dep-after-start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(dependencyBody(5, firstId, "BEFORE_START")))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(put("/api/v1/tasks/{id}/dependencies", secondId)
                        .header("Authorization", bearer(leader))
                        .header("Idempotency-Key", "p05-dep-foreign")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(dependencyBody(5, foreign.get("id").asText(), "BEFORE_SUBMIT")))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(put("/api/v1/tasks/{id}/dependencies", secondId)
                        .header("Authorization", bearer(leader))
                        .header("Idempotency-Key", "p05-dep-forward")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(dependencyBody(5, firstId, "BEFORE_SUBMIT")))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/v1/tasks/{id}/dependencies", firstId)
                        .header("Authorization", bearer(leader))
                        .header("Idempotency-Key", "p05-dep-cycle")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(dependencyBody(1, secondId, "BEFORE_SUBMIT")))
                .andExpect(status().isUnprocessableEntity());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM task_dependency WHERE task_id = ?", Integer.class, firstId)).isZero();
    }

    @Test
    void concurrentClaimsLeaveOneOwner() throws Exception {
        String leader = login("student_demo_01", "demo-password-01");
        String member = login("assistant_demo_01", "demo-password-04");
        Project project = project(leader, "p05-race");
        invite(leader, project.id(), ASSISTANT, "p05-race-invite");
        acceptLatestInvitation(member, project.id(), "p05-race-accept");
        JsonNode task = createTask(leader, project, "争抢", "p05-race-task");
        String taskId = task.get("id").asText();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            List<String> tokens = List.of(leader, member);
            for (int i = 0; i < 2; i++) {
                String token = tokens.get(i);
                String key = "p05-race-claim-" + i;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await(5, java.util.concurrent.TimeUnit.SECONDS);
                    return mockMvc.perform(post("/api/v1/tasks/{id}/claims", taskId)
                                    .header("Authorization", bearer(token))
                                    .header("Idempotency-Key", key)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"expectedRevision\":1,\"startNow\":false}"))
                            .andReturn().getResponse().getStatus();
                }));
            }
            assertThat(ready.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get(15, java.util.concurrent.TimeUnit.SECONDS));
            }
            assertThat(statuses).contains(200);
            assertThat(statuses).contains(409);
        } finally {
            executor.shutdownNow();
        }
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM task WHERE id = ? AND owner_id IS NOT NULL", Integer.class, taskId))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM task WHERE id = ?", String.class, taskId))
                .isEqualTo("TODO");
    }

    @Test
    void taskLimitRejectsThe201st() throws Exception {
        String leader = login("student_demo_01", "demo-password-01");
        Project project = project(leader, "p05-limit");
        OffsetDateTime now = OffsetDateTime.ofInstant(BASE, ZoneOffset.UTC);
        for (int i = 0; i < 200; i++) {
            jdbcTemplate.update("""
                    INSERT INTO task
                        (id, project_id, stage_id, title, description, mode, status, revision, created_at, updated_at)
                    VALUES (?, ?, ?, ?, '', 'ORDINARY', 'TODO', 1, ?, ?)
                    """, UUID.randomUUID().toString(), project.id(), project.stageId(), "填充" + i, now, now);
        }
        mockMvc.perform(post("/api/v1/projects/{id}/tasks", project.id())
                        .header("Authorization", bearer(leader))
                        .header("Idempotency-Key", "p05-limit-create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(taskBody(project.stageId(), "超限", List.of())))
                .andExpect(status().isUnprocessableEntity());
        assertThat(taskCount(project.id())).isEqualTo(200);
    }

    private JsonNode createTask(String token, Project project, String title, String key) throws Exception {
        return data(mockMvc.perform(post("/api/v1/projects/{id}/tasks", project.id())
                        .header("Authorization", bearer(token))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(taskBody(project.stageId(), title, List.of())))
                .andExpect(status().isOk()).andReturn());
    }

    private String taskBody(String stageId, String title, List<Map<String, String>> dependencies) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "title", title, "description", "实现登录与会话", "mode", "ORDINARY",
                "stageId", stageId,
                "criteria", List.of(Map.of("text", "可以演示登录和退出")),
                "dependencies", dependencies,
                "dueAt", "2026-11-06T15:59:59Z"));
    }

    private String dependencyBody(int revision, String taskId, String gate) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "expectedRevision", revision,
                "dependencies", List.of(Map.of("taskId", taskId, "gate", gate))));
    }

    private Project project(String token, String key) throws Exception {
        Bootstrap boot = bootstrap(token, key);
        String body = objectMapper.writeValueAsString(Map.of(
                "squareId", boot.squareId(), "assignmentId", boot.assignmentId(),
                "mode", "QUICK", "name", "任务组", "capacity", 3,
                "discussionTime", "周三", "roles", List.of()));
        JsonNode project = data(mockMvc.perform(post("/api/v1/projects")
                        .header("Authorization", bearer(token))
                        .header("Idempotency-Key", key + "-project")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn());
        return new Project(project.get("id").asText(), project.get("stages").get(0).get("id").asText());
    }

    private Project anotherProject(String token, Project existing, String key) throws Exception {
        String squareId = jdbcTemplate.queryForObject("SELECT square_id FROM project WHERE id = ?",
                String.class, existing.id());
        String assignmentId = jdbcTemplate.queryForObject("SELECT assignment_id FROM project WHERE id = ?",
                String.class, existing.id());
        String body = objectMapper.writeValueAsString(Map.of(
                "squareId", squareId, "assignmentId", assignmentId,
                "mode", "QUICK", "name", "另一组", "capacity", 3,
                "discussionTime", "周三", "roles", List.of()));
        JsonNode project = data(mockMvc.perform(post("/api/v1/projects")
                        .header("Authorization", bearer(token))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn());
        return new Project(project.get("id").asText(), project.get("stages").get(0).get("id").asText());
    }

    private Bootstrap bootstrap(String token, String key) throws Exception {
        String matchBody = objectMapper.writeValueAsString(Map.of(
                "schoolId", SCHOOL, "termId", TERM, "teacherName", "示例教师",
                "slots", List.of(Map.of("weekday", 3, "periodId", "period_demo_2",
                        "weekPattern", "ALL", "startWeek", 1, "endWeek", 16, "customWeeks", List.of())),
                "courseNameId", "course_name_demo_mobile"));
        JsonNode match = data(mockMvc.perform(post("/api/v1/squares/matches")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(matchBody))
                .andExpect(status().isOk()).andReturn());
        String createBody = objectMapper.writeValueAsString(Map.of(
                "schoolId", SCHOOL, "termId", TERM, "teacherName", "示例教师",
                "slots", List.of(Map.of("weekday", 3, "periodId", "period_demo_2",
                        "weekPattern", "ALL", "startWeek", 1, "endWeek", 16, "customWeeks", List.of())),
                "courseNameId", "course_name_demo_mobile",
                "candidateCheckToken", match.get("candidateCheckToken").asText()));
        JsonNode square = data(mockMvc.perform(post("/api/v1/squares")
                        .header("Authorization", bearer(token))
                        .header("Idempotency-Key", key + "-square")
                        .contentType(MediaType.APPLICATION_JSON).content(createBody))
                .andExpect(status().isOk()).andReturn());
        String assignmentBody = objectMapper.writeValueAsString(Map.of(
                "name", "课程设计", "requirements", "完成可演示应用",
                "hardDueAt", "2026-12-15T15:59:59Z", "minMembers", 2, "maxMembers", 6,
                "sourceReferences", List.of()));
        JsonNode assignment = data(mockMvc.perform(post("/api/v1/squares/{id}/assignments", square.get("id").asText())
                        .header("Authorization", bearer(token))
                        .header("Idempotency-Key", key + "-assignment")
                        .contentType(MediaType.APPLICATION_JSON).content(assignmentBody))
                .andExpect(status().isOk()).andReturn());
        String teacher = login("teacher_demo_01", "demo-password-03");
        mockMvc.perform(post("/api/v1/assignments/{id}/confirmations", assignment.get("id").asText())
                        .header("Authorization", bearer(teacher))
                        .header("Idempotency-Key", key + "-confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"decision\":\"CONFIRM_NEW\",\"reason\":\"ok\"}"))
                .andExpect(status().isOk());
        return new Bootstrap(square.get("id").asText(), assignment.get("id").asText());
    }

    private void invite(String leader, String projectId, String userId, String key) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("expectedProjectRevision", projectRevision(leader, projectId));
        body.put("recipientId", userId);
        body.put("message", "加入项目");
        mockMvc.perform(post("/api/v1/projects/{id}/invitations", projectId)
                        .header("Authorization", bearer(leader))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk());
    }

    private void acceptLatestInvitation(String token, String projectId, String key) throws Exception {
        String invitationId = jdbcTemplate.queryForObject("""
                SELECT id FROM team_invitation
                WHERE project_id = ? AND status = 'PENDING'
                ORDER BY created_at DESC LIMIT 1
                """, String.class, projectId);
        mockMvc.perform(post("/api/v1/invitations/{id}/responses", invitationId)
                        .header("Authorization", bearer(token))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"decision\":\"ACCEPT\"}"))
                .andExpect(status().isOk());
    }

    private int projectRevision(String token, String projectId) throws Exception {
        return data(mockMvc.perform(get("/api/v1/projects/{id}", projectId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andReturn()).get("revision").asInt();
    }

    private int taskCount(String projectId) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM task WHERE project_id = ?", Integer.class, projectId);
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

    private record Bootstrap(String squareId, String assignmentId) {
    }

    private record Project(String id, String stageId) {
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
