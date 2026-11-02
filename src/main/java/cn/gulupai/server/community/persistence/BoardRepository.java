package cn.gulupai.server.community.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class BoardRepository {

    private final JdbcTemplate jdbc;

    public BoardRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private final RowMapper<TopicRow> topicMapper = (rs, n) -> new TopicRow(
            rs.getString("id"), rs.getString("project_id"), rs.getString("author_id"),
            rs.getString("author_name"), rs.getString("title"), rs.getString("content"),
            rs.getString("tag"), rs.getString("refs_json"), rs.getString("mentions_json"),
            rs.getString("status"), rs.getInt("revision"), rs.getBoolean("withdrawn"),
            rs.getInt("reply_count"), rs.getString("helpful_reply_id"),
            rs.getString("resolution_note"), rs.getString("close_reason"),
            rs.getBoolean("pinned"), rs.getObject("activity_at", OffsetDateTime.class).toInstant(),
            rs.getObject("created_at", OffsetDateTime.class).toInstant(),
            rs.getObject("updated_at", OffsetDateTime.class).toInstant());

    private final RowMapper<ReplyRow> replyMapper = (rs, n) -> new ReplyRow(
            rs.getString("id"), rs.getString("topic_id"), rs.getString("project_id"),
            rs.getString("author_id"), rs.getString("author_name"), rs.getString("content"),
            rs.getString("reply_to_id"), rs.getString("mentions_json"), rs.getInt("revision"),
            rs.getBoolean("withdrawn"), rs.getObject("created_at", OffsetDateTime.class).toInstant(),
            rs.getObject("updated_at", OffsetDateTime.class).toInstant());

    private final RowMapper<RevisionRow> revisionMapper = (rs, n) -> new RevisionRow(
            rs.getString("id"), rs.getString("content_type"), rs.getString("content_id"),
            rs.getInt("version"), rs.getString("title"), rs.getString("content"),
            rs.getInt("revision"), rs.getObject("created_at", OffsetDateTime.class).toInstant());

    public List<TopicRow> topics(String projectId, String status, String q) {
        StringBuilder sql = new StringBuilder("""
                SELECT t.*, u.display_name AS author_name FROM board_topic t
                JOIN app_user u ON u.id = t.author_id WHERE t.project_id = ?
                """);
        java.util.List<Object> args = new java.util.ArrayList<>();
        args.add(projectId);
        if (status != null && !status.isBlank()) {
            sql.append(" AND t.status = ?"); args.add(status);
        }
        if (q != null && !q.isBlank()) {
            sql.append(" AND (t.title ILIKE ? OR coalesce(t.content,'') ILIKE ?)");
            String p = "%" + q.replace("%", "\\%").replace("_", "\\_") + "%";
            args.add(p); args.add(p);
        }
        sql.append(" ORDER BY t.pinned DESC, t.activity_at DESC, t.id");
        return jdbc.query(sql.toString(), topicMapper, args.toArray());
    }

    public Optional<TopicRow> topic(String id) {
        return jdbc.query("""
                SELECT t.*, u.display_name AS author_name FROM board_topic t
                JOIN app_user u ON u.id = t.author_id WHERE t.id = ?
                """, topicMapper, id).stream().findFirst();
    }

    public Optional<TopicRow> lockTopic(String id) {
        return jdbc.query("""
                SELECT t.*, u.display_name AS author_name FROM board_topic t
                JOIN app_user u ON u.id = t.author_id WHERE t.id = ? FOR UPDATE
                """, topicMapper, id).stream().findFirst();
    }

    public List<ReplyRow> replies(String topicId) {
        return jdbc.query("""
                SELECT r.*, t.project_id, u.display_name AS author_name FROM board_reply r
                JOIN board_topic t ON t.id = r.topic_id JOIN app_user u ON u.id = r.author_id
                WHERE r.topic_id = ? ORDER BY r.created_at, r.id
                """, replyMapper, topicId);
    }

    public Optional<ReplyRow> reply(String id) {
        return jdbc.query("""
                SELECT r.*, t.project_id, u.display_name AS author_name FROM board_reply r
                JOIN board_topic t ON t.id = r.topic_id JOIN app_user u ON u.id = r.author_id
                WHERE r.id = ?
                """, replyMapper, id).stream().findFirst();
    }

    public void insertTopic(String id, String projectId, String authorId, String title,
                            String content, String tag, String refs, String mentions, Instant now) {
        jdbc.update("""
                INSERT INTO board_topic(id, project_id, author_id, title, content, tag,
                    refs_json, mentions_json, status, pinned, reply_count, revision, withdrawn,
                    activity_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, 'OPEN', false, 0, 1, false, ?, ?, ?)
                """, id, projectId, authorId, title, content, tag, refs, mentions,
                time(now), time(now), time(now));
    }

    public int updateTopicContent(String id, int revision, String title, String content, Instant now) {
        return jdbc.update("""
                UPDATE board_topic SET title = ?, content = ?, revision = revision + 1,
                    activity_at = ?, updated_at = ? WHERE id = ? AND revision = ? AND withdrawn = false
                """, title, content, time(now), time(now), id, revision);
    }

    public int withdrawTopic(String id, int revision, Instant now) {
        return jdbc.update("""
                UPDATE board_topic SET content = NULL, refs_json = '[]'::jsonb,
                    mentions_json = '[]'::jsonb, withdrawn = true, revision = revision + 1,
                    activity_at = ?, updated_at = ?, withdrawn_at = ?
                WHERE id = ? AND revision = ? AND withdrawn = false
                """, time(now), time(now), time(now), id, revision);
    }

    public void clearTopicRevisions(String topicId) {
        jdbc.update("UPDATE content_revision SET content = NULL, title = '' WHERE content_type = 'TOPIC' AND content_id = ?",
                topicId);
    }

    public void insertReply(String id, String topicId, String authorId, String replyToId,
                            String content, String mentions, Instant now) {
        jdbc.update("""
                INSERT INTO board_reply(id, topic_id, author_id, reply_to_id, content,
                    mentions_json, withdrawn, revision, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, false, 1, ?, ?)
                """, id, topicId, authorId, replyToId, content, mentions, time(now), time(now));
    }

    public int updateReply(String id, int revision, String content, Instant now) {
        return jdbc.update("""
                UPDATE board_reply SET content = ?, revision = revision + 1, updated_at = ?
                WHERE id = ? AND revision = ? AND withdrawn = false
                """, content, time(now), id, revision);
    }

    public int withdrawReply(String id, int revision, Instant now) {
        return jdbc.update("""
                UPDATE board_reply SET content = NULL, mentions_json = '[]'::jsonb,
                    withdrawn = true, revision = revision + 1, updated_at = ?, withdrawn_at = ?
                WHERE id = ? AND revision = ? AND withdrawn = false
                """, time(now), time(now), id, revision);
    }

    public void clearReplyRevisions(String replyId) {
        jdbc.update("UPDATE content_revision SET content = NULL, title = '' WHERE content_type = 'REPLY' AND content_id = ?",
                replyId);
    }

    public void bumpReplyActivity(String topicId, Instant now) {
        jdbc.update("""
                UPDATE board_topic SET reply_count = reply_count + 1, activity_at = ?, updated_at = ?
                WHERE id = ?
                """, time(now), time(now), topicId);
    }

    public int updateStatus(String id, int revision, String status, String helpfulReplyId,
                            String resolutionNote, String closeReason, Instant now) {
        return jdbc.update("""
                UPDATE board_topic SET status = ?, helpful_reply_id = ?, resolution_note = ?,
                    close_reason = ?, revision = revision + 1, activity_at = ?, updated_at = ?
                WHERE id = ? AND revision = ?
                """, status, helpfulReplyId, resolutionNote, closeReason, time(now), time(now), id, revision);
    }

    public int setPinned(String id, int revision, boolean pinned, Instant now) {
        return jdbc.update("""
                UPDATE board_topic SET pinned = ?, revision = revision + 1, updated_at = ?
                WHERE id = ? AND revision = ?
                """, pinned, time(now), id, revision);
    }

    public int pinnedCount(String projectId) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM board_topic WHERE project_id = ? AND pinned = true
                """, Integer.class, projectId);
        return count == null ? 0 : count;
    }

    public boolean following(String topicId, String userId) {
        Boolean exists = jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM topic_follow WHERE topic_id = ? AND user_id = ?)
                """, Boolean.class, topicId, userId);
        return Boolean.TRUE.equals(exists);
    }

    public int setFollowing(String topicId, String userId, boolean following, Instant now) {
        if (following) {
            return jdbc.update("""
                    INSERT INTO topic_follow(topic_id, user_id, created_at) VALUES (?, ?, ?)
                    ON CONFLICT (topic_id, user_id) DO NOTHING
                    """, topicId, userId, time(now));
        }
        return jdbc.update("DELETE FROM topic_follow WHERE topic_id = ? AND user_id = ?",
                topicId, userId);
    }

    public void insertRevision(String id, String type, String contentId, int version, String title,
                               String content, int revision, Instant now) {
        jdbc.update("""
                INSERT INTO content_revision(id, content_type, content_id, version, title, content,
                    revision, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, id, type, contentId, version, title, content, revision, time(now));
    }

    public List<RevisionRow> revisions(String type, String contentId) {
        return jdbc.query("""
                SELECT * FROM content_revision WHERE content_type = ? AND content_id = ?
                ORDER BY version DESC
                """, revisionMapper, type, contentId);
    }

    public String projectIdOfTopic(String topicId) {
        return jdbc.queryForObject("SELECT project_id FROM board_topic WHERE id = ?", String.class, topicId);
    }

    public String projectIdOfReply(String replyId) {
        return jdbc.queryForObject("""
                SELECT t.project_id FROM board_reply r JOIN board_topic t ON t.id = r.topic_id
                WHERE r.id = ?
                """, String.class, replyId);
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

    public boolean leader(String projectId, String userId) {
        Boolean ok = jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM project p JOIN membership m ON m.project_id = p.id
                              WHERE p.id = ? AND p.owner_id = ? AND m.user_id = ?
                                AND m.status = 'ACTIVE' AND m.project_role = 'LEADER')
                """, Boolean.class, projectId, userId, userId);
        return Boolean.TRUE.equals(ok);
    }

    public String projectStatus(String projectId) {
        return jdbc.queryForObject("SELECT status FROM project WHERE id = ?", String.class, projectId);
    }

    private OffsetDateTime time(Instant instant) {
        return OffsetDateTime.ofInstant(instant, java.time.ZoneOffset.UTC);
    }

    public record TopicRow(String id, String projectId, String authorId, String authorName,
                           String title, String content, String tag, String refsJson,
                           String mentionsJson, String status, int revision, boolean withdrawn,
                           int replyCount, String helpfulReplyId, String resolutionNote,
                           String closeReason, boolean pinned, Instant activityAt, Instant createdAt,
                           Instant updatedAt) {
    }

    public record ReplyRow(String id, String topicId, String projectId, String authorId,
                           String authorName, String content, String replyToId,
                           String mentionsJson, int revision, boolean withdrawn,
                           Instant createdAt, Instant updatedAt) {
    }

    public record RevisionRow(String id, String contentType, String contentId, int version,
                              String title, String content, int revision, Instant createdAt) {
    }
}
