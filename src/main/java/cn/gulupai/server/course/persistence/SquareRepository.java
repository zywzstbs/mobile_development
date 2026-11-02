package cn.gulupai.server.course.persistence;

import cn.gulupai.server.common.security.Hashing;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.Year;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class SquareRepository {

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    private final RowMapper<SquareRow> squareMapper = (rs, rowNum) -> new SquareRow(
            rs.getString("id"),
            rs.getString("verified_course_id"),
            rs.getString("school_id"),
            rs.getString("term_id"),
            rs.getString("name"),
            rs.getString("teacher_name"),
            rs.getString("slots_json"),
            rs.getString("canonical_key"),
            rs.getString("square_no"),
            rs.getString("status"),
            rs.getInt("authorization_epoch"),
            rs.getInt("revision"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant(),
            rs.getObject("updated_at", OffsetDateTime.class).toInstant());

    private final RowMapper<AssignmentRow> assignmentMapper = (rs, rowNum) -> new AssignmentRow(
            rs.getString("id"),
            rs.getString("square_id"),
            rs.getString("name"),
            rs.getString("requirements"),
            rs.getString("origin"),
            rs.getString("confirmation_status"),
            rs.getObject("hard_due_at", OffsetDateTime.class).toInstant(),
            rs.getInt("min_members"),
            rs.getInt("max_members"),
            rs.getBoolean("grading_enabled"),
            rs.getString("source_references_json"),
            rs.getInt("revision"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant());

    public SquareRepository(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    public Optional<SquareRow> findByCanonicalKey(String canonicalKey) {
        return jdbcTemplate.query("""
                        SELECT id, verified_course_id, school_id, term_id, name, teacher_name,
                               slots_json::text AS slots_json, canonical_key, square_no, status,
                               authorization_epoch, revision, created_at, updated_at
                        FROM square WHERE canonical_key = ?
                        """, squareMapper, canonicalKey)
                .stream().findFirst();
    }

    public Optional<SquareRow> findById(String id) {
        return jdbcTemplate.query("""
                        SELECT id, verified_course_id, school_id, term_id, name, teacher_name,
                               slots_json::text AS slots_json, canonical_key, square_no, status,
                               authorization_epoch, revision, created_at, updated_at
                        FROM square WHERE id = ?
                        """, squareMapper, id)
                .stream().findFirst();
    }

    public Optional<SquareRow> findByIdForUpdate(String id) {
        return jdbcTemplate.query("""
                        SELECT id, verified_course_id, school_id, term_id, name, teacher_name,
                               slots_json::text AS slots_json, canonical_key, square_no, status,
                               authorization_epoch, revision, created_at, updated_at
                        FROM square WHERE id = ?
                        FOR UPDATE
                        """, squareMapper, id)
                .stream().findFirst();
    }

    public Optional<SquareRow> findBySquareNo(String squareNo) {
        return jdbcTemplate.query("""
                        SELECT id, verified_course_id, school_id, term_id, name, teacher_name,
                               slots_json::text AS slots_json, canonical_key, square_no, status,
                               authorization_epoch, revision, created_at, updated_at
                        FROM square WHERE square_no = ?
                        """, squareMapper, squareNo)
                .stream().findFirst();
    }

    public List<SquareRow> list(String schoolId, String termId, String q) {
        if (q == null || q.isBlank()) {
            return jdbcTemplate.query("""
                    SELECT id, verified_course_id, school_id, term_id, name, teacher_name,
                           slots_json::text AS slots_json, canonical_key, square_no, status,
                           authorization_epoch, revision, created_at, updated_at
                    FROM square
                    WHERE school_id = ? AND term_id = ?
                    ORDER BY updated_at DESC, id
                    """, squareMapper, schoolId, termId);
        }
        String pattern = "%" + CourseDictionaryRepository.escapeLike(q) + "%";
        return jdbcTemplate.query("""
                SELECT id, verified_course_id, school_id, term_id, name, teacher_name,
                       slots_json::text AS slots_json, canonical_key, square_no, status,
                       authorization_epoch, revision, created_at, updated_at
                FROM square
                WHERE school_id = ? AND term_id = ?
                  AND (name ILIKE ? ESCAPE '\\' OR teacher_name ILIKE ? ESCAPE '\\'
                       OR square_no ILIKE ? ESCAPE '\\')
                ORDER BY updated_at DESC, id
                """, squareMapper, schoolId, termId, pattern, pattern, pattern);
    }

    public void lockCanonical(String canonicalKey) {
        jdbcTemplate.query("SELECT pg_advisory_xact_lock(?)",
                (ResultSetExtractor<Void>) rs -> null,
                Hashing.advisoryLockKey("SQUARE|" + canonicalKey));
    }

    public String nextSquareNo() {
        Long sequence = jdbcTemplate.queryForObject("SELECT nextval('square_no_seq')", Long.class);
        long value = sequence == null ? 1 : sequence;
        return "SQ-" + Year.now(clock).getValue() + "-" + String.format("%06d", value);
    }

    public void insertSquare(String id, String verifiedCourseId, String schoolId, String termId,
                             String name, String teacherName, String slotsJson, String canonicalKey,
                             String squareNo) {
        jdbcTemplate.update("""
                INSERT INTO square
                    (id, verified_course_id, school_id, term_id, name, teacher_name, slots_json,
                     canonical_key, square_no, status, authorization_epoch, revision)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, 'ACTIVE', 1, 1)
                """, id, verifiedCourseId, schoolId, termId, name, teacherName, slotsJson,
                canonicalKey, squareNo);
    }

    public Optional<String> membershipStatus(String squareId, String userId) {
        return jdbcTemplate.query("""
                        SELECT status FROM course_membership WHERE square_id = ? AND user_id = ?
                        """, (rs, rowNum) -> rs.getString("status"), squareId, userId)
                .stream().findFirst();
    }

    public Map<String, String> membershipStatuses(List<String> squareIds, String userId) {
        if (squareIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(squareIds.size(), "?"));
        Object[] args = new Object[squareIds.size() + 1];
        args[0] = userId;
        for (int i = 0; i < squareIds.size(); i++) {
            args[i + 1] = squareIds.get(i);
        }
        List<Map.Entry<String, String>> rows = jdbcTemplate.query("""
                        SELECT square_id, status FROM course_membership
                        WHERE user_id = ? AND square_id IN (""" + placeholders + ")",
                (rs, rowNum) -> Map.entry(rs.getString("square_id"), rs.getString("status")), args);
        java.util.Map<String, String> result = new java.util.HashMap<>();
        rows.forEach(entry -> result.put(entry.getKey(), entry.getValue()));
        return result;
    }

    public Optional<MembershipRow> findMembership(String squareId, String userId) {
        return jdbcTemplate.query("""
                        SELECT id, square_id, user_id, status, joined_at, left_at, revision
                        FROM course_membership WHERE square_id = ? AND user_id = ?
                        """,
                (rs, rowNum) -> new MembershipRow(
                        rs.getString("id"), rs.getString("square_id"), rs.getString("user_id"),
                        rs.getString("status"),
                        rs.getObject("joined_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("left_at", OffsetDateTime.class) == null ? null
                                : rs.getObject("left_at", OffsetDateTime.class).toInstant(),
                        rs.getInt("revision")),
                squareId, userId).stream().findFirst();
    }

    public void ensureMembership(String squareId, String userId) {
        jdbcTemplate.update("""
                INSERT INTO course_membership
                    (id, square_id, user_id, status, joined_at, revision)
                VALUES (?, ?, ?, 'ACTIVE', CURRENT_TIMESTAMP, 1)
                ON CONFLICT (square_id, user_id) DO UPDATE
                SET status = 'ACTIVE', left_at = NULL, joined_at = CURRENT_TIMESTAMP,
                    revision = course_membership.revision + 1, updated_at = CURRENT_TIMESTAMP
                WHERE course_membership.status <> 'ACTIVE'
                """, java.util.UUID.randomUUID().toString(), squareId, userId);
    }

    public void insertPreviewToken(String id, String tokenHash, String userId, String kind,
                                   String bodyHash, String sourceDigest, String payloadJson,
                                   Instant expiresAt) {
        jdbcTemplate.update("""
                INSERT INTO preview_token
                    (id, token_hash, user_id, kind, body_hash, source_digest, payload_json,
                     expires_at, revision)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, 1)
                """, id, tokenHash, userId, kind, bodyHash, sourceDigest, payloadJson,
                OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC));
    }

    public Optional<PreviewTokenRow> findPreviewToken(String tokenHash) {
        return jdbcTemplate.query("""
                        SELECT id, user_id, kind, body_hash, payload_json::text AS payload_json, expires_at
                        FROM preview_token WHERE token_hash = ?
                        """,
                (rs, rowNum) -> new PreviewTokenRow(
                        rs.getString("id"), rs.getString("user_id"), rs.getString("kind"),
                        rs.getString("body_hash"), rs.getString("payload_json"),
                        rs.getObject("expires_at", OffsetDateTime.class).toInstant()),
                tokenHash).stream().findFirst();
    }

    public void bumpRevisionAndEpoch(String squareId) {
        jdbcTemplate.update("""
                UPDATE square
                SET revision = revision + 1, authorization_epoch = authorization_epoch + 1,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, squareId);
    }

    public void bumpEpoch(String squareId) {
        jdbcTemplate.update("""
                UPDATE square
                SET authorization_epoch = authorization_epoch + 1, updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, squareId);
    }

    public void deletePreviewToken(String tokenHash) {
        jdbcTemplate.update("DELETE FROM preview_token WHERE token_hash = ?", tokenHash);
    }

    public List<AssignmentRow> listAssignments(String squareId) {
        return jdbcTemplate.query("""
                SELECT id, square_id, name, requirements, origin, confirmation_status, hard_due_at,
                       min_members, max_members, grading_enabled,
                       source_references_json::text AS source_references_json, revision, created_at
                FROM assignment WHERE square_id = ? ORDER BY created_at, id
                """, assignmentMapper, squareId);
    }

    public Optional<AssignmentRow> findAssignment(String id) {
        return jdbcTemplate.query("""
                        SELECT id, square_id, name, requirements, origin, confirmation_status, hard_due_at,
                               min_members, max_members, grading_enabled,
                               source_references_json::text AS source_references_json, revision, created_at
                        FROM assignment WHERE id = ?
                        """, assignmentMapper, id)
                .stream().findFirst();
    }

    public void insertAssignment(String id, String squareId, String name, String requirements,
                                 String origin, String confirmationStatus, Instant hardDueAt,
                                 int minMembers, int maxMembers, String sourceReferencesJson) {
        jdbcTemplate.update("""
                INSERT INTO assignment
                    (id, square_id, name, requirements, origin, confirmation_status, hard_due_at,
                     min_members, max_members, grading_enabled, source_references_json, revision)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, false, ?::jsonb, 1)
                """, id, squareId, name, requirements, origin, confirmationStatus,
                OffsetDateTime.ofInstant(hardDueAt, ZoneOffset.UTC), minMembers, maxMembers,
                sourceReferencesJson);
    }

    public int confirmAssignment(String id, int expectedRevision) {
        return jdbcTemplate.update("""
                UPDATE assignment
                SET confirmation_status = 'CONFIRMED', revision = revision + 1,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND revision = ? AND confirmation_status = 'PENDING_CONFIRMATION'
                """, id, expectedRevision);
    }
}
