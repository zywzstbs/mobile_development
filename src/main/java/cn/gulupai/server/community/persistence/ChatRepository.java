package cn.gulupai.server.community.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
public class ChatRepository {

    private final JdbcTemplate jdbc;

    public ChatRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private final RowMapper<MessageRow> mapper = (rs, n) -> new MessageRow(
            rs.getString("id"), rs.getString("project_id"), rs.getLong("sequence"),
            rs.getString("client_message_id"), rs.getString("author_id"),
            rs.getString("author_name"), rs.getString("content"), rs.getString("refs_json"),
            rs.getString("mentions_json"), rs.getBoolean("withdrawn"), rs.getInt("revision"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant(),
            rs.getString("request_hash"), rs.getObject("request_hash_version", Integer.class),
            rs.getString("reply_to_id"));

    /**
     * 项目级计数器行锁。发送路径在检查 clientMessageId 前先取该锁，
     * 让同项目发送串行化；序号仍只在真正插入时分配。
     */
    public void lockCounter(String projectId) {
        jdbc.update("""
                INSERT INTO chat_counter(project_id, next_sequence, last_change_sequence)
                VALUES (?, 1, 0)
                ON CONFLICT (project_id) DO UPDATE SET project_id = chat_counter.project_id
                """, projectId);
    }

    public long nextSequence(String projectId) {
        Long value = jdbc.queryForObject("""
                INSERT INTO chat_counter(project_id, next_sequence, last_change_sequence)
                VALUES (?, 2, 0)
                ON CONFLICT (project_id) DO UPDATE
                    SET next_sequence = chat_counter.next_sequence + 1
                RETURNING next_sequence - 1
                """, Long.class, projectId);
        return value == null ? 0 : value;
    }

    public long nextChangeSequence(String projectId) {
        Long value = jdbc.queryForObject("""
                UPDATE chat_counter SET last_change_sequence = last_change_sequence + 1
                WHERE project_id = ? RETURNING last_change_sequence
                """, Long.class, projectId);
        return value == null ? 0 : value;
    }

    public void insertMessage(String id, String projectId, long sequence, String clientId,
                              String authorId, String content, String refs, String mentions,
                              String requestHash, int requestHashVersion, String replyToId, Instant now) {
        jdbc.update("""
                INSERT INTO chat_message(id, project_id, sequence, client_message_id, author_id,
                    content, refs_json, mentions_json, withdrawn, revision, created_at, updated_at,
                    request_hash, request_hash_version, reply_to_id)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, false, 1, ?, ?, ?, ?, ?)
                """, id, projectId, sequence, clientId, authorId, content, refs, mentions,
                time(now), time(now), requestHash, requestHashVersion, replyToId);
    }

    public Optional<MessageRow> message(String id) {
        return jdbc.query("""
                SELECT m.*, u.display_name AS author_name FROM chat_message m
                JOIN app_user u ON u.id = m.author_id WHERE m.id = ?
                """, mapper, id).stream().findFirst();
    }

    public Optional<MessageRow> byClientId(String projectId, String authorId, String clientId) {
        return jdbc.query("""
                SELECT m.*, u.display_name AS author_name FROM chat_message m
                JOIN app_user u ON u.id = m.author_id
                WHERE m.project_id = ? AND m.author_id = ? AND m.client_message_id = ?
                """, mapper, projectId, authorId, clientId).stream().findFirst();
    }

    /**
     * 历史/定位查询。调用方传 limit+1 以判断是否还有下一页；
     * 返回统一正序，多出的那一条在 descending 模式下位于首位，在 after 模式下位于末位。
     */
    public List<MessageRow> list(String projectId, Long after, Long before, Long around,
                                 String q, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT m.*, u.display_name AS author_name FROM chat_message m
                JOIN app_user u ON u.id = m.author_id WHERE m.project_id = ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(projectId);
        if (after != null) {
            sql.append(" AND m.sequence > ?");
            args.add(after);
        }
        if (before != null) {
            sql.append(" AND m.sequence < ?");
            args.add(before);
        }
        if (around != null) {
            sql.append(" AND m.sequence BETWEEN ? AND ?");
            args.add(Math.max(0, around - limit / 2L));
            args.add(around + limit / 2L);
        }
        if (q != null && !q.isBlank()) {
            sql.append(" AND coalesce(m.content,'') ILIKE ?");
            args.add("%" + q.replace("\\", "\\\\")
                    .replace("%", "\\%").replace("_", "\\_") + "%");
        }
        if (after != null) {
            sql.append(" ORDER BY m.sequence ASC LIMIT ?");
            args.add(limit);
            return jdbc.query(sql.toString(), mapper, args.toArray());
        }
        sql.append(" ORDER BY m.sequence DESC LIMIT ?");
        args.add(limit);
        List<MessageRow> rows = jdbc.query(sql.toString(), mapper, args.toArray());
        return rows.reversed();
    }

    public long latestSequence(String projectId) {
        Long value = jdbc.queryForObject("""
                SELECT COALESCE(max(sequence), 0) FROM chat_message WHERE project_id = ?
                """, Long.class, projectId);
        return value == null ? 0 : value;
    }

    public int withdraw(String id, int revision, Instant now) {
        return jdbc.update("""
                UPDATE chat_message SET content = NULL, refs_json = '[]'::jsonb,
                    mentions_json = '[]'::jsonb, withdrawn = true, revision = revision + 1,
                    updated_at = ?, withdrawn_at = ?
                WHERE id = ? AND revision = ? AND withdrawn = false
                """, time(now), time(now), id, revision);
    }

    public void insertChange(String id, String projectId, long changeSeq, String messageId,
                             int revision, Instant now) {
        jdbc.update("""
                INSERT INTO chat_change(id, project_id, change_sequence, message_id,
                    message_revision, created_at) VALUES (?, ?, ?, ?, ?, ?)
                """, id, projectId, changeSeq, messageId, revision, time(now));
    }

    /**
     * 有界变更扫描。返回至多 limit 条，按 change_sequence 正序；消息内容来自当前安全投影。
     */
    public List<ChangeRow> changes(String projectId, long afterSequence, int limit) {
        return jdbc.query("""
                SELECT c.change_sequence, m.id, m.project_id, m.sequence, m.client_message_id,
                       m.author_id, u.display_name AS author_name, m.content, m.refs_json,
                       m.mentions_json, m.withdrawn, m.revision, m.created_at,
                       m.request_hash, m.request_hash_version, m.reply_to_id
                FROM chat_change c JOIN chat_message m ON m.id = c.message_id
                JOIN app_user u ON u.id = m.author_id
                WHERE c.project_id = ? AND c.change_sequence > ?
                ORDER BY c.change_sequence
                LIMIT ?
                """, (rs, n) -> new ChangeRow(rs.getLong("change_sequence"),
                new MessageRow(rs.getString("id"), rs.getString("project_id"),
                        rs.getLong("sequence"), rs.getString("client_message_id"),
                        rs.getString("author_id"), rs.getString("author_name"),
                        rs.getString("content"), rs.getString("refs_json"),
                        rs.getString("mentions_json"), rs.getBoolean("withdrawn"),
                        rs.getInt("revision"), rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                        rs.getString("request_hash"), rs.getObject("request_hash_version", Integer.class),
                        rs.getString("reply_to_id"))),
                projectId, afterSequence, limit);
    }

    public long latestChangeSequence(String projectId) {
        Long value = jdbc.queryForObject("""
                SELECT COALESCE((SELECT last_change_sequence FROM chat_counter WHERE project_id = ?), 0)
                """, Long.class, projectId);
        return value == null ? 0 : value;
    }

    /**
     * 最早仍可连续恢复的变更位置。没有变更事件时返回 0。
     * 若历史清理把事件删到 minChange 之后，小于 minChange-1 的游标不可继续。
     */
    public long earliestChangeSequence(String projectId) {
        Long value = jdbc.queryForObject("""
                SELECT COALESCE(min(change_sequence), 0) FROM chat_change WHERE project_id = ?
                """, Long.class, projectId);
        return value == null ? 0 : value;
    }

    public int projectAuthorizationEpoch(String projectId) {
        Integer value = jdbc.queryForObject("""
                SELECT authorization_epoch FROM project WHERE id = ?
                """, Integer.class, projectId);
        return value == null ? 0 : value;
    }

    public String projectStatus(String projectId) {
        return jdbc.queryForObject("SELECT status FROM project WHERE id = ?", String.class, projectId);
    }

    // ---- 变更同步游标 ----

    public void insertSyncCursor(String id, String tokenHash, String userId, String projectId,
                                 int epoch, long position, Instant expiresAt, Instant now) {
        jdbc.update("""
                INSERT INTO chat_sync_cursor
                    (id, token_hash, user_id, project_id, authorization_epoch,
                     last_change_sequence, expires_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, id, tokenHash, userId, projectId, epoch, position, time(expiresAt), time(now));
    }

    public Optional<SyncCursorRow> syncCursor(String tokenHash) {
        return jdbc.query("""
                SELECT token_hash, user_id, project_id, authorization_epoch,
                       last_change_sequence, expires_at
                FROM chat_sync_cursor WHERE token_hash = ?
                """, (rs, n) -> new SyncCursorRow(rs.getString("token_hash"),
                rs.getString("user_id"), rs.getString("project_id"),
                rs.getInt("authorization_epoch"), rs.getLong("last_change_sequence"),
                rs.getObject("expires_at", OffsetDateTime.class).toInstant()),
                tokenHash).stream().findFirst();
    }

    /**
     * 受控批量清理过期 token，由定时任务调用；不在用户请求内全表删除。
     */
    public int deleteExpiredSyncCursorsBatch(Instant now, int limit) {
        return jdbc.update("""
                DELETE FROM chat_sync_cursor
                WHERE id IN (
                    SELECT id FROM chat_sync_cursor
                    WHERE expires_at <= ?
                    ORDER BY expires_at
                    LIMIT ?
                )
                """, time(now), limit);
    }

    // ---- 已授权下发区间 ----

    public void insertDeliveryRange(String id, String userId, String projectId, int epoch,
                                    long start, long end, Instant now) {
        jdbc.update("""
                INSERT INTO chat_delivery_range
                    (id, user_id, project_id, authorization_epoch, start_sequence, end_sequence, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, id, userId, projectId, epoch, start, end, time(now));
    }

    /** 删除被一条更大区间完全覆盖的旧区间，避免同一页重复拉取导致无限增长。 */
    public boolean deliveryRangeCovered(String userId, String projectId, int epoch,
                                        long start, long end) {
        Boolean covered = jdbc.queryForObject("""
                SELECT EXISTS(
                    SELECT 1 FROM chat_delivery_range
                    WHERE user_id = ? AND project_id = ? AND authorization_epoch = ?
                      AND start_sequence <= ? AND end_sequence >= ?)
                """, Boolean.class, userId, projectId, epoch, start, end);
        return Boolean.TRUE.equals(covered);
    }

    public List<DeliveryRangeRow> deliveryRanges(String userId, String projectId, int epoch) {
        return jdbc.query("""
                SELECT start_sequence, end_sequence FROM chat_delivery_range
                WHERE user_id = ? AND project_id = ? AND authorization_epoch = ?
                ORDER BY start_sequence, end_sequence
                """, (rs, n) -> new DeliveryRangeRow(rs.getLong("start_sequence"),
                rs.getLong("end_sequence")), userId, projectId, epoch);
    }

    public void deleteDeliveryRangesUpTo(String userId, String projectId, int epoch, long sequence) {
        jdbc.update("""
                DELETE FROM chat_delivery_range
                WHERE user_id = ? AND project_id = ? AND authorization_epoch = ?
                  AND end_sequence <= ?
                """, userId, projectId, epoch, sequence);
    }

    // ---- 阅读游标 ----

    public long readCursor(String projectId, String userId) {
        List<Long> rows = jdbc.query("""
                SELECT last_sequence FROM chat_read_state WHERE project_id = ? AND user_id = ?
                """, (rs, n) -> rs.getLong(1), projectId, userId);
        return rows.isEmpty() ? 0 : rows.getFirst();
    }

    /**
     * 首次并发创建用 UPSERT，再取行锁；必须在写事务内调用。
     */
    public long lockReadState(String projectId, String userId, Instant now) {
        jdbc.update("""
                INSERT INTO chat_read_state(project_id, user_id, last_sequence, updated_at)
                VALUES (?, ?, 0, ?) ON CONFLICT (project_id, user_id) DO NOTHING
                """, projectId, userId, time(now));
        Long value = jdbc.queryForObject("""
                SELECT last_sequence FROM chat_read_state
                WHERE project_id = ? AND user_id = ? FOR UPDATE
                """, Long.class, projectId, userId);
        return value == null ? 0 : value;
    }

    public void saveCursor(String projectId, String userId, long sequence, Instant now) {
        jdbc.update("""
                UPDATE chat_read_state SET last_sequence = ?, updated_at = ?
                WHERE project_id = ? AND user_id = ?
                """, sequence, time(now), projectId, userId);
    }

    public boolean member(String projectId, String userId) {
        Boolean ok = jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM membership WHERE project_id = ? AND user_id = ?)
                """, Boolean.class, projectId, userId);
        return Boolean.TRUE.equals(ok);
    }

    public boolean activeMember(String projectId, String userId) {
        Boolean ok = jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM membership
                              WHERE project_id = ? AND user_id = ? AND status = 'ACTIVE')
                """, Boolean.class, projectId, userId);
        return Boolean.TRUE.equals(ok);
    }

    public boolean taskInProject(String taskId, String projectId) {
        Boolean ok = jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM task WHERE id = ? AND project_id = ?)
                """, Boolean.class, taskId, projectId);
        return Boolean.TRUE.equals(ok);
    }

    public void outbox(String id, String projectId, String type, String payload, Instant now) {
        jdbc.update("""
                INSERT INTO notification_outbox(id, project_id, event_type, payload_json, created_at)
                VALUES (?, ?, ?, ?::jsonb, ?)
                """, id, projectId, type, payload, time(now));
    }

    private OffsetDateTime time(Instant instant) {
        return OffsetDateTime.ofInstant(instant, java.time.ZoneOffset.UTC);
    }

    public record MessageRow(String id, String projectId, long sequence, String clientMessageId,
                             String authorId, String authorName, String content, String refsJson,
                             String mentionsJson, boolean withdrawn, int revision,
                             Instant createdAt, String requestHash, Integer requestHashVersion,
                             String replyToId) {
    }

    public record ChangeRow(long changeSequence, MessageRow message) {
    }

    public record SyncCursorRow(String tokenHash, String userId, String projectId,
                                int authorizationEpoch, long lastChangeSequence, Instant expiresAt) {
    }

    public record DeliveryRangeRow(long startSequence, long endSequence) {
    }
}
