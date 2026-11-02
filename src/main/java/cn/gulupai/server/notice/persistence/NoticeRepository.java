package cn.gulupai.server.notice.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class NoticeRepository {
    private final JdbcTemplate jdbc;

    public NoticeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private final RowMapper<AnnouncementRow> annMapper = (rs, n) -> new AnnouncementRow(
            rs.getString("id"), rs.getString("square_id"), rs.getString("assignment_id"),
            rs.getString("author_id"), rs.getString("author_name"), rs.getString("title"),
            rs.getString("content"), rs.getString("publisher_role"), rs.getString("category"),
            rs.getString("importance"), rs.getString("status"), rs.getInt("content_version"),
            rs.getInt("display_revision"), rs.getBoolean("requires_acknowledgement"),
            rs.getObject("acknowledge_due_at", OffsetDateTime.class) == null ? null
                    : rs.getObject("acknowledge_due_at", OffsetDateTime.class).toInstant(),
            rs.getObject("expires_at", OffsetDateTime.class) == null ? null
                    : rs.getObject("expires_at", OffsetDateTime.class).toInstant(),
            rs.getString("recipient_snapshot_id"), rs.getInt("recipient_count"),
            rs.getString("change_summary"), rs.getInt("revision"), rs.getString("revoked_reason"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant(),
            rs.getObject("updated_at", OffsetDateTime.class).toInstant());

    public List<AnnouncementRow> announcements(String squareId) {
        return jdbc.query("""
                SELECT a.*, u.display_name AS author_name FROM announcement a
                JOIN app_user u ON u.id = a.author_id
                WHERE a.square_id = ? AND a.status = 'ACTIVE'
                ORDER BY a.created_at DESC, a.id
                """, annMapper, squareId);
    }

    public Optional<AnnouncementRow> announcement(String id) {
        return jdbc.query("""
                SELECT a.*, u.display_name AS author_name FROM announcement a
                JOIN app_user u ON u.id = a.author_id WHERE a.id = ?
                """, annMapper, id).stream().findFirst();
    }

    public Optional<AnnouncementRow> lockAnnouncement(String id) {
        return jdbc.query("""
                SELECT a.*, u.display_name AS author_name FROM announcement a
                JOIN app_user u ON u.id = a.author_id WHERE a.id = ? FOR UPDATE
                """, annMapper, id).stream().findFirst();
    }

    public void insertAnnouncement(String id, String squareId, String assignmentId, String authorId,
                                   String role, String title, String content, String category,
                                   String importance, int contentVersion, int displayRevision,
                                   boolean requiresAck, Instant ackDue, Instant expires,
                                   String snapshot, int recipientCount, Instant now) {
        jdbc.update("""
                INSERT INTO announcement(id, square_id, assignment_id, author_id, publisher_role,
                    title, content, category, importance, status, content_version, display_revision,
                    requires_acknowledgement, acknowledge_due_at, expires_at, recipient_snapshot_id,
                    recipient_count, revision, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, ?, ?, ?, ?, 1, ?, ?)
                """, id, squareId, assignmentId, authorId, role, title, content, category, importance,
                contentVersion, displayRevision, requiresAck, time(ackDue), time(expires), snapshot,
                recipientCount, time(now), time(now));
    }

    public void insertVersion(String id, String annId, int contentVersion, int displayRevision,
                              String title, String content, boolean requiresAck, Instant ackDue,
                              Instant expires, String snapshot, int count, String changeSummary,
                              String createdBy, Instant now) {
        jdbc.update("""
                INSERT INTO announcement_version(id, announcement_id, content_version,
                    display_revision, title, content, requires_acknowledgement, acknowledge_due_at,
                    expires_at, recipient_snapshot_id, recipient_count, change_summary,
                    created_by, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, annId, contentVersion, displayRevision, title, content, requiresAck,
                time(ackDue), time(expires), snapshot, count, changeSummary, createdBy, time(now));
    }

    public void insertRecipients(String snapshot, String annId, int contentVersion, List<String> ids) {
        for (String id : ids) {
            jdbc.update("""
                    INSERT INTO announcement_recipient(snapshot_id, announcement_id, content_version, user_id)
                    VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING
                    """, snapshot, annId, contentVersion, id);
        }
    }

    public List<String> recipientIds(String annId, int contentVersion) {
        return jdbc.query("""
                SELECT user_id FROM announcement_recipient
                WHERE announcement_id = ? AND content_version = ?
                """, (rs, n) -> rs.getString(1), annId, contentVersion);
    }

    public int updateAnnouncement(String id, int revision, String title, String content,
                                  int contentVersion, int displayRevision, String snapshot,
                                  int recipientCount, String changeSummary, boolean requiresAck,
                                  Instant ackDue, Instant expires, int newRevision, Instant now) {
        return jdbc.update("""
                UPDATE announcement SET title = ?, content = ?, content_version = ?,
                    display_revision = ?, recipient_snapshot_id = ?, recipient_count = ?,
                    change_summary = ?, requires_acknowledgement = ?, acknowledge_due_at = ?,
                    expires_at = ?, revision = ?, updated_at = ? WHERE id = ? AND revision = ?
                """, title, content, contentVersion, displayRevision, snapshot, recipientCount,
                changeSummary, requiresAck, time(ackDue), time(expires), newRevision, time(now), id, revision);
    }

    public int revoke(String id, int revision, String reason, Instant now) {
        return jdbc.update("""
                UPDATE announcement SET status = 'REVOKED', revoked_reason = ?, revision = revision + 1,
                    updated_at = ? WHERE id = ? AND revision = ? AND status = 'ACTIVE'
                """, reason, time(now), id, revision);
    }

    public Optional<ReceiptRow> receipt(String annId, int version, String userId) {
        return jdbc.query("""
                SELECT * FROM announcement_receipt
                WHERE announcement_id = ? AND content_version = ? AND user_id = ?
                """, (rs, n) -> new ReceiptRow(rs.getString("announcement_id"),
                rs.getInt("content_version"), rs.getString("user_id"),
                rs.getObject("read_at", OffsetDateTime.class) == null ? null
                        : rs.getObject("read_at", OffsetDateTime.class).toInstant(),
                rs.getObject("acknowledged_at", OffsetDateTime.class) == null ? null
                        : rs.getObject("acknowledged_at", OffsetDateTime.class).toInstant()),
                annId, version, userId).stream().findFirst();
    }

    public void markRead(String annId, int version, String userId, Instant now) {
        jdbc.update("""
                INSERT INTO announcement_receipt(announcement_id, content_version, user_id, read_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (announcement_id, content_version, user_id)
                DO UPDATE SET read_at = COALESCE(announcement_receipt.read_at, EXCLUDED.read_at)
                """, annId, version, userId, time(now));
    }

    public int markAck(String annId, int version, String userId, Instant now) {
        return jdbc.update("""
                UPDATE announcement_receipt SET acknowledged_at = ?
                WHERE announcement_id = ? AND content_version = ? AND user_id = ?
                  AND acknowledged_at IS NULL
                """, time(now), annId, version, userId);
    }

    public List<ReceiptViewRow> receipts(String annId, int version) {
        return jdbc.query("""
                SELECT r.user_id, u.display_name, r.read_at, r.acknowledged_at
                FROM announcement_receipt r JOIN app_user u ON u.id = r.user_id
                WHERE r.announcement_id = ? AND r.content_version = ?
                ORDER BY r.user_id
                """, (rs, n) -> new ReceiptViewRow(rs.getString("user_id"),
                rs.getString("display_name"),
                rs.getObject("read_at", OffsetDateTime.class) == null ? null
                        : rs.getObject("read_at", OffsetDateTime.class).toInstant(),
                rs.getObject("acknowledged_at", OffsetDateTime.class) == null ? null
                        : rs.getObject("acknowledged_at", OffsetDateTime.class).toInstant()),
                annId, version);
    }

    public List<String> unconfirmedRecipients(String annId, int version, boolean ack) {
        return jdbc.query("SELECT r.user_id FROM announcement_recipient r " +
                        "LEFT JOIN announcement_receipt x ON x.announcement_id = r.announcement_id " +
                        "AND x.content_version = r.content_version AND x.user_id = r.user_id " +
                        "WHERE r.announcement_id = ? AND r.content_version = ? AND " +
                        (ack ? "x.acknowledged_at IS NULL" : "x.read_at IS NULL"),
                (rs, n) -> rs.getString(1), annId, version);
    }

    public void insertNotification(String id, String userId, String sourceType, String sourceId,
                                   String category, String title, String body, int eventVersion,
                                   Instant now) {
        jdbc.update("""
                INSERT INTO notification(id, user_id, source_type, source_id, category, title, body,
                    event_version, visible_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (user_id, source_type, source_id, event_version) DO NOTHING
                """, id, userId, sourceType, sourceId, category, title, body, eventVersion,
                time(now), time(now));
    }

    public List<NotificationRow> notifications(String userId, boolean unreadOnly) {
        return jdbc.query("""
                SELECT * FROM notification WHERE user_id = ? AND (? = false OR read_at IS NULL)
                ORDER BY visible_at DESC, id
                """, (rs, n) -> new NotificationRow(rs.getString("id"),
                rs.getString("source_type"), rs.getString("source_id"), rs.getString("category"),
                rs.getString("title"), rs.getString("body"), rs.getInt("event_version"),
                rs.getObject("visible_at", OffsetDateTime.class).toInstant(),
                rs.getObject("read_at", OffsetDateTime.class) == null ? null
                        : rs.getObject("read_at", OffsetDateTime.class).toInstant(),
                rs.getObject("created_at", OffsetDateTime.class).toInstant()),
                userId, unreadOnly);
    }

    public int readNotifications(String userId, List<String> ids, String through, Instant now) {
        if (ids != null && !ids.isEmpty()) {
            String marks = String.join(",", ids.stream().map(x -> "?").toList());
            Object[] args = new Object[ids.size() + 2];
            args[0] = time(now); args[1] = userId;
            for (int i = 0; i < ids.size(); i++) args[i + 2] = ids.get(i);
            return jdbc.update("UPDATE notification SET read_at = ? WHERE user_id = ? AND id IN (" + marks + ")",
                    args);
        }
        if (through != null) {
            return jdbc.update("""
                    UPDATE notification SET read_at = ?
                    WHERE user_id = ? AND read_at IS NULL
                      AND visible_at <= (SELECT visible_at FROM notification WHERE id = ? AND user_id = ?)
                    """, time(now), userId, through, userId);
        }
        return 0;
    }

    public PreferencesRow preferences(String userId) {
        List<PreferencesRow> rows = jdbc.query("""
                SELECT * FROM notification_preference WHERE user_id = ?
                """, (rs, n) -> new PreferencesRow(rs.getString("user_id"), rs.getString("channel"),
                rs.getString("chat_default"), rs.getString("quiet_start"), rs.getString("quiet_end"),
                rs.getString("timezone"), rs.getInt("revision")), userId);
        return rows.isEmpty() ? new PreferencesRow(userId, "IN_APP", "MENTIONS",
                "22:00", "08:00", "Asia/Shanghai", 0) : rows.getFirst();
    }

    public int savePreferences(String userId, int expected, String channel, String chat,
                               String start, String end, String timezone, Instant now) {
        return jdbc.update("""
                INSERT INTO notification_preference(user_id, channel, chat_default, quiet_start,
                    quiet_end, timezone, revision, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, 1, ?)
                ON CONFLICT (user_id) DO UPDATE SET channel = EXCLUDED.channel,
                    chat_default = EXCLUDED.chat_default, quiet_start = EXCLUDED.quiet_start,
                    quiet_end = EXCLUDED.quiet_end, timezone = EXCLUDED.timezone,
                    revision = notification_preference.revision + 1, updated_at = EXCLUDED.updated_at
                WHERE notification_preference.revision = ?
                """, userId, channel, chat, start, end, timezone, time(now), expected);
    }

    public String userName(String userId) {
        return jdbc.queryForObject("SELECT display_name FROM app_user WHERE id = ?", String.class, userId);
    }

    public boolean courseReader(String squareId, String userId) {
        Boolean ok = jdbc.queryForObject("""
                SELECT EXISTS(
                  SELECT 1 FROM course_membership WHERE square_id = ? AND user_id = ? AND status = 'ACTIVE'
                  UNION ALL
                  SELECT 1 FROM verified_enrollment e JOIN square s ON s.verified_course_id = e.verified_course_id
                  WHERE s.id = ? AND e.user_id = ? AND e.valid = true
                  UNION ALL
                  SELECT 1 FROM teacher_assignment WHERE square_id = ? AND user_id = ? AND active
                  UNION ALL
                  SELECT 1 FROM class_grant WHERE square_id = ? AND user_id = ? AND status = 'ACTIVE'
                    AND expires_at > now())
                """, Boolean.class, squareId, userId, squareId, userId, squareId, userId,
                squareId, userId);
        return Boolean.TRUE.equals(ok);
    }

    public List<String> courseAudience(String squareId) {
        return jdbc.query("""
                SELECT DISTINCT user_id FROM (
                  SELECT user_id FROM course_membership WHERE square_id = ? AND status = 'ACTIVE'
                  UNION
                  SELECT e.user_id FROM verified_enrollment e JOIN square s ON s.verified_course_id = e.verified_course_id
                  WHERE s.id = ? AND e.valid = true
                  UNION
                  SELECT user_id FROM teacher_assignment WHERE square_id = ? AND active
                ) x
                """, (rs, n) -> rs.getString(1), squareId, squareId, squareId);
    }

    public void insertPreview(String id, String squareId, String createdBy, String hash,
                              String payload, Instant expires, Instant now) {
        jdbc.update("""
                INSERT INTO announcement_preview(id, square_id, created_by, token_hash, payload_json,
                    expires_at, created_at) VALUES (?, ?, ?, ?, ?::jsonb, ?, ?)
                """, id, squareId, createdBy, hash, payload, time(expires), time(now));
    }

    public Optional<PreviewRow> preview(String hash) {
        return jdbc.query("""
                SELECT * FROM announcement_preview WHERE token_hash = ?
                """, (rs, n) -> new PreviewRow(rs.getString("id"), rs.getString("square_id"),
                rs.getString("created_by"), rs.getString("payload_json"),
                rs.getObject("expires_at", OffsetDateTime.class).toInstant(),
                rs.getObject("used_at", OffsetDateTime.class) == null ? null
                        : rs.getObject("used_at", OffsetDateTime.class).toInstant()),
                hash).stream().findFirst();
    }

    public void usePreview(String id, Instant now) {
        jdbc.update("UPDATE announcement_preview SET used_at = ? WHERE id = ? AND used_at IS NULL",
                time(now), id);
    }

    public void insertReminder(String id, String annId, int version, String createdBy,
                               int count, Instant now) {
        jdbc.update("""
                INSERT INTO announcement_reminder(id, announcement_id, content_version, created_by,
                    recorded_count, created_at) VALUES (?, ?, ?, ?, ?, ?)
                """, id, annId, version, createdBy, count, time(now));
    }

    public boolean remindedWithin24h(String annId, int version, Instant now) {
        Boolean exists = jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM announcement_reminder WHERE announcement_id = ?
                  AND content_version = ? AND created_at > ?)
                """, Boolean.class, annId, version, time(now.minusSeconds(86400)));
        return Boolean.TRUE.equals(exists);
    }

    private OffsetDateTime time(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, java.time.ZoneOffset.UTC);
    }

    public record AnnouncementRow(String id, String squareId, String assignmentId, String authorId,
                                  String authorName, String title, String content, String publisherRole,
                                  String category, String importance, String status, int contentVersion,
                                  int displayRevision, boolean requiresAck, Instant ackDue,
                                  Instant expires, String snapshot, int recipientCount,
                                  String changeSummary, int revision, String revokedReason,
                                  Instant createdAt, Instant updatedAt) {
    }

    public record ReceiptRow(String announcementId, int contentVersion, String userId,
                             Instant readAt, Instant acknowledgedAt) {
    }

    public record ReceiptViewRow(String userId, String displayName, Instant readAt,
                                 Instant acknowledgedAt) {
    }

    public record NotificationRow(String id, String sourceType, String sourceId, String category,
                                  String title, String body, int eventVersion, Instant visibleAt,
                                  Instant readAt, Instant createdAt) {
    }

    public record PreferencesRow(String userId, String channel, String chatDefault, String quietStart,
                                 String quietEnd, String timezone, int revision) {
    }

    public record PreviewRow(String id, String squareId, String createdBy, String payload,
                             Instant expiresAt, Instant usedAt) {
    }
}
