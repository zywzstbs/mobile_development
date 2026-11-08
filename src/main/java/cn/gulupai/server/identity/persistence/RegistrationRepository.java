package cn.gulupai.server.identity.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

@Repository
public class RegistrationRepository {

    private final JdbcTemplate jdbc;

    public RegistrationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public int countOpenSchools() {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM school WHERE registration_enabled", Integer.class);
        return count == null ? 0 : count;
    }

    public List<SchoolOptionRow> openSchools() {
        return jdbc.query("""
                SELECT id, name FROM school WHERE registration_enabled ORDER BY id
                """, (rs, row) -> new SchoolOptionRow(rs.getString("id"), rs.getString("name")));
    }

    public boolean schoolOpen(String schoolId) {
        Boolean open = jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM school WHERE id = ? AND registration_enabled)",
                Boolean.class, schoolId);
        return Boolean.TRUE.equals(open);
    }

    public void lockKey(long key) {
        jdbc.query("SELECT pg_advisory_xact_lock(?)", rs -> null, key);
    }

    public Optional<ReceiptRow> findReceipt(String endpoint, String idempotencyKey) {
        return jdbc.query("""
                SELECT fingerprint, key_id, receipt_json::text AS receipt_json, expires_at
                FROM registration_receipt
                WHERE endpoint = ? AND idempotency_key = ?
                """, (rs, row) -> new ReceiptRow(rs.getString("fingerprint"), rs.getString("key_id"),
                rs.getString("receipt_json"), rs.getObject("expires_at", OffsetDateTime.class).toInstant()),
                endpoint, idempotencyKey).stream().findFirst();
    }

    public void deleteReceipt(String endpoint, String idempotencyKey) {
        jdbc.update("DELETE FROM registration_receipt WHERE endpoint = ? AND idempotency_key = ?",
                endpoint, idempotencyKey);
    }

    public void insertReceipt(String endpoint, String idempotencyKey, String fingerprint, String keyId,
                              String receiptJson, Instant expiresAt) {
        jdbc.update("""
                INSERT INTO registration_receipt
                    (endpoint, idempotency_key, fingerprint, key_id, receipt_json, expires_at)
                VALUES (?, ?, ?, ?, ?::jsonb, ?)
                """, endpoint, idempotencyKey, fingerprint, keyId, receiptJson, ts(expiresAt));
    }

    public void insertUser(String id, String schoolId, String loginId, String displayName,
                           String passwordHash, String accountStatus) {
        String eligibility = switch (accountStatus) {
            case "PENDING_REVIEW" -> "REGISTRATION_PENDING";
            case "REJECTED" -> "REGISTRATION_REJECTED";
            case "ACTIVE" -> "VERIFIED_ACTIVE";
            default -> throw new IllegalArgumentException("accountStatus 无效");
        };
        jdbc.update("""
                INSERT INTO app_user
                    (id, school_id, login_id, display_name, password_hash, identity_status,
                     disabled, account_status, eligibility_source, skills_json, revision)
                VALUES (?, ?, ?, ?, ?, 'PENDING', false, ?, ?, '[]'::jsonb, 1)
                """, id, schoolId, loginId, displayName, passwordHash, accountStatus, eligibility);
    }

    public void insertRole(String userId, String role) {
        jdbc.update("INSERT INTO user_role (user_id, role) VALUES (?, ?)", userId, role);
    }

    public void insertApplication(String id, String userId, String role, String status, String schoolId,
                                  String teacherApplication) {
        jdbc.update("""
                INSERT INTO registration_application
                    (id, user_id, requested_role, status, school_id, teacher_application, revision)
                VALUES (?, ?, ?, ?, ?, ?, 1)
                """, id, userId, role, status, schoolId, teacherApplication);
    }

    public Optional<ApplicationRow> lockApplication(String id) {
        return jdbc.query("""
                SELECT id, user_id, requested_role, status, school_id, teacher_application, revision
                FROM registration_application WHERE id = ? FOR UPDATE
                """, (rs, row) -> new ApplicationRow(rs.getString("id"), rs.getString("user_id"),
                rs.getString("requested_role"), rs.getString("status"), rs.getString("school_id"),
                rs.getString("teacher_application"), rs.getInt("revision")), id).stream().findFirst();
    }

    public List<ApplicationRow> pending() {
        return jdbc.query("""
                SELECT a.id, a.user_id, a.requested_role, a.status, a.school_id, a.teacher_application, a.revision
                FROM registration_application a
                WHERE a.status = 'PENDING_REVIEW'
                ORDER BY a.created_at, a.id
                """, (rs, row) -> new ApplicationRow(rs.getString("id"), rs.getString("user_id"),
                rs.getString("requested_role"), rs.getString("status"), rs.getString("school_id"),
                rs.getString("teacher_application"), rs.getInt("revision")));
    }

    public int decide(String id, int expectedRevision, String status, String reviewer, Instant at, String reason) {
        return jdbc.update("""
                UPDATE registration_application
                SET status = ?, reviewed_by = ?, reviewed_at = ?, review_reason = ?,
                    revision = revision + 1, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND revision = ? AND status = 'PENDING_REVIEW'
                """, status, reviewer, ts(at), reason, id, expectedRevision);
    }

    /**
     * 只推进仍在待审且未被停用的账号。停用账号不能靠审核通过被恢复。
     */
    public int updatePendingAccount(String userId, String accountStatus, String eligibility) {
        return jdbc.update("""
                UPDATE app_user
                SET account_status = ?, eligibility_source = ?, revision = revision + 1,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND disabled = false AND account_status = 'PENDING_REVIEW'
                """, accountStatus, eligibility, userId);
    }

    public void revokeSessions(String userId, Instant now) {
        jdbc.update("""
                UPDATE access_token SET revoked_at = ?, revision = revision + 1, updated_at = CURRENT_TIMESTAMP
                WHERE revoked_at IS NULL AND session_id IN (
                    SELECT id FROM auth_session WHERE user_id = ? AND revoked_at IS NULL)
                """, ts(now), userId);
        jdbc.update("""
                UPDATE auth_session SET revoked_at = ?, revision = revision + 1, updated_at = CURRENT_TIMESTAMP
                WHERE user_id = ? AND revoked_at IS NULL
                """, ts(now), userId);
    }

    public void setSchoolRegistration(String schoolId, boolean enabled) {
        jdbc.update("UPDATE school SET registration_enabled = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                enabled, schoolId);
    }

    public boolean schoolExists(String schoolId) {
        Boolean exists = jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM school WHERE id = ?)", Boolean.class, schoolId);
        return Boolean.TRUE.equals(exists);
    }

    private static OffsetDateTime ts(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    public record SchoolOptionRow(String id, String name) {
    }

    public record ReceiptRow(String fingerprint, String keyId, String receiptJson, Instant expiresAt) {
    }

    public record ApplicationRow(String id, String userId, String requestedRole, String status,
                                 String schoolId, String teacherApplication, int revision) {
    }
}
