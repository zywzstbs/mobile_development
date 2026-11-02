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
public class ClassGrantRepository {

    private final JdbcTemplate jdbcTemplate;

    private final RowMapper<ClassGrantRow> mapper = (rs, rowNum) -> new ClassGrantRow(
            rs.getString("id"),
            rs.getString("square_id"),
            rs.getString("user_id"),
            rs.getString("capabilities_json"),
            rs.getString("status"),
            rs.getObject("expires_at", OffsetDateTime.class).toInstant(),
            rs.getString("granted_by"),
            rs.getInt("revision"),
            rs.getObject("updated_at", OffsetDateTime.class).toInstant());

    public ClassGrantRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<ClassGrantRow> find(String squareId, String userId) {
        return jdbcTemplate.query("""
                        SELECT id, square_id, user_id, capabilities_json::text AS capabilities_json,
                               status, expires_at, granted_by, revision, updated_at
                        FROM class_grant WHERE square_id = ? AND user_id = ?
                        """, mapper, squareId, userId)
                .stream().findFirst();
    }

    public Optional<ClassGrantRow> findById(String id) {
        return jdbcTemplate.query("""
                        SELECT id, square_id, user_id, capabilities_json::text AS capabilities_json,
                               status, expires_at, granted_by, revision, updated_at
                        FROM class_grant WHERE id = ?
                        """, mapper, id)
                .stream().findFirst();
    }

    public List<ClassGrantRow> listBySquare(String squareId) {
        return jdbcTemplate.query("""
                SELECT id, square_id, user_id, capabilities_json::text AS capabilities_json,
                       status, expires_at, granted_by, revision, updated_at
                FROM class_grant WHERE square_id = ? ORDER BY created_at, id
                """, mapper, squareId);
    }

    public List<ClassGrantRow> listCurrentlyActiveByUser(String userId, Instant now) {
        return jdbcTemplate.query("""
                SELECT id, square_id, user_id, capabilities_json::text AS capabilities_json,
                       status, expires_at, granted_by, revision, updated_at
                FROM class_grant
                WHERE user_id = ? AND status = 'ACTIVE' AND expires_at > ?
                ORDER BY updated_at DESC, id
                """, mapper, userId, OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
    }

    public ClassGrantRow upsert(String squareId, String userId, String capabilitiesJson,
                                Instant expiresAt, String grantedBy) {
        jdbcTemplate.update("""
                INSERT INTO class_grant
                    (id, square_id, user_id, capabilities_json, status, expires_at, granted_by, revision)
                VALUES (?, ?, ?, ?::jsonb, 'ACTIVE', ?, ?, 1)
                ON CONFLICT (square_id, user_id) DO UPDATE
                SET capabilities_json = EXCLUDED.capabilities_json,
                    status = 'ACTIVE',
                    expires_at = EXCLUDED.expires_at,
                    granted_by = EXCLUDED.granted_by,
                    revision = class_grant.revision + 1,
                    updated_at = CURRENT_TIMESTAMP
                """, UUID.randomUUID().toString(), squareId, userId, capabilitiesJson,
                OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC), grantedBy);
        return find(squareId, userId).orElseThrow();
    }

    public int revoke(String id, int expectedRevision) {
        return jdbcTemplate.update("""
                UPDATE class_grant
                SET status = 'REVOKED', revision = revision + 1, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND revision = ? AND status = 'ACTIVE'
                """, id, expectedRevision);
    }

    public boolean hasCapability(String squareId, String userId, String capability, Instant now) {
        Boolean exists = jdbcTemplate.queryForObject("""
                SELECT EXISTS(
                    SELECT 1 FROM class_grant
                    WHERE square_id = ? AND user_id = ? AND status = 'ACTIVE'
                      AND expires_at > ? AND jsonb_exists(capabilities_json, ?)
                )
                """, Boolean.class, squareId, userId,
                OffsetDateTime.ofInstant(now, ZoneOffset.UTC), capability);
        return Boolean.TRUE.equals(exists);
    }

    public boolean hasReviewerOtherThan(String squareId, String excludedUserId) {
        Boolean exists = jdbcTemplate.queryForObject("""
                SELECT EXISTS(
                    SELECT 1 FROM class_grant
                    WHERE square_id = ? AND user_id <> ? AND status = 'ACTIVE'
                      AND expires_at > CURRENT_TIMESTAMP
                      AND jsonb_exists(capabilities_json, 'COURSE_MEMBERSHIP_REVIEW')
                )
                """, Boolean.class, squareId, excludedUserId);
        return Boolean.TRUE.equals(exists);
    }

    public int countActive(String squareId, Instant now) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM class_grant
                WHERE square_id = ? AND status = 'ACTIVE' AND expires_at > ?
                """, Integer.class, squareId, OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
        return count == null ? 0 : count;
    }
}
