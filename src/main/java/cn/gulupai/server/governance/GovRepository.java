package cn.gulupai.server.governance;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

@Repository
public class GovRepository {

    private final JdbcTemplate jdbc;

    public GovRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertChange(ChangeRow row, Instant at) {
        jdbc.update("""
                INSERT INTO member_change
                    (id, project_id, target_user_id, initiator_id, kind, status, target_project_id,
                     plan_version, plan_json, personnel_decision_json, source_membership_revision, reason,
                     next_leader_id, notes, target_invite_status, target_invite_expires_at, removal_vote_id,
                     revision, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, row.id(), row.projectId(), row.targetUserId(), row.initiatorId(), row.kind(), row.status(),
                row.targetProjectId(), row.planVersion(), row.planJson(), row.decisionJson(),
                row.sourceMembershipRevision(), row.reason(), row.nextLeaderId(), row.notes(),
                row.targetInviteStatus(), time(row.targetInviteExpiresAt()), row.removalVoteId(), row.revision(),
                time(at), time(at));
    }

    public Optional<ChangeRow> lockChange(String id) {
        return one("SELECT * FROM member_change WHERE id = ? FOR UPDATE", changeMapper, id);
    }

    public Optional<ChangeRow> findChange(String id) {
        return one("SELECT * FROM member_change WHERE id = ?", changeMapper, id);
    }

    public List<ChangeRow> changesForProject(String projectId) {
        return jdbc.query("SELECT * FROM member_change WHERE project_id = ? ORDER BY created_at, id",
                changeMapper, projectId);
    }

    public List<ChangeRow> changesForSquare(String squareId) {
        return jdbc.query("""
                SELECT c.* FROM member_change c JOIN project p ON p.id = c.project_id
                WHERE p.square_id = ? ORDER BY c.created_at, c.id
                """, changeMapper, squareId);
    }

    public List<ChangeRow> incoming(String projectId) {
        return jdbc.query("""
                SELECT * FROM member_change
                WHERE target_project_id = ? AND kind = 'TRANSFER' ORDER BY created_at, id
                """, changeMapper, projectId);
    }

    public int updateChange(ChangeRow row, Instant at) {
        return jdbc.update("""
                UPDATE member_change
                SET status = ?, plan_version = ?, plan_json = ?::jsonb, personnel_decision_json = ?::jsonb,
                    next_leader_id = ?, notes = ?, target_invite_status = ?, target_invite_expires_at = ?,
                    removal_vote_id = ?, revision = revision + 1, updated_at = ?
                WHERE id = ? AND revision = ?
                """, row.status(), row.planVersion(), row.planJson(), row.decisionJson(), row.nextLeaderId(),
                row.notes(), row.targetInviteStatus(), time(row.targetInviteExpiresAt()), row.removalVoteId(),
                time(at), row.id(), row.revision());
    }

    public void event(String id, String changeId, String action, String actorId, String reason, Instant at) {
        jdbc.update("""
                INSERT INTO member_change_event (id, change_id, action, actor_id, reason, at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, id, changeId, action, actorId, reason == null ? "" : reason, time(at));
    }

    public List<EventRow> events(String changeId) {
        return jdbc.query("SELECT * FROM member_change_event WHERE change_id = ? ORDER BY at, id",
                (rs, n) -> new EventRow(rs.getString("action"), rs.getString("actor_id"),
                        rs.getString("reason"), instant(rs, "at")), changeId);
    }

    public void insertItem(ItemRow row) {
        jdbc.update("""
                INSERT INTO handover_item
                    (id, change_id, plan_version, kind, task_id, source_task_revision, recipient_id, responsibility)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, row.id(), row.changeId(), row.planVersion(), row.kind(), row.taskId(),
                row.sourceTaskRevision(), row.recipientId(), row.responsibility());
    }

    public List<ItemRow> items(String changeId, int version) {
        return jdbc.query("""
                SELECT * FROM handover_item WHERE change_id = ? AND plan_version = ? ORDER BY kind, id
                """, itemMapper, changeId, version);
    }

    public void ack(String changeId, int version, String itemId, String userId, String decision, Instant at) {
        jdbc.update("""
                INSERT INTO handover_ack (change_id, plan_version, item_id, user_id, decision, at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (change_id, plan_version, item_id, user_id) DO UPDATE
                SET decision = EXCLUDED.decision, at = EXCLUDED.at
                """, changeId, version, itemId, userId, decision, time(at));
    }

    public List<AckRow> acks(String changeId, int version) {
        return jdbc.query("""
                SELECT * FROM handover_ack WHERE change_id = ? AND plan_version = ? ORDER BY item_id, user_id
                """, (rs, n) -> new AckRow(rs.getString("item_id"), rs.getString("user_id"),
                rs.getString("decision")), changeId, version);
    }

    public void insertVote(VoteRow row, Instant at) {
        jdbc.update("""
                INSERT INTO removal_vote
                    (id, project_id, target_user_id, initiator_id, status, facts, communication, statement,
                     statement_ends_at, voting_ends_at, electorate_json, personnel_decision_json,
                     member_change_id, revision, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?)
                """, row.id(), row.projectId(), row.targetUserId(), row.initiatorId(), row.status(), row.facts(),
                row.communication(), row.statement(), time(row.statementEndsAt()), time(row.votingEndsAt()),
                row.electorateJson(), row.decisionJson(), row.memberChangeId(), row.revision(), time(at), time(at));
    }

    public Optional<VoteRow> lockVote(String id) {
        return one("SELECT * FROM removal_vote WHERE id = ? FOR UPDATE", voteMapper, id);
    }

    public Optional<VoteRow> findVote(String id) {
        return one("SELECT * FROM removal_vote WHERE id = ?", voteMapper, id);
    }

    public List<VoteRow> votes(String projectId) {
        return jdbc.query("SELECT * FROM removal_vote WHERE project_id = ? ORDER BY created_at, id", voteMapper, projectId);
    }

    public int updateVote(VoteRow row, Instant at) {
        return jdbc.update("""
                UPDATE removal_vote
                SET status = ?, statement = ?, personnel_decision_json = ?::jsonb, member_change_id = ?,
                    revision = revision + 1, updated_at = ?
                WHERE id = ? AND revision = ?
                """, row.status(), row.statement(), row.decisionJson(), row.memberChangeId(), time(at),
                row.id(), row.revision());
    }

    public Optional<BallotRow> ballot(String voteId, String userId) {
        return one("SELECT * FROM removal_ballot WHERE vote_id = ? AND user_id = ?",
                (rs, n) -> new BallotRow(rs.getString("user_id"), rs.getString("decision"), rs.getInt("revision")),
                voteId, userId);
    }

    public List<BallotRow> ballots(String voteId) {
        return jdbc.query("SELECT * FROM removal_ballot WHERE vote_id = ?",
                (rs, n) -> new BallotRow(rs.getString("user_id"), rs.getString("decision"), rs.getInt("revision")),
                voteId);
    }

    public void insertBallot(String voteId, String userId, String decision, int revision, Instant at) {
        jdbc.update("""
                INSERT INTO removal_ballot (vote_id, user_id, decision, revision, at) VALUES (?, ?, ?, ?, ?)
                """, voteId, userId, decision, revision, time(at));
    }

    public int updateBallot(String voteId, String userId, String decision, int revision, Instant at) {
        return jdbc.update("""
                UPDATE removal_ballot SET decision = ?, revision = revision + 1, at = ?
                WHERE vote_id = ? AND user_id = ? AND revision = ?
                """, decision, time(at), voteId, userId, revision);
    }

    public void deleteBallots(String voteId) {
        jdbc.update("DELETE FROM removal_ballot WHERE vote_id = ?", voteId);
    }

    public void insertCase(CaseRow row, Instant at) {
        jdbc.update("""
                INSERT INTO personnel_case
                    (id, square_id, project_id, subject_user_id, assignee_id, title, status,
                     member_change_id, revision, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, row.id(), row.squareId(), row.projectId(), row.subjectUserId(), row.assigneeId(),
                row.title(), row.status(), row.memberChangeId(), row.revision(), time(at), time(at));
    }

    public Optional<CaseRow> lockCase(String id) {
        return one("SELECT * FROM personnel_case WHERE id = ? FOR UPDATE", caseMapper, id);
    }

    public Optional<CaseRow> findCase(String id) {
        return one("SELECT * FROM personnel_case WHERE id = ?", caseMapper, id);
    }

    public Optional<CaseRow> caseForChange(String changeId) {
        return one("SELECT * FROM personnel_case WHERE member_change_id = ?", caseMapper, changeId);
    }

    /** 同一成员变更理论上只对应一个事项；多行属于数据异常，由调用方显式拒绝。 */
    public List<CaseRow> casesForChange(String changeId) {
        return jdbc.query("SELECT * FROM personnel_case WHERE member_change_id = ? ORDER BY created_at, id",
                caseMapper, changeId);
    }

    public List<CaseRow> casesForSquare(String squareId) {
        return jdbc.query("SELECT * FROM personnel_case WHERE square_id = ? ORDER BY created_at, id",
                caseMapper, squareId);
    }

    public int updateCase(CaseRow row, Instant at) {
        return jdbc.update("""
                UPDATE personnel_case SET assignee_id = ?, status = ?, revision = revision + 1, updated_at = ?
                WHERE id = ? AND revision = ?
                """, row.assigneeId(), row.status(), time(at), row.id(), row.revision());
    }

    public void record(String id, String caseId, String kind, String authorId, String body, Instant at) {
        jdbc.update("""
                INSERT INTO personnel_record (id, case_id, kind, author_id, body_json, at)
                VALUES (?, ?, ?, ?, ?::jsonb, ?)
                """, id, caseId, kind, authorId, body, time(at));
    }

    public List<RecordRow> records(String caseId) {
        return jdbc.query("SELECT * FROM personnel_record WHERE case_id = ? ORDER BY at, id",
                (rs, n) -> new RecordRow(rs.getString("kind"), rs.getString("author_id"), rs.getString("body_json")),
                caseId);
    }

    public void insertMaterial(MaterialRow row, Instant at) {
        jdbc.update("""
                INSERT INTO material_request
                    (id, case_id, recipient_id, status, requirements, due_at, response_version, response_json,
                     revision, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                """, row.id(), row.caseId(), row.recipientId(), row.status(), row.requirements(), time(row.dueAt()),
                row.responseVersion(), row.responseJson(), row.revision(), time(at), time(at));
    }

    public void insertMaterialResponse(String id, String materialId, int version, String authorId,
                                       String content, String evidenceJson, Instant at,
                                       String createdAtConfidence, String timeSource) {
        jdbc.update("""
                INSERT INTO material_response
                    (id, material_id, version, author_id, content, evidence_json, withdrawn, created_at,
                     created_at_confidence, time_source)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, false, ?, ?, ?)
                """, id, materialId, version, authorId, content, evidenceJson, time(at),
                createdAtConfidence, timeSource);
    }

    public List<MaterialResponseRow> materialResponses(String materialId) {
        return jdbc.query("""
                SELECT * FROM material_response WHERE material_id = ? ORDER BY version
                """, materialResponseMapper, materialId);
    }

    public Optional<MaterialResponseRow> materialResponse(String materialId, int version) {
        return one("SELECT * FROM material_response WHERE material_id = ? AND version = ?",
                materialResponseMapper, materialId, version);
    }

    /** 审核/撤回在 case -> material 锁之后再锁目标版本行，避免只凭存在性写入。 */
    public Optional<MaterialResponseRow> lockMaterialResponse(String materialId, int version) {
        return one("SELECT * FROM material_response WHERE material_id = ? AND version = ? FOR UPDATE",
                materialResponseMapper, materialId, version);
    }

    /** 撤回只把对应版本正文标记为占位，保留版本号、作者和提交时间。 */
    public int withdrawMaterialResponse(String materialId, int version) {
        return jdbc.update("""
                UPDATE material_response SET withdrawn = true
                WHERE material_id = ? AND version = ? AND withdrawn = false
                """, materialId, version);
    }

    public void insertMaterialReview(String id, String materialId, int responseVersion, String reviewerId,
                                     String decision, String reason, Instant at) {
        jdbc.update("""
                INSERT INTO material_review
                    (id, material_id, response_version, reviewer_id, decision, reason, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, id, materialId, responseVersion, reviewerId, decision,
                reason == null ? "" : reason, time(at));
    }

    public List<MaterialReviewRow> materialReviews(String materialId) {
        return jdbc.query("""
                SELECT * FROM material_review WHERE material_id = ? ORDER BY created_at, id
                """, (rs, n) -> new MaterialReviewRow(rs.getString("reviewer_id"), rs.getInt("response_version"),
                rs.getString("decision"), rs.getString("reason"), instant(rs, "created_at")), materialId);
    }

    public Optional<MaterialRow> lockMaterial(String id) {
        return one("SELECT * FROM material_request WHERE id = ? FOR UPDATE", materialMapper, id);
    }

    public List<MaterialRow> materialsForUser(String userId) {
        return jdbc.query("SELECT * FROM material_request WHERE recipient_id = ? ORDER BY created_at, id",
                materialMapper, userId);
    }

    public List<MaterialRow> materialsForCase(String caseId) {
        return jdbc.query("SELECT * FROM material_request WHERE case_id = ? ORDER BY created_at, id",
                materialMapper, caseId);
    }

    /**
     * 数据层最后一道保护：每个动作只允许自己的状态转换，并同时重核 case 未结案。
     * 影响行数由调用方断言为 1；0 行表示锁后状态已变化，不能返回虚假成功。
     */
    public int markMaterialResponded(String materialId, int expectedRevision, int expectedResponseVersion,
                                     int newResponseVersion, String responseJson, Instant at) {
        return jdbc.update("""
                UPDATE material_request m
                SET status = 'RESPONDED', response_version = ?, response_json = ?::jsonb,
                    revision = m.revision + 1, updated_at = ?
                FROM personnel_case c
                WHERE m.id = ? AND m.revision = ? AND m.response_version = ?
                  AND m.status IN ('OPEN', 'RESPONDED', 'RETURNED', 'WITHDRAWN')
                  AND c.id = m.case_id AND c.status <> 'CLOSED'
                """, newResponseVersion, responseJson, time(at), materialId, expectedRevision,
                expectedResponseVersion);
    }

    public int markMaterialWithdrawn(String materialId, int expectedRevision, int responseVersion, Instant at) {
        return jdbc.update("""
                UPDATE material_request m
                SET status = 'WITHDRAWN', revision = m.revision + 1, updated_at = ?
                FROM personnel_case c
                WHERE m.id = ? AND m.revision = ? AND m.response_version = ?
                  AND m.status IN ('RESPONDED', 'RETURNED')
                  AND c.id = m.case_id AND c.status <> 'CLOSED'
                """, time(at), materialId, expectedRevision, responseVersion);
    }

    public int markMaterialReviewed(String materialId, int expectedRevision, int responseVersion,
                                    String newStatus, Instant at) {
        return jdbc.update("""
                UPDATE material_request m
                SET status = ?, revision = m.revision + 1, updated_at = ?
                FROM personnel_case c
                WHERE m.id = ? AND m.revision = ? AND m.response_version = ?
                  AND m.status = 'RESPONDED'
                  AND c.id = m.case_id AND c.status <> 'CLOSED'
                  AND EXISTS (SELECT 1 FROM material_response r
                              WHERE r.material_id = m.id AND r.version = m.response_version
                                AND r.withdrawn = false)
                """, newStatus, time(at), materialId, expectedRevision, responseVersion);
    }

    /**
     * 受控恢复：只允许把 UNKNOWN/null 版本绑定到一条已通过完整证据验证的提交结果。
     * 条件更新是并发保护，影响行数由调用方断言；提交时间来自证据，不来自回执创建时间。
     */
    public int recoverMaterialResponseTime(String materialId, int version, Instant createdAt,
                                           String evidence, String actorId, Instant at) {
        return jdbc.update("""
                UPDATE material_response
                SET created_at = ?, created_at_confidence = 'EXACT', time_source = 'RECOVERED_EVIDENCE',
                    time_evidence = ?, time_recovered_by = ?, time_recovered_at = ?
                WHERE material_id = ? AND version = ?
                  AND created_at_confidence = 'UNKNOWN' AND created_at IS NULL
                """, time(createdAt), evidence, actorId, time(at), materialId, version);
    }

    public Optional<OperationReceiptEvidence> operationReceipt(String id) {
        return one("""
                SELECT id, user_id, method, path, http_status, business_code,
                       result_ciphertext, key_id, created_at, expires_at
                FROM operation_receipt WHERE id = ?
                """, (rs, n) -> new OperationReceiptEvidence(rs.getString("id"), rs.getString("user_id"),
                rs.getString("method"), rs.getString("path"), rs.getInt("http_status"),
                rs.getString("business_code"), rs.getString("result_ciphertext"), rs.getString("key_id"),
                instant(rs, "created_at"), instant(rs, "expires_at")), id);
    }

    /**
     * K03 只读核查候选：只扫描曾按回执恢复的行，不含普通服务器提交或 legacy 行。
     * 调用方逐条复用证据验证器；不得在此处解密或改写任何列。
     */
    public List<MaterialResponseRow> recoveredMaterialResponses() {
        return jdbc.query("""
                SELECT * FROM material_response
                WHERE time_source = 'RECOVERED_EVIDENCE'
                ORDER BY material_id, version
                """, materialResponseMapper);
    }

    public void insertHandoff(HandoffRow row, Instant at) {
        jdbc.update("""
                INSERT INTO personnel_handoff
                    (id, case_id, from_user_id, to_user_id, status, source_case_revision, facts, unresolved,
                     decisions_needed, revision, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, row.id(), row.caseId(), row.fromUserId(), row.toUserId(), row.status(), row.sourceRevision(),
                row.facts(), row.unresolved(), row.decisionsNeeded(), row.revision(), time(at));
    }

    public Optional<HandoffRow> findHandoff(String id) {
        return one("SELECT * FROM personnel_handoff WHERE id = ?", handoffMapper, id);
    }

    public Optional<MaterialRow> findMaterial(String id) {
        return one("SELECT * FROM material_request WHERE id = ?", materialMapper, id);
    }

    public Optional<HandoffRow> lockHandoff(String id) {
        return one("SELECT * FROM personnel_handoff WHERE id = ? FOR UPDATE", handoffMapper, id);
    }

    public List<HandoffRow> handoffsForUser(String userId) {
        return jdbc.query("""
                SELECT * FROM personnel_handoff WHERE from_user_id = ? OR to_user_id = ? ORDER BY created_at, id
                """, handoffMapper, userId, userId);
    }

    public int cancelPendingHandoffs(String caseId) {
        return jdbc.update("""
                UPDATE personnel_handoff
                SET status = 'CANCELLED', revision = revision + 1
                WHERE case_id = ? AND status = 'PENDING'
                """, caseId);
    }

    public int updateHandoff(String id, int revision, String status) {
        return jdbc.update("""
                UPDATE personnel_handoff SET status = ?, revision = revision + 1
                WHERE id = ? AND revision = ? AND status = 'PENDING'
                """, status, id, revision);
    }


    // ---- G05 人员事项 case 级教师授权 ----

    private final RowMapper<GrantRow> grantMapper = (rs, n) -> new GrantRow(
            rs.getString("id"), rs.getString("case_id"), rs.getString("user_id"),
            rs.getString("status"), rs.getInt("revision"), rs.getString("granted_by"),
            instant(rs, "granted_at"), rs.getString("revoked_by"), instant(rs, "revoked_at"),
            rs.getString("reason"), instant(rs, "expires_at"));

    public Optional<GrantRow> lockGrant(String id) {
        return one("SELECT * FROM personnel_case_grant WHERE id = ? FOR UPDATE", grantMapper, id);
    }

    public Optional<GrantRow> findGrant(String id) {
        return one("SELECT * FROM personnel_case_grant WHERE id = ?", grantMapper, id);
    }

    public List<GrantRow> grantsForCase(String caseId) {
        return jdbc.query("""
                SELECT * FROM personnel_case_grant WHERE case_id = ? ORDER BY created_at, id
                """, grantMapper, caseId);
    }

    public Optional<GrantRow> activeGrant(String caseId, String userId, Instant now) {
        return jdbc.query("""
                SELECT * FROM personnel_case_grant
                WHERE case_id = ? AND user_id = ? AND status = 'ACTIVE'
                  AND (expires_at IS NULL OR expires_at > ?)
                ORDER BY granted_at DESC, id
                LIMIT 1
                """, grantMapper, caseId, userId, time(now)).stream().findFirst();
    }

    public void insertGrant(String id, String caseId, String userId, String grantedBy, String reason,
                            Instant expiresAt, Instant now) {
        jdbc.update("""
                INSERT INTO personnel_case_grant
                    (id, case_id, user_id, status, revision, granted_by, granted_at, reason, expires_at,
                     created_at, updated_at)
                VALUES (?, ?, ?, 'ACTIVE', 1, ?, ?, ?, ?, ?, ?)
                """, id, caseId, userId, grantedBy, time(now), reason, time(expiresAt), time(now), time(now));
    }

    /** 重新授予前把已过期的 ACTIVE 行迁移为 EXPIRED，避免有效唯一约束冲突。 */
    public int expireActiveGrant(String caseId, String userId, Instant now) {
        return jdbc.update("""
                UPDATE personnel_case_grant
                SET status = 'EXPIRED', revision = revision + 1, updated_at = ?
                WHERE case_id = ? AND user_id = ? AND status = 'ACTIVE'
                  AND expires_at IS NOT NULL AND expires_at <= ?
                """, time(now), caseId, userId, time(now));
    }

    public int revokeGrant(String id, int revision, String revokedBy, String reason, Instant now) {
        return jdbc.update("""
                UPDATE personnel_case_grant
                SET status = 'REVOKED', revision = revision + 1, revoked_by = ?, revoked_at = ?,
                    reason = CASE WHEN ? = '' THEN reason ELSE ? END, updated_at = ?
                WHERE id = ? AND revision = ? AND status = 'ACTIVE'
                """, revokedBy, time(now), reason, reason, time(now), id, revision);
    }

    /**
     * 使用时的完整教师身份：账号未停用、核验有效、仍有 TEACHER 角色，
     * 且账号学校属于该事项广场所在学校。管理员导入删角色后立即不再成立。
     */
    public boolean eligibleTeacher(String squareId, String userId) {
        Boolean ok = jdbc.queryForObject("""
                SELECT EXISTS(
                    SELECT 1
                    FROM app_user u
                    JOIN square s ON s.id = ?
                    WHERE u.id = ? AND u.disabled = false AND u.identity_status = 'VERIFIED'
                      AND u.school_id = s.school_id
                      AND EXISTS (SELECT 1 FROM user_role r
                                  WHERE r.user_id = u.id AND r.role = 'TEACHER'))
                """, Boolean.class, squareId, userId);
        return Boolean.TRUE.equals(ok);
    }

    /** 学生/收件人侧账号仍有效；不要求 TEACHER 角色。 */
    public boolean activeVerifiedAccount(String userId) {
        Boolean ok = jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM app_user
                              WHERE id = ? AND disabled = false AND identity_status = 'VERIFIED')
                """, Boolean.class, userId);
        return Boolean.TRUE.equals(ok);
    }

    /**
     * 本 case 可接续候选：当前任课教师 + 有效的 case 级授权教师；
     * 双方都必须仍是同校有效 TEACHER 账号，排除发起人、学生项目成员、停用/未核验账号。
     */
    public List<PersonRow> handoffCandidates(String caseId, String squareId, String projectId,
                                             String excludeUserId, Instant now) {
        return jdbc.query("""
                SELECT u.id, u.display_name
                FROM (
                    SELECT ta.user_id AS id
                    FROM teacher_assignment ta
                    JOIN app_user tu ON tu.id = ta.user_id
                    JOIN square s ON s.id = ta.square_id
                    WHERE ta.square_id = ? AND ta.active = true
                      AND tu.school_id = s.school_id
                      AND EXISTS (SELECT 1 FROM user_role tr
                                  WHERE tr.user_id = ta.user_id AND tr.role = 'TEACHER')
                    UNION
                    SELECT g.user_id AS id
                    FROM personnel_case_grant g
                    JOIN app_user gu ON gu.id = g.user_id
                    JOIN square gs ON gs.id = ?
                    WHERE g.case_id = ? AND g.status = 'ACTIVE'
                      AND (g.expires_at IS NULL OR g.expires_at > ?)
                      AND gu.school_id = gs.school_id
                      AND EXISTS (SELECT 1 FROM user_role gr
                                  WHERE gr.user_id = g.user_id AND gr.role = 'TEACHER')
                ) candidates
                JOIN app_user u ON u.id = candidates.id
                WHERE u.disabled = false AND u.identity_status = 'VERIFIED'
                  AND candidates.id <> ?
                  AND NOT EXISTS (SELECT 1 FROM membership m
                                  WHERE m.project_id = ? AND m.user_id = candidates.id)
                ORDER BY u.id
                """, (rs, n) -> new PersonRow(rs.getString("id"), rs.getString("display_name")),
                squareId, squareId, caseId, time(now), excludeUserId, projectId);
    }

    public boolean projectMember(String projectId, String userId) {
        Boolean ok = jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM membership WHERE project_id = ? AND user_id = ?)",
                Boolean.class, projectId, userId);
        return Boolean.TRUE.equals(ok);
    }

    public boolean pendingForUser(String userId) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM submission s
                JOIN task t ON t.id = s.task_id
                WHERE s.status = 'PENDING' AND (
                    t.owner_id = ? OR EXISTS (
                        SELECT 1 FROM delivery_unit u
                        JOIN unit_share sh ON sh.unit_id = u.id
                        WHERE u.task_id = t.id AND sh.user_id = ? AND sh.signed))
                """, Integer.class, userId, userId);
        return count != null && count > 0;
    }

    public boolean unconfirmedContribution(String userId) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM unit_share sh
                JOIN delivery_unit u ON u.id = sh.unit_id
                WHERE sh.user_id = ? AND sh.signed = true AND u.passed = true
                  AND NOT EXISTS (
                      SELECT 1 FROM contribution_confirmation c
                      WHERE c.unit_id = u.id AND c.user_id = sh.user_id)
                """, Integer.class, userId);
        return count != null && count > 0;
    }

    public List<GovViews.Outstanding> unfinishedShares(String projectId, String userId) {
        return jdbc.query("""
                SELECT DISTINCT t.id, t.title, t.revision
                FROM task t
                JOIN delivery_unit u ON u.task_id = t.id
                JOIN unit_share s ON s.unit_id = u.id
                WHERE t.project_id = ? AND s.user_id = ? AND s.signed = true AND u.passed = false
                """, (rs, n) -> new GovViews.Outstanding(rs.getString("id"), rs.getString("title"),
                rs.getInt("revision")), projectId, userId);
    }

    public List<MembershipHistory> membershipHistory(String userId) {
        return jdbc.query("""
                SELECT project_id, status, left_at, revision FROM membership
                WHERE user_id = ? AND status <> 'ACTIVE' ORDER BY left_at NULLS LAST, id
                """, (rs, n) -> new MembershipHistory(rs.getString("project_id"), rs.getString("status"),
                instant(rs, "left_at"), rs.getInt("revision")), userId);
    }

    public Optional<TaskHandover> executedTaskHandover(String taskId) {
        return jdbc.query("""
                SELECT c.id, c.target_user_id, i.recipient_id
                FROM handover_item i
                JOIN member_change c ON c.id = i.change_id AND c.plan_version = i.plan_version
                WHERE i.task_id = ? AND i.kind = 'TASK' AND c.status = 'EXECUTED'
                ORDER BY c.updated_at DESC LIMIT 1
                """, (rs, n) -> new TaskHandover(rs.getString("id"), rs.getString("target_user_id"),
                rs.getString("recipient_id")), taskId).stream().findFirst();
    }

    private <T> Optional<T> one(String sql, RowMapper<T> mapper, Object... args) {
        return jdbc.query(sql, mapper, args).stream().findFirst();
    }

    private static OffsetDateTime time(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private final RowMapper<ChangeRow> changeMapper = (rs, n) -> new ChangeRow(
            rs.getString("id"), rs.getString("project_id"), rs.getString("target_user_id"),
            rs.getString("initiator_id"), rs.getString("kind"), rs.getString("status"),
            rs.getString("target_project_id"), rs.getInt("plan_version"), rs.getString("plan_json"),
            rs.getString("personnel_decision_json"), rs.getInt("source_membership_revision"),
            rs.getString("reason"), rs.getString("next_leader_id"), rs.getString("notes"),
            rs.getString("target_invite_status"), instant(rs, "target_invite_expires_at"),
            rs.getString("removal_vote_id"), rs.getInt("revision"), instant(rs, "created_at"));

    private final RowMapper<ItemRow> itemMapper = (rs, n) -> new ItemRow(
            rs.getString("id"), rs.getString("change_id"), rs.getInt("plan_version"), rs.getString("kind"),
            rs.getString("task_id"), rs.getObject("source_task_revision") == null ? null : rs.getInt("source_task_revision"),
            rs.getString("recipient_id"), rs.getString("responsibility"));

    private final RowMapper<VoteRow> voteMapper = (rs, n) -> new VoteRow(
            rs.getString("id"), rs.getString("project_id"), rs.getString("target_user_id"),
            rs.getString("initiator_id"), rs.getString("status"), rs.getString("facts"),
            rs.getString("communication"), rs.getString("statement"), instant(rs, "statement_ends_at"),
            instant(rs, "voting_ends_at"), rs.getString("electorate_json"), rs.getString("personnel_decision_json"),
            rs.getString("member_change_id"), rs.getInt("revision"), instant(rs, "created_at"));

    private final RowMapper<CaseRow> caseMapper = (rs, n) -> new CaseRow(
            rs.getString("id"), rs.getString("square_id"), rs.getString("project_id"),
            rs.getString("subject_user_id"), rs.getString("assignee_id"), rs.getString("title"),
            rs.getString("status"), rs.getString("member_change_id"), rs.getInt("revision"));

    private final RowMapper<MaterialRow> materialMapper = (rs, n) -> new MaterialRow(
            rs.getString("id"), rs.getString("case_id"), rs.getString("recipient_id"), rs.getString("status"),
            rs.getString("requirements"), instant(rs, "due_at"), rs.getInt("response_version"),
            rs.getString("response_json"), rs.getInt("revision"));

    private final RowMapper<MaterialResponseRow> materialResponseMapper = (rs, n) -> new MaterialResponseRow(
            rs.getString("id"), rs.getString("material_id"), rs.getInt("version"), rs.getString("author_id"),
            rs.getString("content"), rs.getString("evidence_json"), rs.getBoolean("withdrawn"),
            instant(rs, "created_at"), rs.getString("created_at_confidence"),
            instant(rs, "legacy_recorded_at"), rs.getString("time_source"), rs.getString("time_evidence"),
            rs.getString("time_recovered_by"), instant(rs, "time_recovered_at"));

    private final RowMapper<HandoffRow> handoffMapper = (rs, n) -> new HandoffRow(
            rs.getString("id"), rs.getString("case_id"), rs.getString("from_user_id"), rs.getString("to_user_id"),
            rs.getString("status"), rs.getInt("source_case_revision"), rs.getString("facts"),
            rs.getString("unresolved"), rs.getString("decisions_needed"), rs.getInt("revision"));

    public record ChangeRow(String id, String projectId, String targetUserId, String initiatorId, String kind,
                            String status, String targetProjectId, int planVersion, String planJson,
                            String decisionJson, int sourceMembershipRevision, String reason, String nextLeaderId,
                            String notes, String targetInviteStatus, Instant targetInviteExpiresAt,
                            String removalVoteId, int revision, Instant createdAt) {
    }

    public record EventRow(String action, String actorId, String reason, Instant at) {
    }

    public record ItemRow(String id, String changeId, int planVersion, String kind, String taskId,
                          Integer sourceTaskRevision, String recipientId, String responsibility) {
    }

    public record AckRow(String itemId, String userId, String decision) {
    }

    public record VoteRow(String id, String projectId, String targetUserId, String initiatorId, String status,
                          String facts, String communication, String statement, Instant statementEndsAt,
                          Instant votingEndsAt, String electorateJson, String decisionJson, String memberChangeId,
                          int revision, Instant createdAt) {
    }

    public record BallotRow(String userId, String decision, int revision) {
    }

    public record CaseRow(String id, String squareId, String projectId, String subjectUserId, String assigneeId,
                          String title, String status, String memberChangeId, int revision) {
    }

    public record RecordRow(String kind, String authorId, String bodyJson) {
    }

    public record MaterialRow(String id, String caseId, String recipientId, String status, String requirements,
                              Instant dueAt, int responseVersion, String responseJson, int revision) {
    }

    public record MaterialResponseRow(String id, String materialId, int version, String authorId, String content,
                                      String evidenceJson, boolean withdrawn, Instant createdAt,
                                      String createdAtConfidence, Instant legacyRecordedAt, String timeSource,
                                      String timeEvidence, String timeRecoveredBy, Instant timeRecoveredAt) {
    }

    public record OperationReceiptEvidence(String id, String userId, String method, String path,
                                           int httpStatus, String businessCode, String resultCiphertext,
                                           String keyId, Instant createdAt, Instant expiresAt) {
    }

    public record MaterialReviewRow(String reviewerId, int responseVersion, String decision, String reason,
                                    Instant createdAt) {
    }

    public record HandoffRow(String id, String caseId, String fromUserId, String toUserId, String status,
                             int sourceRevision, String facts, String unresolved, String decisionsNeeded, int revision) {
    }

    public record GrantRow(String id, String caseId, String userId, String status, int revision,
                           String grantedBy, Instant grantedAt, String revokedBy, Instant revokedAt,
                           String reason, Instant expiresAt) {
    }

    public record PersonRow(String userId, String displayName) {
    }

    public record TaskHandover(String changeId, String departingUserId, String recipientId) {
    }

    public record MembershipHistory(String projectId, String status, Instant leftAt, int revision) {
    }
}
