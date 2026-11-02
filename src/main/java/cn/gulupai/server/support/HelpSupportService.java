package cn.gulupai.server.support;

import cn.gulupai.server.common.api.ApiCode;
import cn.gulupai.server.common.api.ApiException;
import cn.gulupai.server.common.audit.AuditService;
import cn.gulupai.server.common.idempotency.IdempotencyRequest;
import cn.gulupai.server.common.idempotency.IdempotencyService;
import cn.gulupai.server.common.security.AuthenticatedUser;
import cn.gulupai.server.common.security.CurrentUserPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class HelpSupportService {

    private static final Duration REOPEN_WINDOW = Duration.ofDays(7);
    private final JdbcTemplate jdbc;
    private final CurrentUserPolicy policy;
    private final IdempotencyService idempotency;
    private final AuditService audit;
    private final ObjectMapper mapper;
    private final Clock clock;

    public HelpSupportService(JdbcTemplate jdbc, CurrentUserPolicy policy,
                              IdempotencyService idempotency, AuditService audit,
                              ObjectMapper mapper, Clock clock) {
        this.jdbc = jdbc;
        this.policy = policy;
        this.idempotency = idempotency;
        this.audit = audit;
        this.mapper = mapper;
        this.clock = clock;
    }

    // ---------- HELP ----------
    public Map<String, Object> contentPack(String version) {
        List<Map<String, Object>> articles = jdbc.query("""
                SELECT id, title, summary, category, app_version_range FROM help_article
                WHERE audience = 'GENERAL' AND published = true ORDER BY id
                """, (rs, n) -> map("id", rs.getString("id"), "title", rs.getString("title"),
                "summary", rs.getString("summary"), "category", rs.getString("category"),
                "appVersionRange", rs.getString("app_version_range")));
        return map("version", version == null ? "1.0.0" : version, "articles", articles,
                "source", "GENERAL");
    }

    public List<Map<String, Object>> articles(AuthenticatedUser principal, String squareId, String q) {
        if (squareId == null) {
            return jdbc.query("""
                    SELECT * FROM help_article WHERE audience='GENERAL' AND published=true
                      AND (?::text IS NULL OR title ILIKE ? OR body ILIKE ?) ORDER BY id
                    """, (rs, n) -> article(rs), q, like(q), like(q));
        }
        requireCourse(squareId, principal);
        return jdbc.query("""
                SELECT * FROM help_article WHERE audience='COURSE' AND square_id=? AND published=true
                  AND (?::text IS NULL OR title ILIKE ? OR body ILIKE ?) ORDER BY id
                """, (rs, n) -> article(rs), squareId, q, like(q), like(q));
    }

    public Map<String, Object> article(AuthenticatedUser principal, String id) {
        Map<String, Object> row = one("SELECT * FROM help_article WHERE id=?", id);
        if (row == null) throw ApiException.notFound();
        if ("COURSE".equals(row.get("audience"))) requireCourse((String) row.get("squareId"), principal);
        return row;
    }

    @Transactional
    public Map<String, Object> feedback(AuthenticatedUser principal, String id, boolean helpful) {
        policy.requireActiveBusinessUser(principal);
        article(principal, id);
        jdbc.update("""
                INSERT INTO help_feedback(article_id, user_id, helpful, updated_at)
                VALUES (?, ?, ?, ?) ON CONFLICT (article_id, user_id)
                DO UPDATE SET helpful=EXCLUDED.helpful, updated_at=EXCLUDED.updated_at
                """, id, principal.userId(), helpful, now());
        return map("articleId", id, "helpful", helpful);
    }

    @Transactional
    public Map<String, Object> updateArticle(AuthenticatedUser principal, String id, Map<String, Object> body) {
        requireOperator(principal, "HELP_ADMIN");
        Map<String, Object> old = one("SELECT * FROM help_article WHERE id=?", id);
        if (old == null) throw ApiException.notFound();
        String title = str(body.get("title"));
        String content = str(body.get("body"));
        if (title.isBlank() || content.isBlank()) throw ApiException.validation("title/body 必填");
        jdbc.update("UPDATE help_article SET title=?, body=?, summary=?, revision=revision+1, updated_at=? WHERE id=?",
                title, content, str(body.get("summary")), now(), id);
        return article(principal, id);
    }

    public List<Map<String, Object>> routes() {
        return jdbc.query("SELECT id, type, name, available FROM support_route WHERE available=true ORDER BY id",
                (rs, n) -> map("id", rs.getString("id"), "type", rs.getString("type"),
                        "name", rs.getString("name"), "available", true));
    }

    @Transactional
    public Map<String, Object> createTicket(AuthenticatedUser principal, Map<String, Object> body,
                                            String key, String path) {
        policy.requireActiveBusinessUser(principal);
        String routeId = str(body.get("routeId"));
        String clientId = str(body.get("clientRequestId"));
        if (routeId.isBlank() || clientId.isBlank()) throw ApiException.validation("routeId/clientRequestId 必填");
        if (one("SELECT * FROM support_route WHERE id=? AND available=true", routeId) == null) {
            throw new ApiException(ApiCode.SERVICE_UNAVAILABLE, "当前没有可受理的求助路由");
        }
        Map<String, Object> existing = one("SELECT * FROM support_ticket WHERE requester_id=? AND client_request_id=?",
                principal.userId(), clientId);
        if (existing != null) return ticket(existing);
        return idempotency.execute(new IdempotencyRequest(principal, "POST", path, key, body, List.of()),
                Map.class, () -> policy.requireActiveBusinessUser(principal), () -> {
                    String id = UUID.randomUUID().toString();
                    String subject = str(body.get("subject"));
                    String text = str(body.get("body"));
                    if (subject.isBlank() || text.isBlank()) throw ApiException.validation("subject/body 必填");
                    Object attachments = body.getOrDefault("attachments", List.of());
                    if (attachments instanceof List<?> list && list.size() > 3) {
                        throw ApiException.validation("附件最多3个");
                    }
                    jdbc.update("""
                            INSERT INTO support_ticket(id, requester_id, route_id, client_request_id,
                                subject, body, attachments_json, status, revision, created_at, updated_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, 'OPEN', 1, ?, ?)
                            """, id, principal.userId(), routeId, clientId, subject, text,
                            write(attachments), now(), now());
                    history(id, "CREATE", principal.userId(), "");
                    return ticket(one("SELECT * FROM support_ticket WHERE id=?", id));
                });
    }

    public List<Map<String, Object>> myTickets(AuthenticatedUser principal) {
        return jdbc.query("""
                SELECT * FROM support_ticket WHERE requester_id=? ORDER BY created_at DESC, id
                """, (rs, n) -> ticketRow(rs), principal.userId());
    }

    public Map<String, Object> ticketDetail(AuthenticatedUser principal, String id) {
        Map<String, Object> row = one("SELECT * FROM support_ticket WHERE id=?", id);
        if (row == null) throw ApiException.notFound();
        if (!principal.userId().equals(row.get("requesterId"))
                && !principal.userId().equals(row.get("assigneeId"))
                && !isOperator(principal.userId(), "SUPPORT_HANDLE")) {
            throw new ApiException(ApiCode.FORBIDDEN);
        }
        return detail(row);
    }

    @Transactional
    public Map<String, Object> reply(AuthenticatedUser principal, String id, Map<String, Object> body) {
        Map<String, Object> row = requireTicket(id);
        if (!principal.userId().equals(row.get("requesterId"))
                && !principal.userId().equals(row.get("assigneeId"))) throw new ApiException(ApiCode.FORBIDDEN);
        if ("CLOSED".equals(row.get("status"))) throw new ApiException(ApiCode.RESOURCE_CHANGED, "已关闭");
        String text = str(body.get("body"));
        if (text.isBlank()) throw ApiException.validation("body 必填");
        jdbc.update("INSERT INTO support_reply(id, ticket_id, author_id, body, created_at) VALUES (?,?,?,?,?)",
                UUID.randomUUID().toString(), id, principal.userId(), text, now());
        String status = principal.userId().equals(row.get("assigneeId")) ? "ANSWERED" : "IN_PROGRESS";
        jdbc.update("UPDATE support_ticket SET status=?, revision=revision+1, updated_at=? WHERE id=?",
                status, now(), id);
        return ticketDetail(principal, id);
    }

    public List<Map<String, Object>> workspace(AuthenticatedUser principal) {
        requireOperator(principal, "SUPPORT_HANDLE");
        return jdbc.query("""
                SELECT * FROM support_ticket WHERE assignee_id=? AND status <> 'CLOSED'
                ORDER BY updated_at, id
                """, (rs, n) -> ticketRow(rs), principal.userId());
    }

    @Transactional
    public Map<String, Object> accept(AuthenticatedUser principal, String id) {
        requireOperator(principal, "SUPPORT_HANDLE");
        int rows = jdbc.update("""
                UPDATE support_ticket SET assignee_id=?, status='IN_PROGRESS', revision=revision+1, updated_at=?
                WHERE id=? AND assignee_id IS NULL AND status='OPEN'
                """, principal.userId(), now(), id);
        if (rows != 1) throw new ApiException(ApiCode.RESOURCE_CHANGED, "已被其他处理人领取");
        history(id, "ACCEPT", principal.userId(), "");
        return ticketDetail(principal, id);
    }

    @Transactional
    public Map<String, Object> status(AuthenticatedUser principal, String id, Map<String, Object> body) {
        Map<String, Object> row = requireTicket(id);
        if (!principal.userId().equals(row.get("assigneeId"))) throw new ApiException(ApiCode.FORBIDDEN);
        String action = str(body.get("action"));
        if (!List.of("IN_PROGRESS", "WAITING_USER", "ANSWERED").contains(action)) {
            throw ApiException.validation("action 无效");
        }
        jdbc.update("UPDATE support_ticket SET status=?, revision=revision+1, updated_at=? WHERE id=?",
                action, now(), id);
        history(id, action, principal.userId(), str(body.get("reason")));
        return ticketDetail(principal, id);
    }

    @Transactional
    public Map<String, Object> resolution(AuthenticatedUser principal, String id, Map<String, Object> body) {
        Map<String, Object> row = requireTicket(id);
        boolean requester = principal.userId().equals(row.get("requesterId"));
        boolean assignee = principal.userId().equals(row.get("assigneeId"));
        if (!requester && !assignee) throw new ApiException(ApiCode.FORBIDDEN);
        boolean resolved = Boolean.TRUE.equals(body.get("resolved"));
        String resolution = str(body.get("resolution"));
        jdbc.update("""
                UPDATE support_ticket SET status='CLOSED', resolution=?, resolved_by=?,
                    revision=revision+1, updated_at=?, closed_at=?, reopen_until=?
                WHERE id=?
                """, resolution, resolved ? principal.userId() : null, now(), now(),
                now().plus(REOPEN_WINDOW), id);
        history(id, resolved ? "RESOLVED_BY_USER" : "CLOSED_BY_HANDLER", principal.userId(), resolution);
        return ticketDetail(principal, id);
    }

    @Transactional
    public Map<String, Object> transfer(AuthenticatedUser principal, String id, Map<String, Object> body) {
        Map<String, Object> row = requireTicket(id);
        if (!principal.userId().equals(row.get("assigneeId"))) throw new ApiException(ApiCode.FORBIDDEN);
        String target = str(body.get("targetUserId"));
        if (!isOperator(target, "SUPPORT_HANDLE")) throw ApiException.validation("目标处理人无权限");
        jdbc.update("UPDATE support_ticket SET assignee_id=?, revision=revision+1, updated_at=? WHERE id=?",
                target, now(), id);
        history(id, "TRANSFER", principal.userId(), target);
        return ticketDetail(principal, id);
    }

    @Transactional
    public Map<String, Object> closure(AuthenticatedUser principal, String id) {
        Map<String, Object> row = requireTicket(id);
        if (!principal.userId().equals(row.get("assigneeId")) && !isOperator(principal.userId(), "SUPPORT_HANDLE")) {
            throw new ApiException(ApiCode.FORBIDDEN);
        }
        jdbc.update("""
                UPDATE support_ticket SET status='CLOSED', revision=revision+1, updated_at=?,
                    closed_at=?, reopen_until=? WHERE id=?
                """, now(), now(), now().plus(REOPEN_WINDOW), id);
        history(id, "CLOSE", principal.userId(), "");
        return ticketDetail(principal, id);
    }

    @Transactional
    public Map<String, Object> reopen(AuthenticatedUser principal, String id) {
        Map<String, Object> row = requireTicket(id);
        if (!principal.userId().equals(row.get("requesterId"))) throw new ApiException(ApiCode.FORBIDDEN);
        if (!"CLOSED".equals(row.get("status"))) throw new ApiException(ApiCode.RESOURCE_CHANGED);
        Object rawUntil = row.get("reopenUntil");
        java.time.Instant until = rawUntil instanceof java.sql.Timestamp ts ? ts.toInstant()
                : rawUntil instanceof OffsetDateTime odt ? odt.toInstant() : null;
        if (until == null || until.isBefore(clock.instant())) {
            throw new ApiException(ApiCode.RESOURCE_CHANGED, "重开窗口已过");
        }
        jdbc.update("UPDATE support_ticket SET status='OPEN', revision=revision+1, updated_at=? WHERE id=?",
                now(), id);
        history(id, "REOPEN", principal.userId(), "");
        return ticketDetail(principal, id);
    }

    public List<Map<String, Object>> history(AuthenticatedUser principal, String id) {
        Map<String, Object> row = requireTicket(id);
        if (!principal.userId().equals(row.get("requesterId"))
                && !principal.userId().equals(row.get("assigneeId"))) throw new ApiException(ApiCode.FORBIDDEN);
        return jdbc.query("""
                SELECT action, actor_id, reason, created_at FROM support_history
                WHERE ticket_id=? ORDER BY created_at, id
                """, (rs, n) -> map("action", rs.getString("action"), "actorId", rs.getString("actor_id"),
                "reason", rs.getString("reason"), "at", rs.getObject("created_at", OffsetDateTime.class)
                        .toInstant().toString()), id);
    }

    // ---------- REPORTING ----------
    @Transactional
    public Map<String, Object> report(AuthenticatedUser principal, Map<String, Object> body,
                                      String key, String path) {
        policy.requireActiveBusinessUser(principal);
        String clientId = str(body.get("clientRequestId"));
        Map<String, Object> existing = one("SELECT * FROM content_report WHERE reporter_id=? AND client_request_id=?",
                principal.userId(), clientId);
        if (existing != null) return reportView(existing);
        if (!hasOperator("CONTENT_REVIEW")) {
            throw new ApiException(ApiCode.SERVICE_UNAVAILABLE, "当前没有内容举报处理人");
        }
        return idempotency.execute(new IdempotencyRequest(principal, "POST", path, key, body, List.of()),
                Map.class, () -> policy.requireActiveBusinessUser(principal), () -> {
                    Content current = content(principal, str(body.get("contentType")),
                            str(body.get("contentId")));
                    String excerpt = str(body.get("excerpt"));
                    if (excerpt.isBlank() || !current.text().contains(excerpt)) {
                        throw ApiException.validation("举报片段必须来自当前内容");
                    }
                    String id = UUID.randomUUID().toString();
                    jdbc.update("""
                            INSERT INTO content_report(id, reporter_id, client_request_id, content_type,
                                content_id, source_version, excerpt, reason, status, created_at, updated_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'OPEN', ?, ?)
                            """, id, principal.userId(), clientId, current.type(), current.id(),
                            current.version(), excerpt, str(body.get("reason")), now(), now());
                    return reportView(one("SELECT * FROM content_report WHERE id=?", id));
                });
    }

    public List<Map<String, Object>> myReports(AuthenticatedUser principal) {
        return jdbc.query("SELECT * FROM content_report WHERE reporter_id=? ORDER BY created_at DESC",
                (rs, n) -> reportRow(rs), principal.userId());
    }

    public Map<String, Object> reportDetail(AuthenticatedUser principal, String id) {
        Map<String, Object> row = one("SELECT * FROM content_report WHERE id=?", id);
        if (row == null) throw ApiException.notFound();
        if (!principal.userId().equals(row.get("reporterId"))
                && !principal.userId().equals(row.get("assigneeId"))
                && !hasOperator("CONTENT_REVIEW")) throw new ApiException(ApiCode.FORBIDDEN);
        return reportView(row);
    }

    @Transactional
    public Map<String, Object> handleReport(AuthenticatedUser principal, String id, Map<String, Object> body) {
        requireOperator(principal, "CONTENT_REVIEW");
        Map<String, Object> row = one("SELECT * FROM content_report WHERE id=?", id);
        if (row == null) throw ApiException.notFound();
        String action = str(body.get("action"));
        String status;
        switch (action) {
            case "ACCEPT" -> {
                jdbc.update("UPDATE content_report SET assignee_id=?, status='ACCEPTED', updated_at=? WHERE id=?",
                        principal.userId(), now(), id);
                status = "ACCEPTED";
            }
            case "RECORD_RESULT" -> status = "RECORDED";
            case "CLOSE" -> status = "CLOSED";
            default -> throw ApiException.validation("action 无效");
        }
        if (!"ACCEPT".equals(action)) {
            jdbc.update("UPDATE content_report SET status=?, updated_at=? WHERE id=?", status, now(), id);
        }
        jdbc.update("""
                INSERT INTO report_handling(id, report_id, action, actor_id, note, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID().toString(), id, action, principal.userId(),
                str(body.get("note")), now());
        return reportDetail(principal, id);
    }

    private Content content(AuthenticatedUser principal, String type, String id) {
        if ("TOPIC".equals(type)) {
            Map<String, Object> row = one("SELECT t.content, t.revision, t.project_id FROM board_topic t WHERE t.id=?", id);
            if (row == null || row.get("content") == null) throw ApiException.notFound();
            requireMember((String) row.get("projectId"), principal.userId());
            return new Content(id, type, (String) row.get("content"), ((Number) row.get("revision")).intValue());
        }
        if ("REPLY".equals(type)) {
            Map<String, Object> row = one("""
                    SELECT r.content, r.revision, t.project_id FROM board_reply r
                    JOIN board_topic t ON t.id=r.topic_id WHERE r.id=?
                    """, id);
            if (row == null || row.get("content") == null) throw ApiException.notFound();
            requireMember((String) row.get("projectId"), principal.userId());
            return new Content(id, type, (String) row.get("content"), ((Number) row.get("revision")).intValue());
        }
        if ("CHAT_MESSAGE".equals(type)) {
            Map<String, Object> row = one("SELECT content, revision, project_id FROM chat_message WHERE id=?", id);
            if (row == null || row.get("content") == null) throw ApiException.notFound();
            requireMember((String) row.get("projectId"), principal.userId());
            return new Content(id, type, (String) row.get("content"), ((Number) row.get("revision")).intValue());
        }
        throw ApiException.validation("contentType 无效");
    }

    private void requireCourse(String squareId, AuthenticatedUser principal) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM (
                  SELECT 1 FROM verified_enrollment e JOIN square s ON s.verified_course_id=e.verified_course_id
                  WHERE s.id=? AND e.user_id=? AND e.valid=true
                  UNION ALL SELECT 1 FROM course_membership WHERE square_id=? AND user_id=? AND status='ACTIVE'
                  UNION ALL SELECT 1 FROM teacher_assignment WHERE square_id=? AND user_id=? AND active
                ) x
                """, Integer.class, squareId, principal.userId(), squareId, principal.userId(),
                squareId, principal.userId());
        if (count == null || count == 0) throw new ApiException(ApiCode.FORBIDDEN);
    }

    private void requireMember(String projectId, String userId) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM membership WHERE project_id=? AND user_id=?
                """, Integer.class, projectId, userId);
        if (count == null || count == 0) throw new ApiException(ApiCode.FORBIDDEN);
    }

    private void requireOperator(AuthenticatedUser principal, String capability) {
        policy.requireActiveBusinessUser(principal);
        if (!isOperator(principal.userId(), capability)) throw new ApiException(ApiCode.FORBIDDEN);
    }

    private boolean isOperator(String userId, String capability) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM operator_grant WHERE user_id=? AND capability=? AND active=true
                """, Integer.class, userId, capability);
        return count != null && count > 0;
    }

    private boolean hasOperator(String capability) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM operator_grant WHERE capability=? AND active=true
                """, Integer.class, capability);
        return count != null && count > 0;
    }

    private Map<String, Object> ticket(Map<String, Object> row) {
        return map("id", row.get("id"), "requesterId", row.get("requesterId"),
                "routeId", row.get("routeId"), "subject", row.get("subject"),
                "body", row.get("body"), "status", row.get("status"),
                "assigneeId", row.get("assigneeId"), "revision", row.get("revision"),
                "createdAt", row.get("createdAt"), "updatedAt", row.get("updatedAt"));
    }

    private Map<String, Object> ticketRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return map("id", rs.getString("id"), "requesterId", rs.getString("requester_id"),
                "routeId", rs.getString("route_id"), "subject", rs.getString("subject"),
                "body", rs.getString("body"), "status", rs.getString("status"),
                "assigneeId", rs.getString("assignee_id"), "revision", rs.getInt("revision"),
                "createdAt", rs.getObject("created_at", OffsetDateTime.class).toInstant().toString(),
                "updatedAt", rs.getObject("updated_at", OffsetDateTime.class).toInstant().toString());
    }

    private Map<String, Object> detail(Map<String, Object> row) {
        Map<String, Object> result = ticket(row);
        result.put("replies", jdbc.query("""
                SELECT id, author_id, body, created_at FROM support_reply WHERE ticket_id=? ORDER BY created_at, id
                """, (rs, n) -> map("id", rs.getString("id"), "authorId", rs.getString("author_id"),
                "body", rs.getString("body"), "createdAt", rs.getObject("created_at", OffsetDateTime.class)
                        .toInstant().toString()), row.get("id")));
        result.put("history", jdbc.query("""
                SELECT action, actor_id, reason, created_at FROM support_history WHERE ticket_id=?
                ORDER BY created_at, id
                """, (rs, n) -> map("action", rs.getString("action"), "actorId", rs.getString("actor_id"),
                "reason", rs.getString("reason"), "at", rs.getObject("created_at", OffsetDateTime.class)
                        .toInstant().toString()), row.get("id")));
        return result;
    }

    private Map<String, Object> reportRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return map("id", rs.getString("id"), "reporterId", rs.getString("reporter_id"),
                "contentType", rs.getString("content_type"), "contentId", rs.getString("content_id"),
                "sourceVersion", rs.getInt("source_version"), "excerpt", rs.getString("excerpt"),
                "reason", rs.getString("reason"), "status", rs.getString("status"),
                "assigneeId", rs.getString("assignee_id"),
                "createdAt", rs.getObject("created_at", OffsetDateTime.class).toInstant().toString(),
                "updatedAt", rs.getObject("updated_at", OffsetDateTime.class).toInstant().toString());
    }

    private Map<String, Object> reportView(Map<String, Object> row) {
        return map("id", row.get("id"), "reporterId", row.get("reporterId"),
                "contentType", row.get("contentType"), "contentId", row.get("contentId"),
                "sourceVersion", row.get("sourceVersion"), "excerpt", row.get("excerpt"),
                "reason", row.get("reason"), "status", row.get("status"),
                "assigneeId", row.get("assigneeId"), "createdAt", row.get("createdAt"),
                "updatedAt", row.get("updatedAt"));
    }

    private void history(String ticketId, String action, String actor, String reason) {
        jdbc.update("""
                INSERT INTO support_history(id, ticket_id, action, actor_id, reason, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID().toString(), ticketId, action, actor, reason == null ? "" : reason, now());
    }

    private Map<String, Object> requireTicket(String id) {
        Map<String, Object> row = one("SELECT * FROM support_ticket WHERE id=?", id);
        if (row == null) throw ApiException.notFound();
        return row;
    }

    private Map<String, Object> one(String sql, Object... args) {
        List<Map<String, Object>> rows = jdbc.query(sql, (rs, n) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                Object value = rs.getObject(i);
                if (value instanceof OffsetDateTime odt) value = odt.toInstant();
                row.put(camel(rs.getMetaData().getColumnLabel(i)), value);
            }
            return row;
        }, args);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private Map<String, Object> article(java.sql.ResultSet rs) throws java.sql.SQLException {
        return map("id", rs.getString("id"), "audience", rs.getString("audience"),
                "squareId", rs.getString("square_id"), "title", rs.getString("title"),
                "summary", rs.getString("summary"), "body", rs.getString("body"),
                "category", rs.getString("category"), "appVersionRange", rs.getString("app_version_range"),
                "revision", rs.getInt("revision"));
    }

    private String camel(String label) {
        StringBuilder out = new StringBuilder();
        boolean upper = false;
        for (char c : label.toCharArray()) {
            if (c == '_') upper = true;
            else if (upper) { out.append(Character.toUpperCase(c)); upper = false; }
            else out.append(c);
        }
        return out.toString();
    }

    private String like(String q) {
        return q == null ? null : "%" + q.replace("%", "\\%").replace("_", "\\_") + "%";
    }

    private String str(Object value) {
        return value == null ? "" : value.toString().strip();
    }

    private Map<String, Object> map(Object... kv) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) row.put((String) kv[i], kv[i + 1]);
        return row;
    }

    private String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("无法序列化附件", ex);
        }
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private record Content(String id, String type, String text, int version) {
    }
}
