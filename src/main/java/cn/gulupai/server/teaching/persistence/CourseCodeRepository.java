package cn.gulupai.server.teaching.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

@Repository
public class CourseCodeRepository {

    private final JdbcTemplate jdbcTemplate;

    private final RowMapper<CourseCodeRow> mapper = (rs, rowNum) -> new CourseCodeRow(
            rs.getString("id"),
            rs.getString("square_id"),
            rs.getString("issued_by"),
            rs.getString("status"),
            rs.getObject("expires_at", OffsetDateTime.class).toInstant(),
            rs.getString("code_ciphertext"),
            rs.getString("key_id"),
            rs.getInt("revision"));

    public CourseCodeRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<CourseCodeRow> findActive(String squareId, Instant now) {
        return jdbcTemplate.query("""
                        SELECT id, square_id, issued_by, status, expires_at, code_ciphertext, key_id, revision
                        FROM course_code
                        WHERE square_id = ? AND status = 'ACTIVE' AND expires_at > ?
                        """, mapper, squareId, OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .stream().findFirst();
    }

    public Optional<CourseCodeRow> findById(String id) {
        return jdbcTemplate.query("""
                        SELECT id, square_id, issued_by, status, expires_at, code_ciphertext, key_id, revision
                        FROM course_code WHERE id = ?
                        """, mapper, id)
                .stream().findFirst();
    }

    public Optional<CourseCodeRow> findByHash(String codeHash) {
        return jdbcTemplate.query("""
                        SELECT id, square_id, issued_by, status, expires_at, code_ciphertext, key_id, revision
                        FROM course_code WHERE code_hash = ?
                        """, mapper, codeHash)
                .stream().findFirst();
    }

    public int nextCodeVersion(String squareId) {
        Integer max = jdbcTemplate.queryForObject(
                "SELECT COALESCE(max(code_version), 0) FROM course_code WHERE square_id = ?",
                Integer.class, squareId);
        return (max == null ? 0 : max) + 1;
    }

    public void insert(String squareId, String codeHash, String codeCiphertext, String keyId,
                       int codeVersion, Instant expiresAt, String issuedBy) {
        jdbcTemplate.update("""
                INSERT INTO course_code
                    (id, square_id, code_hash, code_ciphertext, key_id, code_version,
                     status, expires_at, issued_by, revision)
                VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?, 1)
                """, UUID.randomUUID().toString(), squareId, codeHash, codeCiphertext, keyId,
                codeVersion, OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC), issuedBy);
    }

    public int revokeActive(String squareId) {
        return jdbcTemplate.update("""
                UPDATE course_code
                SET status = 'REVOKED', revision = revision + 1, updated_at = CURRENT_TIMESTAMP
                WHERE square_id = ? AND status = 'ACTIVE'
                """, squareId);
    }

    public int revoke(String id, int expectedRevision) {
        return jdbcTemplate.update("""
                UPDATE course_code
                SET status = 'REVOKED', revision = revision + 1, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND revision = ? AND status = 'ACTIVE'
                """, id, expectedRevision);
    }
}
