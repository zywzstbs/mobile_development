package cn.gulupai.server.file;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

@Repository
public class FileRepository {

    private final JdbcTemplate jdbc;

    private final RowMapper<StoredFileRow> fileMapper = (rs, n) -> new StoredFileRow(
            rs.getString("id"), rs.getString("uploader_id"), rs.getString("purpose"),
            rs.getString("project_id"), rs.getString("case_id"), rs.getString("ticket_id"),
            rs.getString("file_name"), rs.getString("content_type"), rs.getInt("declared_size"),
            (Integer) rs.getObject("actual_size"), rs.getString("sha256"), rs.getString("declared_sha256"),
            rs.getString("status"), rs.getObject("upload_expires_at", OffsetDateTime.class).toInstant(),
            instant(rs.getObject("received_at", OffsetDateTime.class)),
            instant(rs.getObject("ready_at", OffsetDateTime.class)),
            rs.getInt("revision"), rs.getObject("created_at", OffsetDateTime.class).toInstant());

    private final RowMapper<AuthorizationRow> authorizationMapper = (rs, n) -> new AuthorizationRow(
            rs.getString("id"), rs.getString("file_id"), rs.getString("user_id"), rs.getString("kind"),
            rs.getString("token_hash"), rs.getObject("expires_at", OffsetDateTime.class).toInstant());

    public FileRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertFile(StoredFileRow row) {
        OffsetDateTime created = offset(row.createdAt());
        jdbc.update("""
                INSERT INTO stored_file
                    (id, uploader_id, purpose, project_id, case_id, ticket_id, file_name, content_type,
                     declared_size, declared_sha256, storage_key, status, upload_expires_at, revision,
                     created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, row.id(), row.uploaderId(), row.purpose(), row.projectId(), row.caseId(), row.ticketId(),
                row.fileName(), row.contentType(), row.declaredSize(), row.declaredSha256(), row.id(),
                row.status(), offset(row.uploadExpiresAt()), row.revision(), created, created);
    }

    public Optional<StoredFileRow> findFile(String id) {
        return jdbc.query("SELECT * FROM stored_file WHERE id = ?", fileMapper, id).stream().findFirst();
    }

    public Optional<StoredFileRow> lockFile(String id) {
        return jdbc.query("SELECT * FROM stored_file WHERE id = ? FOR UPDATE", fileMapper, id)
                .stream().findFirst();
    }

    public List<StoredFileRow> filesByStatus(String status) {
        return jdbc.query("SELECT * FROM stored_file WHERE status = ?", fileMapper, status);
    }

    public int markReceived(String id, int revision, long size, String sha256, Instant at) {
        return jdbc.update("""
                UPDATE stored_file
                SET actual_size = ?, sha256 = ?, received_at = ?, updated_at = ?
                WHERE id = ? AND revision = ? AND status = 'UPLOADING' AND received_at IS NULL
                """, (int) size, sha256, offset(at), offset(at), id, revision);
    }

    public int markReady(String id, int revision, Instant at) {
        return jdbc.update("""
                UPDATE stored_file
                SET status = 'READY', ready_at = ?, revision = revision + 1, updated_at = ?
                WHERE id = ? AND revision = ? AND status = 'UPLOADING'
                """, offset(at), offset(at), id, revision);
    }

    public int markFailed(String id, int revision, Instant at) {
        return jdbc.update("""
                UPDATE stored_file
                SET status = 'FAILED', revision = revision + 1, updated_at = ?
                WHERE id = ? AND revision = ? AND status = 'UPLOADING'
                """, offset(at), id, revision);
    }

    public int markFailedIfUnreferenced(String id, Instant at) {
        return jdbc.update("""
                UPDATE stored_file
                SET status = 'FAILED', revision = revision + 1, updated_at = ?
                WHERE id = ? AND status = 'READY'
                  AND NOT EXISTS (SELECT 1 FROM file_reference WHERE file_id = stored_file.id)
                """, offset(at), id);
    }

    public void insertAuthorization(String id, String fileId, String userId, String kind,
                                   String tokenHash, Instant expiresAt, Instant at) {
        jdbc.update("""
                INSERT INTO file_authorization
                    (id, file_id, user_id, kind, token_hash, expires_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, id, fileId, userId, kind, tokenHash, offset(expiresAt), offset(at));
    }

    public Optional<AuthorizationRow> findAuthorization(String tokenHash) {
        return jdbc.query("SELECT * FROM file_authorization WHERE token_hash = ?",
                authorizationMapper, tokenHash).stream().findFirst();
    }

    public void insertReference(String id, String fileId, String resourceType, String resourceId,
                               int resourceVersion, Instant retainUntil, Instant at) {
        jdbc.update("""
                INSERT INTO file_reference
                    (id, file_id, resource_type, resource_id, resource_version, retain_until, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, id, fileId, resourceType, resourceId, resourceVersion,
                retainUntil == null ? null : offset(retainUntil), offset(at));
    }

    public boolean referenced(String fileId) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM file_reference WHERE file_id = ?",
                Integer.class, fileId);
        return count != null && count > 0;
    }

    public void insertCleanupPlan(String id, String fileId, String reason, Instant eligibleAt, Instant at) {
        jdbc.update("""
                INSERT INTO cleanup_plan
                    (id, resource_type, resource_id, reason, eligible_at, execute_after, status,
                     created_at, updated_at)
                VALUES (?, 'FILE', ?, ?, ?, ?, 'PLANNED', ?, ?)
                """, id, fileId, reason, offset(eligibleAt), offset(eligibleAt.plus(java.time.Duration.ofDays(7))),
                offset(at), offset(at));
    }

    public List<CleanupPlanRow> duePlans(Instant now) {
        return jdbc.query("""
                SELECT id, resource_id, execute_after, status FROM cleanup_plan
                WHERE resource_type = 'FILE' AND status = 'PLANNED' AND execute_after <= ?
                """, (rs, n) -> new CleanupPlanRow(rs.getString("id"), rs.getString("resource_id"),
                rs.getObject("execute_after", OffsetDateTime.class).toInstant(), rs.getString("status")),
                offset(now));
    }

    public boolean plannedCleanup(String fileId) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM cleanup_plan
                WHERE resource_type = 'FILE' AND resource_id = ? AND status = 'PLANNED'
                """, Integer.class, fileId);
        return count != null && count > 0;
    }

    public List<PlannedCleanup> plannedCleanups() {
        return jdbc.query("""
                SELECT id, resource_id, reason, execute_after FROM cleanup_plan
                WHERE resource_type = 'FILE' AND status = 'PLANNED'
                ORDER BY execute_after, id
                """, (rs, n) -> new PlannedCleanup(rs.getString("id"), rs.getString("resource_id"),
                rs.getString("reason"), rs.getObject("execute_after", OffsetDateTime.class).toInstant()));
    }

    public int cancelPlan(String id, Instant at) {
        return jdbc.update("""
                UPDATE cleanup_plan SET status = 'CANCELLED', updated_at = ?
                WHERE id = ? AND status = 'PLANNED'
                """, offset(at), id);
    }

    public void executePlan(String id, Instant at) {
        jdbc.update("""
                UPDATE cleanup_plan SET status = 'EXECUTED', updated_at = ? WHERE id = ? AND status = 'PLANNED'
                """, offset(at), id);
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime offset(Instant value) {
        return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    public record StoredFileRow(String id, String uploaderId, String purpose, String projectId, String caseId,
                               String ticketId, String fileName, String contentType, int declaredSize,
                               Integer actualSize, String sha256, String declaredSha256, String status,
                               Instant uploadExpiresAt, Instant receivedAt, Instant readyAt, int revision,
                               Instant createdAt) {
    }

    public record AuthorizationRow(String id, String fileId, String userId, String kind, String tokenHash,
                                  Instant expiresAt) {
    }

    public record CleanupPlanRow(String id, String resourceId, Instant executeAfter, String status) {
    }

    public record PlannedCleanup(String id, String resourceId, String reason, Instant executeAfter) {
    }
}
