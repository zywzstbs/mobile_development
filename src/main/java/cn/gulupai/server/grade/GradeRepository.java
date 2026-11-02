package cn.gulupai.server.grade;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

@Repository
public class GradeRepository {

    private final JdbcTemplate jdbc;

    public GradeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<AssignmentHead> lockAssignment(String id) {
        return jdbc.query("""
                SELECT id, square_id, revision FROM assignment WHERE id = ? FOR UPDATE
                """, (rs, n) -> new AssignmentHead(rs.getString("id"), rs.getString("square_id"),
                rs.getInt("revision")), id).stream().findFirst();
    }

    public Optional<AssignmentHead> assignment(String id) {
        return jdbc.query("""
                SELECT id, square_id, revision FROM assignment WHERE id = ?
                """, (rs, n) -> new AssignmentHead(rs.getString("id"), rs.getString("square_id"),
                rs.getInt("revision")), id).stream().findFirst();
    }

    public String displayName(String userId) {
        List<String> names = jdbc.query("SELECT display_name FROM app_user WHERE id = ?",
                (rs, n) -> rs.getString(1), userId);
        return names.isEmpty() ? "" : names.getFirst();
    }

    public boolean activeMember(String squareId, String userId) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM course_membership
                WHERE square_id = ? AND user_id = ? AND status = 'ACTIVE'
                """, Integer.class, squareId, userId);
        return count != null && count > 0;
    }

    public List<ShareFact> shares(String assignmentId, String studentId) {
        return jdbc.query("""
                SELECT t.id AS task_id, t.title AS task_title, u.id AS unit_id, u.title AS unit_title,
                       u.due_at, s.id AS share_id, s.points, s.signed, s.agreement_version,
                       ag.version AS active_version,
                       oa.version AS open_version, oa.status AS open_status
                FROM project p
                JOIN task t ON t.project_id = p.id AND t.mode = 'ASSESSED'
                JOIN delivery_unit u ON u.task_id = t.id
                JOIN unit_share s ON s.unit_id = u.id AND s.user_id = ?
                LEFT JOIN agreement ag ON ag.task_id = t.id AND ag.status = 'ACTIVE'
                LEFT JOIN agreement oa ON oa.task_id = t.id AND oa.status IN ('PENDING', 'CHANGES_REQUESTED')
                WHERE p.assignment_id = ?
                ORDER BY u.id, s.agreement_version
                """, (rs, n) -> new ShareFact(
                rs.getString("task_id"), rs.getString("task_title"), rs.getString("unit_id"),
                rs.getString("unit_title"), rs.getObject("due_at", OffsetDateTime.class).toInstant(),
                rs.getString("share_id"), rs.getBigDecimal("points"), rs.getBoolean("signed"),
                rs.getInt("agreement_version"),
                rs.getObject("active_version") == null ? null : rs.getInt("active_version"),
                rs.getObject("open_version") == null ? 0 : rs.getInt("open_version"),
                rs.getString("open_status")),
                studentId, assignmentId);
    }

    /**
     * 当前 PENDING 提交所覆盖的交付项。按 submission.unit_ids_json 匹配真实覆盖项，
     * 并只取本人在该交付项有份额的记录，避免把同任务其他项的待验收误算到本项。
     */
    public List<PendingSubmissionFact> pendingSubmissions(String assignmentId, String studentId) {
        return jdbc.query("""
                SELECT DISTINCT u.id AS unit_id, s.id AS submission_id
                FROM submission s
                JOIN task t ON t.id = s.task_id
                JOIN project p ON p.id = t.project_id
                JOIN delivery_unit u ON u.task_id = t.id
                JOIN unit_share sh ON sh.unit_id = u.id AND sh.user_id = ?
                WHERE s.status = 'PENDING' AND p.assignment_id = ?
                  AND u.id IN (SELECT jsonb_array_elements_text(s.unit_ids_json))
                ORDER BY u.id, s.id
                """, (rs, n) -> new PendingSubmissionFact(rs.getString("unit_id"),
                rs.getString("submission_id")), studentId, assignmentId);
    }

    /**
     * 交接中或已执行但尚未重签的 TASK 交接项。交接执行后以 task.handover_blocked 为持久标记；
     * 新约定生效时由交付模块清除。
     */
    public List<HandoverFact> handovers(String assignmentId) {
        return jdbc.query("""
                SELECT h.id AS item_id, h.task_id, mc.id AS change_id, mc.status, h.plan_version,
                       h.recipient_id, t.handover_blocked
                FROM handover_item h
                JOIN member_change mc ON mc.id = h.change_id AND h.plan_version = mc.plan_version
                JOIN task t ON t.id = h.task_id
                JOIN project p ON p.id = t.project_id
                WHERE p.assignment_id = ? AND h.kind = 'TASK'
                  AND (mc.status IN ('WAIT_PERSONNEL', 'RETURNED', 'READY')
                       OR (mc.status = 'EXECUTED' AND t.handover_blocked))
                ORDER BY h.task_id, mc.id, h.plan_version, h.id
                """, (rs, n) -> new HandoverFact(rs.getString("task_id"), rs.getString("item_id"),
                rs.getString("change_id"), rs.getString("status"), rs.getInt("plan_version"),
                rs.getString("recipient_id"), rs.getBoolean("handover_blocked")), assignmentId);
    }

    public List<AcceptanceFact> passedAcceptances(String assignmentId, String studentId) {
        return jdbc.query("""
                SELECT ua.unit_id, ua.acceptance_id, ac.kind
                FROM unit_acceptance ua
                JOIN acceptance ac ON ac.id = ua.acceptance_id
                JOIN delivery_unit u ON u.id = ua.unit_id
                JOIN task t ON t.id = u.task_id
                JOIN project p ON p.id = t.project_id
                WHERE ua.passed = true AND p.assignment_id = ? AND t.mode = 'ASSESSED'
                  AND EXISTS (
                    SELECT 1 FROM unit_share s WHERE s.unit_id = u.id AND s.user_id = ?)
                """, (rs, n) -> new AcceptanceFact(rs.getString("unit_id"), rs.getString("acceptance_id"),
                rs.getString("kind")), assignmentId, studentId);
    }

    public List<String> confirmedUnits(String studentId, String acceptanceId) {
        return jdbc.query("""
                SELECT unit_id FROM contribution_confirmation
                WHERE user_id = ? AND acceptance_id = ?
                """, (rs, n) -> rs.getString(1), studentId, acceptanceId);
    }

    public List<CorrectionFact> latestCorrections(String assignmentId, String studentId) {
        return jdbc.query("""
                SELECT DISTINCT ON (unit_id)
                       id, task_id, unit_id, responsibility_points, accepted_points, reason,
                       evidence_refs_json::text AS evidence_refs_json, source_key, author_id, revision, created_at
                FROM ledger_correction
                WHERE assignment_id = ? AND student_id = ?
                ORDER BY unit_id, created_at DESC, id DESC
                """, (rs, n) -> new CorrectionFact(rs.getString("id"), rs.getString("task_id"),
                rs.getString("unit_id"), rs.getBigDecimal("responsibility_points"),
                rs.getBigDecimal("accepted_points"), rs.getString("reason"),
                rs.getString("evidence_refs_json"), rs.getString("source_key").trim(),
                rs.getString("author_id"), rs.getInt("revision"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant()), assignmentId, studentId);
    }

    public void insertCorrection(CorrectionFact row, String assignmentId, String studentId) {
        jdbc.update("""
                INSERT INTO ledger_correction
                    (id, assignment_id, student_id, task_id, unit_id, responsibility_points, accepted_points,
                     reason, evidence_refs_json, source_key, author_id, revision, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)
                """, row.id(), assignmentId, studentId, row.taskId(), row.unitId(),
                row.responsibility(), row.accepted(), row.reason(), row.evidenceJson(), row.sourceKey(),
                row.authorId(), row.revision(), at(row.createdAt()));
    }

    public Optional<RuleFact> latestRule(String assignmentId) {
        return jdbc.query("""
                SELECT id, assignment_id, version, process_weight, description, confirmed_by, confirmed_at
                FROM grade_rule WHERE assignment_id = ? ORDER BY version DESC LIMIT 1
                """, (rs, n) -> rule(rs), assignmentId).stream().findFirst();
    }

    public void insertRule(RuleFact row) {
        jdbc.update("""
                INSERT INTO grade_rule
                    (id, assignment_id, version, process_weight, description, confirmed_by, confirmed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, row.id(), row.assignmentId(), row.version(), row.weight(), row.description(),
                row.confirmedBy(), at(row.confirmedAt()));
        jdbc.update("UPDATE assignment SET grading_enabled = true, updated_at = ? WHERE id = ?",
                at(row.confirmedAt()), row.assignmentId());
    }

    public void lockTasks(String assignmentId) {
        jdbc.query("""
                SELECT t.id
                FROM task t
                JOIN project p ON p.id = t.project_id
                WHERE p.assignment_id = ?
                ORDER BY p.id, t.id
                FOR UPDATE OF t
                """, (rs, n) -> rs.getString(1), assignmentId);
    }

    public List<String> gradedStudentIds(String assignmentId) {
        return jdbc.query("""
                SELECT DISTINCT s.user_id
                FROM project p
                JOIN task t ON t.project_id = p.id AND t.mode = 'ASSESSED'
                JOIN delivery_unit u ON u.task_id = t.id
                JOIN unit_share s ON s.unit_id = u.id
                WHERE p.assignment_id = ?
                ORDER BY s.user_id
                """, (rs, n) -> rs.getString(1), assignmentId);
    }

    public int affectedStudents(String assignmentId) {
        Integer count = jdbc.queryForObject("""
                SELECT count(DISTINCT s.user_id)
                FROM project p
                JOIN task t ON t.project_id = p.id AND t.mode = 'ASSESSED'
                JOIN delivery_unit u ON u.task_id = t.id
                JOIN unit_share s ON s.unit_id = u.id
                WHERE p.assignment_id = ?
                """, Integer.class, assignmentId);
        return count == null ? 0 : count;
    }

    public Optional<SettlementFact> settlement(String assignmentId, String studentId, String sourceKey) {
        return jdbc.query("""
                SELECT id, assignment_id, student_id, source_key, rule_version,
                       responsibility_points, accepted_points, b, settled_at
                FROM settlement
                WHERE assignment_id = ? AND student_id = ? AND source_key = ?
                """, (rs, n) -> new SettlementFact(rs.getString("id"), rs.getString("assignment_id"),
                rs.getString("student_id"), rs.getString("source_key").trim(), rs.getInt("rule_version"),
                rs.getBigDecimal("responsibility_points"), rs.getBigDecimal("accepted_points"),
                rs.getInt("b"), rs.getObject("settled_at", OffsetDateTime.class).toInstant()),
                assignmentId, studentId, sourceKey).stream().findFirst();
    }

    public void insertSettlement(SettlementFact row) {
        jdbc.update("""
                INSERT INTO settlement
                    (id, assignment_id, student_id, source_key, rule_version,
                     responsibility_points, accepted_points, b, settled_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, row.id(), row.assignmentId(), row.studentId(), row.sourceKey(), row.ruleVersion(),
                row.responsibility(), row.accepted(), row.b(), at(row.settledAt()));
    }

    public Optional<DraftFact> draft(String assignmentId, String studentId) {
        return jdbc.query("""
                SELECT id, assignment_id, student_id, q, delta, delta_reason,
                       evidence_refs_json::text AS evidence_refs_json, source_key, rule_version, revision
                FROM grade_draft WHERE assignment_id = ? AND student_id = ?
                """, (rs, n) -> new DraftFact(rs.getString("id"), rs.getString("assignment_id"),
                rs.getString("student_id"), rs.getInt("q"), rs.getInt("delta"), rs.getString("delta_reason"),
                rs.getString("evidence_refs_json"), rs.getString("source_key").trim(),
                rs.getInt("rule_version"), rs.getInt("revision")), assignmentId, studentId).stream().findFirst();
    }

    public void insertDraft(DraftFact row) {
        jdbc.update("""
                INSERT INTO grade_draft
                    (id, assignment_id, student_id, q, delta, delta_reason, evidence_refs_json,
                     source_key, rule_version, revision)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                """, row.id(), row.assignmentId(), row.studentId(), row.q(), row.delta(), row.deltaReason(),
                row.evidenceJson(), row.sourceKey(), row.ruleVersion(), row.revision());
    }

    public int updateDraft(DraftFact row) {
        return jdbc.update("""
                UPDATE grade_draft
                SET q = ?, delta = ?, delta_reason = ?, evidence_refs_json = ?::jsonb,
                    source_key = ?, rule_version = ?, revision = revision + 1
                WHERE id = ? AND revision = ?
                """, row.q(), row.delta(), row.deltaReason(), row.evidenceJson(), row.sourceKey(),
                row.ruleVersion(), row.id(), row.revision());
    }

    public Optional<SnapshotFact> latestSnapshot(String assignmentId, String studentId) {
        return jdbc.query("""
                SELECT id, publication_id, assignment_id, student_id, version, b, q, delta, g,
                       basis_digest, rule_snapshot::text AS rule_snapshot,
                       ledger_snapshot::text AS ledger_snapshot,
                       evidence_snapshot::text AS evidence_snapshot, created_at
                FROM grade_snapshot
                WHERE assignment_id = ? AND student_id = ?
                ORDER BY version DESC LIMIT 1
                """, (rs, n) -> snapshot(rs), assignmentId, studentId).stream().findFirst();
    }

    public Optional<SnapshotFact> snapshot(String id) {
        return jdbc.query("""
                SELECT id, publication_id, assignment_id, student_id, version, b, q, delta, g,
                       basis_digest, rule_snapshot::text AS rule_snapshot,
                       ledger_snapshot::text AS ledger_snapshot,
                       evidence_snapshot::text AS evidence_snapshot, created_at
                FROM grade_snapshot WHERE id = ?
                """, (rs, n) -> snapshot(rs), id).stream().findFirst();
    }

    public List<SnapshotFact> history(String assignmentId, String studentId) {
        return jdbc.query("""
                SELECT id, publication_id, assignment_id, student_id, version, b, q, delta, g,
                       basis_digest, rule_snapshot::text AS rule_snapshot,
                       ledger_snapshot::text AS ledger_snapshot,
                       evidence_snapshot::text AS evidence_snapshot, created_at
                FROM grade_snapshot
                WHERE assignment_id = ? AND student_id = ?
                ORDER BY version
                """, (rs, n) -> snapshot(rs), assignmentId, studentId);
    }

    public List<SnapshotFact> myGrades(String studentId) {
        return jdbc.query("""
                SELECT id, publication_id, assignment_id, student_id, version, b, q, delta, g,
                       basis_digest, rule_snapshot::text AS rule_snapshot,
                       ledger_snapshot::text AS ledger_snapshot,
                       evidence_snapshot::text AS evidence_snapshot, created_at
                FROM grade_snapshot WHERE student_id = ? ORDER BY created_at, id
                """, (rs, n) -> snapshot(rs), studentId);
    }

    public boolean publicationExists(String previewId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM grade_publication WHERE preview_id = ?", Integer.class, previewId);
        return count != null && count > 0;
    }

    public void insertPublication(String id, String assignmentId, String previewId, String userId, Instant at) {
        jdbc.update("""
                INSERT INTO grade_publication (id, assignment_id, preview_id, published_by, published_at)
                VALUES (?, ?, ?, ?, ?)
                """, id, assignmentId, previewId, userId, at(at));
    }

    public void insertSnapshot(SnapshotFact row) {
        jdbc.update("""
                INSERT INTO grade_snapshot
                    (id, publication_id, assignment_id, student_id, version, b, q, delta, g, basis_digest,
                     rule_snapshot, ledger_snapshot, evidence_snapshot, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?)
                """, row.id(), row.publicationId(), row.assignmentId(), row.studentId(), row.version(),
                row.b(), row.q(), row.delta(), row.g(), row.basisDigest(), row.ruleJson(), row.ledgerJson(),
                row.evidenceJson(), at(row.createdAt()));
    }

    public void insertAppeal(AppealFact row) {
        jdbc.update("""
                INSERT INTO grade_appeal
                    (id, grade_snapshot_id, assignment_id, student_id, status, issue, source_refs_json,
                     evidence_refs_json, responses_json, revised_snapshot_id, revision, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?, ?)
                """, row.id(), row.gradeSnapshotId(), row.assignmentId(), row.studentId(), row.status(),
                row.issue(), row.sourceJson(), row.evidenceJson(), row.responsesJson(),
                row.revisedSnapshotId(), row.revision(), at(row.createdAt()));
    }

    public Optional<AppealFact> lockAppeal(String id) {
        return jdbc.query("""
                SELECT id, grade_snapshot_id, assignment_id, student_id, status, issue,
                       source_refs_json::text AS source_refs_json,
                       evidence_refs_json::text AS evidence_refs_json,
                       responses_json::text AS responses_json, revised_snapshot_id, revision, created_at
                FROM grade_appeal WHERE id = ? FOR UPDATE
                """, (rs, n) -> appeal(rs), id).stream().findFirst();
    }

    public Optional<AppealFact> appeal(String id) {
        return jdbc.query("""
                SELECT id, grade_snapshot_id, assignment_id, student_id, status, issue,
                       source_refs_json::text AS source_refs_json,
                       evidence_refs_json::text AS evidence_refs_json,
                       responses_json::text AS responses_json, revised_snapshot_id, revision, created_at
                FROM grade_appeal WHERE id = ?
                """, (rs, n) -> appeal(rs), id).stream().findFirst();
    }

    public List<AppealFact> appealsForAssignment(String assignmentId) {
        return jdbc.query("""
                SELECT id, grade_snapshot_id, assignment_id, student_id, status, issue,
                       source_refs_json::text AS source_refs_json,
                       evidence_refs_json::text AS evidence_refs_json,
                       responses_json::text AS responses_json, revised_snapshot_id, revision, created_at
                FROM grade_appeal WHERE assignment_id = ? ORDER BY created_at, id
                """, (rs, n) -> appeal(rs), assignmentId);
    }

    public List<AppealFact> myAppeals(String studentId) {
        return jdbc.query("""
                SELECT id, grade_snapshot_id, assignment_id, student_id, status, issue,
                       source_refs_json::text AS source_refs_json,
                       evidence_refs_json::text AS evidence_refs_json,
                       responses_json::text AS responses_json, revised_snapshot_id, revision, created_at
                FROM grade_appeal WHERE student_id = ? ORDER BY created_at, id
                """, (rs, n) -> appeal(rs), studentId);
    }

    public int updateAppeal(AppealFact row, int expectedRevision) {
        return jdbc.update("""
                UPDATE grade_appeal
                SET status = ?, responses_json = ?::jsonb, revised_snapshot_id = ?, revision = revision + 1
                WHERE id = ? AND revision = ?
                """, row.status(), row.responsesJson(), row.revisedSnapshotId(), row.id(), expectedRevision);
    }

    public boolean openAppeal(String snapshotId) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM grade_appeal
                WHERE grade_snapshot_id = ?
                  AND status IN ('PENDING', 'REVIEWING', 'AWAITING_REVISED_PUBLICATION')
                """, Integer.class, snapshotId);
        return count != null && count > 0;
    }

    public Optional<SummaryFact> summary(String id) {
        return jdbc.query("SELECT * FROM teacher_summary WHERE id = ?",
                (rs, n) -> summaryRow(rs), id).stream().findFirst();
    }

    public Optional<SummaryFact> summaryFor(String assignmentId, String studentId) {
        return jdbc.query("SELECT * FROM teacher_summary WHERE assignment_id = ? AND student_id = ?",
                (rs, n) -> summaryRow(rs), assignmentId, studentId).stream().findFirst();
    }

    public Optional<SummaryFact> lockSummary(String id) {
        return jdbc.query("SELECT * FROM teacher_summary WHERE id = ? FOR UPDATE",
                (rs, n) -> summaryRow(rs), id).stream().findFirst();
    }

    public void insertSummary(SummaryFact row) {
        jdbc.update("""
                INSERT INTO teacher_summary
                    (id, assignment_id, student_id, version, revision, grade_snapshot_id, summary, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, row.id(), row.assignmentId(), row.studentId(), row.version(), row.revision(),
                row.snapshotId(), row.summary(), row.status());
    }

    public int updateSummary(SummaryFact row, int expectedRevision, boolean bumpVersion) {
        return jdbc.update("""
                UPDATE teacher_summary
                SET version = ?, revision = revision + 1, grade_snapshot_id = ?, summary = ?, status = ?
                WHERE id = ? AND revision = ?
                """, bumpVersion ? row.version() + 1 : row.version(), row.snapshotId(), row.summary(),
                row.status(), row.id(), expectedRevision);
    }

    public List<SummaryFact> mySummaries(String studentId) {
        return jdbc.query("SELECT * FROM teacher_summary WHERE student_id = ? ORDER BY id",
                (rs, n) -> summaryRow(rs), studentId);
    }

    public void upsertConsent(String summaryId, int version, String studentId, String decision) {
        jdbc.update("""
                INSERT INTO teacher_summary_consent (summary_id, content_version, student_id, decision)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (summary_id, content_version, student_id) DO UPDATE SET decision = EXCLUDED.decision
                """, summaryId, version, studentId, decision);
    }

    public Optional<String> consent(String summaryId, int version, String studentId) {
        return jdbc.query("""
                SELECT decision FROM teacher_summary_consent
                WHERE summary_id = ? AND content_version = ? AND student_id = ?
                """, (rs, n) -> rs.getString(1), summaryId, version, studentId).stream().findFirst();
    }

    private static RuleFact rule(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new RuleFact(rs.getString("id"), rs.getString("assignment_id"), rs.getInt("version"),
                rs.getBigDecimal("process_weight"), rs.getString("description"), rs.getString("confirmed_by"),
                rs.getObject("confirmed_at", OffsetDateTime.class).toInstant());
    }

    private static SnapshotFact snapshot(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new SnapshotFact(rs.getString("id"), rs.getString("publication_id"),
                rs.getString("assignment_id"), rs.getString("student_id"), rs.getInt("version"),
                rs.getInt("b"), rs.getInt("q"), rs.getInt("delta"), rs.getInt("g"),
                rs.getString("basis_digest").trim(), rs.getString("rule_snapshot"),
                rs.getString("ledger_snapshot"), rs.getString("evidence_snapshot"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }

    private static AppealFact appeal(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new AppealFact(rs.getString("id"), rs.getString("grade_snapshot_id"),
                rs.getString("assignment_id"), rs.getString("student_id"), rs.getString("status"),
                rs.getString("issue"), rs.getString("source_refs_json"), rs.getString("evidence_refs_json"),
                rs.getString("responses_json"), rs.getString("revised_snapshot_id"), rs.getInt("revision"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }

    private static SummaryFact summaryRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new SummaryFact(rs.getString("id"), rs.getString("assignment_id"), rs.getString("student_id"),
                rs.getInt("version"), rs.getInt("revision"), rs.getString("grade_snapshot_id"),
                rs.getString("summary"), rs.getString("status"));
    }

    private static OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    public record AssignmentHead(String id, String squareId, int revision) {
    }

    public record ShareFact(String taskId, String taskTitle, String unitId, String unitTitle, Instant dueAt,
                            String shareId, BigDecimal points, boolean signed, int agreementVersion,
                            Integer activeVersion, int openAgreementVersion, String openAgreementStatus) {
    }

    public record PendingSubmissionFact(String unitId, String submissionId) {
    }

    public record HandoverFact(String taskId, String itemId, String changeId, String status,
                               int planVersion, String recipientId, boolean blocked) {
    }

    public record AcceptanceFact(String unitId, String acceptanceId, String kind) {
    }

    public record CorrectionFact(String id, String taskId, String unitId, BigDecimal responsibility,
                                 BigDecimal accepted, String reason, String evidenceJson, String sourceKey,
                                 String authorId, int revision, Instant createdAt) {
    }

    public record RuleFact(String id, String assignmentId, int version, BigDecimal weight, String description,
                           String confirmedBy, Instant confirmedAt) {
    }

    public record SettlementFact(String id, String assignmentId, String studentId, String sourceKey,
                                 int ruleVersion, BigDecimal responsibility, BigDecimal accepted, int b,
                                 Instant settledAt) {
    }

    public record DraftFact(String id, String assignmentId, String studentId, int q, int delta,
                            String deltaReason, String evidenceJson, String sourceKey, int ruleVersion,
                            int revision) {
    }

    public record SnapshotFact(String id, String publicationId, String assignmentId, String studentId,
                               int version, int b, int q, int delta, int g, String basisDigest,
                               String ruleJson, String ledgerJson, String evidenceJson, Instant createdAt) {
    }

    public record AppealFact(String id, String gradeSnapshotId, String assignmentId, String studentId,
                             String status, String issue, String sourceJson, String evidenceJson,
                             String responsesJson, String revisedSnapshotId, int revision, Instant createdAt) {
    }

    public record SummaryFact(String id, String assignmentId, String studentId, int version, int revision,
                              String snapshotId, String summary, String status) {
    }
}
