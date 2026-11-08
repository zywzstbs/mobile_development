package cn.gulupai.server.platform.persistence;

import cn.gulupai.server.platform.domain.ManagementAuditEvent;
import cn.gulupai.server.platform.domain.PlatformCapability;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class PlatformRepository {

    private final JdbcTemplate jdbc;

    public PlatformRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ActiveGrant> findActiveGrant(String userId) {
        List<GrantHead> heads = jdbc.query("""
                SELECT id, user_id, revision
                FROM platform_admin_grant
                WHERE user_id = ? AND status = 'ACTIVE'
                """, (rs, row) -> new GrantHead(rs.getString("id"), rs.getString("user_id"), rs.getInt("revision")),
                userId);
        if (heads.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(loadGrant(heads.getFirst()));
    }

    public Optional<ActiveGrant> findGrant(String grantId) {
        List<GrantHead> heads = jdbc.query("""
                SELECT id, user_id, revision FROM platform_admin_grant WHERE id = ?
                """, (rs, row) -> new GrantHead(rs.getString("id"), rs.getString("user_id"), rs.getInt("revision")),
                grantId);
        if (heads.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(loadGrant(heads.getFirst()));
    }

    public Optional<GrantHead> lockActiveGrantByUser(String userId) {
        return jdbc.query("""
                SELECT id, user_id, revision
                FROM platform_admin_grant
                WHERE user_id = ? AND status = 'ACTIVE'
                FOR UPDATE
                """, (rs, row) -> new GrantHead(rs.getString("id"), rs.getString("user_id"), rs.getInt("revision")),
                userId).stream().findFirst();
    }

    /**
     * 全平台管理约束锁。所有会改变平台角色、授权、能力、范围或账号可用性的受控写入，
     * 都在同一事务内先取这一行，再读授权事实；锁行缺失说明 schema 未正确初始化，
     * 必须显式失败，不能在无锁状态下静默继续。
     */
    public void lockAdminConstraint() {
        List<String> rows = jdbc.query("SELECT id FROM platform_admin_lock WHERE id = 1 FOR UPDATE",
                (rs, row) -> rs.getString("id"));
        if (rows.isEmpty()) {
            throw new IllegalStateException("平台管理约束锁行缺失，拒绝在无锁状态下执行管理写入");
        }
    }

    /**
     * 可恢复管理员的唯一判定：账号可用 + 真实 SUPER_ADMIN 角色 + ACTIVE 授权 + 两项管理能力
     * + 覆盖全平台的 scope + 可登录的密码凭据 + 与授予规则一致的资格状态。
     * 任一条件缺失都不计入，避免用残缺账号冒充最后一位可恢复管理员。
     */
    private static final String PLATFORM_RECOVERY_ADMIN_FROM = """
            FROM platform_admin_grant g
            JOIN app_user u ON u.id = g.user_id
            WHERE g.status = 'ACTIVE'
              AND u.account_status = 'ACTIVE' AND u.disabled = false
              AND u.identity_status = 'VERIFIED'
              AND u.eligibility_source = 'VERIFIED_ACTIVE'
              AND u.password_hash IS NOT NULL AND length(u.password_hash) > 0
              AND EXISTS (SELECT 1 FROM user_role r WHERE r.user_id = u.id AND r.role = 'SUPER_ADMIN')
              AND EXISTS (
                  SELECT 1 FROM platform_admin_capability c
                  WHERE c.grant_id = g.id AND c.capability = 'PLATFORM_SUPER_ADMIN_MANAGE')
              AND EXISTS (
                  SELECT 1 FROM platform_admin_capability c
                  WHERE c.grant_id = g.id AND c.capability = 'PLATFORM_ACCOUNT_MANAGE')
              AND EXISTS (
                  SELECT 1 FROM platform_admin_scope s
                  WHERE s.grant_id = g.id AND s.scope_type = 'PLATFORM')
            """;

    public boolean isPlatformRecoveryAdmin(String userId) {
        Boolean exists = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 " + PLATFORM_RECOVERY_ADMIN_FROM + " AND g.user_id = ?)",
                Boolean.class, userId);
        return Boolean.TRUE.equals(exists);
    }

    /**
     * 排除目标后仍可恢复全平台的管理员数量。学校范围管理员不计入：当前没有把学校授权
     * 升级为 PLATFORM 的路径，因此它们不能替代全平台恢复能力。
     */
    public int countPlatformRecoveryExcluding(String userId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) " + PLATFORM_RECOVERY_ADMIN_FROM + " AND g.user_id <> ?",
                Integer.class, userId);
        return count == null ? 0 : count;
    }

    /**
     * 当前可恢复全平台的管理员总数。导入等整批操作在提交前用它证明批次前后
     * 没有把可恢复管理员从“有”变成“没有”。
     */
    public int countPlatformRecoveryAdmins() {
        Integer count = jdbc.queryForObject("SELECT count(*) " + PLATFORM_RECOVERY_ADMIN_FROM, Integer.class);
        return count == null ? 0 : count;
    }

    public List<String> rolesOf(String userId) {
        return jdbc.query("SELECT role FROM user_role WHERE user_id = ? ORDER BY role",
                (rs, row) -> rs.getString("role"), userId);
    }

    /**
     * 当前访问令牌所属会话必须仍属于该操作者、未撤销且未过期。
     * 这是锁内的会话事实重读，不取代业务会话刷新/退出各自的锁顺序。
     */
    public boolean activeSession(String sessionId, String userId, Instant now) {
        Boolean exists = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM auth_session
                    WHERE id = ? AND user_id = ? AND revoked_at IS NULL AND expires_at > ?
                )
                """, Boolean.class, sessionId, userId, ts(now));
        return Boolean.TRUE.equals(exists);
    }

    public String insertGrant(String userId, String grantedBy, Instant at, boolean platform, String schoolId) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO platform_admin_grant
                    (id, user_id, status, granted_by, granted_at, revision)
                VALUES (?, ?, 'ACTIVE', ?, ?, 1)
                """, id, userId, grantedBy, ts(at));
        for (String capability : PlatformCapability.ALL) {
            jdbc.update("INSERT INTO platform_admin_capability (grant_id, capability) VALUES (?, ?)",
                    id, capability);
        }
        if (platform) {
            jdbc.update("""
                    INSERT INTO platform_admin_scope (grant_id, scope_type, school_id)
                    VALUES (?, 'PLATFORM', NULL)
                    """, id);
        } else {
            jdbc.update("""
                    INSERT INTO platform_admin_scope (grant_id, scope_type, school_id)
                    VALUES (?, 'SCHOOL', ?)
                    """, id, schoolId);
        }
        jdbc.update("""
                INSERT INTO user_role (user_id, role) VALUES (?, 'SUPER_ADMIN')
                ON CONFLICT DO NOTHING
                """, userId);
        return id;
    }

    public int revokeGrant(String grantId, int expectedRevision, String actorId, String reason, Instant at) {
        return jdbc.update("""
                UPDATE platform_admin_grant
                SET status = 'REVOKED', revoked_by = ?, revoked_at = ?, revoke_reason = ?,
                    revision = revision + 1, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND revision = ? AND status = 'ACTIVE'
                """, actorId, ts(at), reason, grantId, expectedRevision);
    }

    public void deleteSuperAdminRole(String userId) {
        jdbc.update("DELETE FROM user_role WHERE user_id = ? AND role = 'SUPER_ADMIN'", userId);
    }

    public Optional<ManagedUser> lockUser(String userId) {
        return jdbc.query("""
                SELECT id, school_id, login_id, display_name, identity_status, disabled, account_status,
                       eligibility_source, revision,
                       (password_hash IS NOT NULL AND length(password_hash) > 0) AS has_password
                FROM app_user WHERE id = ? FOR UPDATE
                """, (rs, row) -> new ManagedUser(
                rs.getString("id"), rs.getString("school_id"), rs.getString("login_id"),
                rs.getString("display_name"), rs.getString("identity_status"), rs.getBoolean("disabled"),
                rs.getString("account_status"), rs.getString("eligibility_source"), rs.getInt("revision"),
                rs.getBoolean("has_password")), userId).stream().findFirst();
    }

    public Optional<ManagedUser> findUser(String userId) {
        return jdbc.query("""
                SELECT id, school_id, login_id, display_name, identity_status, disabled, account_status,
                       eligibility_source, revision,
                       (password_hash IS NOT NULL AND length(password_hash) > 0) AS has_password
                FROM app_user WHERE id = ?
                """, (rs, row) -> new ManagedUser(
                rs.getString("id"), rs.getString("school_id"), rs.getString("login_id"),
                rs.getString("display_name"), rs.getString("identity_status"), rs.getBoolean("disabled"),
                rs.getString("account_status"), rs.getString("eligibility_source"), rs.getInt("revision"),
                rs.getBoolean("has_password")), userId).stream().findFirst();
    }

    public int updateAccount(String userId, int expectedRevision, String fromStatus, boolean fromDisabled,
                             String toStatus, boolean toDisabled, String eligibility) {
        return jdbc.update("""
                UPDATE app_user
                SET account_status = ?, disabled = ?, eligibility_source = ?,
                    revision = revision + 1, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND revision = ? AND account_status = ? AND disabled = ?
                """, toStatus, toDisabled, eligibility, userId, expectedRevision, fromStatus, fromDisabled);
    }

    public void insertReauth(String id, String tokenHash, String actorId, String sessionId, String operationId,
                             String action, String targetType, String targetId, Integer expectedRevision,
                             String precondition, String digest, Instant expiresAt) {
        jdbc.update("""
                INSERT INTO admin_reauthentication
                    (id, token_hash, actor_id, session_id, operation_id, action, target_type, target_id,
                     expected_revision, precondition, payload_digest, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, tokenHash, actorId, sessionId, operationId, action, targetType, targetId,
                expectedRevision, precondition, digest, ts(expiresAt));
    }

    public Optional<ReauthRow> lockReauth(String tokenHash) {
        return jdbc.query("""
                SELECT id, actor_id, session_id, operation_id, action, target_type, target_id,
                       expected_revision, precondition, payload_digest, expires_at, consumed_at
                FROM admin_reauthentication WHERE token_hash = ? FOR UPDATE
                """, (rs, row) -> new ReauthRow(
                rs.getString("id"), rs.getString("actor_id"), rs.getString("session_id"),
                rs.getString("operation_id"), rs.getString("action"), rs.getString("target_type"),
                rs.getString("target_id"), (Integer) rs.getObject("expected_revision"),
                rs.getString("precondition"), rs.getString("payload_digest"),
                rs.getObject("expires_at", OffsetDateTime.class).toInstant(),
                rs.getObject("consumed_at", OffsetDateTime.class) == null ? null
                        : rs.getObject("consumed_at", OffsetDateTime.class).toInstant()), tokenHash)
                .stream().findFirst();
    }

    /**
     * 同事务内原子核销：除了未使用之外，还要求核销时仍未过期。
     * 校验与核销之间可能等待业务行锁很久，过期判定必须以核销这一刻为准。
     */
    public int consumeReauth(String id, Instant consumedAt, Instant now) {
        return jdbc.update("""
                UPDATE admin_reauthentication
                SET consumed_at = ?
                WHERE id = ? AND consumed_at IS NULL AND expires_at > ?
                """, ts(consumedAt), id, ts(now));
    }

    public void lockOperation(String actorId, String operationId) {
        jdbc.query("SELECT pg_advisory_xact_lock(?)", rs -> null,
                cn.gulupai.server.common.security.Hashing.advisoryLockKey(
                        "ADMIN-OP|" + actorId + "|" + operationId));
    }

    public Optional<StoredOperation> findOperation(String actorId, String operationId) {
        return jdbc.query("""
                SELECT operation_id, action, target_type, target_id, payload_digest, resulting_revision,
                       audit_id, receipt_json::text AS receipt_json
                FROM admin_operation WHERE actor_id = ? AND operation_id = ?
                """, (rs, row) -> new StoredOperation(
                rs.getString("operation_id"), rs.getString("action"), rs.getString("target_type"),
                rs.getString("target_id"), rs.getString("payload_digest"), rs.getInt("resulting_revision"),
                rs.getString("audit_id"), rs.getString("receipt_json")), actorId, operationId)
                .stream().findFirst();
    }

    public void insertOperation(String actorId, String operationId, String action, String targetType,
                                String targetId, String digest, int resultingRevision, String auditId,
                                String receiptJson) {
        jdbc.update("""
                INSERT INTO admin_operation
                    (actor_id, operation_id, action, target_type, target_id, payload_digest,
                     status, resulting_revision, audit_id, receipt_json)
                VALUES (?, ?, ?, ?, ?, ?, 'COMMITTED', ?, ?, ?::jsonb)
                """, actorId, operationId, action, targetType, targetId, digest, resultingRevision,
                auditId, receiptJson);
    }

    public Optional<TargetRef> findApplication(String id) {
        return jdbc.query("""
                SELECT id, user_id, school_id, status, revision
                FROM registration_application WHERE id = ?
                """, (rs, row) -> new TargetRef(rs.getString("id"), rs.getString("user_id"),
                rs.getString("school_id"), rs.getString("status"), rs.getInt("revision")), id)
                .stream().findFirst();
    }

    public Optional<TargetRef> findClaimTarget(String id) {
        return jdbc.query("""
                SELECT c.id, c.user_id, s.school_id, c.status, c.revision
                FROM teacher_claim c JOIN square s ON s.id = c.square_id
                WHERE c.id = ?
                """, (rs, row) -> new TargetRef(rs.getString("id"), rs.getString("user_id"),
                rs.getString("school_id"), rs.getString("status"), rs.getInt("revision")), id)
                .stream().findFirst();
    }

    public Optional<TargetRef> findClassGrantTarget(String id) {
        return jdbc.query("""
                SELECT g.id, g.user_id, s.school_id, g.status, g.revision
                FROM class_grant g JOIN square s ON s.id = g.square_id
                WHERE g.id = ?
                """, (rs, row) -> new TargetRef(rs.getString("id"), rs.getString("user_id"),
                rs.getString("school_id"), rs.getString("status"), rs.getInt("revision")), id)
                .stream().findFirst();
    }

    public List<String> userIds(boolean platform, String grantId, String status, String query) {
        Query queryParts = userFilter(platform, grantId, status, query);
        return jdbc.query("SELECT u.id FROM app_user u WHERE " + queryParts.sql()
                        + " ORDER BY u.created_at DESC, u.id DESC LIMIT 5001",
                (rs, row) -> rs.getString("id"), queryParts.args().toArray());
    }

    public List<UserListRow> usersByIds(List<String> ids) {
        return usersByIds(ids, "", List.of());
    }

    /**
     * 续页按当前范围重载。游标快照只保存 ID；账号转校或授权范围收缩后，
     * 旧快照里的 ID 也不能再读到范围外的行。
     */
    public List<UserListRow> usersByIds(boolean platform, String grantId, List<String> ids) {
        List<Object> scopeArgs = new ArrayList<>();
        String scope = " AND " + schoolPredicate("u.school_id", platform);
        if (!platform) {
            scopeArgs.add(grantId);
        }
        return usersByIds(ids, scope, scopeArgs);
    }

    private List<UserListRow> usersByIds(List<String> ids, String scope, List<Object> scopeArgs) {
        if (ids.isEmpty()) {
            return List.of();
        }
        List<Object> args = new ArrayList<>(ids);
        args.addAll(scopeArgs);
        return jdbc.query("""
                SELECT u.id, u.display_name, u.login_id, u.school_id, u.account_status, u.identity_status,
                       u.disabled, u.revision
                FROM app_user u WHERE u.id IN (""" + placeholders(ids.size()) + ")" + scope,
                (rs, row) -> new UserListRow(rs.getString("id"), rs.getString("display_name"),
                        rs.getString("login_id"), rs.getString("school_id"), rs.getString("account_status"),
                        rs.getString("identity_status"), rs.getBoolean("disabled"), rs.getInt("revision")),
                args.toArray());
    }

    public List<String> platformCapabilities(String userId) {
        return jdbc.query("""
                SELECT c.capability
                FROM platform_admin_capability c
                JOIN platform_admin_grant g ON g.id = c.grant_id
                WHERE g.user_id = ? AND g.status = 'ACTIVE'
                ORDER BY c.capability
                """, (rs, row) -> rs.getString("capability"), userId);
    }

    public int activeGrantCount(String userId, Instant now) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM class_grant
                WHERE user_id = ? AND status = 'ACTIVE' AND expires_at > ?
                """, Integer.class, userId, ts(now));
        return count == null ? 0 : count;
    }

    /**
     * 账号详情里的助理授权计数：只统计当前操作者有权查看的课程。教学/助理关系可能跨校，
     * 账号所属学校通过并不代表其全部课程关系都在范围内。
     */
    public int activeGrantCount(boolean platform, String grantId, String userId, Instant now) {
        List<Object> args = new ArrayList<>();
        args.add(userId);
        args.add(ts(now));
        StringBuilder sql = new StringBuilder("""
                SELECT count(*) FROM class_grant g
                JOIN square s ON s.id = g.square_id
                WHERE g.user_id = ? AND g.status = 'ACTIVE' AND g.expires_at > ?
                """);
        appendScope(sql, args, "s.school_id", platform, grantId);
        Integer count = jdbc.queryForObject(sql.toString(), Integer.class, args.toArray());
        return count == null ? 0 : count;
    }

    public int teachingCount(String userId) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM teacher_assignment WHERE user_id = ? AND active
                """, Integer.class, userId);
        return count == null ? 0 : count;
    }

    public int teachingCount(boolean platform, String grantId, String userId) {
        List<Object> args = new ArrayList<>();
        args.add(userId);
        StringBuilder sql = new StringBuilder("""
                SELECT count(*) FROM teacher_assignment ta
                JOIN square s ON s.id = ta.square_id
                WHERE ta.user_id = ? AND ta.active
                """);
        appendScope(sql, args, "s.school_id", platform, grantId);
        Integer count = jdbc.queryForObject(sql.toString(), Integer.class, args.toArray());
        return count == null ? 0 : count;
    }

    public List<String> applicationIds(boolean platform, String grantId, String dbStatus) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                SELECT a.id FROM registration_application a
                WHERE a.requested_role = 'TEACHER'
                """);
        if (dbStatus != null) {
            sql.append(" AND a.status = ?");
            args.add(dbStatus);
        }
        sql.append(" AND ").append(schoolPredicate("a.school_id", platform));
        if (!platform) {
            args.add(grantId);
        }
        sql.append(" ORDER BY a.created_at DESC, a.id DESC LIMIT 5001");
        return jdbc.query(sql.toString(), (rs, row) -> rs.getString("id"), args.toArray());
    }

    public List<ApplicationRow> applicationsByIds(List<String> ids) {
        return applicationsByIds(ids, "", List.of());
    }

    /** 续页按当前范围重载，防止游标快照保留转校或范围收缩前的申请行。 */
    public List<ApplicationRow> applicationsByIds(boolean platform, String grantId, List<String> ids) {
        List<Object> scopeArgs = new ArrayList<>();
        String scope = " AND " + schoolPredicate("a.school_id", platform);
        if (!platform) {
            scopeArgs.add(grantId);
        }
        return applicationsByIds(ids, scope, scopeArgs);
    }

    private List<ApplicationRow> applicationsByIds(List<String> ids, String scope, List<Object> scopeArgs) {
        if (ids.isEmpty()) {
            return List.of();
        }
        List<Object> args = new ArrayList<>(ids);
        args.addAll(scopeArgs);
        return jdbc.query("""
                SELECT a.id, a.user_id, u.display_name, a.created_at, a.status, a.revision, a.school_id,
                       a.teacher_application, a.reviewed_by, a.review_reason, a.reviewed_at
                FROM registration_application a
                JOIN app_user u ON u.id = a.user_id
                WHERE a.id IN (""" + placeholders(ids.size()) + ")" + scope,
                (rs, row) -> new ApplicationRow(
                        rs.getString("id"), rs.getString("user_id"), rs.getString("display_name"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                        rs.getString("status"), rs.getInt("revision"), rs.getString("school_id"),
                        rs.getString("teacher_application"), rs.getString("reviewed_by"),
                        rs.getString("review_reason"),
                        rs.getObject("reviewed_at", OffsetDateTime.class) == null ? null
                                : rs.getObject("reviewed_at", OffsetDateTime.class).toInstant()),
                args.toArray());
    }

    public String displayName(String userId) {
        List<String> names = jdbc.query("SELECT display_name FROM app_user WHERE id = ?",
                (rs, row) -> rs.getString("display_name"), userId);
        return names.isEmpty() ? "" : names.getFirst();
    }

    public List<String> courseIds(boolean platform, String grantId, String schoolId, String termId, String query) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT s.id FROM square s WHERE s.status = 'ACTIVE' AND ");
        sql.append(schoolPredicate("s.school_id", platform));
        if (!platform) {
            args.add(grantId);
        }
        if (schoolId != null) {
            sql.append(" AND s.school_id = ?");
            args.add(schoolId);
        }
        if (termId != null) {
            sql.append(" AND s.term_id = ?");
            args.add(termId);
        }
        if (query != null && !query.isEmpty()) {
            sql.append(" AND (s.name ILIKE ? ESCAPE '\\' OR s.square_no ILIKE ? ESCAPE '\\')");
            String like = like(query);
            args.add(like);
            args.add(like);
        }
        sql.append(" ORDER BY s.created_at DESC, s.id DESC LIMIT 5001");
        return jdbc.query(sql.toString(), (rs, row) -> rs.getString("id"), args.toArray());
    }

    public List<CourseRow> coursesByIds(List<String> ids) {
        return coursesByIds(ids, "", List.of());
    }

    /** 续页按当前范围重载；课程转校或范围收缩后，旧快照不能继续读出。 */
    public List<CourseRow> coursesByIds(boolean platform, String grantId, List<String> ids) {
        List<Object> scopeArgs = new ArrayList<>();
        String scope = " AND " + schoolPredicate("s.school_id", platform);
        if (!platform) {
            scopeArgs.add(grantId);
        }
        return coursesByIds(ids, scope, scopeArgs);
    }

    private List<CourseRow> coursesByIds(List<String> ids, String scope, List<Object> scopeArgs) {
        if (ids.isEmpty()) {
            return List.of();
        }
        List<Object> args = new ArrayList<>(ids);
        args.addAll(scopeArgs);
        return jdbc.query("""
                SELECT s.id, s.name, s.square_no, s.school_id, s.term_id,
                       COALESCE(tu.id, '') AS teacher_id,
                       COALESCE(tu.display_name, '') AS teacher_name,
                       CASE
                           WHEN EXISTS (
                               SELECT 1 FROM teacher_assignment ta
                               WHERE ta.square_id = s.id AND ta.active) THEN 'APPROVED'
                           WHEN EXISTS (
                               SELECT 1 FROM teacher_claim c
                               WHERE c.square_id = s.id AND c.status = 'PENDING') THEN 'PENDING'
                           ELSE 'NONE'
                       END AS claim_status,
                       (SELECT count(*) FROM teacher_claim c
                        WHERE c.square_id = s.id AND c.status = 'PENDING') AS pending_claims
                FROM square s
                LEFT JOIN teacher_assignment ta ON ta.square_id = s.id AND ta.active
                LEFT JOIN app_user tu ON tu.id = ta.user_id
                WHERE s.id IN (""" + placeholders(ids.size()) + ")" + scope,
                (rs, row) -> new CourseRow(rs.getString("id"), rs.getString("name"), rs.getString("square_no"),
                        rs.getString("school_id"), rs.getString("term_id"), rs.getString("teacher_id"),
                        rs.getString("teacher_name"), rs.getString("claim_status"), rs.getInt("pending_claims")),
                args.toArray());
    }

    public Optional<CourseRow> course(String squareId) {
        List<CourseRow> rows = coursesByIds(List.of(squareId));
        return rows.stream().findFirst();
    }

    public String squareSchool(String squareId) {
        List<String> schools = jdbc.query("SELECT school_id FROM square WHERE id = ?",
                (rs, row) -> rs.getString("school_id"), squareId);
        return schools.isEmpty() ? null : schools.getFirst();
    }

    public List<ClaimRow> claimsForSquare(String squareId) {
        return jdbc.query("""
                SELECT c.id, c.user_id, u.display_name, c.message, c.status, c.created_at, c.revision
                FROM teacher_claim c JOIN app_user u ON u.id = c.user_id
                WHERE c.square_id = ? AND c.status IN ('PENDING', 'APPROVED', 'REJECTED')
                ORDER BY c.created_at DESC, c.id DESC
                """, (rs, row) -> new ClaimRow(rs.getString("id"), rs.getString("user_id"),
                rs.getString("display_name"), rs.getString("message"), rs.getString("status"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(), rs.getInt("revision")), squareId);
    }

    public List<AssistantGrantRow> grantsForSquare(String squareId) {
        return jdbc.query("""
                SELECT g.id, g.user_id, u.display_name, g.capabilities_json::text AS capabilities_json,
                       g.status, g.expires_at, g.revision
                FROM class_grant g JOIN app_user u ON u.id = g.user_id
                WHERE g.square_id = ?
                ORDER BY g.created_at, g.id
                """, (rs, row) -> new AssistantGrantRow(rs.getString("id"), rs.getString("user_id"),
                rs.getString("display_name"), rs.getString("capabilities_json"), rs.getString("status"),
                rs.getObject("expires_at", OffsetDateTime.class).toInstant(), rs.getInt("revision")), squareId);
    }

    /**
     * 账号详情里的有效助理授权。除账号所属学校外，还要按课程真实 school_id 过滤：
     * 学校范围操作者不能借账号详情读到其它学校课程的名称、能力或期限。
     */
    public List<UserCourseGrantRow> activeGrantsForUser(boolean platform, String grantId, String userId,
                                                        Instant now) {
        List<Object> args = new ArrayList<>();
        args.add(userId);
        args.add(ts(now));
        StringBuilder sql = new StringBuilder("""
                SELECT g.square_id, s.name AS course_name, g.capabilities_json::text AS capabilities_json,
                       g.status, g.expires_at
                FROM class_grant g JOIN square s ON s.id = g.square_id
                WHERE g.user_id = ? AND g.status = 'ACTIVE' AND g.expires_at > ?
                """);
        appendScope(sql, args, "s.school_id", platform, grantId);
        sql.append(" ORDER BY g.updated_at DESC, g.id");
        return jdbc.query(sql.toString(), (rs, row) -> new UserCourseGrantRow(
                rs.getString("square_id"), rs.getString("course_name"), rs.getString("capabilities_json"),
                rs.getString("status"), rs.getObject("expires_at", OffsetDateTime.class).toInstant()),
                args.toArray());
    }

    public List<TeachingRow> teachingForUser(boolean platform, String grantId, String userId) {
        List<Object> args = new ArrayList<>();
        args.add(userId);
        StringBuilder sql = new StringBuilder("""
                SELECT s.id, s.name,
                       CASE
                           WHEN EXISTS (
                               SELECT 1 FROM teacher_claim c
                               WHERE c.square_id = s.id AND c.user_id = ta.user_id AND c.status = 'PENDING')
                               THEN 'PENDING'
                           ELSE 'APPROVED'
                       END AS claim_status
                FROM teacher_assignment ta JOIN square s ON s.id = ta.square_id
                WHERE ta.user_id = ? AND ta.active
                """);
        appendScope(sql, args, "s.school_id", platform, grantId);
        sql.append(" ORDER BY ta.started_at, s.id");
        return jdbc.query(sql.toString(), (rs, row) -> new TeachingRow(rs.getString("id"), rs.getString("name"),
                rs.getString("claim_status")), args.toArray());
    }

    /**
     * 账号详情里的“管理记录”同样只取已登记的管理事件。登录、资料修改等普通业务事件
     * 虽然也以 {@code USER} 为资源类型，但不属于管理投影，不能因为查看账号就被顺带读出。
     */
    public List<AuditHead> managementForUser(String userId) {
        List<String> actions = ManagementAuditEvent.actionsFor("USER");
        StringBuilder sql = new StringBuilder("""
                SELECT id, action, at FROM audit_event
                WHERE resource_type = 'USER' AND resource_id = ?
                  AND action IN (""");
        sql.append(placeholders(actions.size()));
        sql.append("""
                )
                ORDER BY at DESC, id DESC
                LIMIT 20
                """);
        List<Object> args = new ArrayList<>();
        args.add(userId);
        args.addAll(actions);
        return jdbc.query(sql.toString(), (rs, row) -> new AuditHead(rs.getString("id"), rs.getString("action"),
                null, null, null, null, null, null,
                rs.getObject("at", OffsetDateTime.class).toInstant()), args.toArray());
    }

    public int countPendingApplications(boolean platform, String grantId) {
        return count("""
                SELECT count(*) FROM registration_application a
                WHERE a.status = 'PENDING_REVIEW' AND a.requested_role = 'TEACHER' AND
                """ + schoolPredicate("a.school_id", platform), platform, grantId);
    }

    public int countPendingClaims(boolean platform, String grantId) {
        return count("""
                SELECT count(*) FROM teacher_claim c
                JOIN square s ON s.id = c.square_id
                WHERE c.status = 'PENDING' AND
                """ + schoolPredicate("s.school_id", platform), platform, grantId);
    }

    public int countExceptions(boolean platform, String grantId) {
        return count("""
                SELECT count(*) FROM app_user u
                WHERE (
                    u.eligibility_source LIKE 'LEGACY_DISABLED%'
                    OR u.eligibility_source = 'IMPORT_DISABLED'
                    OR (u.disabled = true AND u.account_status <> 'DISABLED')
                ) AND
                """ + schoolPredicate("u.school_id", platform), platform, grantId);
    }

    public List<AuditHead> recentAudits(boolean platform, String grantId, int limit) {
        List<String> ids = auditIds(platform, grantId);
        if (ids.size() > limit) {
            ids = new ArrayList<>(ids.subList(0, limit));
        }
        Map<String, AuditHead> byId = new java.util.LinkedHashMap<>();
        for (AuditHead row : auditsByIds(platform, grantId, ids)) {
            byId.put(row.id(), row);
        }
        List<AuditHead> ordered = new ArrayList<>();
        for (String id : ids) {
            AuditHead row = byId.get(id);
            if (row != null) {
                ordered.add(row);
            }
        }
        return ordered;
    }

    /** 只返回已登记管理事件中、真实目标范围可见的 ID；total 与游标都基于该集合。 */
    public List<String> auditIds(boolean platform, String grantId) {
        List<Object> args = new ArrayList<>();
        String sql = auditSelect() + " WHERE " + auditAdmissionPredicate(args) + " AND "
                + auditScopePredicate(platform, args, grantId)
                + " ORDER BY e.at DESC, e.id DESC LIMIT 5001";
        return jdbc.query(sql, (rs, row) -> rs.getString("id"), args.toArray());
    }

    /**
     * 按 ID 加载也套用同一白名单与范围条件：修复前生成的游标即使保存了全表 ID，
     * 也不能借此读到当前授权之外的事件。
     */
    public List<AuditHead> auditsByIds(boolean platform, String grantId, List<String> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        List<Object> args = new ArrayList<>();
        String sql = auditSelect() + " WHERE " + auditAdmissionPredicate(args) + " AND "
                + auditScopePredicate(platform, args, grantId)
                + " AND e.id IN (" + placeholders(ids.size()) + ")";
        args.addAll(ids);
        return jdbc.query(sql, (rs, row) -> new AuditHead(
                        rs.getString("id"), rs.getString("action"), rs.getString("actor_id"),
                        rs.getString("actor_name"), rs.getString("actor_roles"),
                        rs.getString("resource_type"), rs.getString("resource_id"),
                        rs.getString("scope_school"), rs.getString("reason"),
                        rs.getString("request_id"),
                        rs.getObject("at", OffsetDateTime.class).toInstant()),
                args.toArray());
    }

    /**
     * 回放只核对“当前可见范围”，需要从已提交操作自身的事实追溯原授权：
     * 操作行保存的审计 ID 指向真实的 {@code PLATFORM_ADMIN_GRANT} 资源，
     * 因此即使后来撤销并重新授予，也不会把旧操作错套到新授权上。
     */
    public Optional<String> auditResourceId(String auditId) {
        if (auditId == null || auditId.isBlank()) {
            return Optional.empty();
        }
        List<String> rows = jdbc.query("SELECT resource_id FROM audit_event WHERE id = ?",
                (rs, row) -> rs.getString("resource_id"), auditId);
        return rows.stream().filter(value -> value != null && !value.isBlank()).findFirst();
    }

    /**
     * 读取授权当时登记的范围，**不要求授权仍为 ACTIVE**：合法撤销后仍要能追溯原操作。
     */
    public Optional<GrantScope> grantScope(String grantId) {
        List<String[]> rows = jdbc.query(
                "SELECT scope_type, school_id FROM platform_admin_scope WHERE grant_id = ? ORDER BY school_id",
                (rs, row) -> new String[]{rs.getString("scope_type"), rs.getString("school_id")}, grantId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        boolean platform = rows.stream().anyMatch(row -> "PLATFORM".equals(row[0]));
        List<String> schools = new ArrayList<>();
        for (String[] row : rows) {
            if ("SCHOOL".equals(row[0]) && row[1] != null && !row[1].isBlank()) {
                schools.add(row[1]);
            }
        }
        return Optional.of(new GrantScope(platform, List.copyOf(schools)));
    }

    /**
     * 现态目录：每个用户只返回一条。优先当前 ACTIVE 授权；没有当前授权时返回最近一次
     * 已撤销授权。撤销后重新授予会产生新的授权行，不能把同一用户列成多条。
     *
     * <p>除授权状态外，这里把“这条授权当下是否真的可治理”所需的事实一并带出：账号资格、
     * 凭据存在性，以及按同一个选中的 {@code g.id} 核验的角色/能力/范围。能力与范围必须用
     * 当前授权行，否则历史授权的残留能力会被误当成新授权的能力。全部用 EXISTS 表达，
     * 避免连接能力/范围表后倍增行数让 DISTINCT ON 选到不稳定的一条。
     */
    public List<SuperAdminRow> superAdmins() {
        return jdbc.query("""
                SELECT * FROM (
                    SELECT DISTINCT ON (g.user_id)
                           g.id AS grant_id, g.user_id, u.display_name, g.status, g.granted_at, g.granted_by,
                           g.revision, u.account_status, u.disabled, u.identity_status, u.eligibility_source,
                           (u.password_hash IS NOT NULL AND length(u.password_hash) > 0) AS has_password,
                           EXISTS (
                               SELECT 1 FROM user_role r
                               WHERE r.user_id = g.user_id AND r.role = 'SUPER_ADMIN'
                           ) AS role_held,
                           EXISTS (
                               SELECT 1 FROM platform_admin_capability c
                               WHERE c.grant_id = g.id
                           ) AS has_capabilities,
                           EXISTS (
                               SELECT 1 FROM platform_admin_scope s
                               WHERE s.grant_id = g.id
                                 AND (s.scope_type = 'PLATFORM'
                                      OR (s.scope_type = 'SCHOOL' AND s.school_id IS NOT NULL))
                           ) AS has_scope
                    FROM platform_admin_grant g
                    JOIN app_user u ON u.id = g.user_id
                    ORDER BY g.user_id,
                             CASE WHEN g.status = 'ACTIVE' THEN 0 ELSE 1 END,
                             g.granted_at DESC, g.id DESC
                ) d
                ORDER BY d.granted_at, d.user_id
                """, (rs, row) -> new SuperAdminRow(
                rs.getString("grant_id"), rs.getString("user_id"), rs.getString("display_name"),
                rs.getString("status"),
                rs.getObject("granted_at", OffsetDateTime.class).toInstant(),
                rs.getString("granted_by") == null ? "" : rs.getString("granted_by"),
                rs.getInt("revision"), rs.getString("account_status"), rs.getBoolean("disabled"),
                rs.getString("identity_status"), rs.getString("eligibility_source"),
                rs.getBoolean("has_password"), rs.getBoolean("role_held"),
                rs.getBoolean("has_capabilities"), rs.getBoolean("has_scope")));
    }

    private ActiveGrant loadGrant(GrantHead head) {
        List<String> capabilities = jdbc.query("""
                SELECT capability FROM platform_admin_capability WHERE grant_id = ? ORDER BY capability
                """, (rs, row) -> rs.getString("capability"), head.id());
        List<ScopeRow> scopes = jdbc.query("""
                SELECT scope_type, school_id FROM platform_admin_scope WHERE grant_id = ?
                """, (rs, row) -> new ScopeRow(rs.getString("scope_type"), rs.getString("school_id")), head.id());
        boolean platform = scopes.stream().anyMatch(scope -> "PLATFORM".equals(scope.type()));
        List<String> schools = scopes.stream().map(ScopeRow::schoolId).filter(id -> id != null && !id.isBlank()).toList();
        return new ActiveGrant(head.id(), head.userId(), head.revision(), platform, schools, capabilities);
    }

    private Query userFilter(boolean platform, String grantId, String status, String query) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder(schoolPredicate("u.school_id", platform));
        if (!platform) {
            args.add(grantId);
        }
        if (status != null) {
            sql.append(" AND u.account_status = ?");
            args.add(status);
        }
        if (query != null && !query.isEmpty()) {
            sql.append(" AND (u.display_name ILIKE ? ESCAPE '\\' OR u.login_id ILIKE ? ESCAPE '\\')");
            String like = like(query);
            args.add(like);
            args.add(like);
        }
        return new Query(sql.toString(), args);
    }

    private static String schoolPredicate(String column, boolean platform) {
        if (platform) {
            return "TRUE";
        }
        return column + " IN (SELECT school_id FROM platform_admin_scope WHERE grant_id = ? AND scope_type = 'SCHOOL')";
    }

    /** 在已有 WHERE 之后追加范围条件；学校范围授权把 grantId 作为下一个占位符参数。 */
    private static void appendScope(StringBuilder sql, List<Object> args, String column, boolean platform,
                                    String grantId) {
        sql.append(" AND ").append(schoolPredicate(column, platform));
        if (!platform) {
            args.add(grantId);
        }
    }

    /** 白名单条件：只有 {@link ManagementAuditEvent} 登记过的 action + resourceType 组合才进入。 */
    private static String auditAdmissionPredicate(List<Object> args) {
        List<ManagementAuditEvent.Entry> entries = ManagementAuditEvent.entries();
        StringBuilder sql = new StringBuilder("(");
        for (int i = 0; i < entries.size(); i++) {
            ManagementAuditEvent.Entry entry = entries.get(i);
            if (i > 0) {
                sql.append(" OR ");
            }
            sql.append("(e.action = ? AND e.resource_type = ?)");
            args.add(entry.storedAction());
            args.add(entry.resourceType());
        }
        return sql.append(")").toString();
    }

    /**
     * 范围条件：按真实目标关系计算，删除原来的 {@code actor.school_id} 兜底。
     *
     * <p>平台授权事件使用该 grant 自己的 scope：全平台授权记作 {@code PLATFORM}，
     * 学校范围授权记为其学校，因此学校范围管理员看不到全平台授权的授撤事件。</p>
     */
    private static String auditScopePredicate(boolean platform, List<Object> args, String grantId) {
        if (platform) {
            return "e.scope_school <> ''";
        }
        args.add(grantId);
        return "e.scope_school IN (SELECT school_id FROM platform_admin_scope"
                + " WHERE grant_id = ? AND scope_type = 'SCHOOL')";
    }

    private static String auditSelect() {
        return "SELECT * FROM (" + auditFrom() + ") e";
    }

    private static String auditFrom() {
        return """
                SELECT e.id, e.action, e.actor_id, COALESCE(actor.display_name, '') AS actor_name,
                       COALESCE((
                           SELECT string_agg(r.role, ',' ORDER BY r.role)
                           FROM user_role r WHERE r.user_id = e.actor_id
                       ), '') AS actor_roles,
                       e.resource_type, e.resource_id, e.reason, e.request_id, e.at,
                       CASE
                           WHEN e.resource_type = 'USER' THEN COALESCE(target_user.school_id, '')
                           WHEN e.resource_type = 'REGISTRATION' THEN COALESCE(reg.school_id, '')
                           WHEN e.resource_type = 'TEACHER_CLAIM' THEN COALESCE(claim_square.school_id, '')
                           WHEN e.resource_type = 'CLASS_GRANT' THEN COALESCE(grant_square.school_id, '')
                           WHEN e.resource_type = 'PLATFORM_ADMIN_GRANT' THEN
                               CASE WHEN grant_scope.platform_scope THEN 'PLATFORM'
                                    ELSE COALESCE(grant_scope.school_id, '') END
                           ELSE ''
                       END AS scope_school
                FROM audit_event e
                LEFT JOIN app_user target_user ON e.resource_type = 'USER' AND target_user.id = e.resource_id
                LEFT JOIN registration_application reg ON e.resource_type = 'REGISTRATION' AND reg.id = e.resource_id
                LEFT JOIN teacher_claim claim ON e.resource_type = 'TEACHER_CLAIM' AND claim.id = e.resource_id
                LEFT JOIN square claim_square ON claim_square.id = claim.square_id
                LEFT JOIN class_grant cg ON e.resource_type = 'CLASS_GRANT' AND cg.id = e.resource_id
                LEFT JOIN square grant_square ON grant_square.id = cg.square_id
                LEFT JOIN platform_admin_grant pg
                    ON e.resource_type = 'PLATFORM_ADMIN_GRANT' AND pg.id = e.resource_id
                LEFT JOIN LATERAL (
                    SELECT
                        (SELECT ps.school_id FROM platform_admin_scope ps
                          WHERE ps.grant_id = pg.id AND ps.scope_type = 'SCHOOL'
                          ORDER BY ps.school_id LIMIT 1) AS school_id,
                        EXISTS (SELECT 1 FROM platform_admin_scope ps2
                                 WHERE ps2.grant_id = pg.id AND ps2.scope_type = 'PLATFORM') AS platform_scope
                ) grant_scope ON TRUE
                LEFT JOIN app_user actor ON actor.id = e.actor_id
                """;
    }

    private int count(String sql, boolean platform, String grantId) {
        Integer count = platform
                ? jdbc.queryForObject(sql, Integer.class)
                : jdbc.queryForObject(sql, Integer.class, grantId);
        return count == null ? 0 : count;
    }

    private static String placeholders(int size) {
        return String.join(",", java.util.Collections.nCopies(size, "?"));
    }

    private static String like(String query) {
        return "%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    private static OffsetDateTime ts(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    public record ActiveGrant(String id, String userId, int revision, boolean platform, List<String> schools,
                              List<String> capabilities) {
        public ActiveGrant {
            schools = List.copyOf(schools);
            capabilities = List.copyOf(capabilities);
        }

        public boolean covers(String schoolId) {
            return platform || (schoolId != null && schools.contains(schoolId));
        }
    }

    public record GrantHead(String id, String userId, int revision) {
    }

    public record ManagedUser(String id, String schoolId, String loginId, String displayName,
                              String identityStatus, boolean disabled, String accountStatus,
                              String eligibility, int revision, boolean hasPassword) {
    }

    public record ReauthRow(String id, String actorId, String sessionId, String operationId, String action,
                            String targetType, String targetId, Integer expectedRevision, String precondition,
                            String payloadDigest, Instant expiresAt, Instant consumedAt) {
    }

    public record StoredOperation(String operationId, String action, String targetType, String targetId,
                                  String payloadDigest, int resultingRevision, String auditId, String receiptJson) {
    }

    /** 授权登记时的范围，与授权当前状态无关，供回放追溯原操作使用。 */
    public record GrantScope(boolean platform, List<String> schools) {
        public GrantScope {
            schools = List.copyOf(schools);
        }
    }

    public record TargetRef(String id, String userId, String schoolId, String status, int revision) {
    }

    public record UserListRow(String id, String displayName, String loginId, String schoolId,
                              String accountStatus, String identityStatus, boolean disabled, int revision) {
    }

    public record ApplicationRow(String id, String userId, String applicantName, Instant submittedAt,
                                 String status, int revision, String schoolId, String teacherApplication,
                                 String reviewedBy, String reviewReason, Instant reviewedAt) {
    }

    public record CourseRow(String id, String name, String squareNo, String schoolId, String termId,
                            String teacherId, String teacherName, String claimStatus, int pendingClaims) {
    }

    public record ClaimRow(String id, String teacherId, String teacherName, String message, String status,
                           Instant submittedAt, int revision) {
    }

    /** 课程详情里的助理授权行，带真实 grantId 与版本，供 A12 使用。 */
    public record AssistantGrantRow(String id, String userId, String userName, String capabilitiesJson,
                                    String status, Instant expiresAt, int revision) {
    }

    /** 账号详情里的有效助理授权行；A03 只读投影，不暴露 grantId 与版本。 */
    public record UserCourseGrantRow(String squareId, String courseName, String capabilitiesJson,
                                     String status, Instant expiresAt) {
    }

    public record TeachingRow(String squareId, String fullCourseName, String claimStatus) {
    }

    public record AuditHead(String id, String action, String actorId, String actorName, String actorRoles,
                            String resourceType, String resourceId, String scopeSchool, String reason,
                            String requestId, Instant at) {
        public AuditHead(String id, String action, String actorId, String actorName, String actorRoles,
                         String resourceType, String resourceId, String scopeSchool,
                         Instant at) {
            this(id, action, actorId, actorName, actorRoles, resourceType, resourceId, scopeSchool, null, null, at);
        }
    }

    /**
     * A14 目录行。{@code grantId} 只用于内部诊断，不进入对外 DTO；其余布尔事实用于把
     * “真实有效”“真实停用”“残缺待核对”区分开，而不是见 ACTIVE 就投影成 ACTIVE。
     */
    public record SuperAdminRow(String grantId, String userId, String displayName, String grantStatus,
                                Instant grantedAt, String grantedBy, int revision, String accountStatus,
                                boolean disabled, String identityStatus, String eligibilitySource,
                                boolean hasPassword, boolean roleHeld, boolean hasCapabilities,
                                boolean hasScope) {
    }

    private record ScopeRow(String type, String schoolId) {
    }

    private record Query(String sql, List<Object> args) {
    }
}
