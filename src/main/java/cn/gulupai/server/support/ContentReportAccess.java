package cn.gulupai.server.support;

import cn.gulupai.server.identity.persistence.UserRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 内容举报的当前读取和处理资格。历史 assignee 不能单独证明访问权。
 *
 * <p>第一轮把既有 CONTENT_REVIEW 与新增 CONTENT_REPORT_HANDLE 都视为处理能力。
 * 这里沿用这个映射，但只承认授权记录自己的范围：GLOBAL 覆盖全部举报，
 * SQUARE 只覆盖同一广场。CONTENT_REVIEW 不会被自动提升成全局授权。
 * 广场无法追溯的历史举报只接受 GLOBAL。不使用求助工单的资源类型或回避规则。
 *
 * <p>创建举报时用 {@link #lockEligibleReceiver(String)} 锁定当前仍有效的接收授权。
 * squareId 为空只匹配 GLOBAL，不会落到某个不相关的 SQUARE 授权。
 */
@Component
public class ContentReportAccess {

    private static final String COVERED = """
            g.active = true
              AND g.capability IN ('CONTENT_REPORT_HANDLE', 'CONTENT_REVIEW')
              AND (
                g.scope_type = 'GLOBAL'
                OR (?::text IS NOT NULL AND g.scope_type = 'SQUARE' AND g.scope_id = ?)
              )
            """;

    private final JdbcTemplate jdbc;
    private final UserRepository users;

    public ContentReportAccess(JdbcTemplate jdbc, UserRepository users) {
        this.jdbc = jdbc;
        this.users = users;
    }

    public boolean canRead(String userId, Map<String, Object> report) {
        if (!accountActive(userId) || report == null) {
            return false;
        }
        if (userId.equals(report.get("reporterId"))) {
            return true;
        }
        return userId.equals(report.get("assigneeId")) && canHandle(userId, report);
    }

    public boolean canHandle(String userId, Map<String, Object> report) {
        if (!accountActive(userId) || report == null) {
            return false;
        }
        String squareId = text(report.get("squareId"));
        Boolean covered = jdbc.queryForObject("""
                SELECT EXISTS(
                    SELECT 1 FROM operator_grant g
                    WHERE g.user_id = ? AND
                """ + COVERED + """
                )
                """, Boolean.class, userId, squareId, squareId);
        return Boolean.TRUE.equals(covered);
    }

    /**
     * 锁定当前能够受理该广场的授权和账号行。没有合法接收者时返回 false，不把未来可能出现的人算进去。
     */
    public boolean lockEligibleReceiver(String squareId) {
        List<Integer> rows = jdbc.query("""
                SELECT 1
                FROM operator_grant g
                JOIN app_user u ON u.id = g.user_id
                WHERE u.disabled = false AND
                """ + COVERED + """
                FOR UPDATE OF g, u
                """, (rs, row) -> 1, squareId, squareId);
        return !rows.isEmpty();
    }

    private boolean accountActive(String userId) {
        return userId != null && users.findById(userId).filter(user -> !user.disabled()).isPresent();
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }
}
