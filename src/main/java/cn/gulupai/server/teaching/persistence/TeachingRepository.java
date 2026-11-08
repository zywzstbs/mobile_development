package cn.gulupai.server.teaching.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class TeachingRepository {

    private final JdbcTemplate jdbcTemplate;

    private final RowMapper<TeacherAssignmentRow> assignmentMapper = (rs, rowNum) -> new TeacherAssignmentRow(
            rs.getString("id"),
            rs.getString("square_id"),
            rs.getString("user_id"),
            rs.getBoolean("active"),
            rs.getObject("started_at", OffsetDateTime.class).toInstant(),
            rs.getObject("ended_at", OffsetDateTime.class) == null ? null
                    : rs.getObject("ended_at", OffsetDateTime.class).toInstant(),
            rs.getInt("revision"));

    private final RowMapper<TeacherClaimRow> claimMapper = (rs, rowNum) -> new TeacherClaimRow(
            rs.getString("id"),
            rs.getString("square_id"),
            rs.getString("user_id"),
            rs.getString("status"),
            rs.getString("message"),
            rs.getString("proof_refs_json"),
            rs.getInt("revision"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant());

    private final RowMapper<TeacherTransferRow> transferMapper = (rs, rowNum) -> new TeacherTransferRow(
            rs.getString("id"),
            rs.getString("square_id"),
            rs.getString("from_user_id"),
            rs.getString("to_user_id"),
            rs.getString("status"),
            rs.getInt("source_revision"),
            rs.getString("reason"),
            rs.getInt("revision"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant());

    public TeachingRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<TeacherAssignmentRow> activeAssignment(String squareId) {
        return jdbcTemplate.query("""
                        SELECT id, square_id, user_id, active, started_at, ended_at, revision
                        FROM teacher_assignment WHERE square_id = ? AND active
                        """, assignmentMapper, squareId)
                .stream().findFirst();
    }

    public boolean isActiveTeacher(String squareId, String userId) {
        Boolean exists = jdbcTemplate.queryForObject("""
                SELECT EXISTS(
                    SELECT 1 FROM teacher_assignment
                    WHERE square_id = ? AND user_id = ? AND active
                )
                """, Boolean.class, squareId, userId);
        return Boolean.TRUE.equals(exists);
    }

    public List<TeacherAssignmentRow> activeAssignmentsForUser(String userId) {
        return jdbcTemplate.query("""
                SELECT id, square_id, user_id, active, started_at, ended_at, revision
                FROM teacher_assignment WHERE user_id = ? AND active ORDER BY started_at, id
                """, assignmentMapper, userId);
    }

    /** 仅在没有任何 active 教师时建立；已有 active 时保持原权属。 */
    public void ensureActive(String squareId, String userId) {
        jdbcTemplate.update("""
                INSERT INTO teacher_assignment (id, square_id, user_id, active, started_at, revision)
                SELECT ?, ?, ?, true, CURRENT_TIMESTAMP, 1
                WHERE NOT EXISTS (
                    SELECT 1 FROM teacher_assignment WHERE square_id = ? AND active
                )
                """, UUID.randomUUID().toString(), squareId, userId, squareId);
    }

    public int endActive(String squareId, Instant now) {
        return jdbcTemplate.update("""
                UPDATE teacher_assignment
                SET active = false, ended_at = ?, revision = revision + 1,
                    updated_at = CURRENT_TIMESTAMP
                WHERE square_id = ? AND active
                """, OffsetDateTime.ofInstant(now, ZoneOffset.UTC), squareId);
    }

    public Optional<TeacherClaimRow> pendingClaim(String squareId, String userId) {
        return jdbcTemplate.query("""
                        SELECT id, square_id, user_id, status, message,
                               proof_refs_json::text AS proof_refs_json, revision, created_at
                        FROM teacher_claim
                        WHERE square_id = ? AND user_id = ? AND status = 'PENDING'
                        """, claimMapper, squareId, userId)
                .stream().findFirst();
    }

    public Optional<TeacherClaimRow> findClaim(String id) {
        return jdbcTemplate.query("""
                        SELECT id, square_id, user_id, status, message,
                               proof_refs_json::text AS proof_refs_json, revision, created_at
                        FROM teacher_claim WHERE id = ?
                        """, claimMapper, id)
                .stream().findFirst();
    }

    public Optional<TeacherClaimRow> findClaimForUpdate(String id) {
        return jdbcTemplate.query("""
                        SELECT id, square_id, user_id, status, message,
                               proof_refs_json::text AS proof_refs_json, revision, created_at
                        FROM teacher_claim WHERE id = ?
                        FOR UPDATE
                        """, claimMapper, id)
                .stream().findFirst();
    }

    public void insertClaim(String id, String squareId, String userId, String message, String proofRefsJson) {
        jdbcTemplate.update("""
                INSERT INTO teacher_claim
                    (id, square_id, user_id, status, message, proof_refs_json, revision)
                VALUES (?, ?, ?, 'PENDING', ?, ?::jsonb, 1)
                """, id, squareId, userId, message, proofRefsJson);
    }

    public List<TeacherClaimRow> pendingClaimsForUser(String userId) {
        return jdbcTemplate.query("""
                SELECT id, square_id, user_id, status, message,
                       proof_refs_json::text AS proof_refs_json, revision, created_at
                FROM teacher_claim WHERE user_id = ? AND status = 'PENDING'
                ORDER BY created_at DESC, id
                """, claimMapper, userId);
    }

    public int decideClaim(String id, String status, int expectedRevision) {
        return jdbcTemplate.update("""
                UPDATE teacher_claim
                SET status = ?, revision = revision + 1, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status = 'PENDING' AND revision = ?
                """, status, id, expectedRevision);
    }

    public Optional<TeacherTransferRow> pendingTransfer(String squareId) {
        return jdbcTemplate.query("""
                        SELECT id, square_id, from_user_id, to_user_id, status, source_revision,
                               reason, revision, created_at
                        FROM teacher_transfer WHERE square_id = ? AND status = 'PENDING'
                        """, transferMapper, squareId)
                .stream().findFirst();
    }

    public Optional<TeacherTransferRow> findTransfer(String id) {
        return jdbcTemplate.query("""
                        SELECT id, square_id, from_user_id, to_user_id, status, source_revision,
                               reason, revision, created_at
                        FROM teacher_transfer WHERE id = ?
                        """, transferMapper, id)
                .stream().findFirst();
    }

    public List<TeacherTransferRow> pendingTransfersForRecipient(String userId) {
        return jdbcTemplate.query("""
                SELECT id, square_id, from_user_id, to_user_id, status, source_revision,
                       reason, revision, created_at
                FROM teacher_transfer WHERE to_user_id = ? AND status = 'PENDING'
                ORDER BY created_at DESC, id
                """, transferMapper, userId);
    }

    public void insertTransfer(String id, String squareId, String fromUserId, String toUserId,
                               int sourceRevision, String reason) {
        jdbcTemplate.update("""
                INSERT INTO teacher_transfer
                    (id, square_id, from_user_id, to_user_id, status, source_revision, reason, revision)
                VALUES (?, ?, ?, ?, 'PENDING', ?, ?, 1)
                """, id, squareId, fromUserId, toUserId, sourceRevision, reason);
    }

    public int decideTransfer(String id, int expectedRevision, String status) {
        return jdbcTemplate.update("""
                UPDATE teacher_transfer
                SET status = ?, revision = revision + 1, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND revision = ? AND status = 'PENDING'
                """, status, id, expectedRevision);
    }
}
