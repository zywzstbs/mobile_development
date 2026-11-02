package cn.gulupai.server.support;

import cn.gulupai.server.common.audit.AuditService;
import cn.gulupai.server.common.validation.Ids;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

/**
 * 运营授权只从受控 CLI 写入。班级范围的授权不会被投影成全局能力。
 */
@Service
public class OperatorGrantAdminService {

    static final Set<String> CAPABILITIES = Set.of(
            "HELP_ADMIN", "HELP_CONTENT_WRITE", "SUPPORT_HANDLE", "CONTENT_REVIEW");

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final Clock clock;

    public OperatorGrantAdminService(JdbcTemplate jdbc, AuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public GrantResult grant(String userId, String capability, String scopeType, String scopeId,
                             boolean active, boolean dryRun, String actorId) {
        if (!Ids.isValid(userId)) {
            throw new IllegalArgumentException("用户编号无效");
        }
        if (!CAPABILITIES.contains(capability)) {
            throw new IllegalArgumentException("不支持的运营能力: " + capability);
        }
        String storedScopeId = normalizeScope(scopeType, scopeId);
        Integer users = jdbc.queryForObject("SELECT count(*) FROM app_user WHERE id=?", Integer.class, userId);
        if (users == null || users == 0) {
            throw new IllegalArgumentException("用户不存在: " + userId);
        }
        String existing = findGrant(userId, capability, scopeType, storedScopeId);
        String action = existing == null ? "CREATED" : "UPDATED";
        if (!dryRun) {
            OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
            if (existing == null) {
                jdbc.update("""
                        INSERT INTO operator_grant
                            (id, user_id, scope_type, scope_id, capability, active, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """, UUID.randomUUID().toString(), userId, scopeType, storedScopeId,
                        capability, active, now);
            } else {
                jdbc.update("UPDATE operator_grant SET active=? WHERE id=?", active, existing);
            }
            audit.record(actorId, "OPERATOR_GRANT", "OPERATOR_GRANT", userId, null, null,
                    capability + " " + scopeType + " active=" + active);
        }
        return new GrantResult(userId, capability, scopeType, storedScopeId, active, action, dryRun);
    }

    private String normalizeScope(String scopeType, String scopeId) {
        if ("GLOBAL".equals(scopeType)) {
            if (scopeId != null && !scopeId.isBlank()) {
                throw new IllegalArgumentException("全局授权不能填写范围编号");
            }
            return null;
        }
        if ("SQUARE".equals(scopeType)) {
            if (!Ids.isValid(scopeId)) {
                throw new IllegalArgumentException("广场范围授权必须填写有效 squareId");
            }
            Integer squares = jdbc.queryForObject("SELECT count(*) FROM square WHERE id=?", Integer.class, scopeId);
            if (squares == null || squares == 0) {
                throw new IllegalArgumentException("广场不存在: " + scopeId);
            }
            return scopeId;
        }
        throw new IllegalArgumentException("范围只能是 GLOBAL 或 SQUARE");
    }

    private String findGrant(String userId, String capability, String scopeType, String scopeId) {
        return jdbc.query("""
                SELECT id FROM operator_grant
                WHERE user_id=? AND capability=? AND scope_type=?
                  AND scope_id IS NOT DISTINCT FROM ?
                """, (rs, n) -> rs.getString("id"), userId, capability, scopeType, scopeId)
                .stream().findFirst().orElse(null);
    }

    public record GrantResult(String userId, String capability, String scopeType, String scopeId,
                              boolean active, String action, boolean dryRun) {
        public String describe() {
            return "运营授权：" + action + " user=" + userId + " capability=" + capability
                    + " scope=" + scopeType + " active=" + active + " dryRun=" + dryRun;
        }
    }
}
