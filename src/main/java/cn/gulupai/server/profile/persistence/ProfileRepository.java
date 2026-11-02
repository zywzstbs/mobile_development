package cn.gulupai.server.profile.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class ProfileRepository {

    private final JdbcTemplate jdbc;

    public ProfileRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private final RowMapper<UserProfileRow> userMapper = (rs, n) -> new UserProfileRow(
            rs.getString("id"), rs.getString("school_id"), rs.getString("display_name"),
            rs.getString("skills_json"), rs.getInt("profile_revision"),
            rs.getString("profile_json"), rs.getString("intro"));

    private final RowMapper<WorkRow> workMapper = (rs, n) -> new WorkRow(
            rs.getString("id"), rs.getString("user_id"), rs.getString("source_project_id"),
            rs.getString("title"), rs.getString("description"), rs.getString("role"),
            rs.getString("my_contribution"), rs.getString("audience"), rs.getString("status"),
            rs.getInt("revision"), rs.getInt("current_version"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant());

    private final RowMapper<WorkVersionRow> workVersionMapper = (rs, n) -> new WorkVersionRow(
            rs.getString("id"), rs.getString("work_id"), rs.getInt("version"),
            rs.getString("body_json"), rs.getInt("revision"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant());

    private final RowMapper<PeerRow> peerMapper = (rs, n) -> new PeerRow(
            rs.getString("id"), rs.getString("project_id"), rs.getString("author_id"),
            rs.getString("target_user_id"), rs.getBoolean("observed_enough"),
            rs.getString("observations_json"), rs.getString("comment"), rs.getString("status"),
            rs.getInt("revision"), rs.getObject("created_at", OffsetDateTime.class).toInstant());

    private final RowMapper<PeerVersionRow> peerVersionMapper = (rs, n) -> new PeerVersionRow(
            rs.getString("id"), rs.getString("peer_review_id"), rs.getInt("version"),
            rs.getString("body_json"), rs.getInt("revision"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant());

    private final RowMapper<AiRow> aiMapper = (rs, n) -> new AiRow(
            rs.getString("id"), rs.getString("project_id"), rs.getString("source_project_id"),
            rs.getString("client_record_id"), rs.getString("scene"), rs.getString("model"),
            rs.getString("title"), rs.getString("text"), rs.getString("created_by"),
            rs.getInt("revision"), rs.getObject("created_at", OffsetDateTime.class).toInstant(),
            rs.getString("origin"), rs.getString("author_id"));

    private final RowMapper<ProjectBrief> projectMapper = (rs, n) -> new ProjectBrief(
            rs.getString("id"), rs.getString("name"), rs.getString("school_id"),
            rs.getString("status"), rs.getObject("archived_at", OffsetDateTime.class) == null ? null
            : rs.getObject("archived_at", OffsetDateTime.class).toInstant(),
            rs.getString("owner_id"), rs.getString("square_id"));

    private final RowMapper<MemberRow> memberMapper = (rs, n) -> new MemberRow(
            rs.getString("user_id"), rs.getString("display_name"), rs.getString("status"));

    public Optional<UserProfileRow> userProfile(String userId) {
        return jdbc.query("""
                SELECT u.id, u.school_id, u.display_name, u.skills_json, u.revision AS profile_revision,
                       p.profile_json::text AS profile_json, p.intro
                FROM app_user u LEFT JOIN user_profile p ON p.user_id = u.id
                WHERE u.id = ?
                """, userMapper, userId).stream().findFirst();
    }

    public boolean sameSchool(String userId, String schoolId) {
        Boolean ok = jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM app_user WHERE id = ? AND school_id = ?)",
                Boolean.class, userId, schoolId);
        return Boolean.TRUE.equals(ok);
    }

    public Optional<ProjectBrief> project(String projectId) {
        return jdbc.query("""
                SELECT p.id, p.name, s.school_id, p.status, p.archived_at, p.owner_id, p.square_id
                FROM project p JOIN square s ON s.id = p.square_id WHERE p.id = ?
                """, projectMapper, projectId).stream().findFirst();
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

    public List<MemberRow> members(String projectId) {
        return jdbc.query("""
                SELECT m.user_id, u.display_name, m.status FROM membership m
                JOIN app_user u ON u.id = m.user_id WHERE m.project_id = ?
                ORDER BY m.joined_at, m.id
                """, memberMapper, projectId);
    }

    public boolean courseQualified(String squareId, String userId) {
        Boolean ok = jdbc.queryForObject("""
                SELECT EXISTS(
                    SELECT 1 FROM square s
                    JOIN verified_course vc ON vc.id = s.verified_course_id
                    JOIN verified_enrollment e ON e.verified_course_id = vc.id
                    WHERE s.id = ? AND e.user_id = ? AND e.valid = true)
                OR EXISTS(SELECT 1 FROM course_membership cm
                          WHERE cm.square_id = ? AND cm.user_id = ? AND cm.status = 'ACTIVE')
                """, Boolean.class, squareId, userId, squareId, userId);
        return Boolean.TRUE.equals(ok);
    }

    public List<ProjectBrief> archivedProjectsForUser(String userId) {
        return jdbc.query("""
                SELECT p.id, p.name, s.school_id, p.status, p.archived_at, p.owner_id, p.square_id
                FROM project p JOIN square s ON s.id = p.square_id
                JOIN membership m ON m.project_id = p.id
                WHERE m.user_id = ? AND p.status = 'ARCHIVED'
                ORDER BY p.archived_at DESC, p.id
                """, projectMapper, userId);
    }

    public List<WorkRow> works(String userId) {
        return jdbc.query("SELECT * FROM work WHERE user_id = ? ORDER BY created_at DESC, id",
                workMapper, userId);
    }

    public Optional<WorkRow> work(String id) {
        return jdbc.query("SELECT * FROM work WHERE id = ?", workMapper, id).stream().findFirst();
    }

    public List<WorkRow> visibleWorks(String userId) {
        return jdbc.query("""
                SELECT * FROM work WHERE user_id = ? AND status = 'ACTIVE'
                ORDER BY created_at DESC, id
                """, workMapper, userId);
    }

    public void insertWork(String id, String userId, String sourceProjectId, String title,
                           String description, String role, String contribution, String audience,
                           Instant now) {
        jdbc.update("""
                INSERT INTO work(id, user_id, source_project_id, title, description, role,
                                 my_contribution, audience, status, revision, current_version,
                                 created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', 1, 1, ?, ?)
                """, id, userId, sourceProjectId, title, description, role, contribution, audience,
                time(now), time(now));
    }

    public int updateWork(String id, int revision, String title, String description, String role,
                          String contribution, String audience, int nextVersion, Instant now) {
        return jdbc.update("""
                UPDATE work SET title = ?, description = ?, role = ?, my_contribution = ?,
                    audience = ?, revision = revision + 1, current_version = ?, updated_at = ?
                WHERE id = ? AND revision = ? AND status = 'ACTIVE'
                """, title, description, role, contribution, audience, nextVersion, time(now), id, revision);
    }

    public int withdrawWork(String id, int revision, Instant now) {
        return jdbc.update("""
                UPDATE work SET status = 'WITHDRAWN', revision = revision + 1, withdrawn_at = ?,
                    updated_at = ? WHERE id = ? AND revision = ? AND status = 'ACTIVE'
                """, time(now), time(now), id, revision);
    }

    public void insertWorkVersion(String id, String workId, int version, String bodyJson,
                                  int revision, Instant now) {
        jdbc.update("""
                INSERT INTO work_version(id, work_id, version, body_json, revision, created_at)
                VALUES (?, ?, ?, ?::jsonb, ?, ?)
                """, id, workId, version, bodyJson, revision, time(now));
    }

    public List<WorkVersionRow> workVersions(String workId) {
        return jdbc.query("SELECT * FROM work_version WHERE work_id = ? ORDER BY version DESC",
                workVersionMapper, workId);
    }

    public Optional<PeerRow> peer(String id) {
        return jdbc.query("SELECT * FROM peer_review WHERE id = ?", peerMapper, id).stream().findFirst();
    }

    public Optional<PeerRow> peerByAuthorTarget(String projectId, String authorId, String targetId) {
        return jdbc.query("""
                SELECT * FROM peer_review WHERE project_id = ? AND author_id = ? AND target_user_id = ?
                """, peerMapper, projectId, authorId, targetId).stream().findFirst();
    }

    public List<PeerRow> peersByAuthor(String projectId, String authorId) {
        return jdbc.query("""
                SELECT * FROM peer_review WHERE project_id = ? AND author_id = ?
                ORDER BY created_at DESC, id
                """, peerMapper, projectId, authorId);
    }

    public void insertPeer(String id, String projectId, String authorId, String targetId,
                           boolean observed, String observationsJson, String comment, Instant now) {
        jdbc.update("""
                INSERT INTO peer_review(id, project_id, author_id, target_user_id, observed_enough,
                    observations_json, comment, status, revision, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, 'ACTIVE', 1, ?, ?)
                """, id, projectId, authorId, targetId, observed, observationsJson, comment,
                time(now), time(now));
    }

    public int updatePeer(String id, int revision, boolean observed, String observationsJson,
                          String comment, Instant now) {
        return jdbc.update("""
                UPDATE peer_review SET observed_enough = ?, observations_json = ?::jsonb,
                    comment = ?, revision = revision + 1, updated_at = ?
                WHERE id = ? AND revision = ? AND status = 'ACTIVE'
                """, observed, observationsJson, comment, time(now), id, revision);
    }

    public int withdrawPeer(String id, int revision, Instant now) {
        return jdbc.update("""
                UPDATE peer_review SET status = 'WITHDRAWN', revision = revision + 1,
                    withdrawn_at = ?, updated_at = ? WHERE id = ? AND revision = ? AND status = 'ACTIVE'
                """, time(now), time(now), id, revision);
    }

    public void insertPeerVersion(String id, String peerId, int version, String bodyJson,
                                  int revision, Instant now) {
        jdbc.update("""
                INSERT INTO peer_review_version(id, peer_review_id, version, body_json, revision, created_at)
                VALUES (?, ?, ?, ?::jsonb, ?, ?)
                """, id, peerId, version, bodyJson, revision, time(now));
    }

    public List<PeerVersionRow> peerVersions(String peerId) {
        return jdbc.query("SELECT * FROM peer_review_version WHERE peer_review_id = ? ORDER BY version DESC",
                peerVersionMapper, peerId);
    }

    public int peerVersion(String peerId) {
        Integer version = jdbc.queryForObject(
                "SELECT COALESCE(max(version), 0) FROM peer_review_version WHERE peer_review_id = ?",
                Integer.class, peerId);
        return (version == null ? 0 : version) + 1;
    }

    public List<DimensionSummaryRow> peerDimensionSummaries(String targetUserId) {
        return jdbc.query("""
                SELECT o.value->>'dimension' AS dimension,
                       count(DISTINCT pr.author_id) AS authors,
                       count(*) FILTER (WHERE o.value->>'level' = 'POSITIVE') AS positive,
                       count(*) FILTER (WHERE o.value->>'level' = 'NEEDS_IMPROVEMENT') AS needs
                FROM peer_review pr
                CROSS JOIN LATERAL jsonb_array_elements(pr.observations_json) AS o(value)
                WHERE pr.target_user_id = ? AND pr.status = 'ACTIVE'
                GROUP BY o.value->>'dimension'
                """, (rs, n) -> new DimensionSummaryRow(rs.getString("dimension"),
                rs.getInt("authors"), rs.getInt("positive"), rs.getInt("needs")), targetUserId);
    }

    public List<AiRow> aiReferences(String projectId) {
        return jdbc.query("SELECT * FROM ai_reference WHERE project_id = ? ORDER BY created_at DESC, id",
                aiMapper, projectId);
    }

    public Optional<AiRow> aiByClientRecord(String projectId, String clientRecordId) {
        return jdbc.query("""
                SELECT * FROM ai_reference WHERE project_id = ? AND client_record_id = ?
                """, aiMapper, projectId, clientRecordId).stream().findFirst();
    }

    public void insertAi(String id, String projectId, String sourceProjectId, String clientRecordId,
                         String scene, String model, String title, String text, String userId,
                         Instant now) {
        jdbc.update("""
                INSERT INTO ai_reference(id, project_id, source_project_id, client_record_id, scene,
                    model, title, text, created_by, author_id, origin, revision, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'CLIENT_BYOK_LEADER_CONFIRMED', 1, ?)
                """, id, projectId, sourceProjectId, clientRecordId, scene, model, title, text,
                userId, userId, time(now));
    }

    public Instant lockedProjectArchivedAt(String projectId) {
        List<OffsetDateTime> rows = jdbc.query(
                "SELECT archived_at FROM project WHERE id = ? FOR UPDATE",
                (rs, n) -> rs.getObject("archived_at", OffsetDateTime.class), projectId);
        return rows.isEmpty() || rows.getFirst() == null ? null : rows.getFirst().toInstant();
    }

    public int projectRevision(String projectId) {
        Integer revision = jdbc.queryForObject("SELECT revision FROM project WHERE id = ?",
                Integer.class, projectId);
        return revision == null ? 0 : revision;
    }

    private OffsetDateTime time(Instant instant) {
        return OffsetDateTime.ofInstant(instant, java.time.ZoneOffset.UTC);
    }

    public record UserProfileRow(String id, String schoolId, String displayName, String skillsJson,
                                 int profileRevision, String profileJson, String intro) {
    }

    public record WorkRow(String id, String userId, String sourceProjectId, String title,
                          String description, String role, String contribution, String audience,
                          String status, int revision, int currentVersion, Instant createdAt) {
    }

    public record WorkVersionRow(String id, String workId, int version, String bodyJson,
                                 int revision, Instant createdAt) {
    }

    public record PeerRow(String id, String projectId, String authorId, String targetUserId,
                          boolean observedEnough, String observationsJson, String comment,
                          String status, int revision, Instant createdAt) {
    }

    public record PeerVersionRow(String id, String peerId, int version, String bodyJson,
                                 int revision, Instant createdAt) {
    }

    public record AiRow(String id, String projectId, String sourceProjectId, String clientRecordId,
                        String scene, String model, String title, String text, String createdBy,
                        int revision, Instant createdAt, String origin, String authorId) {
    }

    public record ProjectBrief(String id, String name, String schoolId, String status,
                               Instant archivedAt, String ownerId, String squareId) {
    }

    public record MemberRow(String userId, String displayName, String status) {
    }

    public record DimensionSummaryRow(String dimension, int authors, int positive, int needs) {
    }
}
