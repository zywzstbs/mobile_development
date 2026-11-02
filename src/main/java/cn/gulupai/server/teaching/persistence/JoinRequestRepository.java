package cn.gulupai.server.teaching.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JoinRequestRepository {

    private final JdbcTemplate jdbcTemplate;

    private final RowMapper<JoinRequestRow> mapper = (rs, rowNum) -> new JoinRequestRow(
            rs.getString("id"),
            rs.getString("square_id"),
            rs.getString("user_id"),
            rs.getString("status"),
            rs.getString("message"),
            rs.getString("proof_refs_json"),
            rs.getString("decided_by"),
            rs.getString("decision_reason"),
            rs.getInt("revision"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant());

    public JoinRequestRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<JoinRequestRow> findPending(String squareId, String userId) {
        return jdbcTemplate.query("""
                        SELECT id, square_id, user_id, status, message,
                               proof_refs_json::text AS proof_refs_json, decided_by,
                               decision_reason, revision, created_at
                        FROM course_join_request
                        WHERE square_id = ? AND user_id = ? AND status = 'PENDING'
                        """, mapper, squareId, userId)
                .stream().findFirst();
    }

    public Optional<JoinRequestRow> findById(String id) {
        return jdbcTemplate.query("""
                        SELECT id, square_id, user_id, status, message,
                               proof_refs_json::text AS proof_refs_json, decided_by,
                               decision_reason, revision, created_at
                        FROM course_join_request WHERE id = ?
                        """, mapper, id)
                .stream().findFirst();
    }

    public void insert(String id, String squareId, String userId, String message, String proofRefsJson) {
        jdbcTemplate.update("""
                INSERT INTO course_join_request
                    (id, square_id, user_id, status, message, proof_refs_json, revision)
                VALUES (?, ?, ?, 'PENDING', ?, ?::jsonb, 1)
                """, id, squareId, userId, message, proofRefsJson);
    }

    public List<String> listIdsForUser(String userId, String status) {
        if (status == null) {
            return jdbcTemplate.queryForList("""
                    SELECT id FROM course_join_request
                    WHERE user_id = ? ORDER BY created_at DESC, id
                    """, String.class, userId);
        }
        return jdbcTemplate.queryForList("""
                SELECT id FROM course_join_request
                WHERE user_id = ? AND status = ? ORDER BY created_at DESC, id
                """, String.class, userId, status);
    }

    public List<String> listIdsForSquare(String squareId, String status, String excludedUserId) {
        if (status == null) {
            return jdbcTemplate.queryForList("""
                    SELECT id FROM course_join_request
                    WHERE square_id = ? AND user_id <> ? ORDER BY created_at DESC, id
                    """, String.class, squareId, excludedUserId);
        }
        return jdbcTemplate.queryForList("""
                SELECT id FROM course_join_request
                WHERE square_id = ? AND status = ? AND user_id <> ?
                ORDER BY created_at DESC, id
                """, String.class, squareId, status, excludedUserId);
    }

    public List<JoinRequestRow> listByIds(List<String> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        return jdbcTemplate.query("""
                SELECT id, square_id, user_id, status, message,
                       proof_refs_json::text AS proof_refs_json, decided_by,
                       decision_reason, revision, created_at
                FROM course_join_request WHERE id IN (""" + placeholders + ")",
                mapper, ids.toArray());
    }

    public int decide(String id, int expectedRevision, String status, String decidedBy, String reason) {
        return jdbcTemplate.update("""
                UPDATE course_join_request
                SET status = ?, decided_by = ?, decision_reason = ?, revision = revision + 1,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND revision = ? AND status = 'PENDING'
                """, status, decidedBy, reason, id, expectedRevision);
    }

    public int countPendingForUser(String userId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM course_join_request WHERE user_id = ? AND status = 'PENDING'",
                Integer.class, userId);
        return count == null ? 0 : count;
    }
}
