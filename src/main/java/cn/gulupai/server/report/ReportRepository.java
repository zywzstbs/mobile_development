package cn.gulupai.server.report;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class ReportRepository {

    private final JdbcTemplate jdbc;
    public ReportRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ProjectRow> project(String id, boolean lock) {
        String suffix = lock ? " FOR UPDATE OF p" : "";
        return jdbc.query("""
                SELECT p.id, p.assignment_id, p.square_id, p.owner_id, p.mode,
                       COALESCE(p.name, '') AS name, COALESCE(p.intro, '') AS intro,
                       p.capacity, p.status, p.closing_cycle, p.revision, p.recruitment_open,
                       a.name AS assignment_name, s.name AS course_name,
                       p.discussion_time, p.recruitment_description, p.professional_direction,
                       p.weekly_hours, p.accept_beginners
                FROM project p
                JOIN assignment a ON a.id = p.assignment_id
                JOIN square s ON s.id = p.square_id
                WHERE p.id = ?
                """ + suffix, (rs, n) -> new ProjectRow(
                rs.getString("id"), rs.getString("assignment_id"), rs.getString("square_id"),
                rs.getString("owner_id"), rs.getString("mode"), rs.getString("name"),
                rs.getString("intro"), rs.getInt("capacity"), rs.getString("status"),
                rs.getInt("closing_cycle"), rs.getInt("revision"), rs.getBoolean("recruitment_open"),
                rs.getString("assignment_name"), rs.getString("course_name"),
                rs.getString("discussion_time"), rs.getString("recruitment_description"),
                rs.getString("professional_direction"), rs.getInt("weekly_hours"),
                rs.getBoolean("accept_beginners")), id).stream().findFirst();
    }

    public boolean isLeader(String projectId, String userId) {
        return exists("""
                SELECT EXISTS(
                    SELECT 1 FROM project p JOIN membership m ON m.project_id = p.id
                    WHERE p.id = ? AND p.owner_id = ? AND m.user_id = ?
                      AND m.status = 'ACTIVE' AND m.project_role = 'LEADER')
                """, projectId, userId, userId);
    }

    public boolean isActiveMember(String projectId, String userId) {
        Boolean result = jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM membership
                              WHERE project_id = ? AND user_id = ? AND status = 'ACTIVE')
                """, Boolean.class, projectId, userId);
        return Boolean.TRUE.equals(result);
    }

    public boolean canRead(String projectId, String userId) {
        return exists("""
                SELECT EXISTS(
                    SELECT 1 FROM membership m
                    WHERE m.project_id = ? AND m.user_id = ? AND m.status = 'ACTIVE')
                OR EXISTS(
                    SELECT 1 FROM membership m
                    WHERE m.project_id = ? AND m.user_id = ? AND (
                        EXISTS (SELECT 1 FROM task t WHERE t.project_id = m.project_id AND t.owner_id = m.user_id)
                        OR EXISTS (SELECT 1 FROM task t JOIN delivery_unit du ON du.task_id = t.id
                                   JOIN unit_share us ON us.unit_id = du.id
                                   WHERE t.project_id = m.project_id AND us.user_id = m.user_id)
                        OR EXISTS (SELECT 1 FROM task t JOIN submission sub ON sub.task_id = t.id
                                   WHERE t.project_id = m.project_id AND sub.submitted_by = m.user_id)
                        OR EXISTS (SELECT 1 FROM contribution_confirmation cc
                                   JOIN delivery_unit du ON du.id = cc.unit_id
                                   JOIN task t ON t.id = du.task_id
                                   WHERE t.project_id = m.project_id AND cc.user_id = m.user_id)
                    ))
                """, projectId, userId, projectId, userId);
    }

    public Optional<DraftRow> draft(String projectId) {
        return jdbc.query("""
                SELECT id, project_id, body_json::text AS body_json, revision, updated_at
                FROM delivery_release_draft WHERE project_id = ?
                """, (rs, n) -> new DraftRow(rs.getString("id"), rs.getString("project_id"),
                rs.getString("body_json"), rs.getInt("revision"), instant(rs, "updated_at")), projectId)
                .stream().findFirst();
    }

    public void insertDraft(String id, String projectId, String bodyJson, Instant at) {
        jdbc.update("""
                INSERT INTO delivery_release_draft(id, project_id, body_json, revision, updated_at)
                VALUES (?, ?, ?::jsonb, 1, ?)
                """, id, projectId, bodyJson, time(at));
    }

    public int updateDraft(String projectId, int expectedRevision, String bodyJson, Instant at) {
        return jdbc.update("""
                UPDATE delivery_release_draft SET body_json = ?::jsonb, revision = revision + 1, updated_at = ?
                WHERE project_id = ? AND revision = ?
                """, bodyJson, time(at), projectId, expectedRevision);
    }

    public Optional<ReflectionRow> reflection(String projectId, String userId) {
        return jdbc.query("""
                SELECT r.id, r.project_id, r.user_id, r.body_json::text AS body_json,
                       r.revision, r.updated_at, u.display_name
                FROM project_reflection r JOIN app_user u ON u.id = r.user_id
                WHERE r.project_id = ? AND r.user_id = ?
                """, (rs, n) -> new ReflectionRow(rs.getString("id"), rs.getString("project_id"),
                rs.getString("user_id"), rs.getString("body_json"), rs.getInt("revision"),
                instant(rs, "updated_at"), rs.getString("display_name")), projectId, userId).stream().findFirst();
    }

    public void insertReflection(String id, String projectId, String userId, String bodyJson, Instant at) {
        jdbc.update("""
                INSERT INTO project_reflection(id, project_id, user_id, body_json, revision, updated_at)
                VALUES (?, ?, ?, ?::jsonb, 1, ?)
                """, id, projectId, userId, bodyJson, time(at));
    }

    public int updateReflection(String projectId, String userId, int expectedRevision,
                                String bodyJson, Instant at) {
        return jdbc.update("""
                UPDATE project_reflection SET body_json = ?::jsonb, revision = revision + 1, updated_at = ?
                WHERE project_id = ? AND user_id = ? AND revision = ?
                """, bodyJson, time(at), projectId, userId, expectedRevision);
    }

    public Optional<OverallRow> overall(String projectId) {
        return jdbc.query("""
                SELECT o.id, o.project_id, o.author_id, o.body_json::text AS body_json,
                       o.revision, o.updated_at, u.display_name
                FROM overall_reflection o JOIN app_user u ON u.id = o.author_id
                WHERE o.project_id = ?
                """, (rs, n) -> new OverallRow(rs.getString("id"), rs.getString("project_id"),
                rs.getString("author_id"), rs.getString("body_json"), rs.getInt("revision"),
                instant(rs, "updated_at"), rs.getString("display_name")), projectId).stream().findFirst();
    }

    public void insertOverall(String id, String projectId, String authorId, String bodyJson, Instant at) {
        jdbc.update("""
                INSERT INTO overall_reflection(id, project_id, author_id, body_json, revision, updated_at)
                VALUES (?, ?, ?, ?::jsonb, 1, ?)
                """, id, projectId, authorId, bodyJson, time(at));
    }

    public int updateOverall(String projectId, int expectedRevision, String bodyJson, Instant at) {
        return jdbc.update("""
                UPDATE overall_reflection SET body_json = ?::jsonb, revision = revision + 1, updated_at = ?
                WHERE project_id = ? AND revision = ?
                """, bodyJson, time(at), projectId, expectedRevision);
    }

    public List<Map<String, Object>> tasks(String projectId) {
        return jdbc.query("""
                SELECT t.id, t.title, t.mode, t.status, t.owner_id, t.due_at, t.revision,
                       COALESCE(u.display_name, '未认领') AS owner_name
                FROM task t LEFT JOIN app_user u ON u.id = t.owner_id
                WHERE t.project_id = ? ORDER BY t.created_at, t.id
                """, (rs, n) -> {
            Map<String, Object> row = map();
            row.put("id", rs.getString("id"));
            row.put("title", rs.getString("title"));
            row.put("mode", rs.getString("mode"));
            row.put("status", rs.getString("status"));
            row.put("ownerId", rs.getString("owner_id"));
            row.put("ownerName", rs.getString("owner_name"));
            row.put("dueAt", nullableInstant(rs, "due_at"));
            row.put("revision", rs.getInt("revision"));
            return row;
        }, projectId);
    }

    public List<Map<String, Object>> shares(String projectId) {
        return jdbc.query("""
                SELECT t.id AS task_id, t.title AS task_title, u.id AS unit_id, u.title AS unit_title,
                       u.due_at, u.points AS unit_points, u.revision AS unit_revision,
                       s.user_id, member.display_name, s.points AS share_points, s.responsibility,
                       s.signed, COALESCE(ua.passed, false) AS passed, u.position AS unit_position,
                       s.agreement_version
                FROM task t
                JOIN agreement ag ON ag.task_id = t.id AND ag.status = 'ACTIVE'
                JOIN delivery_unit u ON u.task_id = t.id
                JOIN unit_share s ON s.unit_id = u.id AND s.agreement_version = ag.version
                JOIN app_user member ON member.id = s.user_id
                LEFT JOIN unit_acceptance ua ON ua.unit_id = u.id AND ua.passed = true
                WHERE t.project_id = ?
                ORDER BY t.created_at, t.id, u.position, u.id, s.user_id
                """, (rs, n) -> {
            Map<String, Object> row = map();
            row.put("taskId", rs.getString("task_id"));
            row.put("taskTitle", rs.getString("task_title"));
            row.put("unitId", rs.getString("unit_id"));
            row.put("unitTitle", rs.getString("unit_title"));
            row.put("dueAt", instant(rs, "due_at"));
            row.put("unitPoints", rs.getBigDecimal("unit_points").toPlainString());
            row.put("unitRevision", rs.getInt("unit_revision"));
            row.put("userId", rs.getString("user_id"));
            row.put("displayName", rs.getString("display_name"));
            row.put("points", rs.getBigDecimal("share_points").toPlainString());
            row.put("responsibility", rs.getString("responsibility"));
            row.put("signed", rs.getBoolean("signed"));
            row.put("passed", rs.getBoolean("passed"));
            row.put("position", rs.getInt("unit_position"));
            row.put("agreementVersion", rs.getInt("agreement_version"));
            return row;
        }, projectId);
    }

    public Optional<UnitReportRow> reportUnit(String unitId, String submissionId) {
        return jdbc.query("""
                SELECT du.revision, ua.passed
                FROM delivery_unit du
                LEFT JOIN unit_acceptance ua ON ua.unit_id = du.id AND ua.submission_id = ?
                WHERE du.id = ?
                """, (rs, n) -> new UnitReportRow(rs.getInt("revision"),
                (Boolean) rs.getObject("passed")), submissionId, unitId).stream().findFirst();
    }

    public List<UnitShareReportRow> reportUnitShares(String unitId, Integer agreementVersion) {
        if (agreementVersion == null) return List.of();
        return jdbc.query("""
                SELECT us.user_id, u.display_name, us.points, us.responsibility, us.signed
                FROM unit_share us JOIN app_user u ON u.id = us.user_id
                WHERE us.unit_id = ? AND us.agreement_version = ?
                ORDER BY us.user_id
                """, (rs, n) -> new UnitShareReportRow(rs.getString("user_id"),
                rs.getString("display_name"), rs.getBigDecimal("points").toPlainString(),
                rs.getString("responsibility"), rs.getBoolean("signed")), unitId, agreementVersion);
    }

    public List<ContributionReportRow> contributionConfirmations(String unitId, String submissionId) {
        return jdbc.query("""
                SELECT user_id, submission_id, acceptance_id, statement, at
                FROM contribution_confirmation
                WHERE unit_id = ? AND submission_id = ?
                ORDER BY at, user_id
                """, (rs, n) -> new ContributionReportRow(rs.getString("user_id"),
                rs.getString("submission_id"), rs.getString("acceptance_id"),
                rs.getString("statement"), instant(rs, "at")), unitId, submissionId);
    }

    public List<Map<String, Object>> submissions(String projectId) {
        return jdbc.query("""
                SELECT sub.id, sub.task_id, t.title AS task_title, t.mode AS task_mode,
                       sub.sequence, sub.submitted_by, submitter.display_name AS submitted_by_name,
                       sub.summary, sub.artifacts_json::text AS artifacts_json,
                       sub.unit_ids_json::text AS unit_ids_json,
                       sub.criteria_snapshot::text AS criteria_snapshot,
                       sub.unit_due_snapshot_json::text AS unit_due_snapshot_json,
                       sub.agreement_snapshot::text AS agreement_snapshot,
                       sub.agreement_version,
                       sub.due_snapshot, sub.status, sub.revision, sub.submitted_at,
                       ac.id AS acceptance_id, ac.reviewer_id, reviewer.display_name AS reviewer_name,
                       ac.kind AS acceptance_kind, ac.checks_json::text AS checks_json,
                       ac.reason AS acceptance_reason, ac.decision AS acceptance_decision,
                       ac.accepted_at
                FROM submission sub
                JOIN task t ON t.id = sub.task_id
                JOIN app_user submitter ON submitter.id = sub.submitted_by
                LEFT JOIN acceptance ac ON ac.submission_id = sub.id
                LEFT JOIN app_user reviewer ON reviewer.id = ac.reviewer_id
                WHERE t.project_id = ? ORDER BY sub.submitted_at, sub.id
                """, (rs, n) -> {
            Map<String, Object> row = map();
            row.put("id", rs.getString("id"));
            row.put("taskId", rs.getString("task_id"));
            row.put("taskTitle", rs.getString("task_title"));
            row.put("taskMode", rs.getString("task_mode"));
            row.put("sequence", rs.getInt("sequence"));
            row.put("submittedBy", rs.getString("submitted_by"));
            row.put("submittedByName", rs.getString("submitted_by_name"));
            row.put("summary", rs.getString("summary"));
            row.put("artifactsJson", rs.getString("artifacts_json"));
            row.put("unitIdsJson", rs.getString("unit_ids_json"));
            row.put("criteriaJson", rs.getString("criteria_snapshot"));
            row.put("unitDueJson", rs.getString("unit_due_snapshot_json"));
            row.put("agreementJson", rs.getString("agreement_snapshot"));
            row.put("agreementVersion", rs.getInt("agreement_version"));
            row.put("dueAt", nullableInstant(rs, "due_snapshot"));
            row.put("status", rs.getString("status"));
            row.put("revision", rs.getInt("revision"));
            row.put("submittedAt", instant(rs, "submitted_at"));
            row.put("acceptanceId", rs.getString("acceptance_id"));
            row.put("reviewerId", rs.getString("reviewer_id"));
            row.put("reviewerName", rs.getString("reviewer_name"));
            row.put("acceptanceKind", rs.getString("acceptance_kind"));
            row.put("checksJson", rs.getString("checks_json"));
            row.put("acceptanceReason", rs.getString("acceptance_reason"));
            row.put("acceptanceDecision", rs.getString("acceptance_decision"));
            row.put("acceptedAt", nullableInstant(rs, "accepted_at"));
            return row;
        }, projectId);
    }

    public List<Map<String, Object>> members(String projectId) {
        return jdbc.query("""
                WITH participants AS (
                    SELECT m.user_id FROM membership m WHERE m.project_id = ? AND m.status = 'ACTIVE'
                    UNION
                    SELECT t.owner_id FROM task t
                    WHERE t.project_id = ? AND t.owner_id IS NOT NULL
                    UNION
                    SELECT us.user_id FROM task t
                    JOIN delivery_unit du ON du.task_id = t.id
                    JOIN unit_share us ON us.unit_id = du.id
                    WHERE t.project_id = ?
                    UNION
                    SELECT sub.submitted_by FROM submission sub
                    JOIN task t ON t.id = sub.task_id
                    WHERE t.project_id = ?
                    UNION
                    SELECT cc.user_id FROM contribution_confirmation cc
                    JOIN delivery_unit du ON du.id = cc.unit_id
                    JOIN task t ON t.id = du.task_id
                    WHERE t.project_id = ?
                )
                SELECT u.id AS user_id, u.display_name,
                       EXISTS (SELECT 1 FROM membership am WHERE am.project_id = ?
                               AND am.user_id = u.id AND am.status = 'ACTIVE') AS current_member,
                       (SELECT count(*) FROM task t WHERE t.project_id = ? AND t.owner_id = u.id) AS responsible_tasks,
                       (SELECT count(*) FROM task t WHERE t.project_id = ? AND t.owner_id = u.id
                               AND t.status = 'DONE') AS completed_responsible_tasks,
                       COALESCE((SELECT sum(us.points) FROM task t
                           JOIN agreement ag ON ag.task_id = t.id AND ag.status = 'ACTIVE'
                           JOIN delivery_unit du ON du.task_id = t.id
                           JOIN unit_share us ON us.unit_id = du.id AND us.agreement_version = ag.version
                           WHERE t.project_id = ? AND us.user_id = u.id AND us.signed), 0) AS responsibility_points,
                       COALESCE((SELECT sum(us.points) FROM task t
                           JOIN agreement ag ON ag.task_id = t.id AND ag.status = 'ACTIVE'
                           JOIN delivery_unit du ON du.task_id = t.id
                           JOIN unit_share us ON us.unit_id = du.id AND us.agreement_version = ag.version
                           JOIN unit_acceptance ua ON ua.unit_id = du.id AND ua.passed = true
                           JOIN contribution_confirmation cc ON cc.unit_id = du.id
                               AND cc.user_id = us.user_id AND cc.acceptance_id = ua.acceptance_id
                           WHERE t.project_id = ? AND us.user_id = u.id AND us.signed), 0) AS confirmed_points
                FROM participants p JOIN app_user u ON u.id = p.user_id
                ORDER BY u.display_name, u.id
                """, (rs, n) -> {
            Map<String, Object> row = map();
            row.put("userId", rs.getString("user_id"));
            row.put("name", rs.getString("display_name"));
            row.put("currentMember", rs.getBoolean("current_member"));
            row.put("responsibleTasks", rs.getInt("responsible_tasks"));
            row.put("completedResponsibleTasks", rs.getInt("completed_responsible_tasks"));
            row.put("responsibilityPoints", rs.getBigDecimal("responsibility_points").toPlainString());
            row.put("confirmedPoints", rs.getBigDecimal("confirmed_points").toPlainString());
            return row;
        }, projectId, projectId, projectId, projectId, projectId,
                projectId, projectId, projectId, projectId, projectId);
    }

    public List<ReflectionRow> reflections(String projectId) {
        return jdbc.query("""
                SELECT r.id, r.project_id, r.user_id, r.body_json::text AS body_json,
                       r.revision, r.updated_at, u.display_name
                FROM project_reflection r JOIN app_user u ON u.id = r.user_id
                WHERE r.project_id = ? ORDER BY u.display_name, r.user_id
                """, (rs, n) -> new ReflectionRow(rs.getString("id"), rs.getString("project_id"),
                rs.getString("user_id"), rs.getString("body_json"), rs.getInt("revision"),
                instant(rs, "updated_at"), rs.getString("display_name")), projectId);
    }

    public Optional<ReleaseRow> latestRelease(String projectId) {
        return releaseQuery("WHERE project_id = ? ORDER BY version DESC LIMIT 1", projectId).stream().findFirst();
    }

    public Optional<ReleaseRow> release(String id) {
        return releaseQuery("WHERE id = ?", id).stream().findFirst();
    }

    public List<ReleaseRow> releases(String projectId) {
        return releaseQuery("WHERE project_id = ? ORDER BY version DESC, id", projectId);
    }

    private List<ReleaseRow> releaseQuery(String suffix, Object arg) {
        return jdbc.query("""
                SELECT id, project_id, version, source_key, body_snapshot::text AS body_snapshot,
                       confirmed_by, confirmed_at, revision
                FROM delivery_release
                """ + suffix, (rs, n) -> new ReleaseRow(rs.getString("id"), rs.getString("project_id"),
                rs.getInt("version"), rs.getString("source_key").trim(), rs.getString("body_snapshot"),
                rs.getString("confirmed_by"), instant(rs, "confirmed_at"), rs.getInt("revision")), arg);
    }

    public Optional<PreviewRow> preview(String projectId, String tokenHash) {
        return jdbc.query("""
                SELECT id, project_id, created_by, token_hash, source_key, draft_revision,
                       body_snapshot::text AS body_snapshot, sources_json::text AS sources_json,
                       expires_at, used_at
                FROM delivery_release_preview WHERE project_id = ? AND token_hash = ? FOR UPDATE
                """, (rs, n) -> new PreviewRow(rs.getString("id"), rs.getString("project_id"),
                rs.getString("created_by"), rs.getString("token_hash"), rs.getString("source_key").trim(),
                rs.getInt("draft_revision"), rs.getString("body_snapshot"), rs.getString("sources_json"),
                instant(rs, "expires_at"), nullableInstant(rs, "used_at")), projectId, tokenHash)
                .stream().findFirst();
    }

    public void insertPreview(String id, String projectId, String userId, String tokenHash, String sourceKey,
                              int draftRevision, String bodyJson, String sourcesJson, Instant expiresAt, Instant now) {
        jdbc.update("""
                INSERT INTO delivery_release_preview
                    (id, project_id, created_by, token_hash, source_key, draft_revision,
                     body_snapshot, sources_json, expires_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?)
                """, id, projectId, userId, tokenHash, sourceKey, draftRevision,
                bodyJson, sourcesJson, time(expiresAt), time(now));
    }

    public int markPreviewUsed(String id, Instant now) {
        return jdbc.update("UPDATE delivery_release_preview SET used_at = ? WHERE id = ? AND used_at IS NULL",
                time(now), id);
    }

    public void insertRelease(String id, String projectId, int version, String sourceKey, String bodyJson,
                              String userId, Instant now) {
        jdbc.update("""
                INSERT INTO delivery_release
                    (id, project_id, version, source_key, body_snapshot, confirmed_by, confirmed_at, revision)
                VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, 1)
                """, id, projectId, version, sourceKey, bodyJson, userId, time(now));
    }

    public List<SnapshotRow> snapshots(String projectId) {
        return jdbc.query("""
                SELECT id, project_id, type, name, closing_cycle, source_key, release_id,
                       body_snapshot::text AS body_snapshot, empty_reason, created_by, created_at, revision
                FROM report_snapshot WHERE project_id = ? ORDER BY created_at DESC, id DESC
                """, snapshotMapper(), projectId);
    }

    public Optional<SnapshotRow> snapshot(String id) {
        return jdbc.query("""
                SELECT id, project_id, type, name, closing_cycle, source_key, release_id,
                       body_snapshot::text AS body_snapshot, empty_reason, created_by, created_at, revision
                FROM report_snapshot WHERE id = ?
                """, snapshotMapper(), id).stream().findFirst();
    }

    public Optional<SnapshotRow> snapshot(String projectId, String type, int cycle, String sourceKey) {
        return jdbc.query("""
                SELECT id, project_id, type, name, closing_cycle, source_key, release_id,
                       body_snapshot::text AS body_snapshot, empty_reason, created_by, created_at, revision
                FROM report_snapshot
                WHERE project_id = ? AND type = ? AND closing_cycle = ? AND source_key = ?
                """, snapshotMapper(), projectId, type, cycle, sourceKey).stream().findFirst();
    }

    public void insertSnapshot(String id, String projectId, String type, String name, int cycle, String sourceKey,
                               String releaseId, String bodyJson, String emptyReason, String userId, Instant now) {
        jdbc.update("""
                INSERT INTO report_snapshot
                    (id, project_id, type, name, closing_cycle, source_key, release_id,
                     body_snapshot, empty_reason, created_by, created_at, revision)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, 1)
                """, id, projectId, type, name, cycle, sourceKey, releaseId, bodyJson,
                emptyReason, userId, time(now));
    }

    public int countUnfinishedTasks(String projectId) {
        return count("SELECT count(*) FROM task WHERE project_id = ? AND status NOT IN ('DONE', 'CANCELLED')", projectId);
    }

    public int countPendingSubmissions(String projectId) {
        return count("SELECT count(*) FROM submission sub JOIN task t ON t.id = sub.task_id WHERE t.project_id = ? AND sub.status = 'PENDING'", projectId);
    }

    public int countOpenPersonnelChanges(String projectId) {
        return count("SELECT count(*) FROM member_change WHERE project_id = ? AND status NOT IN ('COMPLETED', 'REJECTED', 'CANCELLED')", projectId);
    }

    public int countOpenAgreements(String projectId) {
        return count("SELECT count(*) FROM agreement ag JOIN task t ON t.id = ag.task_id WHERE t.project_id = ? AND ag.status IN ('PENDING', 'CHANGES_REQUESTED')", projectId);
    }

    public int countOpenProposals(String projectId) {
        return count("SELECT count(*) FROM unit_change_proposal cp JOIN task t ON t.id = cp.task_id WHERE t.project_id = ? AND cp.status = 'PENDING'", projectId);
    }

    public int countHandoverBlockedTasks(String projectId) {
        return count("SELECT count(*) FROM task WHERE project_id = ? AND handover_blocked", projectId);
    }

    public int countPendingReviewerInvitations(String projectId) {
        return count("SELECT count(*) FROM reviewer_invitation ri JOIN task t ON t.id = ri.task_id WHERE t.project_id = ? AND ri.status = 'PENDING'", projectId);
    }

    public int countUnconfirmedContributions(String projectId) {
        return count("""
                SELECT count(*)
                FROM unit_acceptance ua
                JOIN delivery_unit du ON du.id = ua.unit_id
                JOIN task t ON t.id = du.task_id
                JOIN agreement ag ON ag.task_id = t.id AND ag.status = 'ACTIVE'
                JOIN unit_share us ON us.unit_id = du.id AND us.agreement_version = ag.version AND us.signed = true
                WHERE t.project_id = ? AND ua.passed = true
                  AND NOT EXISTS (SELECT 1 FROM contribution_confirmation cc
                                  WHERE cc.unit_id = du.id AND cc.user_id = us.user_id
                                    AND cc.acceptance_id = ua.acceptance_id)
                """, projectId);
    }

    public boolean emptyProjectHistory(String projectId) {
        Integer count = jdbc.queryForObject("""
                SELECT (SELECT count(*) FROM task WHERE project_id = ?)
                     + (SELECT count(*) FROM agreement ag JOIN task t ON t.id = ag.task_id WHERE t.project_id = ?)
                     + (SELECT count(*) FROM submission sub JOIN task t ON t.id = sub.task_id WHERE t.project_id = ?)
                """, Integer.class, projectId, projectId, projectId);
        return count != null && count == 0;
    }

    public void enterClosing(String projectId, int expectedRevision, Instant now) {
        jdbc.update("""
                UPDATE project SET status = 'CLOSING', closing_cycle = closing_cycle + 1,
                    recruitment_open = false, revision = revision + 1, updated_at = ?
                WHERE id = ? AND revision = ? AND status = 'ACTIVE'
                """, time(now), projectId, expectedRevision);
        jdbc.update("""
                UPDATE team_invitation SET status = 'CANCELLED', revision = revision + 1, updated_at = ?
                WHERE project_id = ? AND status = 'PENDING'
                """, time(now), projectId);
        jdbc.update("""
                UPDATE team_application SET status = 'CLOSED', revision = revision + 1, updated_at = ?
                WHERE project_id = ? AND status = 'PENDING'
                """, time(now), projectId);
        jdbc.update("""
                UPDATE seat_reservation SET status = 'RELEASED', revision = revision + 1, updated_at = ?
                WHERE project_id = ? AND status = 'ACTIVE'
                """, time(now), projectId);
        jdbc.update("UPDATE project_code SET status = 'REVOKED', revision = revision + 1, updated_at = ? WHERE project_id = ? AND status = 'ACTIVE'",
                time(now), projectId);
    }

    public void restoreActive(String projectId, int expectedRevision, Instant now) {
        jdbc.update("""
                UPDATE project SET status = 'ACTIVE', revision = revision + 1, updated_at = ?
                WHERE id = ? AND revision = ? AND status = 'CLOSING'
                """, time(now), projectId, expectedRevision);
    }

    public void archive(String projectId, int expectedRevision, Instant now) {
        jdbc.update("""
                UPDATE project SET status = 'ARCHIVED', archived_at = ?, recruitment_open = false,
                    revision = revision + 1, updated_at = ?
                WHERE id = ? AND revision = ? AND status = 'CLOSING'
                """, time(now), time(now), projectId, expectedRevision);
        jdbc.update("""
                UPDATE team_invitation SET status = 'CANCELLED', revision = revision + 1, updated_at = ?
                WHERE project_id = ? AND status = 'PENDING'
                """, time(now), projectId);
        jdbc.update("""
                UPDATE seat_reservation SET status = 'RELEASED', revision = revision + 1, updated_at = ?
                WHERE project_id = ? AND status = 'ACTIVE'
                """, time(now), projectId);
        jdbc.update("UPDATE project_code SET status = 'REVOKED', revision = revision + 1, updated_at = ? WHERE project_id = ? AND status = 'ACTIVE'",
                time(now), projectId);
    }

    public int nextReleaseVersion(String projectId) {
        Integer next = jdbc.queryForObject("SELECT COALESCE(MAX(version), 0) + 1 FROM delivery_release WHERE project_id = ?",
                Integer.class, projectId);
        return next == null ? 1 : next;
    }

    public int count(String sql, Object... args) {
        Integer result = jdbc.queryForObject(sql, Integer.class, args);
        return result == null ? 0 : result;
    }

    private boolean exists(String sql, Object... args) {
        Boolean result = jdbc.queryForObject(sql, Boolean.class, args);
        return Boolean.TRUE.equals(result);
    }

    private ReportRowMapper snapshotMapper() {
        return (rs, n) -> new SnapshotRow(rs.getString("id"), rs.getString("project_id"),
                rs.getString("type"), rs.getString("name"), rs.getInt("closing_cycle"),
                rs.getString("source_key").trim(), rs.getString("release_id"),
                rs.getString("body_snapshot"), rs.getString("empty_reason"),
                rs.getString("created_by"), instant(rs, "created_at"), rs.getInt("revision"));
    }

    private Map<String, Object> map() {
        return new LinkedHashMap<>();
    }

    private Instant nullableInstant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }

    private OffsetDateTime time(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    public record ProjectRow(String id, String assignmentId, String squareId, String ownerId,
                             String mode, String name, String intro, int capacity, String status,
                             int closingCycle, int revision, boolean recruitmentOpen,
                             String assignmentName, String courseName, String discussionTime,
                             String recruitmentDescription, String professionalDirection,
                             int weeklyHours, boolean acceptBeginners) {
    }

    public record DraftRow(String id, String projectId, String bodyJson, int revision, Instant updatedAt) {
    }

    public record ReflectionRow(String id, String projectId, String userId, String bodyJson,
                                int revision, Instant updatedAt, String displayName) {
    }

    public record OverallRow(String id, String projectId, String authorId, String bodyJson,
                             int revision, Instant updatedAt, String displayName) {
    }

    public record ReleaseRow(String id, String projectId, int version, String sourceKey, String bodyJson,
                             String confirmedBy, Instant confirmedAt, int revision) {
    }

    public record PreviewRow(String id, String projectId, String createdBy, String tokenHash, String sourceKey,
                             int draftRevision, String bodyJson, String sourcesJson,
                             Instant expiresAt, Instant usedAt) {
    }

    public record SnapshotRow(String id, String projectId, String type, String name, int closingCycle,
                              String sourceKey, String releaseId, String bodyJson, String emptyReason,
                              String createdBy, Instant createdAt, int revision) {
    }

    public record UnitReportRow(int revision, Boolean passed) {
    }

    public record UnitShareReportRow(String userId, String displayName, String points,
                                     String responsibility, boolean accepted) {
    }

    public record ContributionReportRow(String userId, String submissionId, String acceptanceId,
                                        String statement, Instant createdAt) {
    }

    @FunctionalInterface
    private interface ReportRowMapper extends org.springframework.jdbc.core.RowMapper<SnapshotRow> {
    }
}
