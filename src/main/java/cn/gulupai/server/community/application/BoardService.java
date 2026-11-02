package cn.gulupai.server.community.application;

import cn.gulupai.server.common.api.ApiCode;
import cn.gulupai.server.common.api.ApiException;
import cn.gulupai.server.common.api.ListEnvelope;
import cn.gulupai.server.common.audit.AuditService;
import cn.gulupai.server.common.idempotency.IdempotencyRequest;
import cn.gulupai.server.common.idempotency.IdempotencyService;
import cn.gulupai.server.common.security.AuthenticatedUser;
import cn.gulupai.server.common.security.CurrentUserPolicy;
import cn.gulupai.server.community.api.BoardRequests;
import cn.gulupai.server.community.api.BoardViews;
import cn.gulupai.server.community.persistence.BoardRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class BoardService {

    private static final TypeReference<List<BoardViews.Ref>> REFS = new TypeReference<>() {
    };
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {
    };

    private final BoardRepository repo;
    private final CurrentUserPolicy policy;
    private final IdempotencyService idempotency;
    private final AuditService audit;
    private final ObjectMapper mapper;
    private final Clock clock;

    public BoardService(BoardRepository repo, CurrentUserPolicy policy,
                        IdempotencyService idempotency, AuditService audit,
                        ObjectMapper mapper, Clock clock) {
        this.repo = repo;
        this.policy = policy;
        this.idempotency = idempotency;
        this.audit = audit;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ListEnvelope<BoardViews.Topic> topics(AuthenticatedUser principal, String projectId,
                                                 String status, String q) {
        policy.requireActiveBusinessUser(principal);
        requireMember(projectId, principal.userId());
        if (q != null && q.length() > 80) throw ApiException.validation("q 不能超过80个字符");
        List<BoardViews.Topic> items = repo.topics(projectId, status, q).stream()
                .map(row -> topicView(row, principal.userId())).toList();
        return ListEnvelope.full(items);
    }

    @Transactional(readOnly = true)
    public BoardViews.Topic topic(AuthenticatedUser principal, String topicId) {
        policy.requireActiveBusinessUser(principal);
        BoardRepository.TopicRow topic = requireTopic(topicId);
        requireMember(topic.projectId(), principal.userId());
        return topicView(topic, principal.userId());
    }

    public BoardViews.Topic createTopic(AuthenticatedUser principal, String projectId,
                                        BoardRequests.CreateTopic request, String key, String path) {
        policy.requireActiveBusinessUser(principal);
        requireWritable(projectId, principal.userId());
        validateMentions(projectId, request.mentions());
        String refs = writeRefs(request.refs());
        return idempotency.execute(request(principal, "POST", path, key, request),
                BoardViews.Topic.class,
                () -> {
                    policy.requireActiveBusinessUser(principal);
                    requireMember(projectId, principal.userId());
                },
                () -> {
                    String id = UUID.randomUUID().toString();
                    repo.insertTopic(id, projectId, principal.userId(), request.title().strip(),
                            request.content().strip(), request.tag(), refs,
                            writeMentions(request.mentions()), clock.instant());
                    repo.insertRevision(UUID.randomUUID().toString(), "TOPIC", id, 1,
                            request.title().strip(), request.content().strip(), 1, clock.instant());
                    audit.record(principal.userId(), "BOARD_TOPIC_CREATE", "BOARD_TOPIC", id, null, 1, null);
                    return topicView(repo.topic(id).orElseThrow(), principal.userId());
                },
                cached -> currentTopic(cached.id(), projectId, principal.userId()));
    }

    @Transactional(readOnly = true)
    public ListEnvelope<BoardViews.Reply> replies(AuthenticatedUser principal, String topicId) {
        policy.requireActiveBusinessUser(principal);
        BoardRepository.TopicRow topic = requireTopic(topicId);
        requireMember(topic.projectId(), principal.userId());
        return ListEnvelope.full(repo.replies(topicId).stream()
                .map(row -> replyView(row)).toList());
    }

    public BoardViews.Reply createReply(AuthenticatedUser principal, String topicId,
                                        BoardRequests.CreateReply request, String key, String path) {
        policy.requireActiveBusinessUser(principal);
        BoardRepository.TopicRow topic = requireTopic(topicId);
        requireWritable(topic.projectId(), principal.userId());
        validateMentions(topic.projectId(), request.mentions());
        return idempotency.execute(request(principal, "POST", path, key, request),
                BoardViews.Reply.class,
                () -> {
                    policy.requireActiveBusinessUser(principal);
                    requireMember(topic.projectId(), principal.userId());
                },
                () -> {
                    BoardRepository.TopicRow locked = repo.lockTopic(topicId).orElseThrow();
                    if (locked.revision() != request.expectedTopicRevision()) {
                        throw new ApiException(ApiCode.RESOURCE_CHANGED);
                    }
                    if (request.replyToId() != null && !request.replyToId().isBlank()) {
                        BoardRepository.ReplyRow parent = repo.reply(request.replyToId())
                                .orElseThrow(() -> ApiException.validation("replyToId 不存在"));
                        if (!parent.topicId().equals(topicId) || parent.withdrawn()
                                || parent.replyToId() != null) {
                            throw ApiException.validation("replyToId 必须是同主题可见根回复");
                        }
                    }
                    String id = UUID.randomUUID().toString();
                    repo.insertReply(id, topicId, principal.userId(), request.replyToId(),
                            request.content().strip(), writeMentions(request.mentions()), clock.instant());
                    repo.insertRevision(UUID.randomUUID().toString(), "REPLY", id, 1, "",
                            request.content().strip(), 1, clock.instant());
                    repo.bumpReplyActivity(topicId, clock.instant());
                    return replyView(repo.reply(id).orElseThrow());
                },
                cached -> currentReply(cached.id(), topic.projectId()));
    }

    public BoardViews.Topic reviseTopic(AuthenticatedUser principal, String topicId,
                                        BoardRequests.RevisionRequest request, String key, String path) {
        policy.requireActiveBusinessUser(principal);
        BoardRepository.TopicRow topic = requireTopic(topicId);
        if (!topic.authorId().equals(principal.userId())) throw new ApiException(ApiCode.FORBIDDEN);
        requireWritable(topic.projectId(), principal.userId());
        return idempotency.execute(request(principal, "POST", path, key, request),
                BoardViews.Topic.class,
                () -> {
                    policy.requireActiveBusinessUser(principal);
                    requireMember(topic.projectId(), principal.userId());
                },
                () -> {
                    int next = request.expectedRevision() + 1;
                    if (repo.updateTopicContent(topicId, request.expectedRevision(), request.title().strip(),
                            request.content().strip(), clock.instant()) != 1) {
                        throw new ApiException(ApiCode.RESOURCE_CHANGED);
                    }
                    repo.insertRevision(UUID.randomUUID().toString(), "TOPIC", topicId, next,
                            request.title().strip(), request.content().strip(), next, clock.instant());
                    return topicView(repo.topic(topicId).orElseThrow(), principal.userId());
                },
                cached -> currentTopic(cached.id(), topic.projectId(), principal.userId()));
    }

    public BoardViews.Reply reviseReply(AuthenticatedUser principal, String replyId,
                                        BoardRequests.ReplyRevisionRequest request, String key, String path) {
        policy.requireActiveBusinessUser(principal);
        BoardRepository.ReplyRow reply = repo.reply(replyId).orElseThrow(ApiException::notFound);
        if (!reply.authorId().equals(principal.userId())) throw new ApiException(ApiCode.FORBIDDEN);
        requireWritable(reply.projectId(), principal.userId());
        return idempotency.execute(request(principal, "POST", path, key, request),
                BoardViews.Reply.class,
                () -> {
                    policy.requireActiveBusinessUser(principal);
                    requireMember(reply.projectId(), principal.userId());
                },
                () -> {
                    if (repo.updateReply(replyId, request.expectedRevision(), request.content().strip(),
                            clock.instant()) != 1) {
                        throw new ApiException(ApiCode.RESOURCE_CHANGED);
                    }
                    int next = request.expectedRevision() + 1;
                    repo.insertRevision(UUID.randomUUID().toString(), "REPLY", replyId, next, "",
                            request.content().strip(), next, clock.instant());
                    return replyView(repo.reply(replyId).orElseThrow());
                },
                cached -> currentReply(cached.id(), reply.projectId()));
    }

    public BoardViews.Topic withdrawTopic(AuthenticatedUser principal, String topicId,
                                          BoardRequests.WithdrawRequest request, String key, String path) {
        policy.requireActiveBusinessUser(principal);
        BoardRepository.TopicRow topic = requireTopic(topicId);
        if (!topic.authorId().equals(principal.userId())) throw new ApiException(ApiCode.FORBIDDEN);
        requireWritable(topic.projectId(), principal.userId());
        return idempotency.execute(request(principal, "POST", path, key, request),
                BoardViews.Topic.class,
                () -> {
                    policy.requireActiveBusinessUser(principal);
                    requireMember(topic.projectId(), principal.userId());
                },
                () -> {
                    if (repo.withdrawTopic(topicId, request.expectedRevision(), clock.instant()) != 1) {
                        throw new ApiException(ApiCode.RESOURCE_CHANGED);
                    }
                    repo.clearTopicRevisions(topicId);
                    return topicView(repo.topic(topicId).orElseThrow(), principal.userId());
                },
                cached -> currentTopic(cached.id(), topic.projectId(), principal.userId()));
    }

    public BoardViews.Reply withdrawReply(AuthenticatedUser principal, String replyId,
                                          BoardRequests.WithdrawRequest request, String key, String path) {
        policy.requireActiveBusinessUser(principal);
        BoardRepository.ReplyRow reply = repo.reply(replyId).orElseThrow(ApiException::notFound);
        if (!reply.authorId().equals(principal.userId())) throw new ApiException(ApiCode.FORBIDDEN);
        requireWritable(reply.projectId(), principal.userId());
        return idempotency.execute(request(principal, "POST", path, key, request),
                BoardViews.Reply.class,
                () -> {
                    policy.requireActiveBusinessUser(principal);
                    requireMember(reply.projectId(), principal.userId());
                },
                () -> {
                    if (repo.withdrawReply(replyId, request.expectedRevision(), clock.instant()) != 1) {
                        throw new ApiException(ApiCode.RESOURCE_CHANGED);
                    }
                    repo.clearReplyRevisions(replyId);
                    return replyView(repo.reply(replyId).orElseThrow());
                },
                cached -> currentReply(cached.id(), reply.projectId()));
    }

    @Transactional(readOnly = true)
    public ListEnvelope<BoardViews.Revision> revisions(AuthenticatedUser principal, String type, String contentId) {
        policy.requireActiveBusinessUser(principal);
        if ("TOPIC".equals(type)) {
            BoardRepository.TopicRow topic = requireTopic(contentId);
            if (!topic.authorId().equals(principal.userId())) throw new ApiException(ApiCode.FORBIDDEN);
            requireMember(topic.projectId(), principal.userId());
        } else {
            BoardRepository.ReplyRow reply = repo.reply(contentId).orElseThrow(ApiException::notFound);
            if (!reply.authorId().equals(principal.userId())) throw new ApiException(ApiCode.FORBIDDEN);
            requireMember(reply.projectId(), principal.userId());
        }
        return ListEnvelope.full(repo.revisions(type, contentId).stream()
                .map(row -> new BoardViews.Revision(row.id(), row.contentType(), row.contentId(),
                        row.version(), row.title(), row.content(),
                        row.revision(), row.createdAt())).toList());
    }

    public BoardViews.Topic statusAction(AuthenticatedUser principal, String topicId,
                                         BoardRequests.StatusRequest request, String key, String path) {
        policy.requireActiveBusinessUser(principal);
        BoardRepository.TopicRow topic = requireTopic(topicId);
        requireWritable(topic.projectId(), principal.userId());
        boolean author = topic.authorId().equals(principal.userId());
        boolean leader = repo.leader(topic.projectId(), principal.userId());
        if (!author && !leader) throw new ApiException(ApiCode.FORBIDDEN);
        return idempotency.execute(request(principal, "POST", path, key, request),
                BoardViews.Topic.class,
                () -> {
                    policy.requireActiveBusinessUser(principal);
                    requireMember(topic.projectId(), principal.userId());
                },
                () -> {
                    if (topic.revision() != request.expectedRevision()) {
                        throw new ApiException(ApiCode.RESOURCE_CHANGED);
                    }
                    String status = topic.status();
                    String helpful = topic.helpfulReplyId();
                    String resolution = topic.resolutionNote();
                    String close = topic.closeReason();
                    switch (request.action()) {
                        case "RESOLVE" -> {
                            if (!author) throw new ApiException(ApiCode.FORBIDDEN);
                            if (request.note().isBlank()) throw ApiException.validation("解决说明不能为空");
                            if (request.helpfulReplyId() != null) {
                                BoardRepository.ReplyRow reply = repo.reply(request.helpfulReplyId())
                                        .orElseThrow(() -> ApiException.validation("helpfulReplyId 不存在"));
                                if (!reply.topicId().equals(topicId)) {
                                    throw ApiException.validation("helpfulReplyId 必须属于同主题");
                                }
                                helpful = reply.id();
                            }
                            status = "RESOLVED"; resolution = request.note().strip(); close = null;
                        }
                        case "SET_HELPFUL" -> {
                            if (!author) throw new ApiException(ApiCode.FORBIDDEN);
                            if (request.helpfulReplyId() == null || request.helpfulReplyId().isBlank()) {
                                throw ApiException.validation("SET_HELPFUL 必须提供helpfulReplyId");
                            }
                            BoardRepository.ReplyRow reply = repo.reply(request.helpfulReplyId())
                                    .orElseThrow(() -> ApiException.validation("helpfulReplyId 不存在"));
                            if (!reply.topicId().equals(topicId)) {
                                throw ApiException.validation("helpfulReplyId 必须属于同主题");
                            }
                            helpful = reply.id();
                        }
                        case "CLOSE" -> {
                            if (!leader) throw new ApiException(ApiCode.FORBIDDEN);
                            status = "CLOSED"; close = request.note().strip();
                        }
                        case "REOPEN" -> {
                            if ("RESOLVED".equals(topic.status()) && author) {
                                status = "OPEN"; resolution = null; close = null;
                            } else if ("CLOSED".equals(topic.status()) && leader) {
                                status = "OPEN"; close = null;
                            } else {
                                throw new ApiException(ApiCode.FORBIDDEN);
                            }
                        }
                        default -> throw ApiException.validation("action 无效");
                    }
                    if (repo.updateStatus(topicId, request.expectedRevision(), status, helpful,
                            resolution, close, clock.instant()) != 1) {
                        throw new ApiException(ApiCode.RESOURCE_CHANGED);
                    }
                    return topicView(repo.topic(topicId).orElseThrow(), principal.userId());
                },
                cached -> currentTopic(cached.id(), topic.projectId(), principal.userId()));
    }

    public BoardViews.Topic pin(AuthenticatedUser principal, String topicId,
                                BoardRequests.PinRequest request, String key, String path) {
        policy.requireActiveBusinessUser(principal);
        BoardRepository.TopicRow topic = requireTopic(topicId);
        if (!repo.leader(topic.projectId(), principal.userId())) throw new ApiException(ApiCode.FORBIDDEN);
        requireWritable(topic.projectId(), principal.userId());
        return idempotency.execute(request(principal, "PUT", path, key, request),
                BoardViews.Topic.class,
                () -> {
                    policy.requireActiveBusinessUser(principal);
                    requireMember(topic.projectId(), principal.userId());
                },
                () -> {
                    if (request.pinned() && repo.pinnedCount(topic.projectId()) >= 3 && !topic.pinned()) {
                        throw new ApiException(ApiCode.RESOURCE_CHANGED, "置顶主题最多3个");
                    }
                    if (repo.setPinned(topicId, request.expectedRevision(), request.pinned(),
                            clock.instant()) != 1) {
                        throw new ApiException(ApiCode.RESOURCE_CHANGED);
                    }
                    return topicView(repo.topic(topicId).orElseThrow(), principal.userId());
                },
                cached -> currentTopic(cached.id(), topic.projectId(), principal.userId()));
    }

    public BoardViews.Topic follow(AuthenticatedUser principal, String topicId,
                                   BoardRequests.FollowRequest request, String key, String path) {
        policy.requireActiveBusinessUser(principal);
        BoardRepository.TopicRow topic = requireTopic(topicId);
        requireMember(topic.projectId(), principal.userId());
        return idempotency.execute(request(principal, "PUT", path, key, request),
                BoardViews.Topic.class,
                () -> {
                    policy.requireActiveBusinessUser(principal);
                    requireMember(topic.projectId(), principal.userId());
                },
                () -> {
                    if (topic.revision() != request.expectedRevision()) {
                        throw new ApiException(ApiCode.RESOURCE_CHANGED);
                    }
                    repo.setFollowing(topicId, principal.userId(), request.following(), clock.instant());
                    return topicView(repo.topic(topicId).orElseThrow(), principal.userId());
                },
                cached -> currentTopic(cached.id(), topic.projectId(), principal.userId()));
    }

    private BoardViews.Topic topicView(BoardRepository.TopicRow row, String userId) {
        boolean hidden = row.withdrawn();
        return new BoardViews.Topic(row.id(), row.projectId(), row.authorId(), row.authorName(),
                row.title(), hidden ? null : row.content(), row.tag(),
                hidden ? List.of() : readRefs(row.refsJson()),
                hidden ? List.of() : readStrings(row.mentionsJson()), row.status(), row.revision(),
                row.withdrawn(), row.replyCount(), row.helpfulReplyId(), row.resolutionNote(),
                row.closeReason(), row.pinned(), repo.following(row.id(), userId),
                row.createdAt(), row.updatedAt());
    }

    private BoardViews.Reply replyView(BoardRepository.ReplyRow row) {
        boolean hidden = row.withdrawn();
        return new BoardViews.Reply(row.id(), row.topicId(), row.projectId(), row.authorId(),
                row.authorName(), hidden ? null : row.content(), row.replyToId(),
                hidden ? List.of() : readStrings(row.mentionsJson()), row.revision(),
                row.withdrawn(), row.createdAt(), row.updatedAt());
    }

    /** 回放时按当前主题状态重新投影；资源不存在或跨项目按 404 处理。 */
    private BoardViews.Topic currentTopic(String topicId, String projectId, String userId) {
        BoardRepository.TopicRow row = repo.topic(topicId)
                .filter(item -> item.projectId().equals(projectId))
                .orElseThrow(ApiException::notFound);
        return topicView(row, userId);
    }

    /** 回放时按当前回复状态重新投影；撤回后不恢复正文、提及。 */
    private BoardViews.Reply currentReply(String replyId, String projectId) {
        BoardRepository.ReplyRow row = repo.reply(replyId)
                .filter(item -> item.projectId().equals(projectId))
                .orElseThrow(ApiException::notFound);
        return replyView(row);
    }

    private void requireMember(String projectId, String userId) {
        if (!repo.activeMember(projectId, userId)) throw new ApiException(ApiCode.FORBIDDEN);
    }

    private void requireWritable(String projectId, String userId) {
        if (!repo.activeMember(projectId, userId)) throw new ApiException(ApiCode.FORBIDDEN);
        String status = repo.projectStatus(projectId);
        if (!"ACTIVE".equals(status) && !"CLOSING".equals(status)) {
            throw new ApiException(ApiCode.PROJECT_READ_ONLY);
        }
    }

    private BoardRepository.TopicRow requireTopic(String topicId) {
        return repo.topic(topicId).orElseThrow(ApiException::notFound);
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

    private String writeRefs(List<BoardRequests.RefInput> refs) {
        if (refs == null) return "[]";
        if (refs.size() > 5) throw ApiException.validation("refs 最多5个");
        try {
            return mapper.writeValueAsString(refs);
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

    private List<BoardViews.Ref> readRefs(String json) {
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
}
