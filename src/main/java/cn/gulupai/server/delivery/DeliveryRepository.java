package cn.gulupai.server.delivery;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

@Repository
public class DeliveryRepository {

    private final JdbcTemplate jdbc;

    public DeliveryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertAgreement(String id, String taskId, int version, String status, int baseTaskRevision,
                                String planJson, String sourceChangeId, String renewalReason, Instant at) {
        jdbc.update("""
                INSERT INTO agreement
                    (id, task_id, version, status, base_task_revision, plan_json, source_change_id,
                     renewal_reason, revision, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, 1, ?, ?)
                """, id, taskId, version, status, baseTaskRevision, planJson, sourceChangeId, renewalReason,
                time(at), time(at));
    }

    public Optional<AgreementRow> findAgreement(String id) {
        return one("SELECT * FROM agreement WHERE id = ?", agreementMapper, id);
    }

    public Optional<AgreementRow> lockAgreement(String id) {
        return one("SELECT * FROM agreement WHERE id = ? FOR UPDATE", agreementMapper, id);
    }

    public Optional<AgreementRow> activeAgreement(String taskId) {
        return one("SELECT * FROM agreement WHERE task_id = ? AND status = 'ACTIVE'", agreementMapper, taskId);
    }

    public Optional<AgreementRow> openAgreement(String taskId) {
        return one("""
                SELECT * FROM agreement
                WHERE task_id = ? AND status IN ('PENDING', 'CHANGES_REQUESTED')
                """, agreementMapper, taskId);
    }

    public int nextAgreementVersion(String taskId) {
        Integer value = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version), 0) + 1 FROM agreement WHERE task_id = ?", Integer.class, taskId);
        return value == null ? 1 : value;
    }

    public int maxAgreementVersion(String taskId) {
        Integer value = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version), 0) FROM agreement WHERE task_id = ?", Integer.class, taskId);
        return value == null ? 0 : value;
    }

    public void supersedeStatus(String taskId, String fromStatus, Instant at) {
        jdbc.update("""
                UPDATE agreement SET status = 'SUPERSEDED', revision = revision + 1, updated_at = ?
                WHERE task_id = ? AND status = ?
                """, time(at), taskId, fromStatus);
    }

    public int setAgreementStatus(String id, int revision, String status, Instant at) {
        return jdbc.update("""
                UPDATE agreement SET status = ?, revision = revision + 1, updated_at = ?
                WHERE id = ? AND revision = ?
                """, status, time(at), id, revision);
    }

    public List<AckRow> acks(String agreementId) {
        return jdbc.query("SELECT * FROM agreement_ack WHERE agreement_id = ? ORDER BY at, user_id",
                ackMapper, agreementId);
    }

    public List<AckRef> acksIn(List<String> agreementIds) {
        return queryIn("""
                SELECT agreement_id, user_id, decision FROM agreement_ack WHERE agreement_id IN (
                """, ") ORDER BY agreement_id, user_id",
                (rs, n) -> new AckRef(rs.getString("agreement_id"), rs.getString("user_id"), rs.getString("decision")),
                agreementIds);
    }

    public void upsertAck(String agreementId, String userId, String decision, String comment, Instant at) {
        jdbc.update("""
                INSERT INTO agreement_ack (agreement_id, user_id, decision, comment, at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (agreement_id, user_id) DO UPDATE
                SET decision = EXCLUDED.decision, comment = EXCLUDED.comment, at = EXCLUDED.at
                """, agreementId, userId, decision, comment, time(at));
    }

    public void insertUnit(String id, String taskId, String clientKey, String title, Instant dueAt,
                           BigDecimal points, int position, Instant ignored) {
        jdbc.update("""
                INSERT INTO delivery_unit (id, task_id, client_key, title, due_at, points, revision, position, passed)
                VALUES (?, ?, ?, ?, ?, ?, 1, ?, false)
                """, id, taskId, clientKey, title, time(dueAt), points, position);
    }

    public List<UnitRow> units(String taskId) {
        return jdbc.query("SELECT * FROM delivery_unit WHERE task_id = ? ORDER BY position, id", unitMapper, taskId);
    }

    public Optional<UnitRow> lockUnit(String id) {
        return one("SELECT * FROM delivery_unit WHERE id = ? FOR UPDATE", unitMapper, id);
    }

    public int updateUnit(String id, int revision, String title, Instant dueAt, BigDecimal points) {
        return jdbc.update("""
                UPDATE delivery_unit SET title = ?, due_at = ?, points = ?, revision = revision + 1
                WHERE id = ? AND revision = ?
                """, title, time(dueAt), points, id, revision);
    }

    public void markPassed(String id) {
        jdbc.update("UPDATE delivery_unit SET passed = true WHERE id = ? AND passed = false", id);
    }

    public void insertShare(String id, String unitId, String userId, BigDecimal points, String responsibility,
                            int agreementVersion, boolean signed) {
        jdbc.update("""
                INSERT INTO unit_share
                    (id, unit_id, user_id, points, responsibility, agreement_version, signed)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, id, unitId, userId, points, responsibility, agreementVersion, signed);
    }

    public List<ShareRow> shares(String unitId, int agreementVersion) {
        return jdbc.query("""
                SELECT * FROM unit_share WHERE unit_id = ? AND agreement_version = ? ORDER BY user_id
                """, shareMapper, unitId, agreementVersion);
    }

    public void signShares(String taskId, int agreementVersion) {
        jdbc.update("""
                UPDATE unit_share SET signed = true
                WHERE agreement_version = ? AND unit_id IN (SELECT id FROM delivery_unit WHERE task_id = ?)
                """, agreementVersion, taskId);
    }

    public boolean hasSignedShare(String unitId, String userId) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM unit_share WHERE unit_id = ? AND user_id = ? AND signed = true
                """, Integer.class, unitId, userId);
        return count != null && count > 0;
    }

    public void insertCriterion(String id, String taskId, String unitId, String text, int position) {
        jdbc.update("""
                INSERT INTO criterion (id, task_id, unit_id, text, position, standard_version)
                VALUES (?, ?, ?, ?, ?, 1)
                """, id, taskId, unitId, text, position);
    }

    public void deleteUnitCriteria(String unitId) {
        jdbc.update("DELETE FROM criterion WHERE unit_id = ?", unitId);
    }

    public void insertArrangement(String taskId, String userId, String kind, String status, int version) {
        jdbc.update("""
                INSERT INTO reviewer_arrangement (task_id, user_id, kind, status, version)
                VALUES (?, ?, ?, ?, ?)
                """, taskId, userId, kind, status, version);
    }

    public Optional<ArrangementRow> arrangement(String taskId) {
        return one("SELECT * FROM reviewer_arrangement WHERE task_id = ?", arrangementMapper, taskId);
    }

    public void updateArrangement(String taskId, String userId, String kind, String status, int version) {
        jdbc.update("""
                UPDATE reviewer_arrangement SET user_id = ?, kind = ?, status = ?, version = ?
                WHERE task_id = ?
                """, userId, kind, status, version, taskId);
    }

    public void insertInvitation(String id, String taskId, String recipientId, String status, int version,
                                 Instant expiresAt, Instant at) {
        jdbc.update("""
                INSERT INTO reviewer_invitation
                    (id, task_id, recipient_id, status, arrangement_version, expires_at, revision, created_at)
                VALUES (?, ?, ?, ?, ?, ?, 1, ?)
                """, id, taskId, recipientId, status, version, time(expiresAt), time(at));
    }

    public Optional<InvitationRow> lockInvitation(String id) {
        return one("SELECT * FROM reviewer_invitation WHERE id = ? FOR UPDATE", invitationMapper, id);
    }

    public Optional<InvitationRow> pendingInvitation(String taskId) {
        return one("""
                SELECT * FROM reviewer_invitation WHERE task_id = ? AND status = 'PENDING'
                """, invitationMapper, taskId);
    }

    public int setInvitationStatus(String id, int revision, String status) {
        return jdbc.update("""
                UPDATE reviewer_invitation SET status = ?, revision = revision + 1
                WHERE id = ? AND revision = ? AND status = 'PENDING'
                """, status, id, revision);
    }

    public boolean acceptedInvitation(String taskId, String userId) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM reviewer_invitation
                WHERE task_id = ? AND recipient_id = ? AND status = 'ACCEPTED'
                """, Integer.class, taskId, userId);
        return count != null && count > 0;
    }

    public void insertDraft(String id, String taskId, String userId, String unitIdsJson, String summary,
                            String artifactsJson, Integer agreementVersion, int revision, Instant at) {
        jdbc.update("""
                INSERT INTO delivery_draft
                    (id, task_id, user_id, unit_ids_json, summary, artifacts_json, base_agreement_version,
                     revision, updated_at)
                VALUES (?, ?, ?, ?::jsonb, ?, ?::jsonb, ?, ?, ?)
                """, id, taskId, userId, unitIdsJson, summary, artifactsJson, agreementVersion, revision, time(at));
    }

    public int updateDraft(String id, int revision, String unitIdsJson, String summary, String artifactsJson,
                           Integer agreementVersion, Instant at) {
        return jdbc.update("""
                UPDATE delivery_draft
                SET unit_ids_json = ?::jsonb, summary = ?, artifacts_json = ?::jsonb,
                    base_agreement_version = ?, revision = revision + 1, updated_at = ?
                WHERE id = ? AND revision = ?
                """, unitIdsJson, summary, artifactsJson, agreementVersion, time(at), id, revision);
    }

    public Optional<DraftRow> findDraft(String taskId, String userId) {
        return one("SELECT * FROM delivery_draft WHERE task_id = ? AND user_id = ?", draftMapper, taskId, userId);
    }

    public void insertSubmission(String id, String taskId, int sequence, String userId, String summary,
                                 String artifactsJson, String unitIdsJson, String criteriaJson, Instant dueSnapshot,
                                 String unitDueJson, String agreementJson, Integer agreementVersion,
                                 Integer fromDraftRevision, Instant at) {
        jdbc.update("""
                INSERT INTO submission
                    (id, task_id, sequence, submitted_by, status, summary, artifacts_json, unit_ids_json,
                     criteria_snapshot, due_snapshot, unit_due_snapshot_json, agreement_snapshot,
                     agreement_version, from_draft_revision, submitted_at, revision)
                VALUES (?, ?, ?, ?, 'PENDING', ?, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?::jsonb, ?::jsonb, ?, ?, ?, 1)
                """, id, taskId, sequence, userId, summary, artifactsJson, unitIdsJson, criteriaJson,
                dueSnapshot == null ? null : time(dueSnapshot), unitDueJson, agreementJson, agreementVersion,
                fromDraftRevision, time(at));
    }

    public Optional<SubmissionRow> lockSubmission(String id) {
        return one("SELECT * FROM submission WHERE id = ? FOR UPDATE", submissionMapper, id);
    }

    public List<SubmissionRow> submissions(String taskId) {
        return jdbc.query("SELECT * FROM submission WHERE task_id = ? ORDER BY sequence", submissionMapper, taskId);
    }

    public int nextSequence(String taskId) {
        Integer value = jdbc.queryForObject(
                "SELECT COALESCE(MAX(sequence), 0) + 1 FROM submission WHERE task_id = ?", Integer.class, taskId);
        return value == null ? 1 : value;
    }

    public int finishSubmission(String id, int revision, String status) {
        return jdbc.update("""
                UPDATE submission SET status = ?, revision = revision + 1
                WHERE id = ? AND revision = ? AND status = 'PENDING'
                """, status, id, revision);
    }

    public boolean hasPending(String taskId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM submission WHERE task_id = ? AND status = 'PENDING'", Integer.class, taskId);
        return count != null && count > 0;
    }

    public void insertAcceptance(String id, String submissionId, String reviewerId, String kind, String checksJson,
                                 String decision, String reason, String selfCheckReason, Instant at) {
        jdbc.update("""
                INSERT INTO acceptance
                    (id, submission_id, reviewer_id, kind, checks_json, decision, reason, self_check_reason,
                     accepted_at, revision)
                VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, 1)
                """, id, submissionId, reviewerId, kind, checksJson, decision, reason, selfCheckReason, time(at));
    }

    public Optional<AcceptanceRow> acceptanceOf(String submissionId) {
        return one("SELECT * FROM acceptance WHERE submission_id = ?", acceptanceMapper, submissionId);
    }

    public Optional<AcceptanceRow> findAcceptance(String id) {
        return one("SELECT * FROM acceptance WHERE id = ?", acceptanceMapper, id);
    }

    public void insertUnitAcceptance(String unitId, String submissionId, String acceptanceId, boolean passed) {
        jdbc.update("""
                INSERT INTO unit_acceptance (unit_id, submission_id, acceptance_id, passed)
                VALUES (?, ?, ?, ?)
                """, unitId, submissionId, acceptanceId, passed);
    }

    public void insertContribution(String id, String unitId, String userId, String acceptanceId, String submissionId,
                                   String statement, Instant at) {
        jdbc.update("""
                INSERT INTO contribution_confirmation
                    (id, unit_id, user_id, acceptance_id, submission_id, statement, at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, id, unitId, userId, acceptanceId, submissionId, statement, time(at));
    }

    public List<ContributionRow> contributions(String unitId) {
        return jdbc.query("SELECT * FROM contribution_confirmation WHERE unit_id = ? ORDER BY at, id",
                contributionMapper, unitId);
    }

    public void insertProposal(String id, String taskId, int baseVersion, String changesJson, String reason,
                               String affectedJson, String initiatorId, Instant at) {
        jdbc.update("""
                INSERT INTO unit_change_proposal
                    (id, task_id, base_agreement_version, status, changes_json, reason, affected_users_json,
                     initiator_id, revision, created_at, updated_at)
                VALUES (?, ?, ?, 'PENDING', ?::jsonb, ?, ?::jsonb, ?, 1, ?, ?)
                """, id, taskId, baseVersion, changesJson, reason, affectedJson, initiatorId, time(at), time(at));
    }

    public Optional<ProposalRow> lockProposal(String id) {
        return one("SELECT * FROM unit_change_proposal WHERE id = ? FOR UPDATE", proposalMapper, id);
    }

    public List<ProposalRow> proposals(String taskId) {
        return jdbc.query("""
                SELECT * FROM unit_change_proposal WHERE task_id = ? ORDER BY created_at, id
                """, proposalMapper, taskId);
    }

    public int touchProposal(String id, int revision, String status, Instant at) {
        return jdbc.update("""
                UPDATE unit_change_proposal
                SET status = ?, revision = revision + 1, updated_at = ?
                WHERE id = ? AND revision = ?
                """, status, time(at), id, revision);
    }

    public int bumpProposal(String id, int revision, Instant at) {
        return jdbc.update("""
                UPDATE unit_change_proposal SET revision = revision + 1, updated_at = ?
                WHERE id = ? AND revision = ? AND status = 'PENDING'
                """, time(at), id, revision);
    }

    public void upsertConfirmation(String id, String proposalId, String userId, String decision, String comment,
                                   int sourceRevision, Instant at) {
        jdbc.update("""
                INSERT INTO proposal_response
                    (id, proposal_id, user_id, kind, decision, comment, proposal_text, opinion_id, source_revision, at)
                VALUES (?, ?, ?, 'CONFIRMATION', ?, ?, NULL, NULL, ?, ?)
                ON CONFLICT (proposal_id, user_id) WHERE kind = 'CONFIRMATION'
                DO UPDATE SET decision = EXCLUDED.decision, comment = EXCLUDED.comment,
                    source_revision = EXCLUDED.source_revision, at = EXCLUDED.at
                """, id, proposalId, userId, decision, comment, sourceRevision, time(at));
    }

    public void insertOpinion(String id, String proposalId, String userId, String comment, int sourceRevision, Instant at) {
        jdbc.update("""
                INSERT INTO proposal_response
                    (id, proposal_id, user_id, kind, decision, comment, proposal_text, opinion_id, source_revision, at)
                VALUES (?, ?, ?, 'OPINION', NULL, ?, '', NULL, ?, ?)
                """, id, proposalId, userId, comment, sourceRevision, time(at));
    }

    public int updateOpinion(String id, String proposalText, String decision, String comment) {
        return jdbc.update("""
                UPDATE proposal_response
                SET proposal_text = COALESCE(?, proposal_text),
                    decision = COALESCE(?, decision),
                    comment = CASE WHEN ? IS NULL THEN comment ELSE ? END
                WHERE id = ? AND kind = 'OPINION'
                """, proposalText, decision, comment, comment, id);
    }

    public List<ResponseRow> responses(String proposalId) {
        return jdbc.query("SELECT * FROM proposal_response WHERE proposal_id = ? ORDER BY at, id",
                responseMapper, proposalId);
    }

    public List<ResponseRow> responsesIn(List<String> proposalIds) {
        return queryIn("SELECT * FROM proposal_response WHERE proposal_id IN (", ") ORDER BY at, id",
                responseMapper, proposalIds);
    }

    public Optional<ResponseRow> findResponse(String id) {
        return one("SELECT * FROM proposal_response WHERE id = ?", responseMapper, id);
    }

    public List<UnitRow> unitsIn(List<String> taskIds) {
        return queryIn("SELECT * FROM delivery_unit WHERE task_id IN (", ") ORDER BY position, id", unitMapper, taskIds);
    }

    public List<ShareRow> sharesIn(List<String> unitIds) {
        return queryIn("SELECT * FROM unit_share WHERE unit_id IN (", ") ORDER BY user_id", shareMapper, unitIds);
    }

    public List<AgreementRow> agreementsIn(List<String> taskIds) {
        return queryIn("SELECT * FROM agreement WHERE task_id IN (", ") ORDER BY version", agreementMapper, taskIds);
    }

    public List<ArrangementRow> arrangementsIn(List<String> taskIds) {
        return queryIn("SELECT * FROM reviewer_arrangement WHERE task_id IN (", ")", arrangementMapper, taskIds);
    }

    public Optional<ProposalRow> findProposal(String id) {
        return one("SELECT * FROM unit_change_proposal WHERE id = ?", proposalMapper, id);
    }

    public List<UnitAcceptanceLink> unitAcceptancesIn(List<String> unitIds) {
        return queryIn("""
                SELECT unit_id, submission_id, acceptance_id, passed FROM unit_acceptance WHERE unit_id IN (
                """, ") ORDER BY unit_id, submission_id", unitAcceptanceMapper, unitIds);
    }

    public List<AcceptanceRow> acceptancesForSubmissions(List<String> submissionIds) {
        return queryIn("SELECT * FROM acceptance WHERE submission_id IN (", ")", acceptanceMapper, submissionIds);
    }

    public List<ContributionRow> contributionsIn(List<String> unitIds) {
        return queryIn("SELECT * FROM contribution_confirmation WHERE unit_id IN (", ") ORDER BY at, id",
                contributionMapper, unitIds);
    }

    private <T> List<T> queryIn(String prefix, String suffix, RowMapper<T> mapper, List<String> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        String marks = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        return jdbc.query(prefix + marks + suffix, mapper, ids.toArray());
    }

    private <T> Optional<T> one(String sql, RowMapper<T> mapper, Object... args) {
        return jdbc.query(sql, mapper, args).stream().findFirst();
    }

    private static OffsetDateTime time(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private final RowMapper<AgreementRow> agreementMapper = (rs, n) -> new AgreementRow(
            rs.getString("id"), rs.getString("task_id"), rs.getInt("version"), rs.getString("status"),
            rs.getInt("base_task_revision"), rs.getString("plan_json"), rs.getString("source_change_id"),
            rs.getString("renewal_reason"), rs.getInt("revision"), instant(rs, "created_at"));

    private final RowMapper<AckRow> ackMapper = (rs, n) -> new AckRow(
            rs.getString("user_id"), rs.getString("decision"), rs.getString("comment"), instant(rs, "at"));

    private final RowMapper<UnitRow> unitMapper = (rs, n) -> new UnitRow(
            rs.getString("id"), rs.getString("task_id"), rs.getString("client_key"), rs.getString("title"),
            instant(rs, "due_at"), rs.getBigDecimal("points"), rs.getInt("revision"), rs.getInt("position"),
            rs.getBoolean("passed"));

    private final RowMapper<ShareRow> shareMapper = (rs, n) -> new ShareRow(
            rs.getString("id"), rs.getString("unit_id"), rs.getString("user_id"), rs.getBigDecimal("points"),
            rs.getString("responsibility"), rs.getInt("agreement_version"), rs.getBoolean("signed"));

    private final RowMapper<ArrangementRow> arrangementMapper = (rs, n) -> new ArrangementRow(
            rs.getString("task_id"), rs.getString("user_id"), rs.getString("kind"), rs.getString("status"),
            rs.getInt("version"));

    private final RowMapper<InvitationRow> invitationMapper = (rs, n) -> new InvitationRow(
            rs.getString("id"), rs.getString("task_id"), rs.getString("recipient_id"), rs.getString("status"),
            rs.getInt("arrangement_version"), instant(rs, "expires_at"), rs.getInt("revision"));

    private final RowMapper<DraftRow> draftMapper = (rs, n) -> new DraftRow(
            rs.getString("id"), rs.getString("task_id"), rs.getString("user_id"), rs.getString("unit_ids_json"),
            rs.getString("summary"), rs.getString("artifacts_json"),
            rs.getObject("base_agreement_version") == null ? null : rs.getInt("base_agreement_version"),
            rs.getInt("revision"), instant(rs, "updated_at"));

    private final RowMapper<SubmissionRow> submissionMapper = (rs, n) -> new SubmissionRow(
            rs.getString("id"), rs.getString("task_id"), rs.getInt("sequence"), rs.getString("submitted_by"),
            rs.getString("status"), rs.getString("summary"), rs.getString("artifacts_json"),
            rs.getString("unit_ids_json"), rs.getString("criteria_snapshot"), instant(rs, "due_snapshot"),
            rs.getString("unit_due_snapshot_json"),
            rs.getObject("agreement_version") == null ? null : rs.getInt("agreement_version"),
            rs.getObject("from_draft_revision") == null ? null : rs.getInt("from_draft_revision"),
            instant(rs, "submitted_at"), rs.getInt("revision"));

    private final RowMapper<AcceptanceRow> acceptanceMapper = (rs, n) -> new AcceptanceRow(
            rs.getString("id"), rs.getString("submission_id"), rs.getString("reviewer_id"), rs.getString("kind"),
            rs.getString("checks_json"), rs.getString("decision"), rs.getString("reason"),
            rs.getString("self_check_reason"), instant(rs, "accepted_at"), rs.getInt("revision"));

    private final RowMapper<UnitAcceptanceLink> unitAcceptanceMapper = (rs, n) -> new UnitAcceptanceLink(
            rs.getString("unit_id"), rs.getString("submission_id"), rs.getString("acceptance_id"),
            rs.getBoolean("passed"));

    private final RowMapper<ContributionRow> contributionMapper = (rs, n) -> new ContributionRow(
            rs.getString("id"), rs.getString("unit_id"), rs.getString("user_id"), rs.getString("acceptance_id"),
            rs.getString("submission_id"), rs.getString("statement"), instant(rs, "at"));

    private final RowMapper<ProposalRow> proposalMapper = (rs, n) -> new ProposalRow(
            rs.getString("id"), rs.getString("task_id"), rs.getInt("base_agreement_version"), rs.getString("status"),
            rs.getString("changes_json"), rs.getString("reason"), rs.getString("affected_users_json"),
            rs.getString("initiator_id"), rs.getInt("revision"));

    private final RowMapper<ResponseRow> responseMapper = (rs, n) -> new ResponseRow(
            rs.getString("id"), rs.getString("proposal_id"), rs.getString("user_id"), rs.getString("kind"),
            rs.getString("decision"), rs.getString("comment"), rs.getString("proposal_text"), instant(rs, "at"));

    public record AgreementRow(String id, String taskId, int version, String status, int baseTaskRevision,
                               String planJson, String sourceChangeId, String renewalReason, int revision,
                               Instant createdAt) {
    }

    public record UnitAcceptanceLink(String unitId, String submissionId, String acceptanceId, boolean passed) {
    }

    public record AckRow(String userId, String decision, String comment, Instant at) {
    }

    public record AckRef(String agreementId, String userId, String decision) {
    }

    public record UnitRow(String id, String taskId, String clientKey, String title, Instant dueAt,
                          BigDecimal points, int revision, int position, boolean passed) {
    }

    public record ShareRow(String id, String unitId, String userId, BigDecimal points, String responsibility,
                           int agreementVersion, boolean signed) {
    }

    public record ArrangementRow(String taskId, String userId, String kind, String status, int version) {
    }

    public record InvitationRow(String id, String taskId, String recipientId, String status, int arrangementVersion,
                                Instant expiresAt, int revision) {
    }

    public record DraftRow(String id, String taskId, String userId, String unitIdsJson, String summary,
                           String artifactsJson, Integer agreementVersion, int revision, Instant updatedAt) {
    }

    public record SubmissionRow(String id, String taskId, int sequence, String submittedBy, String status,
                                String summary, String artifactsJson, String unitIdsJson, String criteriaJson,
                                Instant dueSnapshot, String unitDueJson, Integer agreementVersion,
                                Integer fromDraftRevision, Instant submittedAt, int revision) {
    }

    public record AcceptanceRow(String id, String submissionId, String reviewerId, String kind, String checksJson,
                                String decision, String reason, String selfCheckReason, Instant acceptedAt,
                                int revision) {
    }

    public record ContributionRow(String id, String unitId, String userId, String acceptanceId, String submissionId,
                                  String statement, Instant at) {
    }

    public record ProposalRow(String id, String taskId, int baseAgreementVersion, String status, String changesJson,
                              String reason, String affectedUsersJson, String initiatorId, int revision) {
    }

    public record ResponseRow(String id, String proposalId, String userId, String kind, String decision,
                              String comment, String proposalText, Instant at) {
    }
}
