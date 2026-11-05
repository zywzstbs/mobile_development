package cn.gulupai.server.support;

import cn.gulupai.server.common.api.ApiCode;
import cn.gulupai.server.common.api.ApiException;
import cn.gulupai.server.common.security.AuthenticatedUser;
import cn.gulupai.server.identity.persistence.UserRepository;
import cn.gulupai.server.teaching.application.CourseAuthorityService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 求助工单的当前访问资格。提问者读取本人工单不需要 SUPPORT_HANDLE。
 * 处理者必须同时满足：账号仍有效、当前有效且范围匹配的 SUPPORT_HANDLE、
 * 仍是 assignee，课程工单还要有未过期的本课教师或助理资格；助理涉及本组时回避。
 * 历史指派不能单独证明仍可访问。
 */
@Component
public class SupportTicketAccess {

    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final CourseAuthorityService courses;
    private final Clock clock;

    public SupportTicketAccess(JdbcTemplate jdbc, UserRepository users, CourseAuthorityService courses,
                               Clock clock) {
        this.jdbc = jdbc;
        this.users = users;
        this.courses = courses;
        this.clock = clock;
    }

    public boolean accountActive(String userId) {
        return userId != null && users.findById(userId)
                .filter(user -> !user.disabled() && "ACTIVE".equals(user.accountStatus()))
                .isPresent();
    }

    public void requireReadable(AuthenticatedUser principal, Map<String, Object> ticket) {
        if (canRead(principal.userId(), ticket)) {
            return;
        }
        if (recusalDeniesQualifiedHandler(principal.userId(), ticket)) {
            throw new ApiException(ApiCode.RECUSAL_REQUIRED);
        }
        throw new ApiException(ApiCode.FORBIDDEN);
    }

    public void requireHandler(AuthenticatedUser principal, Map<String, Object> ticket) {
        if (!accountActive(principal.userId()) || !principal.userId().equals(ticket.get("assigneeId"))) {
            throw new ApiException(ApiCode.FORBIDDEN);
        }
        String squareId = text(ticket.get("squareId"));
        if (!supportHandleCovers(principal.userId(), squareId)) {
            throw new ApiException(ApiCode.FORBIDDEN);
        }
        if (squareId != null && !courseStanding(principal.userId(), squareId)) {
            throw new ApiException(ApiCode.FORBIDDEN);
        }
        if (squareId != null && recused(principal.userId(), text(ticket.get("requesterId")), squareId)) {
            throw new ApiException(ApiCode.RECUSAL_REQUIRED);
        }
    }

    public boolean canRead(String userId, Map<String, Object> ticket) {
        if (!accountActive(userId) || ticket == null) {
            return false;
        }
        if (userId.equals(ticket.get("requesterId"))) {
            return true;
        }
        if (!userId.equals(ticket.get("assigneeId"))) {
            return false;
        }
        String squareId = text(ticket.get("squareId"));
        if (!supportHandleCovers(userId, squareId)) {
            return false;
        }
        if (squareId == null) {
            return true;
        }
        return courseStanding(userId, squareId)
                && !recused(userId, text(ticket.get("requesterId")), squareId);
    }

    /** 第一个参数是读者，第二个才是工单。两个都是字符串，调用时不要对调。 */
    public boolean canReadTicket(String userId, String ticketId) {
        if (ticketId == null || ticketId.isBlank()) {
            return false;
        }
        List<Map<String, Object>> rows = jdbc.query("""
                SELECT requester_id, assignee_id, square_id FROM support_ticket WHERE id = ?
                """, (rs, n) -> {
            Map<String, Object> row = new HashMap<>();
            row.put("requesterId", rs.getString("requester_id"));
            row.put("assigneeId", blankToNull(rs.getString("assignee_id")));
            row.put("squareId", blankToNull(rs.getString("square_id")));
            return row;
        }, ticketId);
        return !rows.isEmpty() && canRead(userId, rows.getFirst());
    }

    /**
     * 渠道接收人在指派之前就要具备当前资格。提问者本人不能接收自己的工单。
     */
    public boolean canReceive(String userId, String squareId, String requesterId) {
        if (!accountActive(userId) || userId.equals(requesterId)) {
            return false;
        }
        if (!supportHandleCovers(userId, squareId)) {
            return false;
        }
        if (squareId == null) {
            return true;
        }
        return courseStanding(userId, squareId) && !recused(userId, requesterId, squareId);
    }

    private boolean supportHandleCovers(String userId, String squareId) {
        Boolean covered = jdbc.queryForObject("""
                SELECT EXISTS(
                    SELECT 1 FROM operator_grant
                    WHERE user_id = ? AND capability = 'SUPPORT_HANDLE' AND active = true
                      AND (
                        scope_type = 'GLOBAL'
                        OR (?::text IS NOT NULL AND scope_type = 'SQUARE' AND scope_id = ?)
                      )
                )
                """, Boolean.class, userId, squareId, squareId);
        return Boolean.TRUE.equals(covered);
    }

    private boolean courseStanding(String userId, String squareId) {
        if (courses.isTeacher(squareId, userId)) {
            return true;
        }
        Boolean grant = jdbc.queryForObject("""
                SELECT EXISTS(
                    SELECT 1 FROM class_grant
                    WHERE square_id = ? AND user_id = ? AND status = 'ACTIVE' AND expires_at > ?
                )
                """, Boolean.class, squareId, userId,
                OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
        return Boolean.TRUE.equals(grant);
    }

    /**
     * 已经是当前指派、授权和课程资格都成立，只因同组回避而不能读。
     * 其他人仍然是普通无权，避免用回避码证明工单存在。
     */
    private boolean recusalDeniesQualifiedHandler(String userId, Map<String, Object> ticket) {
        if (!accountActive(userId) || ticket == null || userId.equals(ticket.get("requesterId"))) {
            return false;
        }
        if (!userId.equals(ticket.get("assigneeId"))) {
            return false;
        }
        String squareId = text(ticket.get("squareId"));
        if (squareId == null || !supportHandleCovers(userId, squareId) || !courseStanding(userId, squareId)) {
            return false;
        }
        return recused(userId, text(ticket.get("requesterId")), squareId);
    }

    private boolean recused(String userId, String requesterId, String squareId) {
        if (requesterId == null || courses.isTeacher(squareId, userId)) {
            return false;
        }
        Boolean sameGroup = jdbc.queryForObject("""
                SELECT EXISTS(
                    SELECT 1 FROM membership handler
                    JOIN membership requester
                      ON requester.project_id = handler.project_id
                     AND requester.user_id = ?
                     AND requester.status = 'ACTIVE'
                    JOIN project p ON p.id = handler.project_id AND p.square_id = ?
                    WHERE handler.user_id = ? AND handler.status = 'ACTIVE'
                )
                """, Boolean.class, requesterId, squareId, userId);
        return Boolean.TRUE.equals(sameGroup);
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
