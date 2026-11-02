package cn.gulupai.server.role.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * 岗位协商持久化。写路径的锁顺序由服务层保证：先 project，再 membership id 升序，再 negotiation。
 */
@Repository
public class RoleRepository {

    private final JdbcTemplate jdbc;

    private final RowMapper<NegotiationRow> negotiationMapper = (rs, n) -> new NegotiationRow(
            rs.getString("id"), rs.getString("project_id"), rs.getString("kind"),
            rs.getString("status"), rs.getInt("source_project_revision"), rs.getString("leader_id"),
            rs.getString("initiator_id"), rs.getString("plan_json"), rs.getString("reason"),
            rs.getString("message"), rs.getInt("revision"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant());

    private final RowMapper<AckRow> ackMapper = (rs, n) -> new AckRow(
            rs.getString("id"), rs.getString("negotiation_id"), rs.getString("user_id"),
            rs.getString("decision"), rs.getString("comment"),
            rs.getObject("at", OffsetDateTime.class).toInstant());

    private final RowMapper<MemberLock> memberMapper = (rs, n) -> new MemberLock(
            rs.getString("id"), rs.getString("project_id"), rs.getString("user_id"),
            rs.getString("display_name"), rs.getString("role_slot_id"), rs.getString("status"),
            rs.getInt("revision"));

    public RoleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertNegotiation(NegotiationRow row) {
        OffsetDateTime created = OffsetDateTime.ofInstant(row.createdAt(), ZoneOffset.UTC);
        jdbc.update("""
                INSERT INTO role_negotiation
                    (id, project_id, kind, status, source_project_revision, leader_id, initiator_id,
                     plan_json, reason, message, revision, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?)
                """, row.id(), row.projectId(), row.kind(), row.status(), row.sourceProjectRevision(),
                row.leaderId(), row.initiatorId(), row.planJson(), row.reason(), row.message(),
                row.revision(), created, created);
    }

    public Optional<NegotiationRow> findNegotiation(String id) {
        return jdbc.query("SELECT * FROM role_negotiation WHERE id = ?", negotiationMapper, id)
                .stream().findFirst();
    }

    public Optional<NegotiationRow> lockNegotiation(String id) {
        return jdbc.query("SELECT * FROM role_negotiation WHERE id = ? FOR UPDATE", negotiationMapper, id)
                .stream().findFirst();
    }

    public List<NegotiationRow> negotiationsForUser(String userId, String kind) {
        return jdbc.query("""
                SELECT * FROM role_negotiation
                WHERE kind = ? AND (
                    initiator_id = ?
                    OR EXISTS (
                        SELECT 1 FROM jsonb_array_elements(plan_json->'parties') party
                        WHERE party->>'userId' = ?
                    )
                )
                ORDER BY created_at DESC, id
                """, negotiationMapper, kind, userId, userId);
    }

    public List<NegotiationRow> negotiationsForProject(String projectId, String kind) {
        return jdbc.query("""
                SELECT * FROM role_negotiation
                WHERE project_id = ? AND kind = ?
                ORDER BY created_at DESC, id
                """, negotiationMapper, projectId, kind);
    }

    public int finishNegotiation(String id, int expectedRevision, String status, Instant at) {
        return jdbc.update("""
                UPDATE role_negotiation
                SET status = ?, revision = revision + 1, updated_at = ?
                WHERE id = ? AND revision = ? AND status = 'PENDING'
                """, status, OffsetDateTime.ofInstant(at, ZoneOffset.UTC), id, expectedRevision);
    }

    public void insertAck(String id, String negotiationId, String userId, String decision,
                          String comment, Instant at) {
        jdbc.update("""
                INSERT INTO role_ack (id, negotiation_id, user_id, decision, comment, at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, id, negotiationId, userId, decision, comment,
                OffsetDateTime.ofInstant(at, ZoneOffset.UTC));
    }

    public List<AckRow> acks(String negotiationId) {
        return jdbc.query("""
                SELECT * FROM role_ack WHERE negotiation_id = ? ORDER BY at, user_id
                """, ackMapper, negotiationId);
    }

    public Optional<AckRow> ack(String negotiationId, String userId) {
        return jdbc.query("""
                SELECT * FROM role_ack WHERE negotiation_id = ? AND user_id = ?
                """, ackMapper, negotiationId, userId).stream().findFirst();
    }

    public Optional<MemberLock> findMember(String id) {
        return jdbc.query("""
                SELECT m.id, m.project_id, m.user_id, u.display_name, m.role_slot_id, m.status, m.revision
                FROM membership m JOIN app_user u ON u.id = m.user_id
                WHERE m.id = ?
                """, memberMapper, id).stream().findFirst();
    }

    public Optional<MemberLock> lockMember(String id) {
        return jdbc.query("""
                SELECT m.id, m.project_id, m.user_id, u.display_name, m.role_slot_id, m.status, m.revision
                FROM membership m JOIN app_user u ON u.id = m.user_id
                WHERE m.id = ? FOR UPDATE OF m
                """, memberMapper, id).stream().findFirst();
    }

    public int updateMemberRole(String id, int expectedRevision, String roleSlotId) {
        return jdbc.update("""
                UPDATE membership
                SET role_slot_id = ?, revision = revision + 1, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND revision = ? AND status = 'ACTIVE'
                """, roleSlotId, id, expectedRevision);
    }

    public int updateCapacity(String projectId, int expectedRevision, int capacity) {
        return jdbc.update("""
                UPDATE project SET capacity = ?, revision = revision + 1, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND revision = ?
                """, capacity, projectId, expectedRevision);
    }

    public int upgradeToDetailed(String projectId, int expectedRevision, String name, String intro,
                                 int capacity) {
        return jdbc.update("""
                UPDATE project
                SET mode = 'DETAILED', name = ?, intro = ?, capacity = ?,
                    revision = revision + 1, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND revision = ? AND mode = 'QUICK'
                """, name, intro, capacity, projectId, expectedRevision);
    }

    public void shiftRolePositions(String projectId) {
        jdbc.update("""
                UPDATE role_slot SET position = position + 10000, updated_at = CURRENT_TIMESTAMP
                WHERE project_id = ?
                """, projectId);
    }

    public void insertRole(String id, String projectId, String roleName, String duty, int total, int position) {
        jdbc.update("""
                INSERT INTO role_slot (id, project_id, role_name, duty, total, position, revision)
                VALUES (?, ?, ?, ?, ?, ?, 1)
                """, id, projectId, roleName, duty, total, position);
    }

    public void updateRole(String id, String projectId, String roleName, String duty, int total, int position) {
        jdbc.update("""
                UPDATE role_slot
                SET role_name = ?, duty = ?, total = ?, position = ?,
                    revision = revision + 1, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND project_id = ?
                """, roleName, duty, total, position, id, projectId);
    }

    public void deleteRole(String id, String projectId) {
        jdbc.update("DELETE FROM role_slot WHERE id = ? AND project_id = ?", id, projectId);
    }

    public boolean roleReferenced(String roleId) {
        Integer count = jdbc.queryForObject("""
                SELECT (
                    (SELECT count(*) FROM membership WHERE role_slot_id = ?)
                    + (SELECT count(*) FROM seat_reservation WHERE role_slot_id = ?)
                    + (SELECT count(*) FROM team_application WHERE role_slot_id = ?)
                    + (SELECT count(*) FROM team_invitation WHERE role_slot_id = ?)
                )
                """, Integer.class, roleId, roleId, roleId, roleId);
        return count != null && count > 0;
    }

    public record NegotiationRow(String id, String projectId, String kind, String status,
                                 int sourceProjectRevision, String leaderId, String initiatorId,
                                 String planJson, String reason, String message, int revision,
                                 Instant createdAt) {
    }

    public record AckRow(String id, String negotiationId, String userId, String decision,
                         String comment, Instant at) {
    }

    public record MemberLock(String id, String projectId, String userId, String displayName,
                             String roleSlotId, String status, int revision) {
    }
}
