package cn.gulupai.server.community.application;

import cn.gulupai.server.common.api.ApiCode;
import cn.gulupai.server.common.api.ApiException;
import cn.gulupai.server.common.audit.AuditService;
import cn.gulupai.server.common.idempotency.CanonicalJson;
import cn.gulupai.server.common.idempotency.IdempotencyRequest;
import cn.gulupai.server.common.idempotency.IdempotencyService;
import cn.gulupai.server.common.idempotency.ReplayProjector;
import cn.gulupai.server.common.security.AuthenticatedUser;
import cn.gulupai.server.common.security.CurrentUserPolicy;
import cn.gulupai.server.common.security.Hashing;
import cn.gulupai.server.community.api.ChatRequests;
import cn.gulupai.server.community.api.ChatViews;
import cn.gulupai.server.community.persistence.ChatRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class ChatService {

    private static final Duration WITHDRAW_WINDOW = Duration.ofSeconds(120);
    private static final Duration SYNC_CURSOR_TTL = Duration.ofDays(7);
    private static final int CHANGE_BATCH = 100;
    private static final TypeReference<List<ChatViews.Ref>> REFS = new TypeReference<>() {
    };
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {
    };

    private final ChatRepository repo;
    private final CurrentUserPolicy policy;
    private final IdempotencyService idempotency;
    private final CanonicalJson canonicalJson;
    private final AuditService audit;
    private final ObjectMapper mapper;
    private final Clock clock;

    public ChatService(ChatRepository repo, CurrentUserPolicy policy,
                       IdempotencyService idempotency, CanonicalJson canonicalJson,
                       AuditService audit, ObjectMapper mapper, Clock clock) {
        this.repo = repo;
        this.policy = policy;
        this.idempotency = idempotency;
        this.canonicalJson = canonicalJson;
        this.audit = audit;
        this.mapper = mapper;
        this.clock = clock;
    }

    /**
     * CHAT-01。历史页、变更批次和同步游标在同一 REPEATABLE READ 快照中读取，
     * 避免“读完历史页后又发生撤回、再取高水位”导致漏掉撤回投影。
     * GET 只登记真实下发的历史序号区间与同步游标，不推进已读位置、不增加未读。
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public ChatViews.Page messages(AuthenticatedUser principal, String projectId,
                                   Long afterSequence, Long beforeSequence, Long aroundSequence,
                                   String q, Integer limit, String afterChangeCursor) {
        policy.requireActiveBusinessUser(principal);
        requireMember(projectId, principal.userId());
        if (afterSequence != null && beforeSequence != null) {
            throw ApiException.validation("afterSequence 与 beforeSequence 互斥");
        }
        if (afterSequence != null && afterSequence < 0 || beforeSequence != null && beforeSequence < 0
                || aroundSequence != null && aroundSequence < 0) {
            throw ApiException.validation("sequence 不能为负");
        }
        if (q != null && q.length() > 80) throw ApiException.validation("q 不能超过80个字符");
        if (q != null && (afterSequence != null || beforeSequence != null)) {
            throw ApiException.validation("q 与 after/before 互斥");
        }
        int size = limit == null ? 50 : limit;
        if (size < 1 || size > 100) throw ApiException.validation("limit 必须为1～100");

        Instant now = clock.instant();
        SyncState sync = resolveSyncCursor(principal.userId(), projectId, afterChangeCursor, now);

        List<ChatRepository.MessageRow> fetched = repo.list(projectId, afterSequence, beforeSequence,
                aroundSequence, q, size + 1);
        boolean ascending = afterSequence != null;
        boolean hasExtra = fetched.size() > size;
        List<ChatRepository.MessageRow> page = hasExtra
                ? (ascending ? fetched.subList(0, size) : fetched.subList(1, fetched.size()))
                : fetched;

        long nextAfter;
        if (afterSequence != null) {
            nextAfter = page.isEmpty() ? afterSequence : page.getLast().sequence();
        } else if (beforeSequence != null || aroundSequence != null || (q != null && !q.isBlank())) {
            // 稀疏/反向定位页不推进连续增量位置；由客户端保留原已知边界。
            nextAfter = 0;
        } else {
            nextAfter = page.isEmpty() ? 0 : page.getLast().sequence();
        }
        long latest = repo.latestSequence(projectId);
        boolean hasMore = hasExtra;

        List<ChatViews.Message> items = page.stream().map(this::view).toList();
        recordDeliveries(projectId, principal.userId(), page, now);

        List<ChatViews.Message> changes = List.of();
        boolean hasMoreChanges = false;
        String nextChangeCursor;
        if (sync.baseline()) {
            // 首次同步只建立当前高水位，不从 0 补发全部历史变更。
            nextChangeCursor = sync.rawToken();
        } else {
            long earliest = repo.earliestChangeSequence(projectId);
            if (earliest > 0 && sync.position() + 1 < earliest) {
                throw new ApiException(ApiCode.CHAT_SYNC_EXPIRED);
            }
            List<ChatRepository.ChangeRow> events = repo.changes(projectId, sync.position(),
                    CHANGE_BATCH + 1);
            hasMoreChanges = events.size() > CHANGE_BATCH;
            List<ChatRepository.ChangeRow> scanned = hasMoreChanges
                    ? events.subList(0, CHANGE_BATCH) : events;
            long nextPosition = scanned.isEmpty() ? sync.position() : scanned.getLast().changeSequence();
            // 输入 token 的位置不可变：响应丢失后客户端仍持旧 token 重试。
            // 只有扫描边界前进时才签发新 token，且不更新旧记录。
            nextChangeCursor = nextPosition > sync.position()
                    ? issueSyncCursor(principal.userId(), projectId, nextPosition, now)
                    : sync.rawToken();
            Map<String, ChatRepository.ChangeRow> merged = new LinkedHashMap<>();
            for (ChatRepository.ChangeRow event : scanned) {
                merged.merge(event.message().id(), event,
                        (left, right) -> right.message().revision() >= left.message().revision() ? right : left);
            }
            changes = merged.values().stream().map(event -> view(event.message())).toList();
        }

        return new ChatViews.Page(items, null, hasMore, nextAfter, latest, changes,
                nextChangeCursor, hasMoreChanges);
    }

    public ChatViews.Message send(AuthenticatedUser principal, String projectId,
                                  ChatRequests.Send request, String key, String path) {
        policy.requireActiveBusinessUser(principal);
        requireMember(projectId, principal.userId());
        String content = request.content() == null ? "" : request.content().strip();
        if (content.isEmpty() && (request.refs() == null || request.refs().isEmpty())) {
            throw ApiException.validation("content 与 refs 不能同时为空");
        }
        validateRefs(projectId, request.refs());
        validateMentions(projectId, request.mentions());
        String requestHash = requestHash(request);
        return idempotency.execute(request(principal, "POST", path, key, request),
                ChatViews.Message.class,
                () -> requireReadAccess(projectId, principal.userId()),
                () -> {
                    requireWritable(projectId, principal.userId());
                    return sendOnce(principal, projectId, request, content, requestHash);
                },
                cached -> currentMessage(cached.id(), projectId));
    }

    private ChatViews.Message sendOnce(AuthenticatedUser principal, String projectId,
                                       ChatRequests.Send request, String content, String requestHash) {
        // 先取项目计数器行锁，使同项目发送串行；唯一约束仍是最终防线。
        repo.lockCounter(projectId);
        ChatRepository.MessageRow existing = repo.byClientId(projectId, principal.userId(),
                request.clientMessageId()).orElse(null);
        if (existing != null) {
            if (existing.requestHash() != null) {
                if (!existing.requestHash().trim().equals(requestHash)) {
                    throw new ApiException(ApiCode.CLIENT_MESSAGE_ID_REUSED,
                            "clientMessageId 已绑定不同的完整消息请求");
                }
                return view(existing);
            }
            // 迁移前的旧记录无法无损还原原始请求：不猜测相同，也不恢复旧正文。
            throw new ApiException(ApiCode.CLIENT_MESSAGE_ID_REUSED,
                    "clientMessageId 已绑定旧版本消息，无法校验完整请求；请使用新的标识");
        }
        Instant now = clock.instant();
        long sequence = repo.nextSequence(projectId);
        String id = UUID.randomUUID().toString();
        try {
            repo.insertMessage(id, projectId, sequence, request.clientMessageId(), principal.userId(),
                    content, writeRefs(request.refs()), writeMentions(request.mentions()), requestHash, now);
        } catch (DataIntegrityViolationException ex) {
            throw new ApiException(ApiCode.CLIENT_MESSAGE_ID_REUSED);
        }
        long change = repo.nextChangeSequence(projectId);
        repo.insertChange(UUID.randomUUID().toString(), projectId, change, id, 1, now);
        repo.outbox(UUID.randomUUID().toString(), projectId, "CHAT_MESSAGE_SENT",
                write(Map.of("messageId", id, "sequence", sequence,
                        "mentions", request.mentions() == null ? List.of() : request.mentions())), now);
        audit.record(principal.userId(), "CHAT_SEND", "CHAT_MESSAGE", id, null, 1, null);
        return view(repo.message(id).orElseThrow());
    }

    @Transactional(readOnly = true)
    public ChatViews.Resolution resolve(AuthenticatedUser principal, String projectId,
                                        String clientMessageId) {
        policy.requireActiveBusinessUser(principal);
        requireMember(projectId, principal.userId());
        return repo.byClientId(projectId, principal.userId(), clientMessageId)
                .map(row -> new ChatViews.Resolution(true, view(row)))
                .orElseGet(() -> new ChatViews.Resolution(false, null));
    }

    public ChatViews.Message withdraw(AuthenticatedUser principal, String messageId,
                                      ChatRequests.Withdraw request, String key, String path) {
        policy.requireActiveBusinessUser(principal);
        ChatRepository.MessageRow message = repo.message(messageId).orElseThrow(ApiException::notFound);
        requireMember(message.projectId(), principal.userId());
        if (!message.authorId().equals(principal.userId())) throw new ApiException(ApiCode.FORBIDDEN);
        return idempotency.execute(request(principal, "POST", path, key, request),
                ChatViews.Message.class,
                () -> {
                    requireReadAccess(message.projectId(), principal.userId());
                    if (!message.authorId().equals(principal.userId())) {
                        throw new ApiException(ApiCode.FORBIDDEN);
                    }
                },
                () -> {
                    requireWritable(message.projectId(), principal.userId());
                    if (message.withdrawn()) throw new ApiException(ApiCode.RESOURCE_CHANGED);
                    if (message.createdAt().plus(WITHDRAW_WINDOW).isBefore(clock.instant())) {
                        throw new ApiException(ApiCode.MESSAGE_WITHDRAW_WINDOW_CLOSED);
                    }
                    if (repo.withdraw(messageId, request.expectedRevision(), clock.instant()) != 1) {
                        throw new ApiException(ApiCode.RESOURCE_CHANGED);
                    }
                    long change = repo.nextChangeSequence(message.projectId());
                    repo.insertChange(UUID.randomUUID().toString(), message.projectId(), change,
                            messageId, request.expectedRevision() + 1, clock.instant());
                    repo.outbox(UUID.randomUUID().toString(), message.projectId(),
                            "CHAT_MESSAGE_WITHDRAWN",
                            write(Map.of("messageId", messageId, "sequence", message.sequence())),
                            clock.instant());
                    return view(repo.message(messageId).orElseThrow());
                },
                cached -> currentMessage(cached.id(), message.projectId()));
    }

    @Transactional(readOnly = true)
    public ChatViews.Cursor cursor(AuthenticatedUser principal, String projectId) {
        policy.requireActiveBusinessUser(principal);
        requireMember(projectId, principal.userId());
        return new ChatViews.Cursor(projectId, repo.readCursor(projectId, principal.userId()));
    }

    public ChatViews.Cursor saveCursor(AuthenticatedUser principal, String projectId,
                                       ChatRequests.CursorPut request, String key, String path) {
        policy.requireActiveBusinessUser(principal);
        requireMember(projectId, principal.userId());
        return idempotency.execute(new IdempotencyRequest(principal, "PUT", path, key, request, List.of()),
                ChatViews.Cursor.class,
                () -> requireReadAccess(projectId, principal.userId()),
                () -> saveCursorOnce(principal, projectId, request),
                // 其他设备可能已推进；回放返回当前合法位置，不回退成旧回执值。
                cached -> new ChatViews.Cursor(projectId,
                        repo.readCursor(projectId, principal.userId())));
    }

    private ChatViews.Cursor saveCursorOnce(AuthenticatedUser principal, String projectId,
                                            ChatRequests.CursorPut request) {
        Instant now = clock.instant();
        long latest = repo.latestSequence(projectId);
        if (request.sequence() > latest) throw ApiException.validation("sequence 不能超过latestSequence");
        int epoch = repo.projectAuthorizationEpoch(projectId);
        long current = repo.lockReadState(projectId, principal.userId(), now);
        long target = request.sequence();
        if (target > current) {
            if (!covered(repo.deliveryRanges(principal.userId(), projectId, epoch), current, target)) {
                throw ApiException.validation("阅读位置必须覆盖从当前已读到目标的连续已授权下发消息");
            }
            repo.saveCursor(projectId, principal.userId(), target, now);
            repo.deleteDeliveryRangesUpTo(principal.userId(), projectId, epoch, target);
        }
        return new ChatViews.Cursor(projectId, Math.max(current, target));
    }

    private boolean covered(List<ChatRepository.DeliveryRangeRow> ranges, long from, long to) {
        long need = from + 1;
        for (ChatRepository.DeliveryRangeRow range : ranges) {
            if (range.endSequence() < need) continue;
            if (range.startSequence() > need) return false;
            need = Math.max(need, range.endSequence() + 1);
            if (need > to) return true;
        }
        return need > to;
    }

    private void recordDeliveries(String projectId, String userId,
                                  List<ChatRepository.MessageRow> page, Instant now) {
        if (page.isEmpty()) return;
        int epoch = repo.projectAuthorizationEpoch(projectId);
        long start = page.getFirst().sequence();
        long end = start;
        for (int i = 1; i < page.size(); i++) {
            long sequence = page.get(i).sequence();
            if (sequence == end + 1) {
                end = sequence;
                continue;
            }
            recordDelivery(projectId, userId, epoch, start, end, now);
            start = sequence;
            end = sequence;
        }
        recordDelivery(projectId, userId, epoch, start, end, now);
    }

    private void recordDelivery(String projectId, String userId, int epoch,
                                long start, long end, Instant now) {
        if (repo.deliveryRangeCovered(userId, projectId, epoch, start, end)) return;
        repo.insertDeliveryRange(UUID.randomUUID().toString(), userId, projectId, epoch, start, end, now);
    }

    private SyncState resolveSyncCursor(String userId, String projectId, String rawCursor, Instant now) {
        if (rawCursor == null || rawCursor.isBlank()) {
            long high = repo.latestChangeSequence(projectId);
            return new SyncState(high, issueSyncCursor(userId, projectId, high, now), true);
        }
        ChatRepository.SyncCursorRow row = repo.syncCursor(Hashing.sha256Hex(rawCursor))
                .orElseThrow(() -> new ApiException(ApiCode.CHAT_SYNC_EXPIRED));
        if (!row.userId().equals(userId) || !row.projectId().equals(projectId)
                || !row.expiresAt().isAfter(now)
                || row.authorizationEpoch() != repo.projectAuthorizationEpoch(projectId)) {
            throw new ApiException(ApiCode.CHAT_SYNC_EXPIRED);
        }
        return new SyncState(row.lastChangeSequence(), rawCursor, false);
    }

    private String issueSyncCursor(String userId, String projectId, long position, Instant now) {
        String token = Hashing.newToken();
        repo.insertSyncCursor(UUID.randomUUID().toString(), Hashing.sha256Hex(token), userId,
                projectId, repo.projectAuthorizationEpoch(projectId), position,
                now.plus(SYNC_CURSOR_TTL), now);
        return token;
    }

    private ChatViews.Message currentMessage(String messageId, String projectId) {
        ChatRepository.MessageRow row = repo.message(messageId)
                .filter(item -> item.projectId().equals(projectId))
                .orElseThrow(() -> new ApiException(ApiCode.NOT_FOUND));
        return view(row);
    }

    private String requestHash(ChatRequests.Send request) {
        List<Map<String, Object>> refs = new ArrayList<>();
        if (request.refs() != null) {
            for (ChatRequests.RefInput ref : request.refs()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("type", ref.type());
                item.put("id", ref.id());
                item.put("version", ref.version());
                refs.add(item);
            }
        }
        List<String> mentions = request.mentions() == null ? List.of()
                : new ArrayList<>(new LinkedHashSet<>(request.mentions()));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("requestHashVersion", 1);
        payload.put("content", request.content());
        payload.put("refs", refs);
        payload.put("mentions", mentions);
        return canonicalJson.hash(payload);
    }

    private ChatViews.Message view(ChatRepository.MessageRow row) {
        return new ChatViews.Message(row.id(), row.projectId(), row.clientMessageId(),
                row.sequence(), row.authorId(), row.authorName(),
                row.withdrawn() ? null : row.content(), null,
                row.withdrawn() ? List.of() : readStrings(row.mentionsJson()),
                row.withdrawn() ? List.of() : readRefs(row.refsJson()), row.revision(),
                row.withdrawn(), row.createdAt());
    }

    private void requireMember(String projectId, String userId) {
        if (!repo.activeMember(projectId, userId)) throw new ApiException(ApiCode.FORBIDDEN);
    }

    /** 回放只要求当前读取资格；归档/只读不阻止安全投影。 */
    private void requireReadAccess(String projectId, String userId) {
        requireMember(projectId, userId);
    }

    private void requireWritable(String projectId, String userId) {
        if (!repo.activeMember(projectId, userId)) throw new ApiException(ApiCode.FORBIDDEN);
        String status = repo.projectStatus(projectId);
        if (!"ACTIVE".equals(status) && !"CLOSING".equals(status)) {
            throw new ApiException(ApiCode.PROJECT_READ_ONLY);
        }
    }

    private void validateMentions(String projectId, List<String> mentions) {
        if (mentions == null) return;
        Set<String> unique = new LinkedHashSet<>(mentions);
        if (unique.size() > 20) throw ApiException.validation("mentions 最多20个");
        for (String mention : unique) {
            if (!repo.activeMember(projectId, mention)) {
                throw ApiException.validation("mention 必须是当前项目成员");
            }
        }
    }

    private void validateRefs(String projectId, List<ChatRequests.RefInput> refs) {
        if (refs == null) return;
        if (refs.size() > 5) throw ApiException.validation("refs 最多5个");
        for (ChatRequests.RefInput ref : refs) {
            if ("TASK".equals(ref.type()) && !repo.taskInProject(ref.id(), projectId)) {
                throw ApiException.validation("TASK 引用不属于当前项目");
            }
            if (!"TASK".equals(ref.type())) {
                throw ApiException.validation("当前聊天引用仅支持TASK");
            }
        }
    }

    private String writeRefs(List<ChatRequests.RefInput> refs) {
        try {
            return mapper.writeValueAsString(refs == null ? List.of() : refs);
        } catch (Exception ex) {
            throw new IllegalStateException("无法序列化引用", ex);
        }
    }

    private String writeMentions(List<String> mentions) {
        try {
            return mapper.writeValueAsString(mentions == null ? List.of()
                    : new ArrayList<>(new LinkedHashSet<>(mentions)));
        } catch (Exception ex) {
            throw new IllegalStateException("无法序列化提及", ex);
        }
    }

    private String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("无法序列化聊天", ex);
        }
    }

    private List<ChatViews.Ref> readRefs(String json) {
        try {
            return mapper.readValue(json, REFS);
        } catch (Exception ex) {
            return List.of();
        }
    }

    private List<String> readStrings(String json) {
        try {
            return mapper.readValue(json, STRINGS);
        } catch (Exception ex) {
            return List.of();
        }
    }

    private IdempotencyRequest request(AuthenticatedUser principal, String method, String path,
                                       String key, Object body) {
        return new IdempotencyRequest(principal, method, path, key, body, List.of());
    }

    private record SyncState(long position, String rawToken, boolean baseline) {
    }
}
