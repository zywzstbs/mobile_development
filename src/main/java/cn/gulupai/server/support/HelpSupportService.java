package cn.gulupai.server.support;

import cn.gulupai.server.common.api.ApiCode;
import cn.gulupai.server.common.api.ApiException;
import cn.gulupai.server.common.api.ListEnvelope;
import cn.gulupai.server.common.audit.AuditService;
import cn.gulupai.server.common.idempotency.CanonicalJson;
import cn.gulupai.server.common.idempotency.IdempotencyRequest;
import cn.gulupai.server.common.idempotency.IdempotencyService;
import cn.gulupai.server.common.pagination.CursorService;
import cn.gulupai.server.common.security.AuthenticatedUser;
import cn.gulupai.server.common.security.CurrentUserPolicy;
import cn.gulupai.server.common.security.Hashing;
import cn.gulupai.server.file.FileRepository;
import cn.gulupai.server.file.FileRepository.StoredFileRow;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

@Service
public class HelpSupportService {

    private static final Duration REOPEN_WINDOW = Duration.ofDays(7);
    private static final int HISTORY_LIMIT = 200;
    private static final int DAILY_TICKETS = 5;
    private static final int DAILY_REPORTS = 10;
    private static final Set<String> IMAGES = Set.of("image/png", "image/jpeg", "image/webp");
    private static final String REPORT_LABEL = "内容举报受理";

    private final JdbcTemplate jdbc;
    private final CurrentUserPolicy policy;
    private final IdempotencyService idempotency;
    private final AuditService audit;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final CanonicalJson canonical;
    private final CursorService cursors;
    private final FileRepository files;
    private final SupportTicketAccess access;
    private final HelpContentPack packs;
    private final ContentReportAccess reports;

    public HelpSupportService(JdbcTemplate jdbc, CurrentUserPolicy policy,
                              IdempotencyService idempotency, AuditService audit,
                              ObjectMapper mapper, Clock clock, CanonicalJson canonical,
                              CursorService cursors, FileRepository files,
                              SupportTicketAccess access, HelpContentPack packs,
                              ContentReportAccess reports) {
        this.jdbc = jdbc;
        this.policy = policy;
        this.idempotency = idempotency;
        this.audit = audit;
        this.mapper = mapper;
        this.clock = clock;
        this.canonical = canonical;
        this.cursors = cursors;
        this.files = files;
        this.access = access;
        this.packs = packs;
        this.reports = reports;
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ, readOnly = true)
    public Map<String, Object> contentPack(String appVersion, Integer knownVersion) {
        Map<String, Object> pack = one(
                "SELECT version, updated_at FROM help_content_pack WHERE id=?", HelpContentPack.GENERAL);
        if (pack == null) {
            throw new ApiException(ApiCode.SERVER_ERROR, "帮助包版本不存在");
        }
        int version = ((Number) pack.get("version")).intValue();
        Instant updated = instant(pack.get("updatedAt"));
        if (knownVersion != null && knownVersion == version) {
            return map("version", version, "articles", List.of(), "updatedAt", updated);
        }
        return map("version", version, "articles", published("GENERAL", null, null, null, appVersion),
                "updatedAt", updated);
    }

    public ListEnvelope<Map<String, Object>> articles(AuthenticatedUser principal, String q, String role,
                                                     String squareId, String appVersion, String cursor, Integer limit) {
        if (role != null && !role.isBlank() && !List.of("STUDENT", "TEACHER", "CLASS_ADMIN").contains(role)) {
            throw ApiException.validation("role 无效");
        }
        if (squareId != null) {
            if (principal == null) {
                throw new ApiException(ApiCode.FORBIDDEN);
            }
            requireCourse(squareId, principal);
        }
        List<Map<String, Object>> rows = published(squareId == null ? "GENERAL" : "COURSE", squareId, q, role, appVersion);
        List<String> ids = rows.stream().map(row -> (String) row.get("id")).toList();
        Function<String, Map<String, Object>> load = id -> rows.stream()
                .filter(row -> id.equals(row.get("id"))).findFirst().orElse(null);
        if (principal == null) {
            return pageAnonymous("HELP_ARTICLES_ANON", queryKey("ANON", "GENERAL", q, role, appVersion),
                    ids, cursor, limit, load);
        }
        return page(principal.userId(), "HELP_ARTICLES", queryKey(squareId, q, role, appVersion), ids, cursor, limit, load);
    }

    public Map<String, Object> article(AuthenticatedUser principal, String id) {
        Map<String, Object> row = one("SELECT * FROM help_article WHERE id=? AND published=true", id);
        if (row == null) {
            throw ApiException.notFound();
        }
        if ("COURSE".equals(row.get("audience"))) {
            if (principal == null) {
                throw new ApiException(ApiCode.FORBIDDEN);
            }
            requireCourse((String) row.get("squareId"), principal);
        }
        return articleView(row);
    }

    @Transactional
    public Map<String, Object> feedback(AuthenticatedUser principal, String id, SupportRequests.Feedback body,
                                       String key, String path) {
        return idempotency.execute(new IdempotencyRequest(principal, "PUT", path, key, body,
                        List.of(new IdempotencyRequest.ResourceRef("HELP_ARTICLE", id))),
                Map.class, () -> requireFeedbackReader(principal, id), () -> {
                    requireFeedbackReader(principal, id);
                    Map<String, Object> row = one(
                            "SELECT * FROM help_article WHERE id=? AND published=true FOR UPDATE", id);
                    if (row == null) {
                        throw ApiException.notFound();
                    }
                    if ("COURSE".equals(row.get("audience"))) {
                        requireCourse((String) row.get("squareId"), principal);
                    }
                    int current = ((Number) row.get("contentVersion")).intValue();
                    if (body.contentVersion() != current) {
                        throw new ApiException(ApiCode.RESOURCE_CHANGED, "反馈必须针对当前正文版本");
                    }
                    jdbc.update("""
                            INSERT INTO help_feedback(article_id, user_id, helpful, updated_at, content_version, reason_code)
                            VALUES (?, ?, ?, ?, ?, ?)
                            ON CONFLICT (article_id, user_id, content_version)
                            DO UPDATE SET helpful=EXCLUDED.helpful, reason_code=EXCLUDED.reason_code,
                                updated_at=EXCLUDED.updated_at
                            """, id, principal.userId(), body.helpful(), now(), body.contentVersion(), body.reasonCode());
                    return map("articleId", id, "contentVersion", body.contentVersion(),
                            "helpful", body.helpful(), "reasonCode", body.reasonCode());
                }, cached -> {
                    requireFeedbackReader(principal, id);
                    return cached;
                });
    }

    @Transactional
    public Map<String, Object> updateArticle(AuthenticatedUser principal, String id, SupportRequests.ArticleUpdate body,
                                             String key, String path) {
        return idempotency.execute(new IdempotencyRequest(principal, "PUT", path, key, body,
                        List.of(new IdempotencyRequest.ResourceRef("HELP_ARTICLE", id))),
                Map.class, () -> requireArticleWriter(principal, id),
                () -> applyArticleUpdate(principal, id, body), cached -> {
                    requireArticleWriter(principal, id);
                    return cached;
                });
    }

    public Map<String, Object> route(AuthenticatedUser principal, String category, String squareId) {
        policy.requireActiveBusinessUser(principal);
        if (category == null || category.isBlank()) {
            return unavailable(null, false);
        }
        if (!List.of("OPERATION", "COURSE_REQUIREMENT").contains(category)) {
            throw ApiException.validation("category 无效");
        }
        boolean requiresSquare = "COURSE_REQUIREMENT".equals(category);
        if (requiresSquare && squareId != null) {
            requireCourse(squareId, principal);
        }
        List<Map<String, Object>> rows = jdbc.query("""
                SELECT * FROM support_route
                WHERE available=true AND category=? AND receiver_user_id IS NOT NULL
                  AND (?::text IS NULL OR square_id IS NULL OR square_id=?)
                ORDER BY CASE WHEN square_id IS NOT DISTINCT FROM ?::text THEN 0 ELSE 1 END, id
                """, (rs, n) -> routeRow(rs), category, squareId, squareId, squareId);
        for (Map<String, Object> row : rows) {
            if (requiresSquare && row.get("squareId") == null) {
                continue;
            }
            if (!requiresSquare && row.get("squareId") != null) {
                continue;
            }
            if (requiresSquare && squareId != null && row.get("squareId") != null
                    && !squareId.equals(row.get("squareId"))) {
                continue;
            }
            if (!access.canReceive((String) row.get("receiverUserId"), (String) row.get("squareId"),
                    principal.userId())) {
                continue;
            }
            return map("available", true, "routeId", row.get("id"), "category", category,
                    "receiverLabel", row.get("name"), "requiresSquare", requiresSquare);
        }
        return unavailable(category, requiresSquare);
    }

    @Transactional
    public Map<String, Object> createTicket(AuthenticatedUser principal, SupportRequests.CreateTicket body,
                                            String key, String path) {
        policy.requireActiveBusinessUser(principal);
        return idempotency.execute(new IdempotencyRequest(principal, "POST", path, key, body, List.of()),
                Map.class, () -> policy.requireActiveBusinessUser(principal), () -> {
                    lockClientRequest("TICKET", principal.userId(), body.clientRequestId());
                    String digest = canonical.hash(body);
                    Map<String, Object> again = ticketByClient(principal.userId(), body.clientRequestId());
                    if (again != null) {
                        if (!digest.equals(trim(again.get("requestDigest")))) {
                            throw new ApiException(ApiCode.CLIENT_REQUEST_ID_REUSED);
                        }
                        return ticketView(again);
                    }
                    Map<String, Object> route = usableRoute(body.routeId(), body.category(), body.squareId(),
                            principal, principal.userId());
                    Integer today = jdbc.queryForObject("""
                            SELECT count(*) FROM support_ticket
                            WHERE requester_id=? AND created_at >= ?
                            """, Integer.class, principal.userId(), startOfUtcDay());
                    if (today != null && today >= DAILY_TICKETS) {
                        throw new ApiException(ApiCode.RATE_LIMITED, "今日新建求助已达上限");
                    }
                    String id = UUID.randomUUID().toString();
                    int inserted = jdbc.update("""
                            INSERT INTO support_ticket(id, requester_id, route_id, client_request_id,
                                subject, body, attachments_json, status, assignee_id, revision,
                                created_at, updated_at, category, title, content, square_id, context_json,
                                attachment_file_ids, request_digest, resolved_by_requester)
                            VALUES (?, ?, ?, ?, ?, ?, '[]'::jsonb, 'SUBMITTED', ?, 1, ?, ?, ?, ?, ?, ?, ?::jsonb,
                                '[]'::jsonb, ?, false)
                            ON CONFLICT (requester_id, client_request_id) DO NOTHING
                            """, id, principal.userId(), body.routeId(), body.clientRequestId(),
                            body.title(), body.content(), route.get("receiverUserId"), now(), now(),
                            body.category(), body.title(), body.content(), body.squareId(),
                            write(body.context()), digest);
                    Map<String, Object> saved = ticketByClient(principal.userId(), body.clientRequestId());
                    if (saved == null) {
                        throw new ApiException(ApiCode.SERVER_ERROR, "求助没有保存");
                    }
                    if (!digest.equals(trim(saved.get("requestDigest")))) {
                        throw new ApiException(ApiCode.CLIENT_REQUEST_ID_REUSED);
                    }
                    if (inserted == 1) {
                        List<String> attachments = bindFiles(principal.userId(), id, id, body.attachmentFileIds());
                        jdbc.update("UPDATE support_ticket SET attachment_file_ids=?::jsonb WHERE id=?",
                                write(attachments), id);
                        history(id, "CREATE", principal.userId(), "");
                        saved = requireTicket(id);
                    }
                    return ticketView(saved);
                }, cached -> {
                    policy.requireActiveBusinessUser(principal);
                    requireReadable(principal, String.valueOf(cached.get("id")));
                    return cached;
                });
    }

    public ListEnvelope<Map<String, Object>> myTickets(AuthenticatedUser principal, String clientRequestId,
                                                      String status, String cursor, Integer limit) {
        policy.requireActiveBusinessUser(principal);
        List<String> ids = jdbc.query("""
                SELECT id FROM support_ticket
                WHERE requester_id=?
                  AND (?::text IS NULL OR client_request_id=?)
                  AND (?::text IS NULL OR status=?)
                ORDER BY created_at DESC, id
                """, (rs, n) -> rs.getString(1), principal.userId(), clientRequestId, clientRequestId,
                status, status);
        return page(principal.userId(), "MY_TICKETS", queryKey(clientRequestId, status), ids, cursor, limit,
                id -> ticketView(requireTicket(id)));
    }

    public Map<String, Object> ticketDetail(AuthenticatedUser principal, String id, String replyCursor, Integer limit) {
        policy.requireActiveBusinessUser(principal);
        Map<String, Object> row = requireReadable(principal, id);
        Map<String, Object> view = ticketView(row);
        List<String> replyIds = jdbc.query("""
                SELECT id FROM support_reply WHERE ticket_id=? ORDER BY created_at, id
                """, (rs, n) -> rs.getString(1), id);
        view.put("replies", page(principal.userId(), "TICKET_REPLIES:" + id, "replies", replyIds,
                replyCursor, limit, replyId -> replyView(one("SELECT * FROM support_reply WHERE id=?", replyId))));
        List<Map<String, Object>> history = jdbc.query("""
                SELECT action, actor_id, reason, created_at FROM support_history WHERE ticket_id=?
                ORDER BY created_at, id
                """, (rs, n) -> map("action", rs.getString("action"), "actorId", rs.getString("actor_id"),
                "reason", rs.getString("reason"), "at", rs.getObject("created_at", OffsetDateTime.class).toInstant()), id);
        if (history.size() > HISTORY_LIMIT) {
            throw new ApiException(ApiCode.COLLECTION_TOO_LARGE, "处理历史超过上限");
        }
        view.put("history", history);
        view.put("transferTargets", transferTargets(principal, row));
        return view;
    }

    public Map<String, Object> reply(AuthenticatedUser principal, String id, SupportRequests.Reply body,
                                    String key, String path) {
        policy.requireActiveBusinessUser(principal);
        return idempotency.execute(new IdempotencyRequest(principal, "POST", path, key, body, List.of(new IdempotencyRequest.ResourceRef("SUPPORT_TICKET", id))),
                Map.class, () -> policy.requireActiveBusinessUser(principal), () -> {
                    Map<String, Object> row = lockTicket(id);
                    requireParticipant(principal, row);
                    if ("CLOSED".equals(row.get("status"))) {
                        throw new ApiException(ApiCode.RESOURCE_CHANGED, "已关闭");
                    }
                    if (((Number) row.get("revision")).intValue() != body.expectedRevision()) {
                        throw new ApiException(ApiCode.RESOURCE_CHANGED);
                    }
                    String replyId = UUID.randomUUID().toString();
                    List<String> attachments = bindFiles(principal.userId(), id, replyId, body.attachmentFileIds());
                    ensureHistoryRoom(id);
                    jdbc.update("""
                            INSERT INTO support_reply(id, ticket_id, author_id, body, created_at, attachment_file_ids)
                            VALUES (?, ?, ?, ?, ?, ?::jsonb)
                            """, replyId, id, principal.userId(), body.content(), now(), write(attachments));
                    if (jdbc.update("""
                            UPDATE support_ticket SET revision=revision+1, updated_at=?
                            WHERE id=? AND revision=?
                            """, now(), id, body.expectedRevision()) != 1) {
                        throw new ApiException(ApiCode.RESOURCE_CHANGED);
                    }
                    history(id, "REPLY", principal.userId(), "");
                    Map<String, Object> saved = requireTicket(id);
                    Map<String, Object> reply = replyView(one("SELECT * FROM support_reply WHERE id=?", replyId));
                    return map("reply", reply, "ticketRevision", saved.get("revision"),
                            "ticketStatus", saved.get("status"));
                }, cached -> {
                    requireReadable(principal, id);
                    return cached;
                });
    }

    public ListEnvelope<Map<String, Object>> workspace(AuthenticatedUser principal, String status,
                                                      String cursor, Integer limit) {
        requireOperator(principal, "SUPPORT_HANDLE");
        List<String> ids = jdbc.query("""
                SELECT id FROM support_ticket
                WHERE assignee_id=? AND status <> 'CLOSED'
                  AND (?::text IS NULL OR status=?)
                ORDER BY updated_at, id
                """, (rs, n) -> rs.getString(1), principal.userId(), status, status).stream()
                .filter(id -> access.canRead(principal.userId(), requireTicket(id)))
                .toList();
        return page(principal.userId(), "SUPPORT_WORKSPACE", queryKey(status), ids, cursor, limit,
                id -> ticketView(requireTicket(id)));
    }

    public Map<String, Object> accept(AuthenticatedUser principal, String id, SupportRequests.ExpectedRevision body,
                                     String key, String path) {
        return mutate(principal, id, body, key, path, row -> {
            access.requireHandler(principal, row);
            if (!"SUBMITTED".equals(row.get("status"))) {
                throw new ApiException(ApiCode.RESOURCE_CHANGED, "当前不能接单");
            }
            if (jdbc.update("""
                    UPDATE support_ticket SET status='IN_PROGRESS', revision=revision+1, updated_at=?
                    WHERE id=? AND revision=? AND assignee_id=? AND status='SUBMITTED'
                    """, now(), id, body.expectedRevision(), principal.userId()) != 1) {
                throw new ApiException(ApiCode.RESOURCE_CHANGED);
            }
            history(id, "ACCEPT", principal.userId(), "");
            return ticketView(requireTicket(id));
        });
    }

    public Map<String, Object> status(AuthenticatedUser principal, String id, SupportRequests.StatusAction body,
                                     String key, String path) {
        return mutate(principal, id, body, key, path, row -> {
            if (principal.userId().equals(row.get("requesterId"))) {
                throw new ApiException(ApiCode.FORBIDDEN);
            }
            access.requireHandler(principal, row);
            if ("CLOSED".equals(row.get("status"))) {
                throw new ApiException(ApiCode.RESOURCE_CHANGED);
            }
            String next = switch (body.action()) {
                case "REQUEST_INFORMATION" -> "WAITING_USER";
                case "ANSWERED" -> "ANSWERED";
                default -> throw ApiException.validation("action 无效");
            };
            if ("ANSWERED".equals(next)) {
                Integer replies = jdbc.queryForObject("""
                        SELECT count(*) FROM support_reply WHERE ticket_id=? AND author_id=?
                        """, Integer.class, id, principal.userId());
                if (replies == null || replies == 0) {
                    throw ApiException.validation("还没有处理者的实际答复");
                }
            }
            if (jdbc.update("""
                    UPDATE support_ticket SET status=?, revision=revision+1, updated_at=?
                    WHERE id=? AND revision=?
                    """, next, now(), id, body.expectedRevision()) != 1) {
                throw new ApiException(ApiCode.RESOURCE_CHANGED);
            }
            history(id, body.action(), principal.userId(), body.reason() == null ? "" : body.reason());
            return ticketView(requireTicket(id));
        });
    }

    public Map<String, Object> resolution(AuthenticatedUser principal, String id, SupportRequests.Resolution body,
                                         String key, String path) {
        return mutate(principal, id, body, key, path, row -> {
            if (!principal.userId().equals(row.get("requesterId"))) {
                throw new ApiException(ApiCode.FORBIDDEN);
            }
            if (Boolean.TRUE.equals(body.resolved())) {
                if (jdbc.update("""
                        UPDATE support_ticket SET status='CLOSED', resolution=?, resolved_by=?,
                            resolved_by_requester=true, resolution_note=?, revision=revision+1,
                            updated_at=?, closed_at=?, reopen_until=?
                        WHERE id=? AND revision=?
                        """, body.note(), principal.userId(), body.note(), now(), now(),
                        now().plus(REOPEN_WINDOW), id, body.expectedRevision()) != 1) {
                    throw new ApiException(ApiCode.RESOURCE_CHANGED);
                }
                history(id, "RESOLVED_BY_USER", principal.userId(), body.note());
            } else {
                if (!"CLOSED".equals(row.get("status"))) {
                    throw new ApiException(ApiCode.RESOURCE_CHANGED, "只有已关闭的求助可以重开");
                }
                Instant until = instant(row.get("reopenUntil"));
                if (until == null || until.isBefore(clock.instant())) {
                    throw new ApiException(ApiCode.TICKET_REOPEN_WINDOW_CLOSED);
                }
                if (jdbc.update("""
                        UPDATE support_ticket SET status='SUBMITTED', resolved_by_requester=false,
                            resolution_note=?, revision=revision+1, updated_at=?, closed_at=NULL
                        WHERE id=? AND revision=?
                        """, body.note(), now(), id, body.expectedRevision()) != 1) {
                    throw new ApiException(ApiCode.RESOURCE_CHANGED);
                }
                history(id, "REOPEN", principal.userId(), body.note());
            }
            return ticketView(requireTicket(id));
        });
    }

    public Map<String, Object> transfer(AuthenticatedUser principal, String id, SupportRequests.Transfer body,
                                       String key, String path) {
        return mutate(principal, id, body, key, path, row -> {
            if (principal.userId().equals(row.get("requesterId"))) {
                throw new ApiException(ApiCode.FORBIDDEN);
            }
            access.requireHandler(principal, row);
            if ("CLOSED".equals(row.get("status"))) {
                throw new ApiException(ApiCode.RESOURCE_CHANGED);
            }
            Map<String, Object> target = usableRoute(body.targetRouteId(), (String) row.get("category"),
                    (String) row.get("squareId"), null, (String) row.get("requesterId"));
            if (body.targetRouteId().equals(row.get("routeId"))) {
                throw ApiException.validation("不能转交到当前渠道");
            }
            if (principal.userId().equals(target.get("receiverUserId"))
                    || row.get("requesterId").equals(target.get("receiverUserId"))) {
                throw ApiException.validation("目标接收人无效");
            }
            if (jdbc.update("""
                    UPDATE support_ticket SET route_id=?, assignee_id=?, status='SUBMITTED',
                        revision=revision+1, updated_at=?
                    WHERE id=? AND revision=? AND assignee_id=?
                    """, body.targetRouteId(), target.get("receiverUserId"), now(), id,
                    body.expectedRevision(), principal.userId()) != 1) {
                throw new ApiException(ApiCode.RESOURCE_CHANGED);
            }
            history(id, "TRANSFER", principal.userId(), body.reason());
            Map<String, Object> view = ticketView(requireTicket(id));
            view.put("transferTargetRouteId", body.targetRouteId());
            return view;
        });
    }

    public Map<String, Object> closure(AuthenticatedUser principal, String id, SupportRequests.Closure body,
                                      String key, String path) {
        return mutate(principal, id, body, key, path, row -> {
            access.requireHandler(principal, row);
            if ("CLOSED".equals(row.get("status"))) {
                throw new ApiException(ApiCode.RESOURCE_CHANGED);
            }
            if (body.relatedTicketId() != null) {
                Map<String, Object> related = requireTicket(body.relatedTicketId());
                if (!row.get("requesterId").equals(related.get("requesterId"))
                        && !row.get("requesterId").equals(related.get("assigneeId"))) {
                    throw ApiException.validation("关联工单提问者不可读");
                }
            }
            if (jdbc.update("""
                    UPDATE support_ticket SET status='CLOSED', resolution=?, resolved_by=NULL,
                        resolved_by_requester=false, closure_next_action=?, related_ticket_id=?,
                        revision=revision+1, updated_at=?, closed_at=?, reopen_until=?
                    WHERE id=? AND revision=? AND assignee_id=?
                    """, body.reason(), body.nextAction(), body.relatedTicketId(), now(), now(),
                    now().plus(REOPEN_WINDOW), id, body.expectedRevision(), principal.userId()) != 1) {
                throw new ApiException(ApiCode.RESOURCE_CHANGED);
            }
            history(id, "CLOSE", principal.userId(), body.reason());
            return ticketView(requireTicket(id));
        });
    }

    @Transactional
    public Map<String, Object> report(AuthenticatedUser principal, SupportRequests.CreateReport body,
                                     String key, String path) {
        policy.requireActiveBusinessUser(principal);
        return idempotency.execute(new IdempotencyRequest(principal, "POST", path, key, body, List.of()),
                Map.class, () -> policy.requireActiveBusinessUser(principal), () -> {
                    lockClientRequest("REPORT", principal.userId(), body.clientRequestId());
                    String digest = canonical.hash(body);
                    Map<String, Object> again = reportByClient(principal.userId(), body.clientRequestId());
                    if (again != null) {
                        if (!digest.equals(trim(again.get("requestDigest")))) {
                            throw new ApiException(ApiCode.CLIENT_REQUEST_ID_REUSED);
                        }
                        return reportPublic(again);
                    }
                    Integer today = jdbc.queryForObject("""
                            SELECT count(*) FROM content_report WHERE reporter_id=? AND created_at >= ?
                            """, Integer.class, principal.userId(), startOfUtcDay());
                    if (today != null && today >= DAILY_REPORTS) {
                        throw new ApiException(ApiCode.RATE_LIMITED, "今日举报已达上限");
                    }
                    Content current = sourceContent(principal, body.source().type(), body.source().id());
                    if (current.version() != body.source().version()) {
                        throw new ApiException(ApiCode.SOURCE_CHANGED, "来源版本已变化");
                    }
                    if (!current.text().contains(body.selectedExcerpt())) {
                        throw ApiException.validation("举报片段必须来自当前内容");
                    }
                    if (!reports.lockEligibleReceiver(current.squareId())) {
                        throw new ApiException(ApiCode.REPORT_ROUTE_UNAVAILABLE);
                    }
                    String id = UUID.randomUUID().toString();
                    jdbc.update("""
                            INSERT INTO content_report(id, reporter_id, client_request_id, content_type,
                                content_id, source_version, excerpt, reason, status, created_at, updated_at,
                                revision, request_digest, square_id)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'SUBMITTED', ?, ?, 1, ?, ?)
                            ON CONFLICT (reporter_id, client_request_id) DO NOTHING
                            """, id, principal.userId(), body.clientRequestId(), current.type(), current.id(),
                            current.version(), body.selectedExcerpt(), body.reason(), now(), now(), digest,
                            current.squareId());
                    Map<String, Object> saved = reportByClient(principal.userId(), body.clientRequestId());
                    if (saved == null) {
                        throw new ApiException(ApiCode.SERVER_ERROR, "举报没有保存");
                    }
                    if (!digest.equals(trim(saved.get("requestDigest")))) {
                        throw new ApiException(ApiCode.CLIENT_REQUEST_ID_REUSED);
                    }
                    return reportPublic(saved);
                }, cached -> {
                    policy.requireActiveBusinessUser(principal);
                    Object id = cached.get("id");
                    Map<String, Object> row = id == null ? null : requireReport(String.valueOf(id));
                    if (row == null || !reports.canRead(principal.userId(), row)) {
                        throw new ApiException(ApiCode.FORBIDDEN);
                    }
                    return cached;
                });
    }

    public ListEnvelope<Map<String, Object>> myReports(AuthenticatedUser principal, String clientRequestId,
                                                      String cursor, Integer limit) {
        policy.requireActiveBusinessUser(principal);
        List<String> ids = jdbc.query("""
                SELECT id FROM content_report
                WHERE reporter_id=? AND (?::text IS NULL OR client_request_id=?)
                ORDER BY created_at DESC, id
                """, (rs, n) -> rs.getString(1), principal.userId(), clientRequestId, clientRequestId);
        return page(principal.userId(), "MY_REPORTS", queryKey(clientRequestId), ids, cursor, limit,
                id -> reportPublic(requireReport(id)));
    }

    public Map<String, Object> reportDetail(AuthenticatedUser principal, String id) {
        policy.requireActiveBusinessUser(principal);
        Map<String, Object> row = requireReport(id);
        if (!reports.canRead(principal.userId(), row)) {
            throw new ApiException(ApiCode.FORBIDDEN);
        }
        Map<String, Object> view = reportPublic(row);
        view.put("selectedExcerpt", row.get("excerpt"));
        view.put("handlingNote", latestNote(id));
        return view;
    }

    public Map<String, Object> handleReport(AuthenticatedUser principal, String id, SupportRequests.ReportAction body,
                                           String key, String path) {
        return idempotency.execute(new IdempotencyRequest(principal, "POST", path, key, body,
                        List.of(new IdempotencyRequest.ResourceRef("CONTENT_REPORT", id))),
                Map.class, () -> requireReportHandler(principal, id), () -> {
                    policy.requireActiveBusinessUser(principal);
                    Map<String, Object> row = one("SELECT * FROM content_report WHERE id=? FOR UPDATE", id);
                    if (row == null) {
                        throw ApiException.notFound();
                    }
                    if (!reports.canHandle(principal.userId(), row)) {
                        throw new ApiException(ApiCode.FORBIDDEN);
                    }
                    if (!assignedFor(body.action(), principal.userId(), row)) {
                        throw new ApiException(ApiCode.FORBIDDEN);
                    }
                    if (((Number) row.get("revision")).intValue() != body.expectedRevision()) {
                        throw new ApiException(ApiCode.RESOURCE_CHANGED);
                    }
                    String next = nextReportStatus((String) row.get("status"), body.action());
                    if (next == null) {
                        throw new ApiException(ApiCode.RESOURCE_CHANGED, "当前状态不能执行该处理动作");
                    }
                    int updated = "ACCEPT".equals(body.action())
                            ? jdbc.update("""
                                    UPDATE content_report SET assignee_id=?, status=?, revision=revision+1, updated_at=?
                                    WHERE id=? AND revision=? AND status=?
                                      AND (assignee_id IS NULL OR assignee_id=?)
                                    """, principal.userId(), next, now(), id, body.expectedRevision(),
                                    row.get("status"), principal.userId())
                            : jdbc.update("""
                                    UPDATE content_report SET status=?, revision=revision+1, updated_at=?
                                    WHERE id=? AND revision=? AND status=? AND assignee_id=?
                                    """, next, now(), id, body.expectedRevision(), row.get("status"),
                                    principal.userId());
                    if (updated != 1) {
                        throw new ApiException(ApiCode.RESOURCE_CHANGED);
                    }
                    jdbc.update("""
                            INSERT INTO report_handling(id, report_id, action, actor_id, note, created_at)
                            VALUES (?, ?, ?, ?, ?, ?)
                            """, UUID.randomUUID().toString(), id, body.action(), principal.userId(),
                            body.note(), now());
                    return reportDetail(principal, id);
                }, cached -> {
                    requireReportHandler(principal, id);
                    Map<String, Object> row = requireReport(id);
                    if (!principal.userId().equals(row.get("assigneeId"))
                            && !principal.userId().equals(row.get("reporterId"))) {
                        throw new ApiException(ApiCode.FORBIDDEN);
                    }
                    if (!reports.canRead(principal.userId(), row)) {
                        throw new ApiException(ApiCode.FORBIDDEN);
                    }
                    return cached;
                });
    }

    private Map<String, Object> mutate(AuthenticatedUser principal, String id, Object body, String key, String path,
                                      Function<Map<String, Object>, Map<String, Object>> action) {
        policy.requireActiveBusinessUser(principal);
        return idempotency.execute(new IdempotencyRequest(principal, "POST", path, key, body, List.of(new IdempotencyRequest.ResourceRef("SUPPORT_TICKET", id))),
                Map.class, () -> policy.requireActiveBusinessUser(principal), () -> {
                    Map<String, Object> row = lockTicket(id);
                    access.requireReadable(principal, row);
                    int revision = expected(body);
                    if (((Number) row.get("revision")).intValue() != revision) {
                        throw new ApiException(ApiCode.RESOURCE_CHANGED);
                    }
                    ensureHistoryRoom(id);
                    return action.apply(row);
                }, cached -> {
                    requireReadable(principal, id);
                    return cached;
                });
    }

    private int expected(Object body) {
        if (body instanceof SupportRequests.ExpectedRevision revision) {
            return revision.expectedRevision();
        }
        if (body instanceof SupportRequests.StatusAction action) {
            return action.expectedRevision();
        }
        if (body instanceof SupportRequests.Resolution resolution) {
            return resolution.expectedRevision();
        }
        if (body instanceof SupportRequests.Transfer transfer) {
            return transfer.expectedRevision();
        }
        if (body instanceof SupportRequests.Closure closure) {
            return closure.expectedRevision();
        }
        throw ApiException.validation("缺少版本");
    }

    private List<Map<String, Object>> published(String audience, String squareId, String q, String role,
                                               String appVersion) {
        List<Map<String, Object>> rows = jdbc.query("""
                SELECT * FROM help_article
                WHERE audience=? AND published=true
                  AND (?::text IS NULL OR square_id=?)
                  AND (?::text IS NULL OR title ILIKE ? OR body ILIKE ?)
                ORDER BY updated_at DESC, id ASC
                """, (rs, n) -> articleRow(rs), audience, squareId, squareId, q, like(q), like(q));
        return rows.stream()
                .filter(row -> versionMatches((String) row.get("appVersionRange"), appVersion))
                .filter(row -> role == null || role.isBlank() || roles(row).isEmpty() || roles(row).contains(role))
                .map(this::articleView)
                .toList();
    }

    private Map<String, Object> articleView(Map<String, Object> row) {
        JsonNode entry = json(row.get("entryJson"));
        String entryType = entry.path("type").isMissingNode() || entry.path("type").isNull()
                || entry.path("type").asText().isBlank() ? null : entry.path("type").asText();
        String entryId = entry.path("id").isNull() || entry.path("id").asText().isBlank() ? null : entry.path("id").asText();
        return map("id", row.get("id"), "title", row.get("title"), "category", row.get("category"),
                "roles", roles(row), "scope", row.get("scope") == null ? row.get("audience") : row.get("scope"),
                "squareId", row.get("squareId"), "appVersionRange", row.get("appVersionRange"),
                "prerequisites", textList(row.get("prerequisitesJson")), "steps", textList(row.get("stepsJson")),
                "outcomes", textList(row.get("outcomesJson")),
                "failureAction", row.get("failureAction") == null ? "" : row.get("failureAction"),
                "entry", map("type", entryType, "id", entryId),
                "contentVersion", row.get("contentVersion"), "revision", row.get("revision"),
                "updatedAt", row.get("updatedAt"), "maintainer", null);
    }

    private Map<String, Object> ticketView(Map<String, Object> row) {
        return map("id", row.get("id"), "clientRequestId", row.get("clientRequestId"),
                "requesterId", row.get("requesterId"), "category", row.get("category"),
                "title", row.get("title") == null ? row.get("subject") : row.get("title"),
                "content", row.get("content") == null ? row.get("body") : row.get("content"),
                "squareId", row.get("squareId"), "context", context(row.get("contextJson")),
                "attachmentFileIds", textList(row.get("attachmentFileIds")),
                "status", row.get("status"), "assigneeId", row.get("assigneeId"),
                "resolvedByRequester", Boolean.TRUE.equals(row.get("resolvedByRequester")),
                "resolutionNote", row.get("resolutionNote"), "revision", row.get("revision"),
                "createdAt", row.get("createdAt"), "updatedAt", row.get("updatedAt"));
    }

    private Map<String, Object> replyView(Map<String, Object> row) {
        return map("id", row.get("id"), "ticketId", row.get("ticketId"), "authorId", row.get("authorId"),
                "content", row.get("body"), "attachmentFileIds", textList(row.get("attachmentFileIds")),
                "createdAt", row.get("createdAt"));
    }

    private Map<String, Object> reportPublic(Map<String, Object> row) {
        return map("id", row.get("id"), "reporterId", row.get("reporterId"),
                "source", map("type", row.get("contentType"), "id", row.get("contentId"),
                        "version", row.get("sourceVersion")),
                "reason", row.get("reason"), "status", row.get("status"),
                "revision", row.get("revision"), "receiverLabel", REPORT_LABEL,
                "createdAt", row.get("createdAt"));
    }

    private List<Map<String, Object>> transferTargets(AuthenticatedUser principal, Map<String, Object> row) {
        if (principal.userId().equals(row.get("requesterId")) || !principal.userId().equals(row.get("assigneeId"))
                || "CLOSED".equals(row.get("status"))) {
            return List.of();
        }
        List<Map<String, Object>> targets = new ArrayList<>();
        for (Map<String, Object> route : jdbc.query("SELECT * FROM support_route WHERE available=true ORDER BY id",
                (rs, n) -> routeRow(rs))) {
            if (targets.size() == 20) {
                break;
            }
            if (route.get("id").equals(row.get("routeId")) || route.get("receiverUserId") == null) {
                continue;
            }
            if (!row.get("category").equals(route.get("category"))) {
                continue;
            }
            if ("OPERATION".equals(row.get("category"))) {
                if (route.get("squareId") != null || row.get("squareId") != null) {
                    continue;
                }
            } else if (route.get("squareId") == null || !route.get("squareId").equals(row.get("squareId"))) {
                continue;
            }
            if (row.get("requesterId").equals(route.get("receiverUserId"))) {
                continue;
            }
            if (!access.canReceive((String) route.get("receiverUserId"), (String) route.get("squareId"),
                    (String) row.get("requesterId"))) {
                continue;
            }
            targets.add(map("routeId", route.get("id"), "receiverLabel", route.get("name"),
                    "category", route.get("category"), "squareId", route.get("squareId")));
        }
        return targets;
    }

    private Map<String, Object> usableRoute(String routeId, String category, String squareId,
                                            AuthenticatedUser courseUser, String excludedUserId) {
        Map<String, Object> route = one("SELECT * FROM support_route WHERE id=?", routeId);
        if (route == null || !Boolean.TRUE.equals(route.get("available")) || route.get("receiverUserId") == null
                || !access.canReceive((String) route.get("receiverUserId"), (String) route.get("squareId"),
                excludedUserId)) {
            throw new ApiException(ApiCode.SUPPORT_ROUTE_UNAVAILABLE);
        }
        if (!category.equals(route.get("category"))) {
            throw ApiException.validation("渠道与求助类别不一致");
        }
        if ("OPERATION".equals(category)) {
            if (squareId != null || route.get("squareId") != null) {
                throw ApiException.validation("操作类求助不能绑定课程");
            }
        } else {
            if (squareId == null || route.get("squareId") == null || !squareId.equals(route.get("squareId"))) {
                throw ApiException.validation("课程求助必须使用同一课程范围的渠道");
            }
            if (courseUser != null) {
                requireCourse(squareId, courseUser);
            }
        }
        return route;
    }

    private List<String> bindFiles(String userId, String ticketId, String resourceId, List<String> requested) {
        List<String> ids = requested == null ? List.of() : requested;
        if (ids.size() > 3 || new LinkedHashSet<>(ids).size() != ids.size()) {
            throw ApiException.validation("附件最多3个且不能重复");
        }
        for (String fileId : ids) {
            StoredFileRow file = files.lockFile(fileId).orElseThrow(() -> ApiException.validation("附件不存在或不可用"));
            if (!userId.equals(file.uploaderId()) || !"READY".equals(file.status())
                    || !"SUPPORT_ATTACHMENT".equals(file.purpose()) || !IMAGES.contains(file.contentType())) {
                throw ApiException.validation("只能引用本人已就绪的求助图片");
            }
            if (file.ticketId() != null && !ticketId.equals(file.ticketId())) {
                throw ApiException.validation("附件已属于其他求助");
            }
            Integer elsewhere = jdbc.queryForObject("""
                    SELECT count(*) FROM file_reference
                    WHERE file_id=? AND resource_id <> ?
                    """, Integer.class, fileId, resourceId);
            if (elsewhere != null && elsewhere > 0 && file.ticketId() != null && !ticketId.equals(file.ticketId())) {
                throw ApiException.validation("附件已属于其他求助");
            }
            if (files.assignTicket(fileId, ticketId, clock.instant()) != 1) {
                throw ApiException.validation("附件不能绑定到当前求助");
            }
            files.insertReference(UUID.randomUUID().toString(), fileId,
                    resourceId.equals(ticketId) ? "SUPPORT_TICKET" : "SUPPORT_REPLY",
                    resourceId, 1, null, clock.instant());
        }
        return List.copyOf(ids);
    }

    private Content sourceContent(AuthenticatedUser principal, String type, String id) {
        if ("BOARD_TOPIC".equals(type)) {
            Map<String, Object> row = one("SELECT content, revision, project_id FROM board_topic WHERE id=?", id);
            if (row == null || row.get("content") == null) {
                throw ApiException.notFound();
            }
            requireMember((String) row.get("projectId"), principal.userId());
            return contentOf(id, type, row);
        }
        if ("BOARD_REPLY".equals(type)) {
            Map<String, Object> row = one("""
                    SELECT r.content, r.revision, t.project_id FROM board_reply r
                    JOIN board_topic t ON t.id=r.topic_id WHERE r.id=?
                    """, id);
            if (row == null || row.get("content") == null) {
                throw ApiException.notFound();
            }
            requireMember((String) row.get("projectId"), principal.userId());
            return contentOf(id, type, row);
        }
        if ("CHAT_MESSAGE".equals(type)) {
            Map<String, Object> row = one("""
                    SELECT content, revision, project_id FROM chat_message WHERE id=? AND withdrawn=false
                    """, id);
            if (row == null || row.get("content") == null) {
                throw ApiException.notFound();
            }
            requireMember((String) row.get("projectId"), principal.userId());
            return contentOf(id, type, row);
        }
        throw ApiException.validation("source.type 无效");
    }

    private Content contentOf(String id, String type, Map<String, Object> row) {
        String projectId = (String) row.get("projectId");
        String squareId = jdbc.queryForObject("SELECT square_id FROM project WHERE id=?", String.class, projectId);
        return new Content(id, type, (String) row.get("content"), ((Number) row.get("revision")).intValue(), squareId);
    }

    private void requireArticleWriter(AuthenticatedUser principal, String id) {
        policy.requireActiveBusinessUser(principal);
        Map<String, Object> row = one("SELECT audience, square_id FROM help_article WHERE id=?", id);
        if (row == null) {
            throw ApiException.notFound();
        }
        authorizeStoredArticle(principal, row);
    }

    private void authorizeStoredArticle(AuthenticatedUser principal, Map<String, Object> row) {
        String audience = (String) row.get("audience");
        if ("GENERAL".equals(audience)) {
            if (!globalCapability(principal.userId(), "HELP_CONTENT_WRITE")) {
                throw new ApiException(ApiCode.FORBIDDEN);
            }
            return;
        }
        if ("COURSE".equals(audience)) {
            requireTeacher((String) row.get("squareId"), principal.userId());
            return;
        }
        throw new ApiException(ApiCode.FORBIDDEN);
    }

    private Map<String, Object> applyArticleUpdate(AuthenticatedUser principal, String id,
                                                  SupportRequests.ArticleUpdate body) {
        policy.requireActiveBusinessUser(principal);
        Map<String, Object> old = one("SELECT * FROM help_article WHERE id=? FOR UPDATE", id);
        if (old == null) {
            throw ApiException.notFound();
        }
        authorizeStoredArticle(principal, old);
        String audience = (String) old.get("audience");
        String squareId = (String) old.get("squareId");
        if (!audience.equals(old.get("scope"))
                || ("GENERAL".equals(audience) && squareId != null)
                || ("COURSE".equals(audience) && squareId == null)) {
            throw ApiException.validation("帮助条目的范围记录不一致");
        }
        SupportRequests.ArticleBody article = body.article();
        if (!audience.equals(article.scope()) || !Objects.equals(squareId, article.squareId())) {
            throw ApiException.validation("不能改变帮助条目的通用或课程范围");
        }
        if (((Number) old.get("revision")).intValue() != body.expectedRevision()) {
            throw new ApiException(ApiCode.RESOURCE_CHANGED);
        }
        String stepsText = String.join("\n", article.steps());
        if (stepsText.length() > HelpArticleText.MAX_DERIVED_BODY) {
            throw ApiException.validation("步骤拼接后的正文超过" + HelpArticleText.MAX_DERIVED_BODY + "字符");
        }
        int updated = jdbc.update("""
                UPDATE help_article SET title=?, body=?, summary=?, category=?, app_version_range=?,
                    roles_json=?::jsonb, prerequisites_json=?::jsonb,
                    steps_json=?::jsonb, outcomes_json=?::jsonb, failure_action=?, entry_json=?::jsonb,
                    content_version=content_version+1, revision=revision+1, updated_at=?
                WHERE id=? AND revision=?
                """, article.title(), stepsText, article.failureAction(), article.category(),
                article.appVersionRange(), write(article.roles()),
                write(article.prerequisites()), write(article.steps()), write(article.outcomes()),
                article.failureAction(), write(article.entry()), now(), id, body.expectedRevision());
        if (updated != 1) {
            throw new ApiException(ApiCode.RESOURCE_CHANGED);
        }
        if (HelpContentPack.visible(audience, Boolean.TRUE.equals(old.get("published")))) {
            packs.advance(now());
        }
        return article(principal, id);
    }

    private void requireFeedbackReader(AuthenticatedUser principal, String id) {
        policy.requireActiveBusinessUser(principal);
        Map<String, Object> row = one(
                "SELECT audience, square_id, published FROM help_article WHERE id=? AND published=true", id);
        if (row == null) {
            throw ApiException.notFound();
        }
        if ("COURSE".equals(row.get("audience"))) {
            requireCourse((String) row.get("squareId"), principal);
        }
    }

    private void requireReportHandler(AuthenticatedUser principal, String id) {
        policy.requireActiveBusinessUser(principal);
        Map<String, Object> row = requireReport(id);
        if (!reports.canHandle(principal.userId(), row)) {
            throw new ApiException(ApiCode.FORBIDDEN);
        }
    }

    private boolean assignedFor(String action, String userId, Map<String, Object> row) {
        Object assignee = row.get("assigneeId");
        if ("ACCEPT".equals(action)) {
            return assignee == null || userId.equals(assignee);
        }
        return userId.equals(assignee);
    }

    /**
     * 普通处理动作不能离开 CLOSED，也不能把 HANDLED 退回处理中。
     * 契约只写明动作进入的目标状态，没有重开或在已处理后补记结果，因此这两种都不做。
     */
    private String nextReportStatus(String status, String action) {
        return switch (status) {
            case "SUBMITTED" -> "ACCEPT".equals(action) ? "IN_PROGRESS" : null;
            case "IN_PROGRESS" -> switch (action) {
                case "RECORD_RESULT" -> "HANDLED";
                case "CLOSE" -> "CLOSED";
                default -> null;
            };
            case "HANDLED" -> "CLOSE".equals(action) ? "CLOSED" : null;
            default -> null;
        };
    }

    private boolean globalCapability(String userId, String capability) {
        Boolean covered = jdbc.queryForObject("""
                SELECT EXISTS(
                    SELECT 1 FROM operator_grant
                    WHERE user_id=? AND capability=? AND active=true AND scope_type='GLOBAL'
                )
                """, Boolean.class, userId, capability);
        return Boolean.TRUE.equals(covered);
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
        if (count == null || count == 0) {
            throw new ApiException(ApiCode.FORBIDDEN);
        }
    }

    private void requireTeacher(String squareId, String userId) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM teacher_assignment WHERE square_id=? AND user_id=? AND active
                """, Integer.class, squareId, userId);
        if (count == null || count == 0) {
            throw new ApiException(ApiCode.FORBIDDEN);
        }
    }

    private void requireMember(String projectId, String userId) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM membership WHERE project_id=? AND user_id=? AND status='ACTIVE'
                """, Integer.class, projectId, userId);
        if (count == null || count == 0) {
            throw new ApiException(ApiCode.FORBIDDEN);
        }
    }

    private void requireOperator(AuthenticatedUser principal, String capability) {
        policy.requireActiveBusinessUser(principal);
        if (!isOperator(principal.userId(), capability)) {
            throw new ApiException(ApiCode.FORBIDDEN);
        }
    }

    private boolean isOperator(String userId, String capability) {
        if (userId == null) {
            return false;
        }
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM operator_grant WHERE user_id=? AND capability=? AND active=true
                """, Integer.class, userId, capability);
        return count != null && count > 0;
    }

    private Map<String, Object> requireReadable(AuthenticatedUser principal, String id) {
        Map<String, Object> row = requireTicket(id);
        access.requireReadable(principal, row);
        return row;
    }

    private void requireParticipant(AuthenticatedUser principal, Map<String, Object> row) {
        access.requireReadable(principal, row);
    }

    private Map<String, Object> lockTicket(String id) {
        Map<String, Object> row = one("SELECT * FROM support_ticket WHERE id=? FOR UPDATE", id);
        if (row == null) {
            throw ApiException.notFound();
        }
        return row;
    }

    private Map<String, Object> requireTicket(String id) {
        Map<String, Object> row = one("SELECT * FROM support_ticket WHERE id=?", id);
        if (row == null) {
            throw ApiException.notFound();
        }
        return row;
    }

    private void lockClientRequest(String kind, String userId, String clientRequestId) {
        jdbc.query("SELECT pg_advisory_xact_lock(?)",
                (ResultSetExtractor<Void>) rs -> null,
                Hashing.advisoryLockKey(kind + "|" + userId + "|" + clientRequestId));
    }

    private Map<String, Object> ticketByClient(String userId, String clientRequestId) {
        return one("SELECT * FROM support_ticket WHERE requester_id=? AND client_request_id=?",
                userId, clientRequestId);
    }

    private Map<String, Object> reportByClient(String userId, String clientRequestId) {
        return one("SELECT * FROM content_report WHERE reporter_id=? AND client_request_id=?",
                userId, clientRequestId);
    }

    private Map<String, Object> requireReport(String id) {
        Map<String, Object> row = one("SELECT * FROM content_report WHERE id=?", id);
        if (row == null) {
            throw ApiException.notFound();
        }
        return row;
    }

    private String latestNote(String reportId) {
        List<String> notes = jdbc.query("""
                SELECT note FROM report_handling WHERE report_id=? ORDER BY created_at DESC, id DESC LIMIT 1
                """, (rs, n) -> rs.getString(1), reportId);
        return notes.isEmpty() ? null : notes.getFirst();
    }

    private void ensureHistoryRoom(String ticketId) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM support_history WHERE ticket_id=?",
                Integer.class, ticketId);
        if (count != null && count >= HISTORY_LIMIT) {
            throw new ApiException(ApiCode.COLLECTION_TOO_LARGE, "处理历史已满，不能继续追加");
        }
    }

    private void history(String ticketId, String action, String actor, String reason) {
        ensureHistoryRoom(ticketId);
        jdbc.update("""
                INSERT INTO support_history(id, ticket_id, action, actor_id, reason, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID().toString(), ticketId, action, actor, reason == null ? "" : reason, now());
        audit.record(actor, "SUPPORT_" + action, "SUPPORT_TICKET", ticketId, null, null,
                reason == null || reason.isBlank() ? null : reason);
    }

    private <T> ListEnvelope<T> page(String userId, String scope, String query, List<String> orderedIds,
                                    String cursor, Integer limit, Function<String, T> load) {
        return page(false, userId, scope, query, orderedIds, cursor, limit, load);
    }

    private <T> ListEnvelope<T> pageAnonymous(String scope, String query, List<String> orderedIds,
                                             String cursor, Integer limit, Function<String, T> load) {
        return page(true, null, scope, query, orderedIds, cursor, limit, load);
    }

    private <T> ListEnvelope<T> page(boolean anonymous, String userId, String scope, String query,
                                    List<String> orderedIds, String cursor, Integer limit,
                                    Function<String, T> load) {
        int size = limit == null ? 20 : limit;
        if (size < 1 || size > 100) {
            throw ApiException.validation("limit 必须在 1 到 100");
        }
        String hash = Hashing.sha256Hex(query == null ? "" : query);
        List<String> ids = orderedIds;
        int position = 0;
        Instant expires = null;
        if (cursor != null && !cursor.isBlank()) {
            CursorService.CursorData data = anonymous
                    ? cursors.resolveAnonymous(cursor, scope, hash)
                    : cursors.resolve(cursor, userId, scope, hash);
            ids = data.ids();
            position = data.position();
            expires = data.expiresAt();
        }
        int end = Math.min(position + size, ids.size());
        List<T> items = new ArrayList<>();
        for (int i = position; i < end; i++) {
            T item = load.apply(ids.get(i));
            if (item != null) {
                items.add(item);
            }
        }
        boolean more = end < ids.size();
        Instant expiry = expires == null ? clock.instant().plus(Duration.ofMinutes(15)) : expires;
        String next = null;
        if (more) {
            next = anonymous
                    ? cursors.createAnonymous(scope, hash, 1, ids, end, expiry)
                    : cursors.create(userId, scope, hash, 1, ids, end, expiry);
        }
        return ListEnvelope.page(items, next, more, ids.size());
    }

    private Map<String, Object> one(String sql, Object... args) {
        List<Map<String, Object>> rows = jdbc.query(sql, (rs, n) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            var meta = rs.getMetaData();
            for (int i = 1; i <= meta.getColumnCount(); i++) {
                row.put(camel(meta.getColumnLabel(i)), normalizeTemporal(rs.getObject(i)));
            }
            return row;
        }, args);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private Map<String, Object> articleRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return oneRow(rs);
    }

    private Map<String, Object> routeRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return oneRow(rs);
    }

    private Map<String, Object> oneRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        var meta = rs.getMetaData();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            row.put(camel(meta.getColumnLabel(i)), normalizeTemporal(rs.getObject(i)));
        }
        return row;
    }

    private Map<String, Object> context(Object value) {
        JsonNode node = json(value);
        if (node.isMissingNode() || node.isNull() || !node.isObject() || node.isEmpty()) {
            return map("pageType", null, "resourceId", null, "errorCode", null, "appVersion", null);
        }
        return map("pageType", text(node, "pageType"), "resourceId", text(node, "resourceId"),
                "errorCode", text(node, "errorCode"), "appVersion", text(node, "appVersion"));
    }

    private List<String> roles(Map<String, Object> row) {
        return textList(row.get("rolesJson"));
    }

    private List<String> textList(Object value) {
        JsonNode node = json(value);
        if (!node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        node.forEach(item -> {
            if (!item.isNull()) {
                values.add(item.asText());
            }
        });
        return values;
    }

    private JsonNode json(Object value) {
        if (value == null) {
            return mapper.nullNode();
        }
        try {
            if (value instanceof JsonNode node) {
                return node;
            }
            String text = value instanceof String string ? string : value.toString();
            if (text.isBlank()) {
                return mapper.nullNode();
            }
            return mapper.readTree(text);
        } catch (Exception ex) {
            return mapper.nullNode();
        }
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || value.asText().isBlank()) {
            return null;
        }
        return value.asText();
    }

    private boolean versionMatches(String range, String appVersion) {
        if (appVersion == null || appVersion.isBlank() || range == null || range.isBlank() || "*".equals(range)) {
            return true;
        }
        if (range.endsWith(".*")) {
            return appVersion.startsWith(range.substring(0, range.length() - 1));
        }
        return range.equals(appVersion);
    }

    private Map<String, Object> unavailable(String category, boolean requiresSquare) {
        return map("available", false, "routeId", null, "category", category,
                "receiverLabel", null, "requiresSquare", requiresSquare);
    }

    private String queryKey(String... parts) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                out.append('|');
            }
            if (parts[i] != null) {
                out.append(parts[i]);
            }
        }
        return out.toString();
    }

    private String trim(Object value) {
        return value == null ? null : value.toString().strip();
    }

    private static Object normalizeTemporal(Object value) {
        if (value instanceof OffsetDateTime odt) {
            return odt.toInstant();
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toInstant();
        }
        if (value instanceof java.time.ZonedDateTime zoned) {
            return zoned.toInstant();
        }
        return value;
    }

    private Instant instant(Object value) {
        Object normalized = normalizeTemporal(value);
        return normalized instanceof Instant time ? time : null;
    }

    private OffsetDateTime startOfUtcDay() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).toLocalDate().atStartOfDay().atOffset(ZoneOffset.UTC);
    }

    private String camel(String label) {
        StringBuilder out = new StringBuilder();
        boolean upper = false;
        for (char c : label.toCharArray()) {
            if (c == '_') {
                upper = true;
            } else if (upper) {
                out.append(Character.toUpperCase(c));
                upper = false;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private String like(String q) {
        return q == null ? null : "%" + q.replace("%", "\\%").replace("_", "\\_") + "%";
    }

    private Map<String, Object> map(Object... kv) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            row.put((String) kv[i], kv[i + 1]);
        }
        return row;
    }

    private String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("无法序列化", ex);
        }
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private record Content(String id, String type, String text, int version, String squareId) {
    }
}
