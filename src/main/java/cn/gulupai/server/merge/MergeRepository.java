package cn.gulupai.server.merge;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

@Repository
public class MergeRepository {

    private final JdbcTemplate jdbc;

    public MergeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(ProposalRow row, Instant at) {
        jdbc.update("""
                INSERT INTO square_merge
                    (id, source_square_id, target_square_id, initiator_id, reason, evidence_json, status,
                     electorates_json, source_epochs_json, fact_check_json, voting_ends_at, revision,
                     created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?, ?, ?)
                """, row.id(), row.sourceSquareId(), row.targetSquareId(), row.initiatorId(), row.reason(),
                row.evidenceJson(), row.status(), row.electoratesJson(), row.epochsJson(), row.factCheckJson(),
                time(row.votingEndsAt()), row.revision(), time(at), time(at));
    }

    public Optional<ProposalRow> lock(String id) {
        return jdbc.query("SELECT * FROM square_merge WHERE id = ? FOR UPDATE", this::map, id).stream().findFirst();
    }

    public Optional<ProposalRow> find(String id) {
        return jdbc.query("SELECT * FROM square_merge WHERE id = ?", this::map, id).stream().findFirst();
    }

    public List<ProposalRow> forSquare(String squareId) {
        return jdbc.query("""
                SELECT * FROM square_merge
                WHERE source_square_id = ? OR target_square_id = ?
                ORDER BY created_at, id
                """, this::map, squareId, squareId);
    }

    public boolean openInvolving(String left, String right) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM square_merge
                WHERE status IN ('VOTING', 'WAIT_TEACHER', 'READY')
                  AND (source_square_id IN (?, ?) OR target_square_id IN (?, ?))
                """, Integer.class, left, right, left, right);
        return count != null && count > 0;
    }

    public int update(ProposalRow row, Instant at) {
        return jdbc.update("""
                UPDATE square_merge
                SET status = ?, fact_check_json = ?::jsonb, revision = revision + 1, updated_at = ?
                WHERE id = ? AND revision = ?
                """, row.status(), row.factCheckJson(), time(at), row.id(), row.revision());
    }

    public Optional<BallotRow> ballot(String proposalId, String squareId, String userId) {
        return jdbc.query("""
                SELECT * FROM merge_ballot WHERE proposal_id = ? AND square_id = ? AND user_id = ?
                """, (rs, n) -> new BallotRow(rs.getString("square_id"), rs.getString("user_id"),
                rs.getString("decision"), rs.getInt("revision")), proposalId, squareId, userId).stream().findFirst();
    }

    public List<BallotRow> ballots(String proposalId) {
        return jdbc.query("SELECT * FROM merge_ballot WHERE proposal_id = ?",
                (rs, n) -> new BallotRow(rs.getString("square_id"), rs.getString("user_id"),
                        rs.getString("decision"), rs.getInt("revision")), proposalId);
    }

    public void insertBallot(String proposalId, String squareId, String userId, String decision, Instant at) {
        jdbc.update("""
                INSERT INTO merge_ballot (proposal_id, square_id, user_id, decision, revision, at)
                VALUES (?, ?, ?, ?, 1, ?)
                """, proposalId, squareId, userId, decision, time(at));
    }

    public int updateBallot(String proposalId, String squareId, String userId, String decision, int revision, Instant at) {
        return jdbc.update("""
                UPDATE merge_ballot SET decision = ?, revision = revision + 1, at = ?
                WHERE proposal_id = ? AND square_id = ? AND user_id = ? AND revision = ?
                """, decision, time(at), proposalId, squareId, userId, revision);
    }

    public void deleteBallots(String proposalId) {
        jdbc.update("DELETE FROM merge_ballot WHERE proposal_id = ?", proposalId);
    }

    public List<String> activeStudents(String squareId) {
        return jdbc.query("""
                SELECT m.user_id FROM course_membership m
                JOIN user_role r ON r.user_id = m.user_id AND r.role = 'STUDENT'
                WHERE m.square_id = ? AND m.status = 'ACTIVE'
                ORDER BY m.user_id
                """, (rs, n) -> rs.getString("user_id"), squareId);
    }

    public void moveAssignments(String sourceSquareId, String targetSquareId) {
        jdbc.update("UPDATE assignment SET square_id = ? WHERE square_id = ?", targetSquareId, sourceSquareId);
    }

    public void moveProjects(String sourceSquareId, String targetSquareId) {
        jdbc.update("""
                UPDATE project SET square_id = ?, authorization_epoch = authorization_epoch + 1,
                    revision = revision + 1, updated_at = CURRENT_TIMESTAMP
                WHERE square_id = ?
                """, targetSquareId, sourceSquareId);
    }

    public void moveMemberships(String sourceSquareId, String targetSquareId, Instant at) {
        jdbc.update("""
                UPDATE course_membership SET status = 'LEFT', left_at = ?, updated_at = ?
                WHERE square_id = ? AND status = 'ACTIVE'
                  AND user_id IN (
                      SELECT user_id FROM course_membership WHERE square_id = ? AND status = 'ACTIVE')
                """, time(at), time(at), sourceSquareId, targetSquareId);
        jdbc.update("""
                UPDATE course_membership SET square_id = ?, updated_at = ?
                WHERE square_id = ? AND status = 'ACTIVE'
                """, targetSquareId, time(at), sourceSquareId);
    }

    public void revokeCodes(String squareId, Instant at) {
        jdbc.update("""
                UPDATE course_code SET status = 'REVOKED', updated_at = ?
                WHERE square_id = ? AND status = 'ACTIVE'
                """, time(at), squareId);
    }

    public void markMerged(String sourceSquareId, String targetSquareId, Instant at) {
        jdbc.update("""
                UPDATE square
                SET status = 'MERGED', merged_into_id = ?, revision = revision + 1,
                    authorization_epoch = authorization_epoch + 1, updated_at = ?
                WHERE id = ? AND status = 'ACTIVE'
                """, targetSquareId, time(at), sourceSquareId);
        jdbc.update("""
                UPDATE square
                SET revision = revision + 1, authorization_epoch = authorization_epoch + 1, updated_at = ?
                WHERE id = ?
                """, time(at), targetSquareId);
    }

    private ProposalRow map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new ProposalRow(rs.getString("id"), rs.getString("source_square_id"), rs.getString("target_square_id"),
                rs.getString("initiator_id"), rs.getString("reason"), rs.getString("evidence_json"),
                rs.getString("status"), rs.getString("electorates_json"), rs.getString("source_epochs_json"),
                rs.getString("fact_check_json"), instant(rs, "voting_ends_at"), rs.getInt("revision"),
                instant(rs, "created_at"));
    }

    private static OffsetDateTime time(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    public record ProposalRow(String id, String sourceSquareId, String targetSquareId, String initiatorId,
                              String reason, String evidenceJson, String status, String electoratesJson,
                              String epochsJson, String factCheckJson, Instant votingEndsAt, int revision,
                              Instant createdAt) {
    }

    public record BallotRow(String squareId, String userId, String decision, int revision) {
    }
}
