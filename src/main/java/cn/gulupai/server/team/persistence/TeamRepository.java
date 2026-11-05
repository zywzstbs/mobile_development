package cn.gulupai.server.team.persistence;

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
public class TeamRepository {

    private final JdbcTemplate jdbc;

    private final RowMapper<ProjectRow> projectMapper = (rs, n) -> new ProjectRow(
            rs.getString("id"), rs.getString("assignment_id"), rs.getString("square_id"),
            rs.getString("owner_id"), rs.getString("mode"), rs.getString("name"),
            rs.getString("intro"), rs.getInt("capacity"), rs.getString("status"),
            rs.getInt("closing_cycle"), rs.getInt("authorization_epoch"),
            rs.getString("discussion_time"), rs.getBoolean("recruitment_open"),
            rs.getString("recruitment_description"), rs.getString("professional_direction"),
            rs.getInt("weekly_hours"), rs.getBoolean("accept_beginners"), rs.getInt("revision"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant(),
            rs.getObject("updated_at", OffsetDateTime.class).toInstant());

    private final RowMapper<RoleRow> roleMapper = (rs, n) -> new RoleRow(
            rs.getString("id"), rs.getString("project_id"), rs.getString("role_name"),
            rs.getString("duty"), rs.getInt("total"), rs.getInt("position"));
    private final RowMapper<StageRow> stageMapper = (rs, n) -> new StageRow(
            rs.getString("id"), rs.getString("project_id"), rs.getString("name"), rs.getInt("position"));
    private final RowMapper<ProjectMemberRow> memberMapper = (rs, n) -> new ProjectMemberRow(
            rs.getString("id"), rs.getString("project_id"), rs.getString("user_id"),
            rs.getString("display_name"), rs.getString("role_slot_id"), rs.getString("project_role"),
            rs.getString("status"), rs.getString("color"), rs.getInt("color_revision"),
            rs.getObject("joined_at", OffsetDateTime.class).toInstant(), rs.getInt("revision"));
    private final RowMapper<ApplicationRow> applicationMapper = (rs, n) -> new ApplicationRow(
            rs.getString("id"), rs.getString("project_id"), rs.getString("applicant_id"),
            rs.getString("role_slot_id"), rs.getString("message"), rs.getString("status"),
            rs.getString("invitation_id"), rs.getString("reason"), rs.getInt("revision"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant());
    private final RowMapper<InvitationRow> invitationMapper = (rs, n) -> new InvitationRow(
            rs.getString("id"), rs.getString("project_id"), rs.getString("recipient_id"),
            rs.getString("role_slot_id"), rs.getString("message"), rs.getString("status"),
            rs.getObject("expires_at", OffsetDateTime.class).toInstant(), rs.getString("reason"),
            rs.getInt("revision"), rs.getObject("created_at", OffsetDateTime.class).toInstant());
    private final RowMapper<ProjectCodeRow> codeMapper = (rs, n) -> new ProjectCodeRow(
            rs.getString("id"), rs.getString("project_id"), rs.getString("status"),
            rs.getString("code_ciphertext"), rs.getInt("revision"));

    public TeamRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ProjectRow> findProject(String id) {
        return jdbc.query("SELECT * FROM project WHERE id = ?", projectMapper, id).stream().findFirst();
    }

    public Optional<ProjectRow> lockProject(String id) {
        return jdbc.query("SELECT * FROM project WHERE id = ? FOR UPDATE", projectMapper, id)
                .stream().findFirst();
    }

    public List<ProjectRow> projectsForAssignment(String assignmentId) {
        return jdbc.query("SELECT * FROM project WHERE assignment_id = ? ORDER BY updated_at DESC, id",
                projectMapper, assignmentId);
    }

    public List<ProjectRow> myProjects(String userId) {
        return jdbc.query("""
                SELECT p.* FROM project p JOIN membership m ON m.project_id = p.id
                WHERE m.user_id = ? AND m.status = 'ACTIVE' ORDER BY p.updated_at DESC, p.id
                """, projectMapper, userId);
    }

    public AssignmentBrief findAssignment(String id) {
        List<AssignmentBrief> rows = jdbc.query("""
                SELECT id, square_id, name, hard_due_at, min_members, max_members
                FROM assignment WHERE id = ?
                """, (rs, n) -> new AssignmentBrief(rs.getString("id"), rs.getString("square_id"),
                rs.getString("name"), rs.getObject("hard_due_at", OffsetDateTime.class).toInstant(),
                rs.getInt("min_members"), rs.getInt("max_members")), id);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public AssignmentBrief lockAssignment(String id) {
        List<AssignmentBrief> rows = jdbc.query("""
                SELECT id, square_id, name, hard_due_at, min_members, max_members
                FROM assignment WHERE id = ? FOR UPDATE
                """, (rs, n) -> new AssignmentBrief(rs.getString("id"), rs.getString("square_id"),
                rs.getString("name"), rs.getObject("hard_due_at", OffsetDateTime.class).toInstant(),
                rs.getInt("min_members"), rs.getInt("max_members")), id);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public void insertProject(ProjectRow p) {
        jdbc.update("""
                INSERT INTO project
                    (id, assignment_id, square_id, owner_id, mode, name, intro, capacity, status,
                     closing_cycle, authorization_epoch, discussion_time, recruitment_open,
                     recruitment_description, professional_direction, weekly_hours,
                     accept_beginners, revision)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)
                """, p.id(), p.assignmentId(), p.squareId(), p.ownerId(), p.mode(), p.name(),
                p.intro(), p.capacity(), p.status(), p.closingCycle(), p.authorizationEpoch(),
                p.discussionTime(), p.recruitmentOpen(), p.recruitmentDescription(),
                p.professionalDirection(), p.weeklyHours(), p.acceptBeginners());
    }

    public void insertStage(String id, String projectId, String name, int position) {
        jdbc.update("""
                INSERT INTO project_stage (id, project_id, name, position, revision)
                VALUES (?, ?, ?, ?, 1)
                """, id, projectId, name, position);
    }

    public void insertRole(String id, String projectId, String roleName, String duty,
                           int total, int position) {
        jdbc.update("""
                INSERT INTO role_slot (id, project_id, role_name, duty, total, position, revision)
                VALUES (?, ?, ?, ?, ?, ?, 1)
                """, id, projectId, roleName, duty, total, position);
    }

    public void insertMembership(String id, String projectId, String userId, String roleSlotId,
                                 String projectRole, String color, Instant joinedAt) {
        jdbc.update("""
                INSERT INTO membership
                    (id, project_id, user_id, role_slot_id, project_role, status, color,
                     color_revision, joined_at, revision)
                VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, 0, ?, 1)
                """, id, projectId, userId, roleSlotId, projectRole, color,
                OffsetDateTime.ofInstant(joinedAt, ZoneOffset.UTC));
    }

    public void insertAssignmentSlot(String id, String assignmentId, String userId,
                                     String projectId, String membershipId) {
        jdbc.update("""
                INSERT INTO assignment_team_slot
                    (id, assignment_id, user_id, project_id, membership_id)
                VALUES (?, ?, ?, ?, ?)
                """, id, assignmentId, userId, projectId, membershipId);
    }

    public boolean assignmentSlot(String assignmentId, String userId) {
        Boolean exists = jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM assignment_team_slot WHERE assignment_id = ? AND user_id = ?)
                """, Boolean.class, assignmentId, userId);
        return Boolean.TRUE.equals(exists);
    }

    public List<RoleRow> roles(String projectId) {
        return jdbc.query("SELECT * FROM role_slot WHERE project_id = ? ORDER BY position, id",
                roleMapper, projectId);
    }

    public Optional<RoleRow> role(String projectId, String roleId) {
        return jdbc.query("SELECT * FROM role_slot WHERE project_id = ? AND id = ?",
                roleMapper, projectId, roleId).stream().findFirst();
    }

    public List<StageRow> stages(String projectId) {
        return jdbc.query("SELECT * FROM project_stage WHERE project_id = ? ORDER BY position, id",
                stageMapper, projectId);
    }

    public List<ProjectMemberRow> members(String projectId) {
        return jdbc.query("""
                SELECT m.*, u.display_name FROM membership m JOIN app_user u ON u.id = m.user_id
                WHERE m.project_id = ? ORDER BY m.joined_at, m.id
                """, memberMapper, projectId);
    }

    public Optional<ProjectMemberRow> membership(String projectId, String userId) {
        return jdbc.query("""
                        SELECT m.*, u.display_name FROM membership m JOIN app_user u ON u.id = m.user_id
                        WHERE m.project_id = ? AND m.user_id = ? AND m.status = 'ACTIVE'
                        """, memberMapper, projectId, userId).stream().findFirst();
    }

    public int leaveMembership(String id, int revision, String status, Instant at) {
        return jdbc.update("""
                UPDATE membership
                SET status = ?, left_at = ?, revision = revision + 1, updated_at = ?
                WHERE id = ? AND revision = ? AND status = 'ACTIVE'
                """, status, OffsetDateTime.ofInstant(at, ZoneOffset.UTC),
                OffsetDateTime.ofInstant(at, ZoneOffset.UTC), id, revision);
    }

    public int setOwner(String projectId, String ownerId) {
        return jdbc.update("UPDATE project SET owner_id = ? WHERE id = ?", ownerId, projectId);
    }

    public void releaseAssignmentSlot(String assignmentId, String userId) {
        jdbc.update("DELETE FROM assignment_team_slot WHERE assignment_id = ? AND user_id = ?",
                assignmentId, userId);
    }

    public void moveAssignmentSlot(String assignmentId, String userId, String projectId, String membershipId) {
        jdbc.update("""
                UPDATE assignment_team_slot
                SET project_id = ?, membership_id = ?
                WHERE assignment_id = ? AND user_id = ?
                """, projectId, membershipId, assignmentId, userId);
    }

    public int memberCount(String projectId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM membership WHERE project_id = ? AND status = 'ACTIVE'",
                Integer.class, projectId);
        return count == null ? 0 : count;
    }

    public int reservedCount(String projectId, Instant now) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM seat_reservation
                WHERE project_id = ? AND status = 'ACTIVE' AND expires_at > ?
                """, Integer.class, projectId, OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
        return count == null ? 0 : count;
    }

    public int roleFilled(String projectId, String roleId) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM membership WHERE project_id = ? AND role_slot_id = ? AND status = 'ACTIVE'
                """, Integer.class, projectId, roleId);
        return count == null ? 0 : count;
    }

    public int roleReserved(String projectId, String roleId, Instant now) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM seat_reservation
                WHERE project_id = ? AND role_slot_id = ? AND status = 'ACTIVE' AND expires_at > ?
                """, Integer.class, projectId, roleId, OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
        return count == null ? 0 : count;
    }

    public int updateProject(String id, int expectedRevision, String name, String intro,
                             String discussionTime, int capacity) {
        return jdbc.update("""
                UPDATE project SET name = ?, intro = ?, discussion_time = ?, capacity = ?,
                    revision = revision + 1, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND revision = ?
                """, name, intro, discussionTime, capacity, id, expectedRevision);
    }

    public int updateRecruitment(String id, int expectedRevision, boolean open, String description,
                                 String direction, int weeklyHours, boolean beginners) {
        return jdbc.update("""
                UPDATE project SET recruitment_open = ?, recruitment_description = ?,
                    professional_direction = ?, weekly_hours = ?, accept_beginners = ?,
                    revision = revision + 1, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND revision = ?
                """, open, description, direction, weeklyHours, beginners, id, expectedRevision);
    }

    public void bumpRevision(String projectId) {
        jdbc.update("UPDATE project SET revision = revision + 1, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                projectId);
    }

    /**
     * 成员关系/项目权限变化：授权代次递增，使既有分页、聊天同步与下发区间失效。
     * revision 同时递增，旧的 expectedProjectRevision 也应要求刷新。
     */
    public void bumpAuthorizationEpoch(String projectId) {
        jdbc.update("""
                UPDATE project
                SET authorization_epoch = authorization_epoch + 1,
                    revision = revision + 1,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, projectId);
    }

    public Optional<ApplicationRow> findApplication(String id) {
        return jdbc.query("SELECT * FROM team_application WHERE id = ?", applicationMapper, id)
                .stream().findFirst();
    }

    public Optional<ApplicationRow> pendingApplication(String projectId, String applicantId) {
        return jdbc.query("""
                        SELECT * FROM team_application
                        WHERE project_id = ? AND applicant_id = ? AND status = 'PENDING'
                        """, applicationMapper, projectId, applicantId).stream().findFirst();
    }

    public List<ApplicationRow> applicationsForProject(String projectId) {
        return jdbc.query("SELECT * FROM team_application WHERE project_id = ? ORDER BY created_at DESC, id",
                applicationMapper, projectId);
    }

    public List<ApplicationRow> applicationsForApplicant(String userId) {
        return jdbc.query("SELECT * FROM team_application WHERE applicant_id = ? ORDER BY created_at DESC, id",
                applicationMapper, userId);
    }

    public void insertApplication(String id, String projectId, String applicantId,
                                  String roleSlotId, String message) {
        jdbc.update("""
                INSERT INTO team_application
                    (id, project_id, applicant_id, role_slot_id, message, status, revision)
                VALUES (?, ?, ?, ?, ?, 'PENDING', 1)
                """, id, projectId, applicantId, roleSlotId, message);
    }

    public int updateApplication(String id, int revision, String status, String reason,
                                 String invitationId) {
        return jdbc.update("""
                UPDATE team_application SET status = ?, reason = ?,
                    invitation_id = COALESCE(?, invitation_id), revision = revision + 1,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND revision = ? AND status = 'PENDING'
                """, status, reason, invitationId, id, revision);
    }

    public Optional<InvitationRow> findInvitation(String id) {
        return jdbc.query("SELECT * FROM team_invitation WHERE id = ?", invitationMapper, id)
                .stream().findFirst();
    }

    public Optional<InvitationRow> pendingInvitation(String projectId, String recipientId) {
        return jdbc.query("""
                        SELECT * FROM team_invitation
                        WHERE project_id = ? AND recipient_id = ? AND status = 'PENDING'
                        """, invitationMapper, projectId, recipientId).stream().findFirst();
    }

    public List<InvitationRow> invitationsForProject(String projectId) {
        return jdbc.query("SELECT * FROM team_invitation WHERE project_id = ? ORDER BY created_at DESC, id",
                invitationMapper, projectId);
    }

    public List<InvitationRow> invitationsForRecipient(String userId) {
        return jdbc.query("SELECT * FROM team_invitation WHERE recipient_id = ? ORDER BY created_at DESC, id",
                invitationMapper, userId);
    }

    public void insertInvitation(String id, String projectId, String recipientId, String roleSlotId,
                                 String message, Instant expiresAt) {
        jdbc.update("""
                INSERT INTO team_invitation
                    (id, project_id, recipient_id, role_slot_id, message, status, expires_at, revision)
                VALUES (?, ?, ?, ?, ?, 'PENDING', ?, 1)
                """, id, projectId, recipientId, roleSlotId, message,
                OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC));
    }

    public int updateInvitation(String id, int revision, String status, String reason) {
        return jdbc.update("""
                UPDATE team_invitation SET status = ?, reason = ?, revision = revision + 1,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND revision = ? AND status = 'PENDING'
                """, status, reason, id, revision);
    }

    public void insertReservation(String id, String projectId, String userId, String roleSlotId,
                                  String invitationId, Instant expiresAt) {
        jdbc.update("""
                INSERT INTO seat_reservation
                    (id, project_id, user_id, role_slot_id, invitation_id, status, expires_at, revision)
                VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, 1)
                """, id, projectId, userId, roleSlotId, invitationId,
                OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC));
    }

    public void closeReservation(String invitationId, String status) {
        jdbc.update("""
                UPDATE seat_reservation SET status = ?, revision = revision + 1,
                    updated_at = CURRENT_TIMESTAMP
                WHERE invitation_id = ? AND status = 'ACTIVE'
                """, status, invitationId);
    }

    public void consumeReservation(String invitationId) {
        closeReservation(invitationId, "CONSUMED");
    }

    public void closeOtherPending(String assignmentId, String userId, String keepInvitationId) {
        jdbc.update("""
                UPDATE team_invitation i SET status = 'CANCELLED', reason = '已加入其他团队',
                    revision = i.revision + 1, updated_at = CURRENT_TIMESTAMP
                FROM project p
                WHERE i.project_id = p.id AND p.assignment_id = ? AND i.recipient_id = ?
                  AND i.status = 'PENDING' AND i.id <> ?
                """, assignmentId, userId, keepInvitationId);
        jdbc.update("""
                UPDATE seat_reservation s SET status = 'RELEASED', revision = s.revision + 1,
                    updated_at = CURRENT_TIMESTAMP
                FROM team_invitation i, project p
                WHERE s.invitation_id = i.id AND i.project_id = p.id
                  AND p.assignment_id = ? AND s.user_id = ? AND s.status = 'ACTIVE'
                """, assignmentId, userId);
        jdbc.update("""
                UPDATE team_application a SET status = 'CLOSED', reason = '已加入其他团队',
                    revision = a.revision + 1, updated_at = CURRENT_TIMESTAMP
                FROM project p
                WHERE a.project_id = p.id AND p.assignment_id = ? AND a.applicant_id = ?
                  AND a.status = 'PENDING'
                """, assignmentId, userId);
    }

    public List<CandidateRow> applicationCandidates(String projectId, String q) {
        String pattern = likePattern(q);
        return jdbc.query("""
                SELECT u.id, u.display_name, COALESCE(u.student_no, '') AS student_no,
                       u.skills_json::text AS skills_json, a.id AS application_id
                FROM team_application a
                JOIN app_user u ON u.id = a.applicant_id
                WHERE a.project_id = ? AND a.status = 'PENDING'
                  AND (u.display_name ILIKE ? ESCAPE '\\'
                       OR COALESCE(u.student_no, '') ILIKE ? ESCAPE '\\'
                       OR EXISTS (SELECT 1 FROM jsonb_array_elements_text(u.skills_json) AS s(skill)
                                  WHERE s.skill ILIKE ? ESCAPE '\\'))
                ORDER BY u.id
                """, (rs, n) -> new CandidateRow(rs.getString("id"), rs.getString("display_name"),
                rs.getString("student_no"), rs.getString("skills_json"),
                rs.getString("application_id"), null),
                projectId, pattern, pattern, pattern);
    }

    /**
     * 课程候选不过滤掉不可邀请者，而是返回 eligible=false 及稳定原因，供前端展示；
     * 真正能否邀请仍由后续写接口重新核权。
     */
    public List<CandidateRow> courseCandidates(String verifiedCourseId, String projectId,
                                               String q) {
        String pattern = likePattern(q);
        return jdbc.query("""
                SELECT u.id, u.display_name, COALESCE(u.student_no, '') AS student_no,
                       u.skills_json::text AS skills_json, NULL::varchar AS application_id,
                       CASE
                         WHEN EXISTS (SELECT 1 FROM membership m
                                      WHERE m.project_id = ? AND m.user_id = u.id AND m.status = 'ACTIVE')
                           THEN 'ALREADY_MEMBER'
                         WHEN EXISTS (SELECT 1 FROM seat_reservation r
                                      WHERE r.project_id = ? AND r.user_id = u.id AND r.status = 'ACTIVE')
                           THEN 'ALREADY_RESERVED'
                         WHEN EXISTS (SELECT 1 FROM team_application a
                                      WHERE a.project_id = ? AND a.applicant_id = u.id AND a.status = 'PENDING')
                           THEN 'APPLICATION_PENDING'
                         WHEN EXISTS (SELECT 1 FROM team_invitation i
                                      WHERE i.project_id = ? AND i.recipient_id = u.id AND i.status = 'PENDING')
                           THEN 'INVITATION_PENDING'
                         ELSE NULL
                       END AS blocked_reason
                FROM verified_enrollment e
                JOIN app_user u ON u.id = e.user_id
                WHERE e.verified_course_id = ? AND e.valid = true
                  AND (u.display_name ILIKE ? ESCAPE '\\'
                       OR COALESCE(u.student_no, '') ILIKE ? ESCAPE '\\'
                       OR EXISTS (SELECT 1 FROM jsonb_array_elements_text(u.skills_json) AS s(skill)
                                  WHERE s.skill ILIKE ? ESCAPE '\\'))
                ORDER BY u.id
                """, (rs, n) -> new CandidateRow(rs.getString("id"), rs.getString("display_name"),
                rs.getString("student_no"), rs.getString("skills_json"),
                rs.getString("application_id"), rs.getString("blocked_reason")),
                projectId, projectId, projectId, projectId, verifiedCourseId,
                pattern, pattern, pattern);
    }

    private String likePattern(String q) {
        String raw = q == null ? "" : q.replace("\\", "\\\\")
                .replace("%", "\\%").replace("_", "\\_");
        return "%" + raw + "%";
    }

    public int pendingApplicationCount(String userId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM team_application WHERE applicant_id = ? AND status = 'PENDING'",
                Integer.class, userId);
        return count == null ? 0 : count;
    }

    public int pendingInvitationCount(String userId, Instant now) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM team_invitation
                WHERE recipient_id = ? AND status = 'PENDING' AND expires_at > ?
                """, Integer.class, userId, OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
        return count == null ? 0 : count;
    }

    public List<InvitationRow> pendingInvitations(String userId, Instant now) {
        return jdbc.query("""
                SELECT * FROM team_invitation
                WHERE recipient_id = ? AND status = 'PENDING' AND expires_at > ?
                ORDER BY created_at DESC, id
                """, invitationMapper, userId, OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
    }

    public Optional<ProjectCodeRow> latestCode(String projectId) {
        return jdbc.query("""
                        SELECT * FROM project_code WHERE project_id = ?
                        ORDER BY created_at DESC LIMIT 1
                        """, codeMapper, projectId).stream().findFirst();
    }

    public Optional<ProjectCodeRow> codeByHash(String hash) {
        return jdbc.query("SELECT * FROM project_code WHERE code_hash = ?", codeMapper, hash)
                .stream().findFirst();
    }

    public void disableActiveCode(String projectId) {
        jdbc.update("""
                UPDATE project_code SET status = 'DISABLED', revision = revision + 1,
                    updated_at = CURRENT_TIMESTAMP WHERE project_id = ? AND status = 'ACTIVE'
                """, projectId);
    }

    public void insertCode(String projectId, String hash, String ciphertext, String keyId, int version) {
        jdbc.update("""
                INSERT INTO project_code
                    (id, project_id, code_hash, code_ciphertext, key_id, status, code_version, revision)
                VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, 1)
                """, UUID.randomUUID().toString(), projectId, hash, ciphertext, keyId, version);
    }

    public void updateCodeStatus(String id, String status) {
        jdbc.update("""
                UPDATE project_code SET status = ?, revision = revision + 1,
                    updated_at = CURRENT_TIMESTAMP WHERE id = ?
                """, status, id);
    }

    public int nextCodeVersion(String projectId) {
        Integer version = jdbc.queryForObject(
                "SELECT COALESCE(max(code_version), 0) FROM project_code WHERE project_id = ?",
                Integer.class, projectId);
        return (version == null ? 0 : version) + 1;
    }

    public record CandidateRow(String userId, String displayName, String studentNo,
                               String skillsJson, String applicationId, String blockedReason) {
    }

    public record AssignmentBrief(String id, String squareId, String name, Instant hardDueAt,
                                  int minMembers, int maxMembers) {
    }
}
