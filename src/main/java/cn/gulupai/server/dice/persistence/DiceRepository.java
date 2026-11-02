package cn.gulupai.server.dice.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class DiceRepository {

    private final JdbcTemplate jdbc;

    public DiceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private final RowMapper<SessionRow> sessionMapper = (rs, n) -> new SessionRow(
            rs.getString("id"), rs.getString("project_id"), rs.getString("task_id"),
            rs.getString("created_by"), rs.getString("rule"),
            (Integer) rs.getObject("target_value"), rs.getInt("max_rounds"), rs.getInt("round_no"),
            rs.getString("status"), rs.getInt("task_revision"), rs.getInt("revision"),
            rs.getObject("invite_ends_at", OffsetDateTime.class).toInstant(),
            rs.getObject("roll_ends_at", OffsetDateTime.class).toInstant(),
            rs.getBoolean("source_frozen"), rs.getString("proposed_owner_id"),
            rs.getString("outcome_decision"), rs.getString("claim_status"),
            rs.getString("termination_reason"), rs.getString("winner_id"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant());

    private final RowMapper<ParticipantRow> participantMapper = (rs, n) -> new ParticipantRow(
            rs.getString("session_id"), rs.getString("user_id"), rs.getString("decision"),
            rs.getBoolean("active_in_round"), rs.getInt("revision"));

    private final RowMapper<RollRow> rollMapper = (rs, n) -> new RollRow(
            rs.getString("id"), rs.getString("session_id"), rs.getInt("round_no"),
            rs.getString("user_id"), rs.getInt("value"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant());

    private final RowMapper<RoundRow> roundMapper = (rs, n) -> new RoundRow(
            rs.getString("session_id"), rs.getInt("round_no"), rs.getString("candidate_ids"),
            rs.getString("winner_id"), rs.getBoolean("revealed"));

    public Optional<SessionRow> session(String id) {
        return jdbc.query("SELECT * FROM dice_session WHERE id = ?", sessionMapper, id)
                .stream().findFirst();
    }

    public Optional<SessionRow> lockSession(String id) {
        return jdbc.query("SELECT * FROM dice_session WHERE id = ? FOR UPDATE", sessionMapper, id)
                .stream().findFirst();
    }

    public Optional<SessionRow> activeForTask(String taskId) {
        return jdbc.query("""
                SELECT * FROM dice_session WHERE task_id = ?
                  AND status IN ('INVITING','ROLLING','TIE_ROUND','AWAITING_CLAIM')
                ORDER BY created_at DESC LIMIT 1
                """, sessionMapper, taskId).stream().findFirst();
    }

    public Optional<SessionRow> lockActiveForTask(String taskId) {
        return jdbc.query("""
                SELECT * FROM dice_session WHERE task_id = ?
                  AND status IN ('INVITING','ROLLING','TIE_ROUND','AWAITING_CLAIM')
                ORDER BY created_at DESC LIMIT 1 FOR UPDATE
                """, sessionMapper, taskId).stream().findFirst();
    }

    public boolean hasSession(String taskId) {
        Boolean exists = jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM dice_session WHERE task_id = ?)", Boolean.class, taskId);
        return Boolean.TRUE.equals(exists);
    }

    public void insertSession(String id, String projectId, String taskId, String createdBy,
                              String rule, Integer targetValue, int taskRevision, int revision,
                              Instant inviteEndsAt, Instant rollEndsAt, Instant now) {
        jdbc.update("""
                INSERT INTO dice_session(id, project_id, task_id, created_by, rule, target_value,
                    max_rounds, round_no, status, task_revision, revision, invite_ends_at, roll_ends_at,
                    source_frozen, claim_status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, 3, 1, 'INVITING', ?, ?, ?, ?, false,
                        'NOT_REQUESTED', ?, ?)
                """, id, projectId, taskId, createdBy, rule, targetValue, taskRevision, revision,
                time(inviteEndsAt), time(rollEndsAt), time(now), time(now));
    }

    public void insertParticipant(String id, String sessionId, String userId, Instant now) {
        jdbc.update("""
                INSERT INTO dice_participant(id, session_id, user_id, decision, active_in_round,
                    revision, created_at, updated_at)
                VALUES (?, ?, ?, 'PENDING', true, 1, ?, ?)
                """, id, sessionId, userId, time(now), time(now));
    }

    public List<ParticipantRow> participants(String sessionId) {
        return jdbc.query("""
                SELECT * FROM dice_participant WHERE session_id = ? ORDER BY created_at, user_id
                """, participantMapper, sessionId);
    }

    public Optional<ParticipantRow> participant(String sessionId, String userId) {
        return jdbc.query("""
                SELECT * FROM dice_participant WHERE session_id = ? AND user_id = ?
                """, participantMapper, sessionId, userId).stream().findFirst();
    }

    public int decide(String sessionId, String userId, String decision, Instant now) {
        return jdbc.update("""
                UPDATE dice_participant SET decision = ?, revision = revision + 1, updated_at = ?
                WHERE session_id = ? AND user_id = ? AND decision = 'PENDING'
                """, decision, time(now), sessionId, userId);
    }

    public int acceptedCount(String sessionId) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM dice_participant WHERE session_id = ? AND decision = 'ACCEPTED'
                """, Integer.class, sessionId);
        return count == null ? 0 : count;
    }

    public int participantCount(String sessionId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM dice_participant WHERE session_id = ?", Integer.class, sessionId);
        return count == null ? 0 : count;
    }

    public int setActiveInRound(String sessionId, List<String> userIds) {
        jdbc.update("""
                UPDATE dice_participant SET active_in_round = false, revision = revision + 1
                WHERE session_id = ?
                """, sessionId);
        if (userIds.isEmpty()) return 0;
        String placeholders = String.join(",", userIds.stream().map(x -> "?").toList());
        Object[] args = new Object[userIds.size() + 1];
        args[0] = sessionId;
        for (int i = 0; i < userIds.size(); i++) args[i + 1] = userIds.get(i);
        return jdbc.update("""
                UPDATE dice_participant SET active_in_round = true, revision = revision + 1
                WHERE session_id = ? AND user_id IN (""" + placeholders + ")", args);
    }

    public int transition(String sessionId, int expectedRevision, String status, String claimStatus,
                          String terminationReason, Instant now) {
        return jdbc.update("""
                UPDATE dice_session SET status = ?, claim_status = COALESCE(?, claim_status),
                    termination_reason = COALESCE(?, termination_reason), source_frozen = true,
                    revision = revision + 1, updated_at = ?
                WHERE id = ? AND revision = ? AND status IN
                    ('INVITING','ROLLING','TIE_ROUND','AWAITING_CLAIM')
                """, status, claimStatus, terminationReason, time(now), sessionId, expectedRevision);
    }

    public int freeze(String sessionId, int expectedRevision, Instant now) {
        return jdbc.update("""
                UPDATE dice_session SET status = 'ROLLING', source_frozen = true,
                    revision = revision + 1, updated_at = ?
                WHERE id = ? AND revision = ? AND status = 'INVITING'
                """, time(now), sessionId, expectedRevision);
    }

    public int advance(String sessionId, int expectedRevision, int nextRound, String status,
                       String proposedOwnerId, String winnerId, Instant now) {
        return jdbc.update("""
                UPDATE dice_session SET round_no = ?, status = ?, proposed_owner_id = ?,
                    winner_id = ?, claim_status = ?, revision = revision + 1, updated_at = ?
                WHERE id = ? AND revision = ?
                """, nextRound, status, proposedOwnerId, winnerId,
                "AWAITING_CLAIM".equals(status) ? "AWAITING" : "NOT_REQUESTED",
                time(now), sessionId, expectedRevision);
    }

    public int setOutcome(String sessionId, int expectedRevision, String status,
                          String proposedOwnerId, String outcomeDecision, String claimStatus,
                          Instant now) {
        return jdbc.update("""
                UPDATE dice_session SET status = ?, proposed_owner_id = ?, outcome_decision = ?,
                    claim_status = ?, revision = revision + 1, updated_at = ?
                WHERE id = ? AND revision = ?
                """, status, proposedOwnerId, outcomeDecision, claimStatus, time(now),
                sessionId, expectedRevision);
    }

    public void insertRoll(String id, String sessionId, int roundNo, String userId, int value,
                           Instant now) {
        jdbc.update("""
                INSERT INTO dice_roll(id, session_id, round_no, user_id, value, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, id, sessionId, roundNo, userId, value, time(now));
    }

    public Optional<RollRow> roll(String sessionId, int roundNo, String userId) {
        return jdbc.query("""
                SELECT * FROM dice_roll WHERE session_id = ? AND round_no = ? AND user_id = ?
                """, rollMapper, sessionId, roundNo, userId).stream().findFirst();
    }

    public List<RollRow> rolls(String sessionId, int roundNo) {
        return jdbc.query("""
                SELECT * FROM dice_roll WHERE session_id = ? AND round_no = ?
                ORDER BY created_at, user_id
                """, rollMapper, sessionId, roundNo);
    }

    public int rollCount(String sessionId, int roundNo) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM dice_roll WHERE session_id = ? AND round_no = ?
                """, Integer.class, sessionId, roundNo);
        return count == null ? 0 : count;
    }

    public void insertRound(String id, String sessionId, int roundNo, String candidatesJson,
                            boolean revealed, Instant now) {
        jdbc.update("""
                INSERT INTO dice_round(id, session_id, round_no, candidate_ids, revealed,
                    created_at, updated_at)
                VALUES (?, ?, ?, ?::jsonb, ?, ?, ?)
                """, id, sessionId, roundNo, candidatesJson, revealed, time(now), time(now));
    }

    public int revealRound(String sessionId, int roundNo, String winnerId) {
        return jdbc.update("""
                UPDATE dice_round SET winner_id = ?, revealed = true, updated_at = CURRENT_TIMESTAMP
                WHERE session_id = ? AND round_no = ?
                """, winnerId, sessionId, roundNo);
    }

    public List<RoundRow> rounds(String sessionId) {
        return jdbc.query("""
                SELECT * FROM dice_round WHERE session_id = ? ORDER BY round_no
                """, roundMapper, sessionId);
    }

    public Optional<RoundRow> round(String sessionId, int roundNo) {
        return jdbc.query("""
                SELECT * FROM dice_round WHERE session_id = ? AND round_no = ?
                """, roundMapper, sessionId, roundNo).stream().findFirst();
    }

    public MarkTaskRow task(String taskId) {
        List<MarkTaskRow> rows = jdbc.query("""
                SELECT t.id, t.project_id, t.title, t.mode, t.status, t.owner_id, t.revision,
                       p.owner_id AS project_owner, p.status AS project_status
                FROM task t JOIN project p ON p.id = t.project_id WHERE t.id = ?
                """, (rs, n) -> new MarkTaskRow(rs.getString("id"), rs.getString("project_id"),
                rs.getString("title"), rs.getString("mode"), rs.getString("status"),
                rs.getString("owner_id"), rs.getInt("revision"), rs.getString("project_owner"),
                rs.getString("project_status")), taskId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public boolean member(String projectId, String userId) {
        Boolean ok = jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM membership WHERE project_id = ? AND user_id = ?
                              AND status = 'ACTIVE')
                """, Boolean.class, projectId, userId);
        return Boolean.TRUE.equals(ok);
    }

    public boolean leader(String projectId, String userId) {
        Boolean ok = jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM project p JOIN membership m ON m.project_id = p.id
                              WHERE p.id = ? AND p.owner_id = ? AND m.user_id = ?
                                AND m.status = 'ACTIVE' AND m.project_role = 'LEADER')
                """, Boolean.class, projectId, userId, userId);
        return Boolean.TRUE.equals(ok);
    }

    public int submissionCount(String taskId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM submission WHERE task_id = ?", Integer.class, taskId);
        return count == null ? 0 : count;
    }

    public String projectOwner(String projectId) {
        return jdbc.queryForObject("SELECT owner_id FROM project WHERE id = ?", String.class, projectId);
    }

    public boolean projectArchived(String projectId) {
        Boolean archived = jdbc.queryForObject(
                "SELECT status = 'ARCHIVED' FROM project WHERE id = ?", Boolean.class, projectId);
        return Boolean.TRUE.equals(archived);
    }

    public void markClaimOutcome(String taskId, String userId) {
        Optional<SessionRow> active = lockActiveForTask(taskId);
        if (active.isEmpty()) return;
        SessionRow session = active.get();
        if ("AWAITING_CLAIM".equals(session.status())
                && "ACCEPT".equals(session.outcomeDecision())
                && userId.equals(session.proposedOwnerId())) {
            jdbc.update("""
                    UPDATE dice_session SET status = 'CLAIMED', claim_status = 'CLAIMED',
                        revision = revision + 1, updated_at = CURRENT_TIMESTAMP WHERE id = ?
                    """, session.id());
        } else {
            jdbc.update("""
                    UPDATE dice_session SET status = 'INVALIDATED', claim_status = 'INVALIDATED',
                        termination_reason = '任务已被他人认领', revision = revision + 1,
                        updated_at = CURRENT_TIMESTAMP WHERE id = ?
                    """, session.id());
        }
    }

    public void expireIfNeeded(String sessionId, Instant now) {
        jdbc.update("""
                UPDATE dice_session SET status = 'EXPIRED', claim_status = 'EXPIRED',
                    revision = revision + 1, updated_at = ?
                WHERE id = ? AND status IN ('INVITING','ROLLING','TIE_ROUND','AWAITING_CLAIM')
                  AND roll_ends_at <= ?
                """, time(now), sessionId, time(now));
    }

    private OffsetDateTime time(Instant instant) {
        return OffsetDateTime.ofInstant(instant, java.time.ZoneOffset.UTC);
    }

    public record SessionRow(String id, String projectId, String taskId, String createdBy,
                             String rule, Integer targetValue, int maxRounds, int roundNo,
                             String status, int taskRevision, int revision, Instant inviteEndsAt,
                             Instant rollEndsAt, boolean sourceFrozen, String proposedOwnerId,
                             String outcomeDecision, String claimStatus, String terminationReason,
                             String winnerId, Instant createdAt) {
    }

    public record ParticipantRow(String sessionId, String userId, String decision,
                                 boolean activeInRound, int revision) {
    }

    public record RollRow(String id, String sessionId, int roundNo, String userId, int value,
                          Instant createdAt) {
    }

    public record RoundRow(String sessionId, int roundNo, String candidateIds, String winnerId,
                           boolean revealed) {
    }

    public record MarkTaskRow(String id, String projectId, String title, String mode, String status,
                              String ownerId, int revision, String projectOwner,
                              String projectStatus) {
    }
}
