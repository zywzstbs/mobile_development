package cn.gulupai.server.notice.application;

import cn.gulupai.server.common.api.ApiCode;
import cn.gulupai.server.common.api.ApiException;
import cn.gulupai.server.common.audit.AuditService;
import cn.gulupai.server.common.idempotency.IdempotencyRequest;
import cn.gulupai.server.common.idempotency.IdempotencyService;
import cn.gulupai.server.common.security.AuthenticatedUser;
import cn.gulupai.server.common.security.CurrentUserPolicy;
import cn.gulupai.server.common.security.Hashing;
import cn.gulupai.server.course.persistence.SquareRepository;
import cn.gulupai.server.course.persistence.SquareRow;
import cn.gulupai.server.notice.api.NoticeRequests;
import cn.gulupai.server.notice.api.NoticeViews;
import cn.gulupai.server.notice.persistence.NoticeRepository;
import cn.gulupai.server.teaching.application.CourseAuthorityService;
import cn.gulupai.server.teaching.domain.TeacherCapabilities;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class NoticeService {

    private static final Duration PREVIEW_TTL = Duration.ofMinutes(10);

    private final NoticeRepository repo;
    private final SquareRepository squares;
    private final CourseAuthorityService authority;
    private final CurrentUserPolicy policy;
    private final IdempotencyService idempotency;
    private final AuditService audit;
    private final ObjectMapper mapper;
    private final Clock clock;

    public NoticeService(NoticeRepository repo, SquareRepository squares,
                         CourseAuthorityService authority, CurrentUserPolicy policy,
                         IdempotencyService idempotency, AuditService audit,
                         ObjectMapper mapper, Clock clock) {
        this.repo = repo;
        this.squares = squares;
        this.authority = authority;
        this.policy = policy;
        this.idempotency = idempotency;
        this.audit = audit;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<NoticeViews.Announcement> announcements(AuthenticatedUser principal, String squareId) {
        policy.requireActiveBusinessUser(principal);
        requireReader(squareId, principal.userId());
        return repo.announcements(squareId).stream()
                .map(row -> view(row, principal.userId())).toList();
    }

    public NoticeViews.Announcement create(AuthenticatedUser principal, String squareId,
                                           NoticeRequests.Create request, String key, String path) {
        policy.requireActiveBusinessUser(principal);
        requirePublisher(squareId, principal.userId(), request.publisherRole());
        return idempotency.execute(req(principal, "POST", path, key, request),
                NoticeViews.Announcement.class,
                () -> requirePublisher(squareId, principal.userId(), request.publisherRole()),
                () -> {
                    Payload payload;
                    if (request.previewToken() != null && !request.previewToken().isBlank()) {
                        payload = consumePreview(squareId, principal.userId(), request.previewToken());
                    } else {
                        payload = new Payload(request.title().strip(), request.content().strip(),
                                "OTHER", "NORMAL", false, null, null, null, null, List.of());
                    }
                    String id = UUID.randomUUID().toString();
                    String role = roleFor(squareId, principal.userId(), request.publisherRole());
                    return publish(principal, squareId, id, role, payload, 1, 1, null);
                });
    }

    public NoticeViews.Preview preview(AuthenticatedUser principal, String squareId,
                                       NoticeRequests.Preview request, String key, String path) {
        policy.requireActiveBusinessUser(principal);
        String role = requirePublisher(squareId, principal.userId(), request.publisherRole());
        return idempotency.execute(req(principal, "POST", path, key, request),
                NoticeViews.Preview.class,
                () -> policy.requireActiveBusinessUser(principal),
                () -> {
                    int count = repo.courseAudience(squareId).size();
                    ObjectNode payload = mapper.createObjectNode();
                    payload.put("title", request.title().strip());
                    payload.put("content", request.content().strip());
                    payload.put("category", request.category());
                    payload.put("importance", request.importance());
                    payload.put("requiresAcknowledgement", request.requiresAcknowledgement());
                    payload.put("acknowledgeDueAt", request.acknowledgeDueAt() == null ? null
                            : request.acknowledgeDueAt().toString());
                    payload.put("expiresAt", request.expiresAt() == null ? null
                            : request.expiresAt().toString());
                    payload.put("assignmentId", request.assignmentId());
                    payload.put("publisherRole", role);
                    payload.set("refs", mapper.valueToTree(request.refs() == null ? List.of() : request.refs()));
                    String token = Hashing.newToken();
                    Instant now = clock.instant();
                    Instant expires = now.plus(PREVIEW_TTL);
                    repo.insertPreview(UUID.randomUUID().toString(), squareId, principal.userId(),
                            Hashing.sha256Hex(token), write(payload), expires, now);
                    return new NoticeViews.Preview(token, expires, role, count,
                            new NoticeViews.Scope(squareId, request.assignmentId()));
                });
    }

    @Transactional(readOnly = true)
    public NoticeViews.Announcement announcement(AuthenticatedUser principal, String id) {
        policy.requireActiveBusinessUser(principal);
        NoticeRepository.AnnouncementRow row = requireAnnouncement(id);
        requireReader(row.squareId(), principal.userId());
        return view(row, principal.userId());
    }

    public NoticeViews.Announcement revise(AuthenticatedUser principal, String id,
                                           NoticeRequests.Revision request, String key, String path) {
        policy.requireActiveBusinessUser(principal);
        NoticeRepository.AnnouncementRow row = requireAnnouncement(id);
        requireManager(row, principal.userId());
        return idempotency.execute(req(principal, "POST", path, key, request),
                NoticeViews.Announcement.class,
                () -> policy.requireActiveBusinessUser(principal),
                () -> {
                    NoticeRepository.AnnouncementRow locked = repo.lockAnnouncement(id).orElseThrow();
                    if (locked.revision() != request.expectedRevision()) {
                        throw new ApiException(ApiCode.RESOURCE_CHANGED);
                    }
                    boolean substantive = "SUBSTANTIVE".equals(request.changeKind());
                    String oldNorm = normalize(locked.content());
                    String newNorm = normalize(request.content());
                    if (!substantive && !oldNorm.equals(newNorm)) {
                        throw ApiException.validation("FORMAT_ONLY 不能修改正文语义");
                    }
                    Instant now = clock.instant();
                    int contentVersion = locked.contentVersion();
                    int displayRevision = locked.displayRevision() + 1;
                    String snapshot = locked.snapshot();
                    int recipientCount = locked.recipientCount();
                    List<String> recipients = null;
                    String role = locked.publisherRole();
                    String assignmentId = locked.assignmentId();
                    String category = locked.category();
                    String importance = locked.importance();
                    boolean requires = locked.requiresAck();
                    Instant ackDue = locked.ackDue();
                    Instant expires = locked.expires();
                    if (substantive) {
                        Payload payload;
                        if (request.previewToken() != null && !request.previewToken().isBlank()) {
                            payload = consumePreview(locked.squareId(), principal.userId(), request.previewToken());
                            category = payload.category();
                            importance = payload.importance();
                            requires = payload.requiresAck();
                            ackDue = payload.ackDue();
                            expires = payload.expires();
                            assignmentId = payload.assignmentId();
                        } else {
                            payload = new Payload(request.title().strip(), request.content().strip(),
                                    category, importance, requires, ackDue, expires, assignmentId,
                                    role, List.of());
                        }
                        contentVersion++;
                        recipients = repo.courseAudience(locked.squareId());
                        snapshot = UUID.randomUUID().toString();
                        recipientCount = recipients.size();
                        repo.insertRecipients(snapshot, id, contentVersion, recipients);
                        repo.insertVersion(UUID.randomUUID().toString(), id, contentVersion,
                                displayRevision, request.title().strip(), request.content().strip(),
                                requires, ackDue, expires, snapshot, recipientCount,
                                request.changeSummary(), principal.userId(), now);
                    }
                    if (repo.updateAnnouncement(id, request.expectedRevision(), request.title().strip(),
                            request.content().strip(), contentVersion, displayRevision, snapshot,
                            recipientCount, request.changeSummary(), requires, ackDue, expires,
                            request.expectedRevision() + 1, now) != 1) {
                        throw new ApiException(ApiCode.RESOURCE_CHANGED);
                    }
                    if (substantive) {
                        notifyRecipients(id, contentVersion, request.title(), request.content(), recipients);
                    }
                    return view(repo.announcement(id).orElseThrow(), principal.userId());
                });
    }

    public NoticeViews.Announcement revoke(AuthenticatedUser principal, String id,
                                           NoticeRequests.Revoke request, String key, String path) {
        policy.requireActiveBusinessUser(principal);
        NoticeRepository.AnnouncementRow row = requireAnnouncement(id);
        requireManager(row, principal.userId());
        return idempotency.execute(req(principal, "POST", path, key, request),
                NoticeViews.Announcement.class,
                () -> policy.requireActiveBusinessUser(principal),
                () -> {
                    if (repo.revoke(id, request.expectedRevision(), request.reason().strip(),
                            clock.instant()) != 1) {
                        throw new ApiException(ApiCode.RESOURCE_CHANGED);
                    }
                    return view(repo.announcement(id).orElseThrow(), principal.userId());
                });
    }

    @Transactional
    public NoticeViews.Receipt read(AuthenticatedUser principal, String id,
                                    NoticeRequests.Receipt request) {
        policy.requireActiveBusinessUser(principal);
        NoticeRepository.AnnouncementRow row = requireAnnouncement(id);
        requireReader(row.squareId(), principal.userId());
        requireRecipient(id, row.contentVersion(), principal.userId());
        if (request.contentVersion() != row.contentVersion()) throw new ApiException(ApiCode.RESOURCE_CHANGED);
        repo.markRead(id, row.contentVersion(), principal.userId(), clock.instant());
        NoticeRepository.ReceiptRow receipt = repo.receipt(id, row.contentVersion(), principal.userId())
                .orElseThrow();
        return new NoticeViews.Receipt(receipt.contentVersion(), receipt.readAt(), receipt.acknowledgedAt());
    }

    @Transactional
    public NoticeViews.Receipt acknowledge(AuthenticatedUser principal, String id,
                                           NoticeRequests.Receipt request) {
        policy.requireActiveBusinessUser(principal);
        NoticeRepository.AnnouncementRow row = requireAnnouncement(id);
        requireReader(row.squareId(), principal.userId());
        requireRecipient(id, row.contentVersion(), principal.userId());
        if (request.contentVersion() != row.contentVersion()) throw new ApiException(ApiCode.RESOURCE_CHANGED);
        if (!row.requiresAck()) throw ApiException.validation("当前公告不要求知悉");
        if (row.ackDue() != null && row.ackDue().isBefore(clock.instant())) {
            throw new ApiException(ApiCode.RESOURCE_CHANGED, "知悉已截止");
        }
        repo.markRead(id, row.contentVersion(), principal.userId(), clock.instant());
        repo.markAck(id, row.contentVersion(), principal.userId(), clock.instant());
        NoticeRepository.ReceiptRow receipt = repo.receipt(id, row.contentVersion(), principal.userId())
                .orElseThrow();
        return new NoticeViews.Receipt(receipt.contentVersion(), receipt.readAt(), receipt.acknowledgedAt());
    }

    @Transactional(readOnly = true)
    public NoticeViews.ReceiptPage receipts(AuthenticatedUser principal, String id,
                                            int contentVersion, String filter) {
        policy.requireActiveBusinessUser(principal);
        NoticeRepository.AnnouncementRow row = requireAnnouncement(id);
        requireManager(row, principal.userId());
        List<NoticeViews.ReceiptRow> all = repo.receipts(id, contentVersion).stream()
                .map(r -> new NoticeViews.ReceiptRow(r.userId(), r.displayName(), r.readAt(),
                        r.acknowledgedAt())).toList();
        List<NoticeViews.ReceiptRow> filtered = all.stream()
                .filter(r -> filter == null || "ALL".equals(filter)
                        || "UNREAD".equals(filter) && r.readAt() == null
                        || "UNACKNOWLEDGED".equals(filter) && r.acknowledgedAt() == null).toList();
        return new NoticeViews.ReceiptPage(contentVersion, row.recipientCount(),
                (int) all.stream().filter(r -> r.readAt() != null).count(),
                (int) all.stream().filter(r -> r.acknowledgedAt() != null).count(), filtered);
    }

    public NoticeViews.ReminderPreview reminderPreview(AuthenticatedUser principal, String id,
                                                       NoticeRequests.ReminderPreview request,
                                                       String key, String path) {
        policy.requireActiveBusinessUser(principal);
        NoticeRepository.AnnouncementRow row = requireAnnouncement(id);
        requireManager(row, principal.userId());
        return idempotency.execute(req(principal, "POST", path, key, request),
                NoticeViews.ReminderPreview.class,
                () -> policy.requireActiveBusinessUser(principal),
                () -> {
                    if (row.revision() != request.expectedRevision()
                            || row.contentVersion() != request.contentVersion()) {
                        throw new ApiException(ApiCode.RESOURCE_CHANGED);
                    }
                    List<String> targets = repo.unconfirmedRecipients(id, request.contentVersion(), true);
                    String token = Hashing.newToken();
                    Instant now = clock.instant();
                    Instant expires = now.plus(PREVIEW_TTL);
                    repo.insertPreview(UUID.randomUUID().toString(), row.squareId(), principal.userId(),
                            Hashing.sha256Hex(token), write(Map.of("kind", "REMINDER",
                                    "announcementId", id, "contentVersion", request.contentVersion())),
                            expires, now);
                    return new NoticeViews.ReminderPreview(token, expires, targets.size());
                });
    }

    public NoticeViews.Reminder reminder(AuthenticatedUser principal, String id,
                                         NoticeRequests.Reminder request, String key, String path) {
        policy.requireActiveBusinessUser(principal);
        NoticeRepository.AnnouncementRow row = requireAnnouncement(id);
        requireManager(row, principal.userId());
        return idempotency.execute(req(principal, "POST", path, key, request),
                NoticeViews.Reminder.class,
                () -> policy.requireActiveBusinessUser(principal),
                () -> {
                    NoticeRepository.PreviewRow preview = repo.preview(Hashing.sha256Hex(request.previewToken()))
                            .orElseThrow(() -> new ApiException(ApiCode.RESOURCE_CHANGED));
                    if (!preview.createdBy().equals(principal.userId())
                            || preview.usedAt() != null || !preview.expiresAt().isAfter(clock.instant())) {
                        throw new ApiException(ApiCode.RESOURCE_CHANGED);
                    }
                    if (request.contentVersion() != row.contentVersion()
                            || repo.remindedWithin24h(id, request.contentVersion(), clock.instant())) {
                        throw new ApiException(ApiCode.RESOURCE_CHANGED, "24小时内已催阅或正文版本不符");
                    }
                    List<String> targets = repo.unconfirmedRecipients(id, request.contentVersion(), true);
                    repo.insertReminder(UUID.randomUUID().toString(), id, request.contentVersion(),
                            principal.userId(), targets.size(), clock.instant());
                    repo.usePreview(preview.id(), clock.instant());
                    for (String user : targets) {
                        repo.insertNotification(UUID.randomUUID().toString(), user, "ANNOUNCEMENT",
                                id, "COURSE_NOTICE", row.title(), "请尽快确认公告", request.contentVersion(),
                                clock.instant());
                    }
                    return new NoticeViews.Reminder(id, request.contentVersion(), targets.size());
                });
    }

    @Transactional(readOnly = true)
    public List<NoticeViews.Notification> notifications(AuthenticatedUser principal, boolean unreadOnly) {
        policy.requireActiveBusinessUser(principal);
        return repo.notifications(principal.userId(), unreadOnly).stream()
                .map(n -> new NoticeViews.Notification(n.id(), n.sourceType(), n.sourceId(),
                        n.category(), n.title(), n.body(), n.eventVersion(), n.visibleAt(),
                        n.readAt(), n.createdAt())).toList();
    }

    @Transactional
    public void readNotifications(AuthenticatedUser principal, NoticeRequests.ReadAction request) {
        policy.requireActiveBusinessUser(principal);
        if ((request.notificationIds() == null || request.notificationIds().isEmpty())
                && (request.expectedVisibleThroughId() == null || request.expectedVisibleThroughId().isBlank())) {
            throw ApiException.validation("notificationIds 与 expectedVisibleThroughId 至少一项");
        }
        repo.readNotifications(principal.userId(), request.notificationIds(),
                request.expectedVisibleThroughId(), clock.instant());
    }

    @Transactional(readOnly = true)
    public NoticeViews.Preferences preferences(AuthenticatedUser principal) {
        policy.requireActiveBusinessUser(principal);
        NoticeRepository.PreferencesRow p = repo.preferences(principal.userId());
        return new NoticeViews.Preferences(p.revision(), p.channel(), p.chatDefault(),
                p.quietStart(), p.quietEnd(), p.timezone());
    }

    @Transactional
    public NoticeViews.Preferences savePreferences(AuthenticatedUser principal,
                                                   NoticeRequests.Preferences request) {
        policy.requireActiveBusinessUser(principal);
        int updated = repo.savePreferences(principal.userId(), request.expectedRevision(),
                request.channel(), request.chatDefault(), request.quietStart(), request.quietEnd(),
                request.timezone(), clock.instant());
        if (updated != 1) throw new ApiException(ApiCode.RESOURCE_CHANGED);
        return preferences(principal);
    }

    private NoticeViews.Announcement publish(AuthenticatedUser principal, String squareId, String id,
                                             String role, Payload payload, int contentVersion,
                                             int displayRevision, Instant ignored) {
        Instant now = clock.instant();
        List<String> audience = repo.courseAudience(squareId);
        String snapshot = UUID.randomUUID().toString();
        repo.insertAnnouncement(id, squareId, payload.assignmentId(), principal.userId(), role,
                payload.title(), payload.content(), payload.category(), payload.importance(),
                contentVersion, displayRevision, payload.requiresAck(), payload.ackDue(),
                payload.expires(), snapshot, audience.size(), now);
        repo.insertVersion(UUID.randomUUID().toString(), id, contentVersion, displayRevision,
                payload.title(), payload.content(), payload.requiresAck(), payload.ackDue(),
                payload.expires(), snapshot, audience.size(), null, principal.userId(), now);
        repo.insertRecipients(snapshot, id, contentVersion, audience);
        notifyRecipients(id, contentVersion, payload.title(), payload.content(), audience);
        return view(repo.announcement(id).orElseThrow(), principal.userId());
    }

    private void notifyRecipients(String id, int version, String title, String content,
                                  List<String> audience) {
        for (String user : audience) {
            repo.insertNotification(UUID.randomUUID().toString(), user, "ANNOUNCEMENT", id,
                    "COURSE_NOTICE", title, content, version, clock.instant());
        }
    }

    private NoticeViews.Announcement view(NoticeRepository.AnnouncementRow row, String userId) {
        NoticeRepository.ReceiptRow receipt = repo.receipt(row.id(), row.contentVersion(), userId)
                .orElse(null);
        NoticeViews.Receipt mine = new NoticeViews.Receipt(row.contentVersion(),
                receipt == null ? null : receipt.readAt(),
                receipt == null ? null : receipt.acknowledgedAt());
        return new NoticeViews.Announcement(row.id(), row.squareId(), row.assignmentId(),
                row.authorId(), row.authorName(), row.title(), row.content(), row.createdAt(),
                row.revision(), row.publisherRole(), row.category(), row.importance(),
                row.contentVersion(), row.displayRevision(), row.snapshot(), row.recipientCount(),
                row.requiresAck(), row.ackDue(), row.expires(), row.status(),
                row.changeSummary(), mine, List.of());
    }

    private String requirePublisher(String squareId, String userId, String requested) {
        SquareRow square = squares.findById(squareId).orElseThrow(ApiException::notFound);
        boolean teacher = authority.isTeacher(squareId, userId);
        boolean assistant = authority.hasCapability(squareId, userId,
                TeacherCapabilities.ANNOUNCEMENT_WRITE);
        if (!teacher && !assistant) throw new ApiException(ApiCode.FORBIDDEN);
        if (requested != null) {
            if ("TEACHER".equals(requested) && !teacher
                    || "CLASS_ASSISTANT".equals(requested) && !assistant) {
                throw new ApiException(ApiCode.FORBIDDEN);
            }
        }
        if (!square.schoolId().equals("school_demo_01")) {
            // school is still enforced by reader/membership; this guard keeps square resolution explicit
        }
        return requested == null ? (teacher ? "TEACHER" : "CLASS_ASSISTANT") : requested;
    }

    private String roleFor(String squareId, String userId, String requested) {
        return requirePublisher(squareId, userId, requested);
    }

    private void requireManager(NoticeRepository.AnnouncementRow row, String userId) {
        boolean teacher = authority.isTeacher(row.squareId(), userId);
        boolean assistant = row.authorId().equals(userId)
                && authority.hasCapability(row.squareId(), userId, TeacherCapabilities.ANNOUNCEMENT_WRITE);
        if (!teacher && !assistant) throw new ApiException(ApiCode.FORBIDDEN);
    }

    private void requireReader(String squareId, String userId) {
        if (!repo.courseReader(squareId, userId)) throw new ApiException(ApiCode.FORBIDDEN);
    }

    private void requireRecipient(String id, int version, String userId) {
        if (!repo.recipientIds(id, version).contains(userId)) {
            throw new ApiException(ApiCode.FORBIDDEN);
        }
    }

    private NoticeRepository.AnnouncementRow requireAnnouncement(String id) {
        return repo.announcement(id).orElseThrow(ApiException::notFound);
    }

    private Payload consumePreview(String squareId, String userId, String token) {
        NoticeRepository.PreviewRow preview = repo.preview(Hashing.sha256Hex(token))
                .orElseThrow(() -> new ApiException(ApiCode.RESOURCE_CHANGED));
        if (!preview.squareId().equals(squareId) || !preview.createdBy().equals(userId)
                || preview.usedAt() != null || !preview.expiresAt().isAfter(clock.instant())) {
            throw new ApiException(ApiCode.RESOURCE_CHANGED);
        }
        repo.usePreview(preview.id(), clock.instant());
        JsonNode p = parse(preview.payload());
        return new Payload(p.path("title").asText(), p.path("content").asText(),
                p.path("category").asText("OTHER"), p.path("importance").asText("NORMAL"),
                p.path("requiresAcknowledgement").asBoolean(false), instant(p, "acknowledgeDueAt"),
                instant(p, "expiresAt"), p.path("assignmentId").isNull() ? null : p.path("assignmentId").asText(),
                p.path("publisherRole").asText("TEACHER"), List.of());
    }

    private Instant instant(JsonNode node, String field) {
        return node.hasNonNull(field) ? Instant.parse(node.path(field).asText()) : null;
    }

    private String normalize(String text) {
        return text.replace("\r\n", "\n").strip();
    }

    private JsonNode parse(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception ex) {
            throw new IllegalStateException("无法解析公告预览", ex);
        }
    }

    private String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("无法序列化公告", ex);
        }
    }

    private IdempotencyRequest req(AuthenticatedUser principal, String method, String path,
                                   String key, Object body) {
        return new IdempotencyRequest(principal, method, path, key, body, List.of());
    }

    private record Payload(String title, String content, String category, String importance,
                           boolean requiresAck, Instant ackDue, Instant expires,
                           String assignmentId, String publisherRole, List<Object> refs) {
    }
}
