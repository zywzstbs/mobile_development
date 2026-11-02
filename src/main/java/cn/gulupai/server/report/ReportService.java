package cn.gulupai.server.report;

import cn.gulupai.server.common.api.ApiCode;
import cn.gulupai.server.common.api.ApiException;
import cn.gulupai.server.common.api.ListEnvelope;
import cn.gulupai.server.common.idempotency.CanonicalJson;
import cn.gulupai.server.common.idempotency.IdempotencyRequest;
import cn.gulupai.server.common.idempotency.IdempotencyService;
import cn.gulupai.server.common.security.AuthenticatedUser;
import cn.gulupai.server.common.security.CurrentUserPolicy;
import cn.gulupai.server.common.security.Hashing;
import cn.gulupai.server.report.ReportRepository.DraftRow;
import cn.gulupai.server.report.ReportRepository.OverallRow;
import cn.gulupai.server.report.ReportRepository.ProjectRow;
import cn.gulupai.server.report.ReportRepository.ReflectionRow;
import cn.gulupai.server.report.ReportRepository.ReleaseRow;
import cn.gulupai.server.report.ReportRepository.SnapshotRow;
import cn.gulupai.server.team.api.TeamViews;
import cn.gulupai.server.team.application.TeamService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class ReportService {

    private final ReportRepository repository;
    private final CurrentUserPolicy policy;
    private final IdempotencyService idempotency;
    private final CanonicalJson canonicalJson;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final TeamService teams;

    public ReportService(ReportRepository repository, CurrentUserPolicy policy,
                         IdempotencyService idempotency, CanonicalJson canonicalJson,
                         ObjectMapper mapper, Clock clock, TeamService teams) {
        this.repository = repository;
        this.policy = policy;
        this.idempotency = idempotency;
        this.canonicalJson = canonicalJson;
        this.mapper = mapper;
        this.clock = clock;
        this.teams = teams;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ReportViews.DeliveryDraft deliveryDraft(AuthenticatedUser principal, String projectId) {
        policy.requireActiveBusinessUser(principal);
        ProjectRow project = requireProject(projectId, false);
        requireRead(principal, projectId);
        Optional<DraftRow> row = repository.draft(projectId);
        JsonNode draft = row.map(value -> parse(value.bodyJson())).orElseGet(mapper::createObjectNode);
        DeliveryBasis basis = deliveryBasis(project, draft, row.map(DraftRow::revision).orElse(0),
                row.map(DraftRow::id).orElse(null));
        List<ReportViews.Gap> gaps = deliveryGaps(projectId, basis);
        List<ReportViews.Gap> ready = gaps.isEmpty()
                ? List.of(new ReportViews.Gap("DELIVERY_READY", "delivery", null, "交付来源与项目状态已核对"))
                : List.of();
        return new ReportViews.DeliveryDraft(basis.body(), basis.sourceKey(), ready, gaps);
    }

    @Transactional
    public ReportViews.DeliveryDraft updateDeliveryDraft(AuthenticatedUser principal, String projectId,
                                                         ReportRequests.UpdateDeliveryDraft request,
                                                         String idempotencyKey, String path) {
        return idempotency.execute(idempotency(principal, "PUT", path, idempotencyKey, request, projectId),
                ReportViews.DeliveryDraft.class, () -> requireLeader(principal, projectId, false), () -> {
                    ProjectRow project = requireProject(projectId, true);
                    ensureWritable(project);
                    validateMaterials(request.work());
                    validateMaterials(request.report());
                    validateMaterials(request.demo());
                    Instant now = clock.instant();
                    ObjectNode body = mapper.createObjectNode();
                    body.set("work", mapper.valueToTree(request.work()));
                    body.set("report", mapper.valueToTree(request.report()));
                    body.set("demo", mapper.valueToTree(request.demo()));
                    body.put("notes", request.notes());
                    Optional<DraftRow> previous = repository.draft(projectId);
                    if (previous.isEmpty()) {
                        if (request.expectedRevision() != 0) {
                            throw new ApiException(ApiCode.RESOURCE_CHANGED);
                        }
                        repository.insertDraft(UUID.randomUUID().toString(), projectId, write(body), now);
                    } else {
                        if (previous.get().revision() != request.expectedRevision()) {
                            throw new ApiException(ApiCode.RESOURCE_CHANGED);
                        }
                        if (repository.updateDraft(projectId, request.expectedRevision(), write(body), now) != 1) {
                            throw new ApiException(ApiCode.RESOURCE_CHANGED);
                        }
                    }
                    ProjectRow fresh = requireProject(projectId, false);
                    DraftRow stored = repository.draft(projectId).orElseThrow();
                    DeliveryBasis basis = deliveryBasis(fresh, parse(stored.bodyJson()), stored.revision(), stored.id());
                    List<ReportViews.Gap> gaps = deliveryGaps(projectId, basis);
                    List<ReportViews.Gap> readiness = gaps.isEmpty()
                            ? List.of(new ReportViews.Gap("DELIVERY_READY", "delivery", null, "交付来源与项目状态已核对"))
                            : List.of();
                    return new ReportViews.DeliveryDraft(basis.body(), basis.sourceKey(), readiness, gaps);
                });
    }

    @Transactional
    public ReportViews.DeliveryPreview previewDelivery(AuthenticatedUser principal, String projectId,
                                                       ReportRequests.PreviewDelivery request,
                                                       String idempotencyKey, String path) {
        return idempotency.execute(idempotency(principal, "POST", path, idempotencyKey, request, projectId),
                ReportViews.DeliveryPreview.class, () -> requireLeader(principal, projectId, false), () -> {
                    ProjectRow project = requireProject(projectId, true);
                    ensureWritable(project);
                    Optional<DraftRow> draftRow = repository.draft(projectId);
                    JsonNode draft = draftRow.map(value -> parse(value.bodyJson())).orElseGet(mapper::createObjectNode);
                    int revision = draftRow.map(DraftRow::revision).orElse(0);
                    DeliveryBasis basis = deliveryBasis(project, draft, revision,
                            draftRow.map(DraftRow::id).orElse(null));
                    if (!basis.sourceKey().equals(request.expectedSourceKey())) {
                        throw new ApiException(ApiCode.RESOURCE_CHANGED);
                    }
                    Instant now = clock.instant();
                    Instant expires = now.plusSeconds(600);
                    String token = Hashing.newToken();
                    String id = UUID.randomUUID().toString();
                    repository.insertPreview(id, projectId, principal.userId(), Hashing.sha256Hex(token),
                            basis.sourceKey(), revision, write(basis.body()), write(mapper.valueToTree(basis.sources())),
                            expires, now);
                    return new ReportViews.DeliveryPreview(token, expires, basis.sourceKey(),
                            basis.body(), basis.sources());
                });
    }

    @Transactional
    public ReportViews.DeliveryRelease publishDelivery(AuthenticatedUser principal, String projectId,
                                                       ReportRequests.PublishDelivery request,
                                                       String idempotencyKey, String path) {
        return idempotency.execute(idempotency(principal, "POST", path, idempotencyKey, request, projectId),
                ReportViews.DeliveryRelease.class, () -> requireLeader(principal, projectId, false), () -> {
                    ProjectRow project = requireProject(projectId, true);
                    ensureWritable(project);
                    ReportRepository.PreviewRow preview = repository.preview(projectId,
                                    Hashing.sha256Hex(request.previewToken()))
                            .orElseThrow(() -> new ApiException(ApiCode.PREVIEW_STALE));
                    Instant now = clock.instant();
                    if (!preview.createdBy().equals(principal.userId()) || preview.usedAt() != null
                            || !preview.expiresAt().isAfter(now)) {
                        throw new ApiException(ApiCode.PREVIEW_STALE);
                    }
                    Optional<DraftRow> draftRow = repository.draft(projectId);
                    JsonNode currentDraft = draftRow.map(value -> parse(value.bodyJson())).orElseGet(mapper::createObjectNode);
                    int revision = draftRow.map(DraftRow::revision).orElse(0);
                    DeliveryBasis current = deliveryBasis(project, currentDraft, revision,
                            draftRow.map(DraftRow::id).orElse(null));
                    if (!current.sourceKey().equals(preview.sourceKey()) || revision != preview.draftRevision()) {
                        throw new ApiException(ApiCode.PREVIEW_STALE);
                    }
                    ObjectNode snapshot = (ObjectNode) parse(preview.bodyJson());
                    ArrayNode artifacts = mapper.createArrayNode();
                    for (ReportViews.SourceRef ref : readSources(preview.sourcesJson())) {
                        repository.submissions(projectId).stream()
                                .filter(row -> ref.submissionId().equals(row.get("id")))
                                .findFirst().ifPresent(row -> {
                                    JsonNode sourceArtifacts = parse((String) row.get("artifactsJson"));
                                    if (sourceArtifacts.isArray()) {
                                        sourceArtifacts.forEach(artifacts::add);
                                    }
                                });
                    }
                    if (artifacts.size() > 10) {
                        throw new ApiException(ApiCode.COLLECTION_TOO_LARGE);
                    }
                    snapshot.set("artifacts", artifacts);
                    int version = repository.nextReleaseVersion(projectId);
                    String releaseId = UUID.randomUUID().toString();
                    repository.insertRelease(releaseId, projectId, version, current.sourceKey(),
                            write(snapshot), principal.userId(), now);
                    if (repository.markPreviewUsed(preview.id(), now) != 1) {
                        throw new ApiException(ApiCode.PREVIEW_STALE);
                    }
                    ReleaseRow stored = repository.release(releaseId).orElseThrow();
                    return releaseView(stored);
                });
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ListEnvelope<ReportViews.DeliveryRelease> releases(AuthenticatedUser principal, String projectId) {
        policy.requireActiveBusinessUser(principal);
        requireProject(projectId, false);
        requireRead(principal, projectId);
        return ListEnvelope.full(repository.releases(projectId).stream().map(this::releaseView).toList());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ReportViews.Report report(AuthenticatedUser principal, String projectId) {
        policy.requireActiveBusinessUser(principal);
        ProjectRow project = requireProject(projectId, false);
        requireRead(principal, projectId);
        ReportBasis basis = reportBasis(project);
        return new ReportViews.Report(basis.sourceKey(), project.closingCycle(), basis.data(),
                reportGaps(projectId, basis));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ListEnvelope<ReportViews.Snapshot> snapshots(AuthenticatedUser principal, String projectId) {
        policy.requireActiveBusinessUser(principal);
        requireProject(projectId, false);
        requireRead(principal, projectId);
        return ListEnvelope.full(repository.snapshots(projectId).stream().map(this::snapshotView).toList());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ReportViews.Snapshot snapshot(AuthenticatedUser principal, String snapshotId) {
        policy.requireActiveBusinessUser(principal);
        SnapshotRow row = repository.snapshot(snapshotId).orElseThrow(ApiException::notFound);
        requireRead(principal, row.projectId());
        return snapshotView(row);
    }

    @Transactional
    public ReportViews.Snapshot createSnapshot(AuthenticatedUser principal, String projectId,
                                               ReportRequests.CreateSnapshot request,
                                               String idempotencyKey, String path) {
        return idempotency.execute(idempotency(principal, "POST", path, idempotencyKey, request, projectId),
                ReportViews.Snapshot.class, () -> requireLeader(principal, projectId, false), () -> {
                    ProjectRow project = requireProject(projectId, true);
                    ensureWritable(project);
                    String type = request.type();
                    if (!Set.of("PROCESS", "FINAL", "EMPTY").contains(type)) {
                        throw ApiException.validation("报告类型仅允许PROCESS、FINAL或EMPTY");
                    }
                    if (request.closingCycle() != project.closingCycle()) {
                        throw new ApiException(ApiCode.RESOURCE_CHANGED);
                    }
                    if (!request.expectedSourceKey().equals(reportSourceKey(project))) {
                        throw new ApiException(ApiCode.SOURCE_CHANGED);
                    }
                    if (!"PROCESS".equals(type) && !"CLOSING".equals(project.status())) {
                        throw ApiException.validation("FINAL和EMPTY报告只能在本轮收尾期间生成");
                    }
                    boolean empty = repository.emptyProjectHistory(projectId);
                    if ("EMPTY".equals(type)) {
                        if (!empty || request.emptyReason().isBlank()) {
                            throw ApiException.validation("EMPTY仅适用于从未建立任务、正式约定或提交的项目，且须填写原因");
                        }
                    } else if (!request.emptyReason().isEmpty()) {
                        throw ApiException.validation("只有EMPTY报告可以填写emptyReason");
                    }
                    if ("FINAL".equals(type)) {
                        if (empty) {
                            throw ApiException.validation("空项目应使用EMPTY报告");
                        }
                        ReleaseRow latest = currentRelease(project).orElseThrow(
                                () -> ApiException.validation("最终报告需要当前有效交付"));
                        if (!latest.sourceKey().equals(deliverySourceKey(project))) {
                            throw new ApiException(ApiCode.SOURCE_CHANGED, "交付来源已变化，请重新预览并发布");
                        }
                    }
                    ReportBasis basis = reportBasis(project);
                    Optional<ReleaseRow> latest = currentRelease(project);
                    Optional<SnapshotRow> duplicate = repository.snapshot(projectId, type,
                            project.closingCycle(), basis.sourceKey());
                    if (duplicate.isPresent()) {
                        return snapshotView(duplicate.get());
                    }
                    String releaseId = "EMPTY".equals(type) ? null : latest.map(ReleaseRow::id).orElse(null);
                    SnapshotRow created = new SnapshotRow(UUID.randomUUID().toString(), projectId, type,
                            request.name(), project.closingCycle(), basis.sourceKey(), releaseId,
                            write(basis.data()), request.emptyReason(), principal.userId(), clock.instant(), 1);
                    repository.insertSnapshot(created.id(), created.projectId(), created.type(), created.name(),
                            created.closingCycle(), created.sourceKey(), created.releaseId(), created.bodyJson(),
                            created.emptyReason(), created.createdBy(), created.createdAt());
                    return snapshotView(repository.snapshot(created.id()).orElseThrow());
                });
    }

    @Transactional
    public TeamViews.ProjectView lifecycle(AuthenticatedUser principal, String projectId,
                                           ReportRequests.LifecycleAction request,
                                           String idempotencyKey, String path) {
        return idempotency.execute(idempotency(principal, "POST", path, idempotencyKey, request, projectId),
                TeamViews.ProjectView.class, () -> requireLeader(principal, projectId, false), () -> {
                    ProjectRow project = requireProject(projectId, true);
                    ensureWritable(project);
                    if (project.revision() != request.expectedRevision()) {
                        throw new ApiException(ApiCode.RESOURCE_CHANGED);
                    }
                    Instant now = clock.instant();
                    switch (request.action()) {
                        case "ENTER_CLOSING" -> {
                            if (!"ACTIVE".equals(project.status())) {
                                throw ApiException.validation("只有ACTIVE项目可以进入收尾");
                            }
                            repository.enterClosing(projectId, request.expectedRevision(), now);
                        }
                        case "RESTORE_ACTIVE" -> {
                            if (!"CLOSING".equals(project.status())) {
                                throw ApiException.validation("只有CLOSING项目可以恢复活动");
                            }
                            repository.restoreActive(projectId, request.expectedRevision(), now);
                        }
                        case "ARCHIVE" -> {
                            if (!"CLOSING".equals(project.status())) {
                                throw ApiException.validation("只有CLOSING项目可以归档");
                            }
                            List<ReportViews.Gap> blockers = archiveBlockers(projectId);
                            if (!blockers.isEmpty()) {
                                throw new ApiException(ApiCode.VALIDATION_FAILED,
                                        blockers.stream().map(ReportViews.Gap::message).reduce((a, b) -> a + "；" + b).orElse("项目尚未满足归档条件"));
                            }
                            if (repository.emptyProjectHistory(projectId)) {
                                archiveEmpty(project, principal, request, now);
                            } else {
                                archiveNonEmpty(project, principal, request, now);
                            }
                        }
                        default -> throw ApiException.validation("生命周期动作无效");
                    }
                    return teams.project(principal, projectId);
                });
    }

    @Transactional
    public ReportViews.Reflection saveReflection(AuthenticatedUser principal, String projectId,
                                                ReportRequests.PersonalReflection request,
                                                String idempotencyKey, String path) {
        return idempotency.execute(idempotency(principal, "PUT", path, idempotencyKey, request, projectId),
                ReportViews.Reflection.class, () -> requireMember(principal, projectId, false), () -> {
                    ProjectRow project = requireProject(projectId, true);
                    ensureWritable(project);
                    if (!repository.canRead(projectId, principal.userId()) || !repository.isActiveMember(projectId, principal.userId())) {
                        throw new ApiException(ApiCode.FORBIDDEN);
                    }
                    ObjectNode body = mapper.createObjectNode();
                    body.put("workSummary", request.workSummary().strip());
                    body.put("learning", request.learning().strip());
                    body.put("problems", request.problems().strip());
                    body.put("improvements", request.improvements().strip());
                    Optional<ReflectionRow> existing = repository.reflection(projectId, principal.userId());
                    Instant now = clock.instant();
                    if (existing.isEmpty()) {
                        if (request.expectedRevision() != 0) throw new ApiException(ApiCode.RESOURCE_CHANGED);
                        repository.insertReflection(UUID.randomUUID().toString(), projectId, principal.userId(), write(body), now);
                    } else {
                        if (existing.get().revision() != request.expectedRevision()) {
                            throw new ApiException(ApiCode.RESOURCE_CHANGED);
                        }
                        if (repository.updateReflection(projectId, principal.userId(), request.expectedRevision(), write(body), now) != 1) {
                            throw new ApiException(ApiCode.RESOURCE_CHANGED);
                        }
                    }
                    return reflectionView(repository.reflection(projectId, principal.userId()).orElseThrow());
                });
    }

    @Transactional
    public ReportViews.OverallReflection saveOverallReflection(AuthenticatedUser principal, String projectId,
                                                              ReportRequests.OverallReflection request,
                                                              String idempotencyKey, String path) {
        return idempotency.execute(idempotency(principal, "PUT", path, idempotencyKey, request, projectId),
                ReportViews.OverallReflection.class, () -> requireLeader(principal, projectId, false), () -> {
                    ProjectRow project = requireProject(projectId, true);
                    ensureWritable(project);
                    ObjectNode body = mapper.createObjectNode();
                    body.put("summary", request.summary().strip());
                    body.put("coordination", request.coordination().strip());
                    body.put("improvements", request.improvements().strip());
                    Optional<OverallRow> existing = repository.overall(projectId);
                    Instant now = clock.instant();
                    if (existing.isEmpty()) {
                        if (request.expectedRevision() != 0) throw new ApiException(ApiCode.RESOURCE_CHANGED);
                        repository.insertOverall(UUID.randomUUID().toString(), projectId, principal.userId(), write(body), now);
                    } else {
                        if (existing.get().revision() != request.expectedRevision()) {
                            throw new ApiException(ApiCode.RESOURCE_CHANGED);
                        }
                        if (repository.updateOverall(projectId, request.expectedRevision(), write(body), now) != 1) {
                            throw new ApiException(ApiCode.RESOURCE_CHANGED);
                        }
                    }
                    return overallView(repository.overall(projectId).orElseThrow());
                });
    }

    private void archiveEmpty(ProjectRow project, AuthenticatedUser principal,
                              ReportRequests.LifecycleAction request, Instant now) {
        if (request.expectedReleaseId() != null && !request.expectedReleaseId().isBlank()) {
            throw ApiException.validation("EMPTY归档不接受expectedReleaseId");
        }
        SnapshotRow report = requiredCurrentSnapshot(project, request.expectedReportId(), "EMPTY");
        if (report.releaseId() != null || report.emptyReason().isBlank()) {
            throw new ApiException(ApiCode.SOURCE_CHANGED, "EMPTY报告状态不符合归档条件");
        }
        repository.archive(project.id(), request.expectedRevision(), now);
    }

    private void archiveNonEmpty(ProjectRow project, AuthenticatedUser principal,
                                 ReportRequests.LifecycleAction request, Instant now) {
        if (request.expectedReleaseId() == null || request.expectedReleaseId().isBlank()) {
            throw ApiException.validation("非空项目归档必须提供当前有效交付");
        }
        if (request.expectedReportId() == null || request.expectedReportId().isBlank()) {
            throw ApiException.validation("非空项目归档必须提供本轮FINAL报告");
        }
        ReleaseRow latest = currentRelease(project).orElseThrow(
                () -> new ApiException(ApiCode.SOURCE_CHANGED, "当前有效交付不存在"));
        if (!latest.id().equals(request.expectedReleaseId())
                || !latest.sourceKey().equals(deliverySourceKey(project))) {
            throw new ApiException(ApiCode.SOURCE_CHANGED, "交付不是当前有效版本");
        }
        SnapshotRow report = requiredCurrentSnapshot(project, request.expectedReportId(), "FINAL");
        if (!latest.id().equals(report.releaseId()) || !report.sourceKey().equals(reportSourceKey(project))) {
            throw new ApiException(ApiCode.SOURCE_CHANGED, "最终报告已过期或未绑定当前交付");
        }
        repository.archive(project.id(), request.expectedRevision(), now);
    }

    private SnapshotRow requiredCurrentSnapshot(ProjectRow project, String id, String type) {
        if (id == null || id.isBlank()) throw ApiException.validation("归档必须指定对应类型的报告快照");
        SnapshotRow row = repository.snapshot(id).orElseThrow(() -> new ApiException(ApiCode.SOURCE_CHANGED));
        if (!row.projectId().equals(project.id()) || !row.type().equals(type)
                || row.closingCycle() != project.closingCycle()
                || !row.sourceKey().equals(reportSourceKey(project))) {
            throw new ApiException(ApiCode.SOURCE_CHANGED, "报告快照不是本轮当前版本");
        }
        return row;
    }

    private ReportViews.DeliveryRelease releaseView(ReleaseRow row) {
        JsonNode body = parse(row.bodyJson());
        return new ReportViews.DeliveryRelease(row.id(), row.projectId(), row.version(), row.sourceKey(),
                readMaterials(body.path("work")), readMaterials(body.path("report")),
                readMaterials(body.path("demo")), readMaterials(body.path("artifacts")),
                row.confirmedBy(), row.confirmedAt(), row.revision());
    }

    private ReportViews.Snapshot snapshotView(SnapshotRow row) {
        return new ReportViews.Snapshot(row.id(), row.projectId(), row.type(), row.name(), row.sourceKey(),
                row.closingCycle(), parse(row.bodyJson()), row.emptyReason(), row.createdBy(),
                row.createdAt(), row.revision());
    }

    private ReportViews.Reflection reflectionView(ReflectionRow row) {
        JsonNode body = parse(row.bodyJson());
        return new ReportViews.Reflection(row.id(), row.projectId(), row.userId(), row.displayName(),
                body.path("workSummary").asText(""), body.path("learning").asText(""),
                body.path("problems").asText(""), body.path("improvements").asText(""),
                row.revision(), row.updatedAt());
    }

    private ReportViews.OverallReflection overallView(OverallRow row) {
        JsonNode body = parse(row.bodyJson());
        return new ReportViews.OverallReflection(row.id(), row.projectId(), row.authorId(),
                body.path("summary").asText(""), body.path("coordination").asText(""),
                body.path("improvements").asText(""), row.revision(), row.updatedAt());
    }

    private DeliveryBasis deliveryBasis(ProjectRow project, JsonNode draft, int draftRevision, String draftId) {
        ReportBasis reportBasis = reportBasis(project);
        List<ReportViews.SourceRef> sources = sources(reportBasis.submissions());
        ObjectNode body = mapper.createObjectNode();
        if (draftId != null) {
            body.put("id", draftId);
            body.put("projectId", project.id());
            body.set("work", arrayOrEmpty(draft.path("work")));
            body.set("report", arrayOrEmpty(draft.path("report")));
            body.set("demo", arrayOrEmpty(draft.path("demo")));
            body.put("notes", draft.path("notes").asText(""));
            body.put("revision", draftRevision);
        }
        LinkedHashMap<String, Object> deliveryCore = new LinkedHashMap<>();
        deliveryCore.put("project", project);
        deliveryCore.put("tasks", reportBasis.tasks());
        deliveryCore.put("shares", reportBasis.shares());
        deliveryCore.put("submissions", reportBasis.submissions());
        deliveryCore.put("members", reportBasis.members());
        LinkedHashMap<String, Object> source = new LinkedHashMap<>();
        source.put("projectSourceKey", canonicalJson.hash(deliveryCore));
        source.put("draftId", draftId);
        source.put("draftRevision", draftRevision);
        source.put("draftBody", draft);
        source.put("sources", sources);
        String key = canonicalJson.hash(source);
        return new DeliveryBasis(body, key, sources, draftRevision, reportBasis);
    }

    private List<ReportViews.SourceRef> sources(List<Map<String, Object>> submissions) {
        return submissions.stream()
                .filter(row -> "REVIEWED".equals(row.get("status")))
                .filter(row -> row.get("acceptanceId") != null)
                .filter(row -> !"RETURNED".equals(row.get("acceptanceDecision")))
                .map(row -> new ReportViews.SourceRef((String) row.get("taskId"), (String) row.get("id")))
                .toList();
    }

    private ReportBasis reportBasis(ProjectRow project) {
        List<Map<String, Object>> tasks = repository.tasks(project.id());
        List<Map<String, Object>> shares = repository.shares(project.id());
        List<Map<String, Object>> submissions = repository.submissions(project.id());
        List<Map<String, Object>> members = repository.members(project.id());
        List<ReflectionRow> reflectionRows = repository.reflections(project.id());
        Optional<OverallRow> overall = repository.overall(project.id());
        ObjectNode data = reportData(project, tasks, shares, submissions, members, reflectionRows, overall);
        LinkedHashMap<String, Object> basis = new LinkedHashMap<>();
        basis.put("project", project);
        basis.put("tasks", tasks);
        basis.put("shares", shares);
        basis.put("submissions", submissions);
        basis.put("members", members);
        basis.put("reflections", reflectionRows);
        basis.put("overall", overall.orElse(null));
        String core = canonicalJson.hash(basis);
        Optional<ReleaseRow> latest = currentRelease(project);
        LinkedHashMap<String, Object> reportSource = new LinkedHashMap<>();
        reportSource.put("coreSourceKey", core);
        reportSource.put("release", latest.<Object>map(row -> Map.of(
                "id", row.id(), "version", row.version(), "sourceKey", row.sourceKey())).orElseGet(Map::of));
        return new ReportBasis(data, canonicalJson.hash(reportSource), core, tasks, shares, submissions,
                members, reflectionRows, overall);
    }

    private ObjectNode reportData(ProjectRow project, List<Map<String, Object>> tasks,
                                  List<Map<String, Object>> shares, List<Map<String, Object>> submissions,
                                  List<Map<String, Object>> members, List<ReflectionRow> reflectionRows,
                                  Optional<OverallRow> overall) {
        ObjectNode data = mapper.createObjectNode();
        ObjectNode overview = data.putObject("overview");
        overview.put("projectName", project.name());
        overview.put("courseName", project.courseName());
        overview.put("assignmentName", project.assignmentName());
        overview.put("taskCount", tasks.size());
        overview.put("memberCount", members.size());

        ArrayNode division = data.putArray("divisionAndProcess");
        Map<String, List<Map<String, Object>>> byTask = new LinkedHashMap<>();
        for (Map<String, Object> share : shares) {
            byTask.computeIfAbsent((String) share.get("taskId"), ignored -> new ArrayList<>()).add(share);
        }
        for (Map<String, Object> task : tasks) {
            ObjectNode item = division.addObject();
            item.put("taskId", (String) task.get("id"));
            item.put("title", (String) task.get("title"));
            item.put("ownerName", (String) task.get("ownerName"));
            item.put("mode", (String) task.get("mode"));
            item.put("status", (String) task.get("status"));
            putInstant(item, "dueAt", (Instant) task.get("dueAt"));
            item.put("revision", (Integer) task.get("revision"));
            ArrayNode taskShares = item.putArray("shares");
            for (Map<String, Object> share : byTask.getOrDefault((String) task.get("id"), List.of())) {
                ObjectNode view = taskShares.addObject();
                view.put("userId", (String) share.get("userId"));
                view.put("displayName", (String) share.get("displayName"));
                view.put("points", (String) share.get("points"));
                view.put("responsibility", (String) share.get("responsibility"));
                view.put("accepted", (Boolean) share.get("signed"));
            }
        }

        ArrayNode deliveries = data.putArray("deliveriesAndAcceptance");
        for (Map<String, Object> submission : submissions) {
            ObjectNode item = deliveries.addObject();
            item.put("taskTitle", (String) submission.get("taskTitle"));
            item.put("submissionId", (String) submission.get("id"));
            item.put("sequence", (Integer) submission.get("sequence"));
            item.put("submittedByName", (String) submission.get("submittedByName"));
            item.put("summary", (String) submission.get("summary"));
            putInstant(item, "submittedAt", (Instant) submission.get("submittedAt"));
            item.put("status", (String) submission.get("status"));
            item.set("artifacts", arrayOrEmpty(parse((String) submission.get("artifactsJson"))));
            item.set("acceptance", acceptance(submission));
            item.set("reviews", reviews(submission));
            item.set("unitsSnapshot", unitsSnapshot(submission));
            item.set("criteria", criteria(submission));
        }

        ArrayNode memberWork = data.putArray("memberWork");
        Map<String, ReflectionRow> reflectionsByUser = new LinkedHashMap<>();
        reflectionRows.forEach(row -> reflectionsByUser.put(row.userId(), row));
        for (Map<String, Object> member : members) {
            ObjectNode view = memberWork.addObject();
            view.put("userId", (String) member.get("userId"));
            view.put("name", (String) member.get("name"));
            view.put("currentMember", (Boolean) member.get("currentMember"));
            view.put("responsibleTasks", (Integer) member.get("responsibleTasks"));
            view.put("completedResponsibleTasks", (Integer) member.get("completedResponsibleTasks"));
            ObjectNode contribution = view.putObject("formalContribution");
            contribution.put("responsibilityPoints", (String) member.get("responsibilityPoints"));
            contribution.put("confirmedPoints", (String) member.get("confirmedPoints"));
        }

        ObjectNode retrospective = data.putObject("retrospective");
        ArrayNode retroMembers = retrospective.putArray("members");
        for (Map<String, Object> member : members) {
            String userId = (String) member.get("userId");
            ReflectionRow reflection = reflectionsByUser.get(userId);
            ObjectNode view = retroMembers.addObject();
            view.put("userId", userId);
            view.put("authorName", reflection == null ? (String) member.get("name") : reflection.displayName());
            view.put("missing", reflection == null);
            JsonNode body = reflection == null ? mapper.createObjectNode() : parse(reflection.bodyJson());
            view.put("workSummary", body.path("workSummary").asText(""));
            view.put("learning", body.path("learning").asText(""));
            view.put("problems", body.path("problems").asText(""));
            view.put("improvements", body.path("improvements").asText(""));
        }
        if (overall.isPresent()) {
            JsonNode body = parse(overall.get().bodyJson());
            ObjectNode overallView = retrospective.putObject("overall");
            overallView.put("summary", body.path("summary").asText(""));
            overallView.put("coordination", body.path("coordination").asText(""));
            overallView.put("improvements", body.path("improvements").asText(""));
        } else {
            retrospective.putObject("overall");
        }
        return data;
    }

    private ObjectNode acceptance(Map<String, Object> submission) {
        ObjectNode result = mapper.createObjectNode();
        if (submission.get("acceptanceId") == null) return result;
        result.put("id", (String) submission.get("acceptanceId"));
        result.put("submissionId", (String) submission.get("id"));
        result.put("reviewerId", (String) submission.get("reviewerId"));
        result.put("kind", (String) submission.get("acceptanceKind"));
        result.set("checks", arrayOrEmpty(parse((String) submission.get("checksJson"))));
        result.put("reason", (String) submission.get("acceptanceReason"));
        result.put("decision", (String) submission.get("acceptanceDecision"));
        putInstant(result, "acceptedAt", (Instant) submission.get("acceptedAt"));
        return result;
    }

    private ArrayNode reviews(Map<String, Object> submission) {
        ArrayNode result = mapper.createArrayNode();
        if (submission.get("acceptanceId") == null) return result;
        ObjectNode review = result.addObject();
        review.put("reviewerId", (String) submission.get("reviewerId"));
        review.put("decision", (String) submission.get("acceptanceDecision"));
        ArrayNode results = review.putArray("unitResults");
        JsonNode checks = parse((String) submission.get("checksJson"));
        Map<String, List<JsonNode>> grouped = new LinkedHashMap<>();
        if (checks.isArray()) {
            checks.forEach(check -> grouped.computeIfAbsent(check.path("unitId").asText(""), ignored -> new ArrayList<>()).add(check));
        }
        grouped.forEach((unitId, values) -> {
            boolean passed = values.stream().allMatch(value -> value.path("met").asBoolean(false));
            ObjectNode unit = results.addObject();
            if (!unitId.isEmpty()) unit.put("unitId", unitId); else unit.putNull("unitId");
            unit.put("decision", passed ? "PASSED" : "RETURNED");
            unit.put("reason", passed ? "" : (String) submission.get("acceptanceReason"));
        });
        return result;
    }

    private ArrayNode unitsSnapshot(Map<String, Object> submission) {
        ArrayNode result = mapper.createArrayNode();
        String raw = (String) submission.get("agreementJson");
        if (raw == null) return result;
        JsonNode plan = parse(raw);
        JsonNode units = plan.path("units");
        if (units.isArray()) {
            JsonNode dueSnapshot = parse((String) submission.get("unitDueJson"));
            Integer agreementVersion = (Integer) submission.get("agreementVersion");
            String submissionId = (String) submission.get("id");
            units.forEach(unit -> {
                String unitId = unit.path("id").asText();
                ReportRepository.UnitReportRow state = repository.reportUnit(unitId, submissionId)
                        .orElseThrow(() -> new IllegalStateException("提交引用的正式交付项不存在"));
                ObjectNode view = result.addObject();
                view.put("id", unitId);
                view.put("title", unit.path("title").asText(""));
                JsonNode dueAt = dueSnapshot.path(unitId);
                view.put("dueAt", dueAt.isTextual() ? dueAt.asText() : plan.path("dueAt").asText(""));
                view.put("points", unit.path("points").asText("0.00"));

                ArrayNode shares = view.putArray("shares");
                for (ReportRepository.UnitShareReportRow share : repository.reportUnitShares(unitId, agreementVersion)) {
                    ObjectNode shareView = shares.addObject();
                    shareView.put("userId", share.userId());
                    shareView.put("displayName", share.displayName());
                    shareView.put("points", share.points());
                    shareView.put("responsibility", share.responsibility());
                    shareView.put("accepted", share.accepted());
                }

                ArrayNode criteria = view.putArray("criteria");
                JsonNode unitCriteria = unit.path("criteria");
                if (unitCriteria.isArray()) {
                    unitCriteria.forEach(criterion -> {
                        ObjectNode criterionView = criteria.addObject();
                        criterionView.put("id", criterion.path("id").asText());
                        criterionView.put("text", criterion.path("text").asText());
                    });
                }
                view.put("revision", state.revision());
                ObjectNode acceptance = view.putObject("acceptance");
                if (Boolean.TRUE.equals(state.passed())) acceptance.put("passed", true);

                ArrayNode confirmations = view.putArray("contributionConfirmations");
                for (ReportRepository.ContributionReportRow confirmation :
                        repository.contributionConfirmations(unitId, submissionId)) {
                    ObjectNode confirmationView = confirmations.addObject();
                    confirmationView.put("userId", confirmation.userId());
                    confirmationView.put("submissionId", confirmation.submissionId());
                    confirmationView.put("acceptanceId", confirmation.acceptanceId());
                    confirmationView.put("statement", confirmation.statement());
                    putInstant(confirmationView, "createdAt", confirmation.createdAt());
                }
            });
        }
        return result;
    }

    private ArrayNode criteria(Map<String, Object> submission) {
        ArrayNode result = mapper.createArrayNode();
        JsonNode snapshot = parse((String) submission.get("criteriaJson"));
        if (snapshot.isArray()) {
            snapshot.forEach(criterion -> {
                if (criterion.path("unitId").isMissingNode() || criterion.path("unitId").isNull()) {
                    ObjectNode view = result.addObject();
                    view.put("id", criterion.path("id").asText());
                    view.put("text", criterion.path("text").asText());
                }
            });
        }
        return result;
    }

    private List<ReportViews.Gap> deliveryGaps(String projectId, DeliveryBasis basis) {
        List<ReportViews.Gap> gaps = archiveBlockers(projectId);
        if (basis.draftRevision() == 0) {
            gaps.add(new ReportViews.Gap("DELIVERY_DRAFT_MISSING", "delivery", null,
                    "尚未保存交付草稿"));
        }
        return gaps;
    }

    private List<ReportViews.Gap> reportGaps(String projectId, ReportBasis basis) {
        List<ReportViews.Gap> gaps = archiveBlockers(projectId);
        Set<String> written = new LinkedHashSet<>();
        basis.reflections().forEach(row -> written.add(row.userId()));
        for (Map<String, Object> member : basis.members()) {
            String user = (String) member.get("userId");
            if (!written.contains(user)) {
                gaps.add(new ReportViews.Gap("REFLECTION_MISSING", "retrospective", user,
                        (String) member.get("name") + "尚未填写个人复盘"));
            }
        }
        if (basis.overall().isEmpty()) {
            gaps.add(new ReportViews.Gap("OVERALL_REFLECTION_MISSING", "retrospective", null,
                    "组长尚未填写整体复盘"));
        }
        return gaps;
    }

    private List<ReportViews.Gap> archiveBlockers(String projectId) {
        List<ReportViews.Gap> gaps = new ArrayList<>();
        for (Map<String, Object> task : repository.tasks(projectId)) {
            String status = (String) task.get("status");
            if (!"DONE".equals(status) && !"CANCELLED".equals(status)) {
                gaps.add(new ReportViews.Gap("TASK_UNFINISHED", "divisionAndProcess",
                        (String) task.get("id"), "任务尚未完成：" + task.get("title")));
            }
        }
        addCountGap(gaps, repository.countPendingSubmissions(projectId), "SUBMISSION_PENDING",
                "deliveriesAndAcceptance", "仍有待验收提交");
        addCountGap(gaps, repository.countUnconfirmedContributions(projectId), "CONTRIBUTION_UNCONFIRMED",
                "memberWork", "仍有已验收但未本人确认的贡献");
        addCountGap(gaps, repository.countOpenAgreements(projectId), "AGREEMENT_OPEN",
                "divisionAndProcess", "仍有未结束的正式约定");
        addCountGap(gaps, repository.countOpenProposals(projectId), "CHANGE_PROPOSAL_OPEN",
                "divisionAndProcess", "仍有未结束的分项调整提案");
        addCountGap(gaps, repository.countOpenPersonnelChanges(projectId), "PERSONNEL_CHANGE_OPEN",
                "memberWork", "仍有未结束的人员变更");
        addCountGap(gaps, repository.countHandoverBlockedTasks(projectId), "HANDOVER_RESIGN_REQUIRED",
                "divisionAndProcess", "仍有等待正式约定重签的任务");
        addCountGap(gaps, repository.countPendingReviewerInvitations(projectId), "REVIEWER_INVITATION_OPEN",
                "deliveriesAndAcceptance", "仍有待回应的验收人邀请");
        return gaps;
    }

    private void addCountGap(List<ReportViews.Gap> gaps, int count, String code, String section, String message) {
        if (count > 0) gaps.add(new ReportViews.Gap(code, section, null, message + "（" + count + "）"));
    }

    private void validateMaterials(List<ReportRequests.MaterialRef> materials) {
        if (materials.size() > 10) throw new ApiException(ApiCode.COLLECTION_TOO_LARGE);
        for (ReportRequests.MaterialRef material : materials) {
            if (!Set.of("LINK", "FILE").contains(material.kind())) {
                throw ApiException.validation("材料kind仅允许LINK或FILE");
            }
            try {
                URI uri = URI.create(material.url());
                if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null
                        || material.url().startsWith("file:") || material.url().startsWith("content:")) {
                    throw ApiException.validation("材料必须使用可访问的HTTP(S)链接或授权下载地址");
                }
            } catch (IllegalArgumentException ex) {
                throw ApiException.validation("材料链接格式无效");
            }
        }
    }

    private void requireMember(AuthenticatedUser principal, String projectId, boolean locking) {
        policy.requireActiveBusinessUser(principal);
        requireProject(projectId, locking);
        if (!repository.isActiveMember(projectId, principal.userId())) {
            throw new ApiException(ApiCode.FORBIDDEN);
        }
    }

    private void requireLeader(AuthenticatedUser principal, String projectId, boolean locking) {
        policy.requireActiveBusinessUser(principal);
        ProjectRow project = requireProject(projectId, locking);
        ensureWritable(project);
        if (!repository.isLeader(projectId, principal.userId())) {
            throw new ApiException(ApiCode.FORBIDDEN);
        }
    }

    private void requireRead(AuthenticatedUser principal, String projectId) {
        if (!repository.canRead(projectId, principal.userId())) {
            throw new ApiException(ApiCode.FORBIDDEN);
        }
    }

    private ProjectRow requireProject(String projectId, boolean lock) {
        return repository.project(projectId, lock).orElseThrow(ApiException::notFound);
    }

    private void ensureWritable(ProjectRow project) {
        if ("ARCHIVED".equals(project.status())) throw new ApiException(ApiCode.PROJECT_READ_ONLY);
    }

    private String reportSourceKey(ProjectRow project) {
        return reportBasis(project).sourceKey();
    }

    private String deliverySourceKey(ProjectRow project) {
        Optional<DraftRow> draft = repository.draft(project.id());
        return deliveryBasis(project, draft.map(row -> (JsonNode) parse(row.bodyJson()).deepCopy())
                        .orElseGet(mapper::createObjectNode), draft.map(DraftRow::revision).orElse(0),
                draft.map(DraftRow::id).orElse(null))
                .sourceKey();
    }

    private Optional<ReleaseRow> currentRelease(ProjectRow project) {
        return repository.latestRelease(project.id());
    }

    private IdempotencyRequest idempotency(AuthenticatedUser principal, String method, String path,
                                           String key, Object body, String projectId) {
        return new IdempotencyRequest(principal, method, path, key, body,
                List.of(new IdempotencyRequest.ResourceRef("project", projectId)));
    }

    private ArrayNode arrayOrEmpty(JsonNode node) {
        if (node != null && node.isArray()) return (ArrayNode) node.deepCopy();
        return mapper.createArrayNode();
    }

    private List<ReportRequests.MaterialRef> readMaterials(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        List<ReportRequests.MaterialRef> result = new ArrayList<>();
        node.forEach(item -> result.add(new ReportRequests.MaterialRef(item.path("kind").asText("LINK"),
                item.hasNonNull("url") ? item.path("url").asText() : null,
                item.path("title").asText(""))));
        return List.copyOf(result);
    }

    private List<ReportViews.SourceRef> readSources(String json) {
        try {
            return mapper.readerForListOf(ReportViews.SourceRef.class).readValue(json);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("无法读取交付预览来源", ex);
        }
    }

    private JsonNode parse(String json) {
        if (json == null || json.isBlank()) return mapper.createObjectNode();
        try {
            return mapper.readTree(json);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("数据库JSON无法读取", ex);
        }
    }

    private String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("无法序列化报告数据", ex);
        }
    }

    private void putInstant(ObjectNode node, String field, Instant instant) {
        if (instant == null) node.putNull(field); else node.put(field, instant.toString());
    }

    private record DeliveryBasis(ObjectNode body, String sourceKey, List<ReportViews.SourceRef> sources,
                                 int draftRevision, ReportBasis reportBasis) {
    }

    private record ReportBasis(ObjectNode data, String sourceKey, String coreSourceKey,
                               List<Map<String, Object>> tasks, List<Map<String, Object>> shares,
                               List<Map<String, Object>> submissions, List<Map<String, Object>> members,
                               List<ReflectionRow> reflections, Optional<OverallRow> overall) {
    }
}
