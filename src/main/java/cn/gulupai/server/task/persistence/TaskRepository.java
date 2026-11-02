package cn.gulupai.server.task.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * 任务写路径锁顺序由服务层保证：先 project，再 task id 升序。
 */
@Repository
public class TaskRepository {

    private final JdbcTemplate jdbc;

    private final RowMapper<TaskRow> taskMapper = (rs, n) -> new TaskRow(
            rs.getString("id"), rs.getString("project_id"), rs.getString("stage_id"),
            rs.getString("title"), rs.getString("description"), rs.getString("mode"),
            rs.getString("owner_id"),
            rs.getObject("due_at", OffsetDateTime.class) == null ? null
                    : rs.getObject("due_at", OffsetDateTime.class).toInstant(),
            rs.getString("status"), rs.getInt("revision"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant());

    private final RowMapper<CriterionRow> criterionMapper = (rs, n) -> new CriterionRow(
            rs.getString("id"), rs.getString("task_id"), rs.getString("unit_id"),
            rs.getString("text"), rs.getInt("position"));

    private final RowMapper<DependencyRow> dependencyMapper = (rs, n) -> new DependencyRow(
            rs.getString("task_id"), rs.getString("depends_on_task_id"), rs.getString("gate"));

    public TaskRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertTask(TaskRow row, Instant at) {
        OffsetDateTime time = OffsetDateTime.ofInstant(at, ZoneOffset.UTC);
        jdbc.update("""
                INSERT INTO task
                    (id, project_id, stage_id, title, description, mode, owner_id, due_at,
                     status, revision, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, row.id(), row.projectId(), row.stageId(), row.title(), row.description(),
                row.mode(), row.ownerId(),
                row.dueAt() == null ? null : OffsetDateTime.ofInstant(row.dueAt(), ZoneOffset.UTC),
                row.status(), row.revision(), time, time);
    }

    public Optional<TaskRow> findTask(String id) {
        return jdbc.query("SELECT * FROM task WHERE id = ?", taskMapper, id).stream().findFirst();
    }

    public Optional<TaskRow> lockTask(String id) {
        return jdbc.query("SELECT * FROM task WHERE id = ? FOR UPDATE", taskMapper, id).stream().findFirst();
    }

    public List<TaskRow> tasksForProject(String projectId) {
        return jdbc.query("""
                SELECT * FROM task WHERE project_id = ? ORDER BY created_at, id
                """, taskMapper, projectId);
    }

    public int countTasks(String projectId) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM task WHERE project_id = ?",
                Integer.class, projectId);
        return count == null ? 0 : count;
    }

    public int claim(String id, int revision, String ownerId, String status, Instant at) {
        return jdbc.update("""
                UPDATE task
                SET owner_id = ?, status = ?, revision = revision + 1, updated_at = ?
                WHERE id = ? AND revision = ? AND owner_id IS NULL AND status = 'TODO' AND mode = 'ORDINARY'
                """, ownerId, status, OffsetDateTime.ofInstant(at, ZoneOffset.UTC), id, revision);
    }

    public int start(String id, int revision, String ownerId, Instant at) {
        return jdbc.update("""
                UPDATE task
                SET status = 'IN_PROGRESS', revision = revision + 1, updated_at = ?
                WHERE id = ? AND revision = ? AND owner_id = ? AND status = 'TODO'
                """, OffsetDateTime.ofInstant(at, ZoneOffset.UTC), id, revision, ownerId);
    }

    public int setStatus(String id, int revision, String status, Instant at) {
        return jdbc.update("""
                UPDATE task SET status = ?, revision = revision + 1, updated_at = ?
                WHERE id = ? AND revision = ?
                """, status, OffsetDateTime.ofInstant(at, ZoneOffset.UTC), id, revision);
    }

    public int bindAgreement(String id, int revision, int agreementVersion, Instant at) {
        return jdbc.update("""
                UPDATE task
                SET agreement_version = ?, revision = revision + 1, updated_at = ?
                WHERE id = ? AND revision = ?
                """, agreementVersion, OffsetDateTime.ofInstant(at, ZoneOffset.UTC), id, revision);
    }

    public void reassignOwner(String id, String ownerId) {
        jdbc.update("UPDATE task SET owner_id = ? WHERE id = ?", ownerId, id);
    }

    public void setHandoverBlocked(String id, boolean blocked) {
        jdbc.update("UPDATE task SET handover_blocked = ? WHERE id = ?", blocked, id);
    }

    public boolean handoverBlocked(String id) {
        Boolean blocked = jdbc.queryForObject("SELECT handover_blocked FROM task WHERE id = ?",
                Boolean.class, id);
        return Boolean.TRUE.equals(blocked);
    }

    public int moveStage(String id, int revision, String stageId, Instant at) {
        return jdbc.update("""
                UPDATE task
                SET stage_id = ?, revision = revision + 1, updated_at = ?
                WHERE id = ? AND revision = ?
                """, stageId, OffsetDateTime.ofInstant(at, ZoneOffset.UTC), id, revision);
    }

    public void insertCriterion(String id, String taskId, String text, int position) {
        jdbc.update("""
                INSERT INTO criterion (id, task_id, text, position, standard_version)
                VALUES (?, ?, ?, ?, 1)
                """, id, taskId, text, position);
    }

    public List<CriterionRow> criteria(String taskId) {
        return jdbc.query("SELECT * FROM criterion WHERE task_id = ? ORDER BY position, id",
                criterionMapper, taskId);
    }

    public void replaceDependencies(String taskId, List<DependencyRow> dependencies) {
        jdbc.update("DELETE FROM task_dependency WHERE task_id = ?", taskId);
        for (DependencyRow dependency : dependencies) {
            jdbc.update("""
                    INSERT INTO task_dependency (task_id, depends_on_task_id, gate)
                    VALUES (?, ?, ?)
                    """, dependency.taskId(), dependency.dependsOnTaskId(), dependency.gate());
        }
    }

    public List<DependencyRow> dependenciesOf(String taskId) {
        return jdbc.query("SELECT * FROM task_dependency WHERE task_id = ? ORDER BY depends_on_task_id",
                dependencyMapper, taskId);
    }

    public List<DependencyRow> dependenciesInProject(String projectId) {
        return jdbc.query("""
                SELECT d.* FROM task_dependency d
                JOIN task t ON t.id = d.task_id
                WHERE t.project_id = ?
                """, dependencyMapper, projectId);
    }

    public boolean stageReferenced(String stageId) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM task WHERE stage_id = ?",
                Integer.class, stageId);
        return count != null && count > 0;
    }

    public void shiftStagePositions(String projectId) {
        jdbc.update("UPDATE project_stage SET position = position + 10000 WHERE project_id = ?", projectId);
    }

    public void insertStage(String id, String projectId, String name, int position) {
        jdbc.update("""
                INSERT INTO project_stage (id, project_id, name, position, revision)
                VALUES (?, ?, ?, ?, 1)
                """, id, projectId, name, position);
    }

    public void updateStage(String id, String projectId, String name, int position) {
        jdbc.update("""
                UPDATE project_stage
                SET name = ?, position = ?, revision = revision + 1, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND project_id = ?
                """, name, position, id, projectId);
    }

    public void deleteStage(String id, String projectId) {
        jdbc.update("DELETE FROM project_stage WHERE id = ? AND project_id = ?", id, projectId);
    }

    public int updateColor(String projectId, String userId, String color, int expectedRevision, Instant at) {
        int updated = jdbc.update("""
                UPDATE membership
                SET color = ?, color_revision = color_revision + 1, updated_at = CURRENT_TIMESTAMP
                WHERE project_id = ? AND user_id = ? AND status = 'ACTIVE' AND color_revision = ?
                """, color, projectId, userId, expectedRevision);
        if (updated == 1) {
            jdbc.update("""
                    INSERT INTO user_project_color (project_id, user_id, color_key, revision, updated_at)
                    VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT (project_id, user_id) DO UPDATE
                    SET color_key = EXCLUDED.color_key, revision = EXCLUDED.revision, updated_at = EXCLUDED.updated_at
                    """, projectId, userId, color, expectedRevision + 1,
                    OffsetDateTime.ofInstant(at, ZoneOffset.UTC));
        }
        return updated;
    }

    public record TaskRow(String id, String projectId, String stageId, String title, String description,
                          String mode, String ownerId, Instant dueAt, String status, int revision,
                          Instant createdAt) {
    }

    public record CriterionRow(String id, String taskId, String unitId, String text, int position) {
    }

    public record DependencyRow(String taskId, String dependsOnTaskId, String gate) {
    }
}
