package cn.gulupai.server.governance;

import cn.gulupai.server.common.audit.AuditService;
import cn.gulupai.server.common.security.Hashing;
import cn.gulupai.server.governance.GovRepository.GrantRow;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * D01：管理员 CLI 对单个人员事项授予/撤销教师处理资格。
 * 不新增 App 管理员页面，不授予课程范围权限，也不自动接任 case。
 */
@Service
public class PersonnelCaseGrantAdminService {

    private final JdbcTemplate jdbc;
    private final GovRepository governance;
    private final AuditService audit;
    private final Clock clock;

    public PersonnelCaseGrantAdminService(JdbcTemplate jdbc, GovRepository governance,
                                          AuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.governance = governance;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public GrantResult grant(String caseId, String teacherUserId, String reason, Instant expiresAt,
                             boolean dryRun, String actorId) {
        if (blanks(caseId, teacherUserId, reason)) {
            throw new IllegalArgumentException("grant 需要 caseId、teacherUserId、reason");
        }
        Instant now = clock.instant();
        if (expiresAt != null && !expiresAt.isAfter(now)) {
            throw new IllegalArgumentException("expiresAt 必须晚于当前时间");
        }
        // 授权变更与 case 办理共用 case 行锁这个协调点：先锁 case，再进入 grant scope。
        Map<String, Object> caseRow = jdbc.queryForMap("""
                SELECT c.id, c.status, c.project_id, s.school_id
                FROM personnel_case c JOIN square s ON s.id = c.square_id
                WHERE c.id = ?
                FOR UPDATE OF c
                """, caseId);
        if ("CLOSED".equals(caseRow.get("status"))) {
            throw new IllegalArgumentException("事项已结案，不能继续授权");
        }
        String projectId = String.valueOf(caseRow.get("project_id"));
        String schoolId = String.valueOf(caseRow.get("school_id"));
        List<Map<String, Object>> users = jdbc.queryForList("""
                SELECT id, school_id, disabled, identity_status,
                       EXISTS(SELECT 1 FROM user_role r WHERE r.user_id = app_user.id AND r.role = 'TEACHER') AS teacher
                FROM app_user WHERE id = ?
                """, teacherUserId);
        if (users.isEmpty()) {
            throw new IllegalArgumentException("教师账号不存在");
        }
        Map<String, Object> user = users.getFirst();
        if (Boolean.TRUE.equals(user.get("disabled")) || !"VERIFIED".equals(user.get("identity_status"))) {
            throw new IllegalArgumentException("目标账号已停用或未核验");
        }
        if (!Boolean.TRUE.equals(user.get("teacher"))) {
            throw new IllegalArgumentException("目标用户不是已核验教师");
        }
        if (!schoolId.equals(String.valueOf(user.get("school_id")))) {
            throw new IllegalArgumentException("目标教师与事项不在同一学校范围");
        }
        if (governance.projectMember(projectId, teacherUserId)) {
            throw new IllegalArgumentException("目标教师是事项学生项目成员，必须回避");
        }

        // 同一 case/user 的 grant/revoke 用事务级 advisory 锁串行，避免唯一冲突后
        // 在已失败事务中继续查询或产生第二条有效授权。
        lockScope(caseId, teacherUserId);
        if (!dryRun) {
            governance.expireActiveGrant(caseId, teacherUserId, now);
        }
        List<GrantRow> existing = governance.activeGrant(caseId, teacherUserId, now).stream().toList();
        if (!existing.isEmpty()) {
            GrantRow row = existing.getFirst();
            return new GrantResult(row.id(), caseId, teacherUserId, row.status(), row.revision(),
                    row.expiresAt(), "EXISTS", dryRun);
        }
        String id = UUID.randomUUID().toString();
        if (!dryRun) {
            try {
                governance.insertGrant(id, caseId, teacherUserId, actorId, reason.strip(), expiresAt, now);
            } catch (DataIntegrityViolationException ex) {
                // 理论上已被 advisory 锁串行；保留为明确冲突，不在已失败事务里继续查询。
                throw new IllegalArgumentException("该事项已有有效授权");
            }
            audit.record(actorId, "PERSONNEL_CASE_GRANT", "PERSONNEL_CASE", caseId, null, null,
                    "grant " + teacherUserId + " reason=" + reason.strip());
        }
        return new GrantResult(id, caseId, teacherUserId, "ACTIVE", 1, expiresAt,
                "CREATED", dryRun);
    }

    @Transactional
    public GrantResult revoke(String grantId, int expectedRevision, String reason,
                              boolean dryRun, String actorId) {
        if (blanks(grantId, reason)) {
            throw new IllegalArgumentException("revoke 需要 grantId、reason");
        }
        GrantRow preview = governance.findGrant(grantId)
                .orElseThrow(() -> new IllegalArgumentException("授权不存在"));
        lockCase(preview.caseId());
        lockScope(preview.caseId(), preview.userId());
        GrantRow row = governance.lockGrant(grantId)
                .orElseThrow(() -> new IllegalArgumentException("授权不存在"));
        if (!"ACTIVE".equals(row.status())) {
            throw new IllegalArgumentException("授权已不是有效状态");
        }
        if (row.revision() != expectedRevision) {
            throw new IllegalArgumentException("授权版本已变化");
        }
        if (!dryRun) {
            if (governance.revokeGrant(grantId, expectedRevision, actorId, reason.strip(),
                    clock.instant()) != 1) {
                throw new IllegalArgumentException("授权版本已变化");
            }
            audit.record(actorId, "PERSONNEL_CASE_GRANT_REVOKE", "PERSONNEL_CASE", row.caseId(),
                    expectedRevision, expectedRevision + 1, "revoke " + grantId + " reason=" + reason.strip());
        }
        return new GrantResult(grantId, row.caseId(), row.userId(), "REVOKED",
                expectedRevision + 1, row.expiresAt(), "REVOKED", dryRun);
    }

    public List<GrantResult> list(String caseId) {
        if (blank(caseId)) {
            throw new IllegalArgumentException("list 需要 caseId");
        }
        return governance.grantsForCase(caseId).stream()
                .map(row -> new GrantResult(row.id(), row.caseId(), row.userId(), row.status(),
                        row.revision(), row.expiresAt(), "LIST", false))
                .toList();
    }

    private void lockCase(String caseId) {
        jdbc.queryForList("SELECT id FROM personnel_case WHERE id = ? FOR UPDATE", caseId);
    }

    private void lockScope(String caseId, String userId) {
        jdbc.queryForList("SELECT pg_advisory_xact_lock(?)",
                Hashing.advisoryLockKey("PERSONNEL_CASE_GRANT|" + caseId + "|" + userId));
    }

    private boolean blanks(String... values) {
        for (String value : values) {
            if (blank(value)) return true;
        }
        return false;
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public record GrantResult(String grantId, String caseId, String userId, String status,
                              int revision, Instant expiresAt, String action, boolean dryRun) {
        public String describe() {
            return "人员事项授权 " + action + (dryRun ? " [dry-run]" : "")
                    + " grant=" + grantId + " case=" + caseId + " user=" + userId
                    + " status=" + status + " revision=" + revision;
        }
    }
}
