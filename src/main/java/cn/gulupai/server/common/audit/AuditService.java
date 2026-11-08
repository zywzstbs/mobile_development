package cn.gulupai.server.common.audit;

import cn.gulupai.server.common.api.RequestIds;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * 审计事件只记录动作、资源与版本，不记录密码、令牌、聊天全文或请求正文。
 */
@Service
public class AuditService {

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;
    private final AdminAuditGate auditGate;

    public AuditService(JdbcTemplate jdbcTemplate, Clock clock, AdminAuditGate auditGate) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
        this.auditGate = auditGate;
    }

    @Transactional
    public void record(String actorId, String action, String resourceType, String resourceId,
                       Integer beforeRevision, Integer afterRevision, String reason) {
        recordReturningId(actorId, action, resourceType, resourceId, beforeRevision, afterRevision, reason);
    }

    /**
     * 与 {@link #record} 写同一行，并返回审计 ID。失败时由调用方事务回滚，不能留下没有审计的业务更新。
     */
    @Transactional
    public String recordReturningId(String actorId, String action, String resourceType, String resourceId,
                                    Integer beforeRevision, Integer afterRevision, String reason) {
        if (action == null || action.isBlank() || action.length() > 80) {
            throw new IllegalArgumentException("audit action 无效");
        }
        if (reason != null && reason.length() > 500) {
            throw new IllegalArgumentException("audit reason 超过500字符");
        }
        auditGate.beforePersist();
        String id = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO audit_event
                    (id, actor_id, action, resource_type, resource_id, before_revision,
                     after_revision, reason, request_id, at, revision)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)
                """,
                id,
                actorId,
                action,
                resourceType,
                resourceId,
                beforeRevision,
                afterRevision,
                reason,
                RequestIds.current(),
                OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
        return id;
    }
}
