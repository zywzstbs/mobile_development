package cn.gulupai.server.profile.application;

import cn.gulupai.server.common.api.ApiCode;
import cn.gulupai.server.common.api.ApiException;
import cn.gulupai.server.common.audit.AuditService;
import cn.gulupai.server.common.idempotency.IdempotencyRequest;
import cn.gulupai.server.common.idempotency.IdempotencyService;
import cn.gulupai.server.common.security.AuthenticatedUser;
import cn.gulupai.server.common.security.CurrentUserPolicy;
import cn.gulupai.server.identity.domain.Role;
import cn.gulupai.server.profile.api.ProfileRequests;
import cn.gulupai.server.profile.api.ProfileViews;
import cn.gulupai.server.profile.persistence.ProfileRepository;
import cn.gulupai.server.team.application.WorkSourceQueryService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class ProfileWorksService {

    private static final Duration REVIEW_WINDOW = Duration.ofDays(14);
    private static final Set<String> DIMENSIONS = Set.of("DELIVERY", "COMMUNICATION", "REWORK");
    private static final Set<String> LEVELS = Set.of("POSITIVE", "NEEDS_IMPROVEMENT", "NOT_OBSERVED");

    private final ProfileRepository repository;
    private final CurrentUserPolicy policy;
    private final IdempotencyService idempotency;
    private final AuditService audit;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final WorkSourceQueryService workSources;

    public ProfileWorksService(ProfileRepository repository, CurrentUserPolicy policy,
                          IdempotencyService idempotency, AuditService audit,
                          ObjectMapper mapper, Clock clock,
                          WorkSourceQueryService workSources) {
        this.repository = repository;
        this.policy = policy;
        this.idempotency = idempotency;
        this.audit = audit;
        this.mapper = mapper;
        this.clock = clock;
        this.workSources = workSources;
    }

    @Transactional(readOnly = true)
    public List<ProfileViews.CooperationView> cooperations(AuthenticatedUser principal) {
        policy.requireActiveBusinessUser(principal);
        List<ProfileViews.CooperationView> result = new ArrayList<>();
        for (ProfileRepository.ProjectBrief project : repository.archivedProjectsForUser(principal.userId())) {
            Instant ended = project.archivedAt();
            Instant reviewEnds = ended == null ? null : ended.plus(REVIEW_WINDOW);
            boolean canReview = ended != null && !clock.instant().isAfter(reviewEnds);
            List<ProfileViews.CooperationMember> members = repository.members(project.id()).stream()
                    .map(m -> new ProfileViews.CooperationMember(m.userId(), m.displayName())).toList();
            result.add(new ProfileViews.CooperationView(project.id(), project.name(), ended,
                    members, canReview, reviewEnds));
        }
        return result;
    }

    @Transactional(readOnly = true)
    public List<ProfileViews.WorkView> myWorks(AuthenticatedUser principal) {
        policy.requireActiveBusinessUser(principal);
        return repository.works(principal.userId()).stream()
                .map(work -> workView(work, principal, true)).toList();
    }

    @Transactional(readOnly = true)
    public ProfileViews.WorkView work(AuthenticatedUser principal, String workId) {
        policy.requireActiveBusinessUser(principal);
        ProfileRepository.WorkRow work = repository.work(workId).orElseThrow(ApiException::notFound);
        boolean owner = work.userId().equals(principal.userId());
        if (!owner && (!"ACTIVE".equals(work.status()) || !workVisible(work, principal))) {
            throw new ApiException(ApiCode.FORBIDDEN);
        }
        return workView(work, principal, owner);
    }

    public ProfileViews.WorkView createWork(AuthenticatedUser principal, ProfileRequests.WorkRequest request,
                                            String idempotencyKey, String path) {
        policy.requireActiveBusinessUser(principal);
        policy.requireRole(principal, Role.STUDENT);
        requireSourceProject(principal, request.sourceProjectId());
        validateArtifacts(request.artifacts());
        return idempotency.execute(idempotency(principal, "POST", path, idempotencyKey, request),
                ProfileViews.WorkView.class,
                () -> policy.requireActiveBusinessUser(principal),
                () -> {
                    if (request.expectedRevision() != 0) {
                        throw new ApiException(ApiCode.RESOURCE_CHANGED);
                    }
                    String id = UUID.randomUUID().toString();
                    Instant now = clock.instant();
                    String description = request.description() == null ? "" : request.description().strip();
                    repository.insertWork(id, principal.userId(), request.sourceProjectId(),
                            request.title().strip(), description, request.role().strip(),
                            request.myContribution().strip(), request.audience(), now);
                    repository.insertWorkVersion(UUID.randomUUID().toString(), id, 1,
                            write(workBody(request.title().strip(), description, request.role().strip(),
                                    request.myContribution().strip(), request.artifacts())),
                            1, now);
                    audit.record(principal.userId(), "WORK_CREATE", "WORK", id, null, 1, null);
                    return workView(repository.work(id).orElseThrow(), principal, true);
                });
    }

    public ProfileViews.WorkView patchWork(AuthenticatedUser principal, String workId,
                                           ProfileRequests.WorkRequest request,
                                           String idempotencyKey, String path) {
        policy.requireActiveBusinessUser(principal);
        ProfileRepository.WorkRow existing = repository.work(workId).orElseThrow(ApiException::notFound);
        if (!existing.userId().equals(principal.userId())) throw new ApiException(ApiCode.FORBIDDEN);
        requireSourceProject(principal, request.sourceProjectId());
        if (!existing.sourceProjectId().equals(request.sourceProjectId())) {
            throw ApiException.validation("作品来源项目不可变更");
        }
        validateArtifacts(request.artifacts());
        return idempotency.execute(idempotency(principal, "PATCH", path, idempotencyKey, request),
                ProfileViews.WorkView.class,
                () -> policy.requireActiveBusinessUser(principal),
                () -> {
                    if (!"ACTIVE".equals(existing.status())) {
                        throw new ApiException(ApiCode.RESOURCE_CHANGED, "已撤回作品不可重新提交");
                    }
                    int nextVersion = existing.currentVersion() + 1;
                    String description = request.description() == null ? "" : request.description().strip();
                    if (repository.updateWork(workId, request.expectedRevision(), request.title().strip(),
                            description, request.role().strip(), request.myContribution().strip(),
                            request.audience(), nextVersion, clock.instant()) != 1) {
                        throw new ApiException(ApiCode.RESOURCE_CHANGED);
                    }
                    repository.insertWorkVersion(UUID.randomUUID().toString(), workId, nextVersion,
                            write(workBody(request.title().strip(), description, request.role().strip(),
                                    request.myContribution().strip(), request.artifacts())),
                            request.expectedRevision() + 1, clock.instant());
                    return workView(repository.work(workId).orElseThrow(), principal, true);
                });
    }

    public ProfileViews.WorkView withdrawWork(AuthenticatedUser principal, String workId,
                                              ProfileRequests.WorkWithdrawRequest request,
                                              String idempotencyKey, String path) {
        policy.requireActiveBusinessUser(principal);
        ProfileRepository.WorkRow existing = repository.work(workId).orElseThrow(ApiException::notFound);
        if (!existing.userId().equals(principal.userId())) throw new ApiException(ApiCode.FORBIDDEN);
        return idempotency.execute(idempotency(principal, "POST", path, idempotencyKey, request),
                ProfileViews.WorkView.class,
                () -> policy.requireActiveBusinessUser(principal),
                () -> {
                    if (repository.withdrawWork(workId, request.expectedRevision(), clock.instant()) != 1) {
                        throw new ApiException(ApiCode.RESOURCE_CHANGED);
                    }
                    return workView(repository.work(workId).orElseThrow(), principal, true);
                });
    }

    @Transactional(readOnly = true)
    public ProfileViews.ProfileView userProfile(AuthenticatedUser principal, String userId,
                                                String contextProjectId, String contextApplicationId) {
        policy.requireActiveBusinessUser(principal);
        ProfileRepository.UserProfileRow user = repository.userProfile(userId).orElseThrow(ApiException::notFound);
        boolean self = userId.equals(principal.userId());
        if (!self) {
            if (!user.schoolId().equals(principal.schoolId())) throw ApiException.notFound();
            if (contextProjectId != null && !contextProjectId.isBlank()) {
                if (!repository.member(contextProjectId, principal.userId())
                        || !repository.member(contextProjectId, userId)) {
                    throw new ApiException(ApiCode.FORBIDDEN);
                }
            }
        }
        JsonNode profile = parse(user.profileJson());
        List<ProfileViews.WorkView> works = new ArrayList<>();
        for (ProfileRepository.WorkRow work : repository.works(userId)) {
            if (self) {
                works.add(workView(work, principal, true));
            } else if ("ACTIVE".equals(work.status()) && workVisible(work, principal)) {
                works.add(workView(work, principal, false));
            }
        }
        ObjectNode process = mapper.createObjectNode();
        List<ProfileViews.CooperationSummary> summaries = new ArrayList<>();
        for (ProfileRepository.DimensionSummaryRow row : repository.peerDimensionSummaries(userId)) {
            if (row.authors() >= 3) {
                summaries.add(new ProfileViews.CooperationSummary(row.dimension(), "VISIBLE",
                        row.authors(), row.positive() + "/" + row.authors(),
                        row.needs() + "/" + row.authors()));
            }
        }
        return new ProfileViews.ProfileView(user.id(), user.displayName(),
                skills(user.skillsJson()), profile.path("weeklyHours").asInt(0),
                profile.path("availability").asText("READY"),
                profile.path("collaborationGoal").asText(""),
                user.intro() == null ? "" : user.intro(), works, process,
                summaries, !summaries.isEmpty(), "NOT_AVAILABLE", List.of(), self);
    }

    @Transactional(readOnly = true)
    public ProfileViews.PeerContextView peerContext(AuthenticatedUser principal, String projectId) {
        policy.requireActiveBusinessUser(principal);
        ProfileRepository.ProjectBrief project = requireProject(projectId);
        if (!repository.member(projectId, principal.userId())) throw new ApiException(ApiCode.FORBIDDEN);
        boolean canReview = reviewOpen(project);
        List<ProfileViews.PeerContextTarget> targets = new ArrayList<>();
        for (ProfileRepository.MemberRow member : repository.members(projectId)) {
            if (member.userId().equals(principal.userId())) continue;
            ProfileRepository.PeerRow mine = repository.peerByAuthorTarget(projectId,
                    principal.userId(), member.userId()).orElse(null);
            ObjectNode myReview = mapper.createObjectNode();
            if (mine != null) {
                myReview.put("id", mine.id());
                myReview.put("status", mine.status());
                myReview.put("revision", mine.revision());
            }
            targets.add(new ProfileViews.PeerContextTarget(member.userId(), member.displayName(),
                    canReview && "ACTIVE".equals(member.status()), myReview));
        }
        return new ProfileViews.PeerContextView(project.id(), project.name(), targets);
    }

    @Transactional(readOnly = true)
    public ProfileViews.PeerReviewView peerReview(AuthenticatedUser principal, String reviewId) {
        policy.requireActiveBusinessUser(principal);
        ProfileRepository.PeerRow review = repository.peer(reviewId).orElseThrow(ApiException::notFound);
        if (!review.authorId().equals(principal.userId())) throw new ApiException(ApiCode.FORBIDDEN);
        return peerView(review, principal);
    }

    public ProfileViews.PeerReviewView createPeerReview(AuthenticatedUser principal, String projectId,
                                                        ProfileRequests.PeerReviewRequest request,
                                                        String idempotencyKey, String path) {
        policy.requireActiveBusinessUser(principal);
        policy.requireRole(principal, Role.STUDENT);
        ProfileRepository.ProjectBrief project = requireProject(projectId);
        if (!repository.member(projectId, principal.userId())) throw new ApiException(ApiCode.FORBIDDEN);
        if (principal.userId().equals(request.targetUserId())) {
            throw ApiException.validation("不能评价本人");
        }
        if (!repository.member(projectId, request.targetUserId())) {
            throw ApiException.validation("被评价者不是本项目实际合作成员");
        }
        validateObservations(request.observations());
        return idempotency.execute(idempotency(principal, "POST", path, idempotencyKey, request),
                ProfileViews.PeerReviewView.class,
                () -> policy.requireActiveBusinessUser(principal),
                () -> {
                    if (!reviewOpen(project)) {
                        throw new ApiException(ApiCode.RESOURCE_CHANGED, "项目不在14天互评窗口内");
                    }
                    if (request.expectedRevision() != 0) {
                        throw new ApiException(ApiCode.RESOURCE_CHANGED);
                    }
                    if (repository.peerByAuthorTarget(projectId, principal.userId(),
                            request.targetUserId()).isPresent()) {
                        throw new ApiException(ApiCode.APPLICATION_EXISTS, "已评价过该成员");
                    }
                    String id = UUID.randomUUID().toString();
                    repository.insertPeer(id, projectId, principal.userId(), request.targetUserId(),
                            request.observedEnough(), write(request.observations()),
                            request.comment() == null ? "" : request.comment().strip(), clock.instant());
                    repository.insertPeerVersion(UUID.randomUUID().toString(), id, 1,
                            write(peerBody(request)), 1, clock.instant());
                    audit.record(principal.userId(), "PEER_REVIEW_CREATE", "PEER_REVIEW", id, null, 1, null);
                    return peerView(repository.peer(id).orElseThrow(), principal);
                });
    }

    public ProfileViews.PeerReviewView updatePeerReview(AuthenticatedUser principal, String reviewId,
                                                        ProfileRequests.PeerReviewRequest request,
                                                        String idempotencyKey, String path) {
        policy.requireActiveBusinessUser(principal);
        ProfileRepository.PeerRow existing = repository.peer(reviewId).orElseThrow(ApiException::notFound);
        if (!existing.authorId().equals(principal.userId())) throw new ApiException(ApiCode.FORBIDDEN);
        if (!existing.targetUserId().equals(request.targetUserId())) {
            throw ApiException.validation("评价对象不可变更");
        }
        validateObservations(request.observations());
        return idempotency.execute(idempotency(principal, "PUT", path, idempotencyKey, request),
                ProfileViews.PeerReviewView.class,
                () -> policy.requireActiveBusinessUser(principal),
                () -> {
                    if (!reviewOpen(requireProject(existing.projectId()))) {
                        throw new ApiException(ApiCode.RESOURCE_CHANGED, "互评窗口已结束");
                    }
                    if (repository.updatePeer(reviewId, request.expectedRevision(), request.observedEnough(),
                            write(request.observations()),
                            request.comment() == null ? "" : request.comment().strip(), clock.instant()) != 1) {
                        throw new ApiException(ApiCode.RESOURCE_CHANGED);
                    }
                    repository.insertPeerVersion(UUID.randomUUID().toString(), reviewId,
                            repository.peerVersion(reviewId), write(peerBody(request)),
                            request.expectedRevision() + 1, clock.instant());
                    return peerView(repository.peer(reviewId).orElseThrow(), principal);
                });
    }

    public ProfileViews.PeerReviewView withdrawPeerReview(AuthenticatedUser principal, String reviewId,
                                                          ProfileRequests.PeerReviewWithdrawRequest request,
                                                          String idempotencyKey, String path) {
        policy.requireActiveBusinessUser(principal);
        ProfileRepository.PeerRow existing = repository.peer(reviewId).orElseThrow(ApiException::notFound);
        if (!existing.authorId().equals(principal.userId())) throw new ApiException(ApiCode.FORBIDDEN);
        return idempotency.execute(idempotency(principal, "POST", path, idempotencyKey, request),
                ProfileViews.PeerReviewView.class,
                () -> policy.requireActiveBusinessUser(principal),
                () -> {
                    if (repository.withdrawPeer(reviewId, request.expectedRevision(), clock.instant()) != 1) {
                        throw new ApiException(ApiCode.RESOURCE_CHANGED);
                    }
                    return peerView(repository.peer(reviewId).orElseThrow(), principal);
                });
    }

    @Transactional(readOnly = true)
    public List<ProfileViews.AiReferenceView> aiReferences(AuthenticatedUser principal, String projectId) {
        policy.requireActiveBusinessUser(principal);
        requireProject(projectId);
        if (!repository.activeMember(projectId, principal.userId())) throw new ApiException(ApiCode.FORBIDDEN);
        return repository.aiReferences(projectId).stream().map(this::aiView).toList();
    }

    public ProfileViews.AiReferenceView createAiReference(AuthenticatedUser principal, String projectId,
                                                          ProfileRequests.AiReferenceRequest request,
                                                          String idempotencyKey, String path) {
        policy.requireActiveBusinessUser(principal);
        ProfileRepository.ProjectBrief project = requireProject(projectId);
        if (!repository.leader(projectId, principal.userId())) throw new ApiException(ApiCode.FORBIDDEN);
        if (!projectId.equals(request.sourceProjectId())) {
            throw ApiException.validation("sourceProjectId 必须等于当前项目");
        }
        if (request.model().toLowerCase().contains("sk-")
                || request.model().toLowerCase().contains("api_key")) {
            throw ApiException.validation("model 不得包含API密钥");
        }
        return idempotency.execute(idempotency(principal, "POST", path, idempotencyKey, request),
                ProfileViews.AiReferenceView.class,
                () -> policy.requireActiveBusinessUser(principal),
                () -> {
                    if (repository.projectRevision(projectId) != request.expectedProjectRevision()) {
                        throw new ApiException(ApiCode.RESOURCE_CHANGED);
                    }
                    ProfileRepository.AiRow existing = repository.aiByClientRecord(projectId,
                            request.clientRecordId()).orElse(null);
                    if (existing != null) {
                        if (existing.title().equals(request.title()) && existing.text().equals(request.text())) {
                            return aiView(existing);
                        }
                        throw new ApiException(ApiCode.APPLICATION_EXISTS, "同一clientRecordId已存在不同内容");
                    }
                    String id = UUID.randomUUID().toString();
                    repository.insertAi(id, projectId, request.sourceProjectId(), request.clientRecordId(),
                            request.scene(), request.model().strip(), request.title().strip(),
                            request.text().strip(), principal.userId(), clock.instant());
                    audit.record(principal.userId(), "AI_REFERENCE_CREATE", "AI_REFERENCE", id,
                            null, 1, null);
                    return aiView(repository.aiReferences(projectId).stream()
                            .filter(row -> row.id().equals(id)).findFirst().orElseThrow());
                });
    }

    private ProfileViews.WorkView workView(ProfileRepository.WorkRow work, AuthenticatedUser principal,
                                           boolean owner) {
        List<ProfileRepository.WorkVersionRow> versions = repository.workVersions(work.id());
        ProfileRepository.WorkVersionRow current = versions.stream()
                .filter(v -> v.version() == work.currentVersion()).findFirst().orElse(null);
        JsonNode body = current == null ? mapper.createObjectNode() : parse(current.bodyJson());
        List<ProfileViews.ArtifactView> artifacts = new ArrayList<>();
        body.path("artifacts").forEach(item -> artifacts.add(new ProfileViews.ArtifactView(
                item.path("kind").asText("LINK"), item.path("url").asText(""),
                item.path("title").asText(""))));
        List<ProfileViews.WorkVersionView> versionViews = owner
                ? versions.stream().map(v -> new ProfileViews.WorkVersionView(v.version(), v.revision(),
                parse(v.bodyJson()).path("title").asText(work.title()),
                parse(v.bodyJson()).path("description").asText(""), v.createdAt())).toList()
                : List.of();
        return new ProfileViews.WorkView(work.id(), work.userId(), work.title(), work.description(),
                work.role(), work.contribution(), work.sourceProjectId(), artifacts, work.audience(),
                work.status(), versionViews, work.revision(), work.createdAt());
    }

    private ProfileViews.PeerReviewView peerView(ProfileRepository.PeerRow review,
                                                AuthenticatedUser principal) {
        ProfileRepository.ProjectBrief project = requireProject(review.projectId());
        ProfileRepository.UserProfileRow target = repository.userProfile(review.targetUserId()).orElse(null);
        JsonNode observations = parse(review.observationsJson());
        List<ProfileViews.PeerObservationView> list = new ArrayList<>();
        observations.forEach(item -> list.add(new ProfileViews.PeerObservationView(
                item.path("dimension").asText(), item.path("level").asText(),
                item.path("evidenceText").asText())));
        Instant reviewEnds = project.archivedAt() == null ? null : project.archivedAt().plus(REVIEW_WINDOW);
        boolean canEdit = "ACTIVE".equals(review.status()) && review.authorId().equals(principal.userId())
                && reviewEnds != null && !clock.instant().isAfter(reviewEnds);
        List<ProfileViews.PeerReviewVersionView> versions = repository.peerVersions(review.id()).stream()
                .map(v -> new ProfileViews.PeerReviewVersionView(v.version(), v.revision(),
                        target == null ? "" : target.displayName(),
                        parse(v.bodyJson()).path("comment").asText(""), v.createdAt())).toList();
        return new ProfileViews.PeerReviewView(review.id(), project.id(), project.name(), review.authorId(),
                review.targetUserId(), target == null ? "" : target.displayName(), review.observedEnough(),
                list, review.comment(), review.status(), canEdit,
                "ACTIVE".equals(review.status()) && review.authorId().equals(principal.userId()),
                versions, review.revision(), review.createdAt());
    }

    private boolean workVisible(ProfileRepository.WorkRow work, AuthenticatedUser viewer) {
        if (work.userId().equals(viewer.userId())) return true;
        if ("PRIVATE".equals(work.audience())) return false;
        ProfileRepository.ProjectBrief project = repository.project(work.sourceProjectId()).orElse(null);
        if (project == null || !project.schoolId().equals(viewer.schoolId())) return false;
        if ("TEAM".equals(work.audience())) return repository.member(project.id(), viewer.userId());
        return repository.courseQualified(project.squareId(), viewer.userId())
                || repository.member(project.id(), viewer.userId());
    }

    private void requireSourceProject(AuthenticatedUser principal, String projectId) {
        ProfileRepository.ProjectBrief project = requireProject(projectId);
        if (!project.schoolId().equals(principal.schoolId())) throw ApiException.notFound();
        if (!repository.member(projectId, principal.userId())) {
            throw new ApiException(ApiCode.FORBIDDEN, "作品来源项目必须是本人实际合作项目");
        }
        if (!workSources.canReference(principal.userId(), projectId)) {
            throw new ApiException(ApiCode.FORBIDDEN, "作品来源项目必须存在本人有效贡献证据");
        }
    }

    private ProfileRepository.ProjectBrief requireProject(String projectId) {
        return repository.project(projectId).orElseThrow(ApiException::notFound);
    }

    private boolean reviewOpen(ProfileRepository.ProjectBrief project) {
        return project.archivedAt() != null
                && !clock.instant().isAfter(project.archivedAt().plus(REVIEW_WINDOW));
    }

    private void validateObservations(List<ProfileRequests.ObservationInput> observations) {
        if (observations == null || observations.size() != 3) {
            throw ApiException.validation("observations 必须包含三维各一条");
        }
        Set<String> dims = new HashSet<>();
        for (ProfileRequests.ObservationInput observation : observations) {
            if (!DIMENSIONS.contains(observation.dimension()) || !dims.add(observation.dimension())) {
                throw ApiException.validation("observations 维度必须为三维各一条");
            }
            if (!LEVELS.contains(observation.level())) throw ApiException.validation("观察等级无效");
            if (observation.evidenceText() == null || observation.evidenceText().isBlank()) {
                throw ApiException.validation("观察事实不能为空");
            }
        }
    }

    private void validateArtifacts(List<ProfileRequests.ArtifactInput> artifacts) {
        if (artifacts != null && artifacts.size() > 10) throw ApiException.validation("材料最多10个");
        if (artifacts == null) return;
        for (ProfileRequests.ArtifactInput artifact : artifacts) {
            if (!"LINK".equals(artifact.kind())) {
                throw ApiException.validation("当前作品材料只接受LINK");
            }
            if (artifact.url() == null || artifact.url().isBlank()
                    || (!artifact.url().startsWith("https://") && !artifact.url().startsWith("http://"))) {
                throw ApiException.validation("LINK材料URL无效");
            }
        }
    }

    private ObjectNode workBody(String title, String description, String role, String contribution,
                                List<ProfileRequests.ArtifactInput> artifacts) {
        ObjectNode body = mapper.createObjectNode();
        body.put("title", title);
        body.put("description", description);
        body.put("role", role);
        body.put("myContribution", contribution);
        body.set("artifacts", mapper.valueToTree(artifacts == null ? List.of() : artifacts));
        return body;
    }

    private ObjectNode peerBody(ProfileRequests.PeerReviewRequest request) {
        ObjectNode body = mapper.createObjectNode();
        body.put("observedEnough", request.observedEnough());
        body.set("observations", mapper.valueToTree(request.observations()));
        body.put("comment", request.comment() == null ? "" : request.comment().strip());
        return body;
    }

    private ProfileViews.AiReferenceView aiView(ProfileRepository.AiRow row) {
        return new ProfileViews.AiReferenceView(row.id(), row.projectId(), row.sourceProjectId(),
                row.clientRecordId(), row.scene(), row.model(), row.title(), row.text(),
                row.createdBy(), row.revision(), row.createdAt(), row.origin(), row.authorId());
    }

    private String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("无法序列化画像数据", ex);
        }
    }

    private JsonNode parse(String json) {
        if (json == null || json.isBlank()) return mapper.createObjectNode();
        try {
            return mapper.readTree(json);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("无法解析画像数据", ex);
        }
    }

    private List<String> skills(String json) {
        if (json == null) return List.of();
        try {
            return mapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<>() {
            });
        } catch (Exception ex) {
            return List.of();
        }
    }

    private IdempotencyRequest idempotency(AuthenticatedUser principal, String method, String path,
                                           String key, Object body) {
        return new IdempotencyRequest(principal, method, path, key, body, List.of());
    }
}
