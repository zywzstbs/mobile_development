package cn.gulupai.server.delivery;

import cn.gulupai.server.common.api.ApiCode;
import cn.gulupai.server.common.api.ApiException;
import cn.gulupai.server.common.audit.AuditService;
import cn.gulupai.server.common.idempotency.IdempotencyRequest;
import cn.gulupai.server.common.idempotency.IdempotencyService;
import cn.gulupai.server.common.security.AuthenticatedUser;
import cn.gulupai.server.common.security.CurrentUserPolicy;
import cn.gulupai.server.course.persistence.SquareRepository;
import cn.gulupai.server.course.persistence.SquareRow;
import cn.gulupai.server.delivery.DeliveryRepository.AcceptanceRow;
import cn.gulupai.server.delivery.DeliveryRepository.AgreementRow;
import cn.gulupai.server.delivery.DeliveryRepository.ArrangementRow;
import cn.gulupai.server.delivery.DeliveryRepository.DraftRow;
import cn.gulupai.server.delivery.DeliveryRepository.InvitationRow;
import cn.gulupai.server.delivery.DeliveryRepository.ProposalRow;
import cn.gulupai.server.delivery.DeliveryRepository.ResponseRow;
import cn.gulupai.server.delivery.DeliveryRepository.ShareRow;
import cn.gulupai.server.delivery.DeliveryRepository.SubmissionRow;
import cn.gulupai.server.delivery.DeliveryRepository.UnitRow;
import cn.gulupai.server.file.FileService;
import cn.gulupai.server.governance.GovRepository;
import cn.gulupai.server.identity.persistence.UserRepository;
import cn.gulupai.server.identity.persistence.UserRow;
import cn.gulupai.server.task.api.TaskRequests;
import cn.gulupai.server.task.api.TaskViews;
import cn.gulupai.server.task.persistence.TaskRepository;
import cn.gulupai.server.task.persistence.TaskRepository.CriterionRow;
import cn.gulupai.server.task.persistence.TaskRepository.DependencyRow;
import cn.gulupai.server.task.persistence.TaskRepository.TaskRow;
import cn.gulupai.server.team.persistence.ProjectMemberRow;
import cn.gulupai.server.team.persistence.ProjectRow;
import cn.gulupai.server.team.persistence.TeamRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.BigDecimal;
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

/**
 * 正式约定、草稿、提交和验收。组长发布不会代替任何人确认。
 * 交接表还没有，重签直接拒绝，不生成看起来已经生效的约定。
 */
@Service
public class DeliveryService {

    private static final Duration INVITE_TTL = Duration.ofDays(7);
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {
    };
    private static final TypeReference<List<DeliveryViews.ArtifactView>> ARTIFACTS = new TypeReference<>() {
    };
    private static final TypeReference<List<DeliveryViews.CriterionSnapshotView>> CRITERIA = new TypeReference<>() {
    };
    private static final TypeReference<List<DeliveryViews.CheckView>> CHECKS = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, String>> DUE_MAP = new TypeReference<>() {
    };
    private static final TypeReference<List<Map<String, Object>>> CHANGES = new TypeReference<>() {
    };

    private final DeliveryRepository deliveries;
    private final TaskRepository tasks;
    private final TeamRepository teams;
    private final SquareRepository squares;
    private final UserRepository users;
    private final FileService files;
    private final GovRepository governance;
    private final CurrentUserPolicy policy;
    private final IdempotencyService idempotency;
    private final AuditService audit;
    private final ObjectMapper json;
    private final Clock clock;

    public DeliveryService(DeliveryRepository deliveries, TaskRepository tasks, TeamRepository teams,
                           SquareRepository squares, UserRepository users, FileService files,
                           GovRepository governance, CurrentUserPolicy policy, IdempotencyService idempotency,
                           AuditService audit, ObjectMapper json, Clock clock) {
        this.deliveries = deliveries;
        this.tasks = tasks;
        this.teams = teams;
        this.squares = squares;
        this.users = users;
        this.files = files;
        this.governance = governance;
        this.policy = policy;
        this.idempotency = idempotency;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
    }

    public void validateAssessed(TaskRequests.AssessedPlanInput input, String leaderId, Set<String> members) {
        ShareLedger.Plan plan = ShareLedger.normalize(input);
        requireParticipants(plan, members);
        ReviewerQualification.resolve(plan.reviewerUserId(), leaderId, contributors(plan), members, plan.requestedKind());
    }

    public void materialize(String taskId, String leaderId, Set<String> members, TaskRequests.AssessedPlanInput input) {
        ShareLedger.Plan plan = ShareLedger.normalize(input);
        requireParticipants(plan, members);
        String kind = ReviewerQualification.resolve(plan.reviewerUserId(), leaderId, contributors(plan), members,
                plan.requestedKind());
        Instant now = clock.instant();
        List<StoredUnit> storedUnits = new ArrayList<>();
        int position = 0;
        for (ShareLedger.Unit unit : plan.units()) {
            String unitId = UUID.randomUUID().toString();
            deliveries.insertUnit(unitId, taskId, unit.clientKey(), unit.title(), plan.dueAt(), unit.points(),
                    position, now);
            List<StoredShare> shares = writeShares(unitId, 1, unit.shares(), false);
            List<StoredCriterion> criteria = writeCriteria(taskId, unitId, position, unit.criteria());
            storedUnits.add(new StoredUnit(unitId, unit.clientKey(), unit.title(), ShareLedger.text(unit.points()),
                    shares, criteria));
            position++;
        }
        String agreementId = UUID.randomUUID().toString();
        deliveries.insertAgreement(agreementId, taskId, 1, "PENDING", 1,
                write(new StoredPlan(plan.ownerId(), plan.dueAt().toString(), storedUnits,
                        new StoredPolicy(plan.reviewerUserId(), kind))), null, now);
        deliveries.insertArrangement(taskId, plan.reviewerUserId(), kind,
                "SELF_CHECK".equals(kind) ? "ACCEPTED" : "PENDING", 1);
        audit.record(leaderId, "AGREEMENT_CREATE", "AGREEMENT", agreementId, null, 1, null);
    }

    public TaskFacts facts(TaskRow task) {
        if (!"ASSESSED".equals(task.mode())) {
            return new TaskFacts(List.of(), Map.of(), null, null);
        }
        List<UnitRow> units = deliveries.units(task.id());
        AgreementRow current = currentAgreement(task.id());
        int version = current == null ? 1 : current.version();
        Map<String, List<CriterionRow>> criteria = new LinkedHashMap<>();
        for (CriterionRow criterion : tasks.criteria(task.id())) {
            if (criterion.unitId() != null) {
                criteria.computeIfAbsent(criterion.unitId(), key -> new ArrayList<>()).add(criterion);
            }
        }
        List<TaskViews.UnitView> views = new ArrayList<>();
        for (UnitRow unit : units) {
            List<TaskViews.ShareView> shares = deliveries.shares(unit.id(), version).stream()
                    .map(share -> new TaskViews.ShareView(share.userId(), name(share.userId()),
                            ShareLedger.text(share.points()), share.responsibility(), share.signed()))
                    .toList();
            List<TaskViews.CriterionView> unitCriteria = criteria.getOrDefault(unit.id(), List.of()).stream()
                    .map(row -> new TaskViews.CriterionView(row.id(), row.text())).toList();
            List<TaskViews.ContributionView> confirmations = deliveries.contributions(unit.id()).stream()
                    .map(row -> new TaskViews.ContributionView(row.id(), row.userId(), row.acceptanceId(),
                            row.statement(), row.at()))
                    .toList();
            Object acceptance = unit.passed() ? Map.of("passed", true) : Map.of();
            views.add(new TaskViews.UnitView(unit.id(), unit.title(), unit.dueAt(), ShareLedger.text(unit.points()),
                    shares, unitCriteria, unit.revision(), acceptance, confirmations));
        }
        ArrangementRow arrangement = deliveries.arrangement(task.id()).orElse(null);
        Object reviewer = arrangement == null ? Map.of() : new TaskViews.ReviewerAssignmentView(
                arrangement.userId(), name(arrangement.userId()), arrangement.kind(), arrangement.status(),
                arrangement.version());
        return new TaskFacts(views, reviewer, current == null ? null : current.status(),
                current == null ? null : current.version());
    }

    public boolean canStart(String userId, TaskRow task) {
        if (tasks.handoverBlocked(task.id())) {
            throw new ApiException(ApiCode.PROJECT_READ_ONLY, "交接尚未重签，不能开始或提交");
        }
        AgreementRow active = deliveries.activeAgreement(task.id()).orElse(null);
        if (active == null) {
            throw new ApiException(ApiCode.AGREEMENT_REQUIRED);
        }
        if (userId.equals(task.ownerId()) && accepted(active.id(), userId)) {
            return true;
        }
        return deliveries.units(task.id()).stream().anyMatch(unit -> deliveries.hasSignedShare(unit.id(), userId));
    }

    public DeliveryViews.AgreementView agreement(AuthenticatedUser principal, String agreementId) {
        policy.requireStudent(principal);
        AgreementRow agreement = deliveries.findAgreement(agreementId).orElseThrow(ApiException::notFound);
        requireMember(principal, tasks.findTask(agreement.taskId()).orElseThrow(ApiException::notFound));
        return agreementView(agreement);
    }

    public DeliveryViews.AgreementView respond(AuthenticatedUser principal, String agreementId,
                                              DeliveryRequests.AgreementResponseRequest request,
                                              String idempotencyKey, String path) {
        policy.requireStudent(principal);
        return write(principal, "POST", path, idempotencyKey, request, DeliveryViews.AgreementView.class,
                () -> respondOnce(principal, agreementId, request));
    }

    public DeliveryViews.AgreementView replaceAgreement(AuthenticatedUser principal, String taskId,
                                                       DeliveryRequests.AgreementCreateRequest request,
                                                       String idempotencyKey, String path) {
        policy.requireStudent(principal);
        return write(principal, "POST", path, idempotencyKey, request, DeliveryViews.AgreementView.class,
                () -> replaceOnce(principal, taskId, request));
    }

    public DeliveryViews.ProposalView proposal(AuthenticatedUser principal, String proposalId) {
        policy.requireStudent(principal);
        ProposalRow proposal = deliveries.lockProposal(proposalId).orElseThrow(ApiException::notFound);
        TaskRow task = tasks.findTask(proposal.taskId()).orElseThrow(ApiException::notFound);
        requireMember(principal, task);
        return proposalView(proposal, task, principal.userId());
    }

    public List<DeliveryViews.ProposalView> proposals(AuthenticatedUser principal, String taskId) {
        policy.requireStudent(principal);
        TaskRow task = tasks.findTask(taskId).orElseThrow(ApiException::notFound);
        requireMember(principal, task);
        return deliveries.proposals(taskId).stream()
                .map(proposal -> proposalView(proposal, task, principal.userId())).toList();
    }

    public DeliveryViews.ProposalView propose(AuthenticatedUser principal, String taskId,
                                             DeliveryRequests.ProposalCreateRequest request,
                                             String idempotencyKey, String path) {
        policy.requireStudent(principal);
        return write(principal, "POST", path, idempotencyKey, request, DeliveryViews.ProposalView.class,
                () -> proposeOnce(principal, taskId, request));
    }

    public DeliveryViews.ProposalView respondProposal(AuthenticatedUser principal, String proposalId,
                                                     DeliveryRequests.ProposalResponseRequest request,
                                                     String idempotencyKey, String path) {
        policy.requireStudent(principal);
        return write(principal, "POST", path, idempotencyKey, request, DeliveryViews.ProposalView.class,
                () -> respondProposalOnce(principal, proposalId, request));
    }

    public DeliveryViews.ProposalView confirmOpinion(AuthenticatedUser principal, String proposalId,
                                                    DeliveryRequests.OpinionConfirmationRequest request,
                                                    String idempotencyKey, String path) {
        policy.requireStudent(principal);
        return write(principal, "POST", path, idempotencyKey, request, DeliveryViews.ProposalView.class,
                () -> confirmOpinionOnce(principal, proposalId, request));
    }

    public DeliveryViews.ProposalView replyOpinion(AuthenticatedUser principal, String proposalId,
                                                  DeliveryRequests.OpinionReplyRequest request,
                                                  String idempotencyKey, String path) {
        policy.requireStudent(principal);
        return write(principal, "POST", path, idempotencyKey, request, DeliveryViews.ProposalView.class,
                () -> replyOpinionOnce(principal, proposalId, request));
    }

    public DeliveryViews.ProposalView withdrawProposal(AuthenticatedUser principal, String proposalId,
                                                      DeliveryRequests.WithdrawalRequest request,
                                                      String idempotencyKey, String path) {
        policy.requireStudent(principal);
        return write(principal, "POST", path, idempotencyKey, request, DeliveryViews.ProposalView.class,
                () -> withdrawProposalOnce(principal, proposalId, request));
    }

    public DeliveryViews.AgreementView renew(AuthenticatedUser principal, String taskId,
                                            DeliveryRequests.RenewalRequest request,
                                            String idempotencyKey, String path) {
        policy.requireStudent(principal);
        return write(principal, "POST", path, idempotencyKey, request, DeliveryViews.AgreementView.class,
                () -> renewOnce(principal, taskId, request));
    }

    public DeliveryViews.InvitationView invite(AuthenticatedUser principal, String taskId,
                                              DeliveryRequests.InvitationCreateRequest request,
                                              String idempotencyKey, String path) {
        policy.requireStudent(principal);
        return write(principal, "POST", path, idempotencyKey, request, DeliveryViews.InvitationView.class,
                () -> inviteOnce(principal, taskId, request));
    }

    public DeliveryViews.InvitationView respondInvitation(AuthenticatedUser principal, String invitationId,
                                                         DeliveryRequests.InvitationResponseRequest request,
                                                         String idempotencyKey, String path) {
        policy.requireStudent(principal);
        return write(principal, "POST", path, idempotencyKey, request, DeliveryViews.InvitationView.class,
                () -> respondInvitationOnce(principal, invitationId, request));
    }

    public DeliveryViews.DraftView draft(AuthenticatedUser principal, String taskId) {
        policy.requireStudent(principal);
        TaskRow task = tasks.findTask(taskId).orElseThrow(ApiException::notFound);
        requireMember(principal, task);
        return deliveries.findDraft(taskId, principal.userId()).map(this::draftView).orElse(null);
    }

    public DeliveryViews.DraftView saveDraft(AuthenticatedUser principal, String taskId,
                                            DeliveryRequests.DraftRequest request,
                                            String idempotencyKey, String path) {
        policy.requireStudent(principal);
        return write(principal, "PUT", path, idempotencyKey, request, DeliveryViews.DraftView.class,
                () -> saveDraftOnce(principal, taskId, request));
    }

    public List<DeliveryViews.SubmissionView> submissions(AuthenticatedUser principal, String taskId) {
        policy.requireStudent(principal);
        TaskRow task = tasks.findTask(taskId).orElseThrow(ApiException::notFound);
        requireMember(principal, task);
        return deliveries.submissions(taskId).stream().map(this::submissionView).toList();
    }

    public DeliveryViews.SubmissionView submit(AuthenticatedUser principal, String taskId,
                                              DeliveryRequests.SubmitRequest request,
                                              String idempotencyKey, String path) {
        policy.requireStudent(principal);
        return write(principal, "POST", path, idempotencyKey, request, DeliveryViews.SubmissionView.class,
                () -> submitOnce(principal, taskId, request));
    }

    public DeliveryViews.ReviewResult accept(AuthenticatedUser principal, String submissionId,
                                            DeliveryRequests.ReviewRequest request,
                                            String idempotencyKey, String path) {
        policy.requireStudent(principal);
        return write(principal, "POST", path, idempotencyKey, request, DeliveryViews.ReviewResult.class,
                () -> reviewOnce(principal, submissionId, request.expectedRevision(), request.unitIds(),
                        request.checks(), request.returnReasons(), null));
    }

    public DeliveryViews.ReviewResult selfCheck(AuthenticatedUser principal, String submissionId,
                                               DeliveryRequests.SelfCheckRequest request,
                                               String idempotencyKey, String path) {
        policy.requireStudent(principal);
        return write(principal, "POST", path, idempotencyKey, request, DeliveryViews.ReviewResult.class,
                () -> reviewOnce(principal, submissionId, request.expectedRevision(), request.unitIds(),
                        request.checks(), request.returnReasons(), request.selfCheckReason()));
    }

    public DeliveryViews.SubmissionView withdrawSubmission(AuthenticatedUser principal, String submissionId,
                                                          DeliveryRequests.SubmissionWithdrawalRequest request,
                                                          String idempotencyKey, String path) {
        policy.requireStudent(principal);
        return write(principal, "POST", path, idempotencyKey, request, DeliveryViews.SubmissionView.class,
                () -> withdrawSubmissionOnce(principal, submissionId, request));
    }

    public DeliveryViews.ContributionView confirmContribution(AuthenticatedUser principal, String taskId, String unitId,
                                                             DeliveryRequests.ContributionRequest request,
                                                             String idempotencyKey, String path) {
        policy.requireStudent(principal);
        return write(principal, "POST", path, idempotencyKey, request, DeliveryViews.ContributionView.class,
                () -> confirmContributionOnce(principal, taskId, unitId, request));
    }

    private DeliveryViews.AgreementView respondOnce(AuthenticatedUser principal, String agreementId,
                                                   DeliveryRequests.AgreementResponseRequest request) {
        AgreementRow current = deliveries.findAgreement(agreementId).orElseThrow(ApiException::notFound);
        TaskRow task = lockedTask(principal, current.taskId(), null);
        AgreementRow agreement = deliveries.lockAgreement(agreementId).orElseThrow(ApiException::notFound);
        if (!"PENDING".equals(agreement.status()) && !"CHANGES_REQUESTED".equals(agreement.status())) {
            throw new ApiException(ApiCode.SOURCE_CHANGED, "这份约定已经不能再确认");
        }
        if (agreement.revision() != request.expectedRevision()) {
            throw new ApiException(ApiCode.RESOURCE_CHANGED);
        }
        Set<String> required = requiredAck(readPlan(agreement.planJson()));
        if (!required.contains(principal.userId())) {
            throw new ApiException(ApiCode.FORBIDDEN, "只有受影响的本人可以确认约定");
        }
        String comment = request.comment() == null ? "" : request.comment().strip();
        deliveries.upsertAck(agreement.id(), principal.userId(), request.decision(), comment, clock.instant());
        refreshAgreement(agreement, task);
        audit.record(principal.userId(), "AGREEMENT_ACK", "AGREEMENT", agreement.id(),
                request.expectedRevision(), request.expectedRevision() + 1, null);
        return agreementView(deliveries.findAgreement(agreement.id()).orElseThrow());
    }

    private DeliveryViews.AgreementView replaceOnce(AuthenticatedUser principal, String taskId,
                                                   DeliveryRequests.AgreementCreateRequest request) {
        TaskRow task = lockedTask(principal, taskId, request.baseTaskRevision());
        requireLeader(task, principal.userId());
        if (!"ASSESSED".equals(task.mode())) {
            throw ApiException.validation("只有正式任务可以重建约定");
        }
        Set<String> members = memberIds(task.projectId());
        ShareLedger.Plan plan = ShareLedger.normalize(request.plan());
        requireParticipants(plan, members);
        String kind = ReviewerQualification.resolve(plan.reviewerUserId(), project(task).ownerId(),
                contributors(plan), members, plan.requestedKind());
        Map<String, UnitRow> existing = new LinkedHashMap<>();
        for (UnitRow unit : deliveries.units(taskId)) {
            existing.put(unit.clientKey(), unit);
        }
        Set<String> incoming = new LinkedHashSet<>();
        for (ShareLedger.Unit unit : plan.units()) {
            incoming.add(unit.clientKey());
        }
        for (UnitRow unit : existing.values()) {
            if (!incoming.contains(unit.clientKey())) {
                throw ApiException.validation("不能删除已有交付项来避开原责任");
            }
        }
        Instant now = clock.instant();
        int version = deliveries.nextAgreementVersion(taskId);
        List<StoredUnit> storedUnits = new ArrayList<>();
        int position = 0;
        for (ShareLedger.Unit unit : plan.units()) {
            UnitRow prior = existing.get(unit.clientKey());
            boolean protectedUnit = prior != null && (prior.passed() || hasAnySigned(prior.id()));
            if (protectedUnit && !sameContent(prior, unit, versionOf(prior))) {
                throw ApiException.validation("已通过或已确认的交付项必须原样保留");
            }
            String unitId = prior == null ? UUID.randomUUID().toString() : prior.id();
            if (prior == null) {
                deliveries.insertUnit(unitId, taskId, unit.clientKey(), unit.title(), plan.dueAt(), unit.points(),
                        position, now);
            } else if (!protectedUnit) {
                deliveries.updateUnit(prior.id(), prior.revision(), unit.title(), plan.dueAt(), unit.points());
            }
            List<StoredShare> shares = protectedUnit
                    ? writeSharesFromRows(unitId, version, deliveries.shares(unitId, versionOf(prior)), false)
                    : writeShares(unitId, version, unit.shares(), false);
            List<StoredCriterion> criteria = protectedUnit
                    ? storedCriteria(tasks.criteria(taskId), unitId)
                    : replaceCriteria(taskId, unitId, prior == null ? position : prior.position(), unit.criteria());
            storedUnits.add(new StoredUnit(unitId, unit.clientKey(), unit.title(), ShareLedger.text(unit.points()),
                    shares, criteria));
            position++;
        }
        deliveries.supersedeStatus(taskId, "PENDING", now);
        deliveries.supersedeStatus(taskId, "CHANGES_REQUESTED", now);
        String agreementId = UUID.randomUUID().toString();
        deliveries.insertAgreement(agreementId, taskId, version, "PENDING", task.revision(),
                write(new StoredPlan(plan.ownerId(), plan.dueAt().toString(), storedUnits,
                        new StoredPolicy(plan.reviewerUserId(), kind))), null, now);
        deliveries.updateArrangement(taskId, plan.reviewerUserId(), kind,
                "SELF_CHECK".equals(kind) ? "ACCEPTED" : "PENDING", version);
        return agreementView(deliveries.findAgreement(agreementId).orElseThrow());
    }

    private DeliveryViews.ProposalView proposeOnce(AuthenticatedUser principal, String taskId,
                                                  DeliveryRequests.ProposalCreateRequest request) {
        TaskRow task = lockedTask(principal, taskId, null);
        if (!"ASSESSED".equals(task.mode())) {
            throw ApiException.validation("普通任务没有分项调整");
        }
        AgreementRow active = deliveries.activeAgreement(taskId).orElseThrow(() -> new ApiException(ApiCode.AGREEMENT_REQUIRED));
        if (active.version() != request.baseAgreementVersion() || deliveries.openAgreement(taskId).isPresent()) {
            throw new ApiException(ApiCode.SOURCE_CHANGED);
        }
        if (deliveries.hasPending(taskId)) {
            throw ApiException.validation("有待验收提交时不能调整分项");
        }
        ProjectRow project = project(task);
        boolean leader = project.ownerId().equals(principal.userId());
        Set<String> affected = new LinkedHashSet<>();
        affected.add(task.ownerId());
        List<Map<String, Object>> stored = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (DeliveryRequests.UnitChange change : request.changes()) {
            if (!seen.add(change.unitId())) {
                throw ApiException.validation("同一次提案不能重复调整同一个交付项");
            }
            UnitRow unit = deliveries.lockUnit(change.unitId()).orElseThrow(ApiException::notFound);
            if (!taskId.equals(unit.taskId())) {
                throw ApiException.validation("交付项不属于当前任务");
            }
            if (unit.passed()) {
                throw ApiException.validation("已通过的交付项不能再调整");
            }
            if (unit.revision() != change.baseUnitRevision()) {
                throw new ApiException(ApiCode.SOURCE_CHANGED);
            }
            if (!leader && !deliveries.hasSignedShare(unit.id(), principal.userId())) {
                throw new ApiException(ApiCode.FORBIDDEN);
            }
            stored.add(normalizeChange(task, unit, active.version(), change.fields()));
            for (ShareRow share : deliveries.shares(unit.id(), active.version())) {
                affected.add(share.userId());
            }
            Object shares = stored.get(stored.size() - 1).get("fields") instanceof Map<?, ?> fields
                    ? fields.get("shares") : null;
            if (shares instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> share && share.get("userId") != null) {
                        affected.add(share.get("userId").toString());
                    }
                }
            }
        }
        String proposalId = UUID.randomUUID().toString();
        deliveries.insertProposal(proposalId, taskId, active.version(), write(stored),
                request.reason() == null ? "" : request.reason().strip(), write(new ArrayList<>(affected)),
                principal.userId(), clock.instant());
        return proposalView(deliveries.lockProposal(proposalId).orElseThrow(), task, principal.userId());
    }

    private DeliveryViews.ProposalView respondProposalOnce(AuthenticatedUser principal, String proposalId,
                                                          DeliveryRequests.ProposalResponseRequest request) {
        ProposalRow proposal = lockedProposal(principal, proposalId, request.expectedRevision());
        requireAffected(proposal, principal.userId());
        Instant now = clock.instant();
        deliveries.upsertConfirmation(UUID.randomUUID().toString(), proposal.id(), principal.userId(),
                request.decision(), request.comment() == null ? "" : request.comment().strip(),
                proposal.revision(), now);
        if ("REQUEST_CHANGE".equals(request.decision())) {
            deliveries.insertOpinion(UUID.randomUUID().toString(), proposal.id(), principal.userId(),
                    request.comment() == null ? "" : request.comment().strip(), proposal.revision(), now);
        }
        maybeApply(proposal, now);
        return proposalView(deliveries.lockProposal(proposal.id()).orElseThrow(),
                tasks.findTask(proposal.taskId()).orElseThrow(), principal.userId());
    }

    private DeliveryViews.ProposalView confirmOpinionOnce(AuthenticatedUser principal, String proposalId,
                                                         DeliveryRequests.OpinionConfirmationRequest request) {
        ProposalRow proposal = lockedProposal(principal, proposalId, request.expectedRevision());
        requireAffected(proposal, principal.userId());
        ResponseRow opinion = deliveries.findResponse(request.opinionId()).orElseThrow(ApiException::notFound);
        if (!proposal.id().equals(opinion.proposalId()) || !"OPINION".equals(opinion.kind())) {
            throw ApiException.validation("意见不属于这份调整");
        }
        deliveries.updateOpinion(opinion.id(), null, request.decision(),
                request.comment() == null ? null : request.comment().strip());
        maybeApply(proposal, clock.instant());
        return proposalView(deliveries.lockProposal(proposal.id()).orElseThrow(),
                tasks.findTask(proposal.taskId()).orElseThrow(), principal.userId());
    }

    private DeliveryViews.ProposalView replyOpinionOnce(AuthenticatedUser principal, String proposalId,
                                                       DeliveryRequests.OpinionReplyRequest request) {
        ProposalRow proposal = lockedProposal(principal, proposalId, request.expectedRevision());
        requireAffected(proposal, principal.userId());
        ResponseRow opinion = deliveries.findResponse(request.opinionId()).orElseThrow(ApiException::notFound);
        if (!proposal.id().equals(opinion.proposalId()) || !"OPINION".equals(opinion.kind())) {
            throw ApiException.validation("意见不属于这份调整");
        }
        if (principal.userId().equals(opinion.userId())) {
            throw ApiException.validation("意见需要其他协商方回复");
        }
        deliveries.updateOpinion(opinion.id(), request.proposalText().strip(), null, null);
        if (deliveries.bumpProposal(proposal.id(), proposal.revision(), clock.instant()) != 1) {
            throw new ApiException(ApiCode.RESOURCE_CHANGED);
        }
        return proposalView(deliveries.lockProposal(proposal.id()).orElseThrow(),
                tasks.findTask(proposal.taskId()).orElseThrow(), principal.userId());
    }

    private DeliveryViews.ProposalView withdrawProposalOnce(AuthenticatedUser principal, String proposalId,
                                                           DeliveryRequests.WithdrawalRequest request) {
        ProposalRow proposal = lockedProposal(principal, proposalId, request.expectedRevision());
        TaskRow task = tasks.findTask(proposal.taskId()).orElseThrow();
        if (!principal.userId().equals(proposal.initiatorId()) && !project(task).ownerId().equals(principal.userId())) {
            throw new ApiException(ApiCode.FORBIDDEN);
        }
        if (request.reason() == null || request.reason().isBlank()) {
            throw ApiException.validation("撤回调整必须填写理由");
        }
        if (deliveries.touchProposal(proposal.id(), proposal.revision(), "WITHDRAWN", clock.instant()) != 1) {
            throw new ApiException(ApiCode.RESOURCE_CHANGED);
        }
        return proposalView(deliveries.lockProposal(proposal.id()).orElseThrow(), task, principal.userId());
    }

    private DeliveryViews.AgreementView renewOnce(AuthenticatedUser principal, String taskId,
                                                 DeliveryRequests.RenewalRequest request) {
        TaskRow task = lockedTask(principal, taskId, request.expectedRevision());
        requireLeader(task, principal.userId());
        GovRepository.TaskHandover handover = governance.executedTaskHandover(taskId).orElseThrow(
                () -> ApiException.validation("当前没有有效交接，不能重签约定"));
        if (!"ASSESSED".equals(task.mode())) {
            tasks.setHandoverBlocked(task.id(), false);
            if (task.ownerId() != null && task.ownerId().equals(handover.departingUserId())) {
                tasks.reassignOwner(task.id(), handover.recipientId());
            }
            ProjectRow owned = teams.findProject(task.projectId()).orElseThrow();
            if (owned.ownerId().equals(handover.departingUserId())) {
                teams.setOwner(owned.id(), handover.recipientId());
            }
            return new DeliveryViews.AgreementView(handover.changeId(), task.id(), 0, "ACTIVE",
                    new DeliveryViews.PlanView(handover.recipientId(), task.dueAt(), List.of(),
                            new DeliveryViews.PolicyView("", "INDEPENDENT")),
                    List.of(), task.revision(), clock.instant());
        }
        AgreementRow active = deliveries.activeAgreement(task.id()).orElseThrow(
                () -> new ApiException(ApiCode.AGREEMENT_REQUIRED));
        StoredPlan plan = readPlan(active.planJson());
        List<StoredUnit> units = new ArrayList<>();
        int version = deliveries.nextAgreementVersion(task.id());
        for (StoredUnit unit : plan.units()) {
            UnitRow row = deliveries.lockUnit(unit.id()).orElseThrow();
            List<StoredShare> shares = row.passed()
                    ? writeSharesFromRows(unit.id(), version, deliveries.shares(unit.id(), active.version()), false)
                    : rewriteDepartingShare(unit, handover.departingUserId(), handover.recipientId(), version);
            units.add(new StoredUnit(unit.id(), unit.clientKey(), unit.title(), unit.points(), shares, unit.criteria()));
        }
        Instant now = clock.instant();
        deliveries.supersedeStatus(task.id(), "PENDING", now);
        deliveries.supersedeStatus(task.id(), "CHANGES_REQUESTED", now);
        String agreementId = UUID.randomUUID().toString();
        String ownerId = plan.ownerId().equals(handover.departingUserId())
                ? handover.recipientId() : plan.ownerId();
        deliveries.insertAgreement(agreementId, task.id(), version, "PENDING", task.revision(),
                write(new StoredPlan(ownerId, plan.dueAt(), units, plan.reviewerPolicy())), handover.changeId(), now);
        return agreementView(deliveries.findAgreement(agreementId).orElseThrow());
    }

    private List<StoredShare> rewriteDepartingShare(StoredUnit unit, String departingUserId, String recipientId,
                                                    int version) {
        Map<String, ShareLedger.Share> merged = new LinkedHashMap<>();
        for (StoredShare share : unit.shares()) {
            String userId = share.userId().equals(departingUserId) ? recipientId : share.userId();
            ShareLedger.Share existing = merged.get(userId);
            BigDecimal points = ShareLedger.points(share.points());
            if (existing == null) {
                merged.put(userId, new ShareLedger.Share(userId, points, share.responsibility()));
            } else {
                merged.put(userId, new ShareLedger.Share(userId, existing.points().add(points), existing.responsibility()));
            }
        }
        return writeShares(unit.id(), version, List.copyOf(merged.values()), false);
    }

    private DeliveryViews.InvitationView inviteOnce(AuthenticatedUser principal, String taskId,
                                                   DeliveryRequests.InvitationCreateRequest request) {
        TaskRow task = lockedTask(principal, taskId, request.expectedTaskRevision());
        requireLeader(task, principal.userId());
        ArrangementRow arrangement = deliveries.arrangement(taskId).orElse(null);
        if ("ORDINARY".equals(task.mode())) {
            if (request.recipientId().equals(task.ownerId()) || !memberIds(task.projectId()).contains(request.recipientId())) {
                throw new ApiException(ApiCode.SELF_REVIEW_FORBIDDEN, "独立验收人必须是没有承担该任务的当前成员");
            }
            if (arrangement == null) {
                deliveries.insertArrangement(taskId, request.recipientId(), "INDEPENDENT", "PENDING", 1);
                arrangement = deliveries.arrangement(taskId).orElseThrow();
            }
        } else if (arrangement == null) {
            throw ApiException.notFound();
        }
        if (!"INDEPENDENT".equals(arrangement.kind())) {
            throw ApiException.validation("当前约定是组长自检，不能另邀独立验收");
        }
        if (!request.recipientId().equals(arrangement.userId())) {
            throw ApiException.validation("邀请对象必须是约定中的独立验收人");
        }
        if (deliveries.pendingInvitation(taskId).isPresent()) {
            throw ApiException.validation("已有未处理的验收邀请");
        }
        if (contributors(task).contains(request.recipientId())) {
            throw new ApiException(ApiCode.SELF_REVIEW_FORBIDDEN);
        }
        Instant now = clock.instant();
        String id = UUID.randomUUID().toString();
        deliveries.insertInvitation(id, taskId, request.recipientId(), "PENDING", arrangement.version(),
                now.plus(INVITE_TTL), now);
        return invitationView(deliveries.lockInvitation(id).orElseThrow());
    }

    private DeliveryViews.InvitationView respondInvitationOnce(AuthenticatedUser principal, String invitationId,
                                                              DeliveryRequests.InvitationResponseRequest request) {
        InvitationRow current = deliveries.lockInvitation(invitationId).orElseThrow(ApiException::notFound);
        TaskRow task = lockedTask(principal, current.taskId(), null);
        InvitationRow invitation = deliveries.lockInvitation(invitationId).orElseThrow();
        if (!principal.userId().equals(invitation.recipientId())) {
            throw new ApiException(ApiCode.FORBIDDEN);
        }
        if (invitation.revision() != request.expectedRevision() || !"PENDING".equals(invitation.status())) {
            throw new ApiException(ApiCode.RESOURCE_CHANGED);
        }
        if (!invitation.expiresAt().isAfter(clock.instant())) {
            throw new ApiException(ApiCode.INVITATION_EXPIRED);
        }
        String status = "ACCEPT".equals(request.decision()) ? "ACCEPTED" : "DECLINED";
        if (deliveries.setInvitationStatus(invitation.id(), invitation.revision(), status) != 1) {
            throw new ApiException(ApiCode.RESOURCE_CHANGED);
        }
        ArrangementRow arrangement = deliveries.arrangement(task.id()).orElseThrow();
        deliveries.updateArrangement(task.id(), arrangement.userId(), arrangement.kind(), status, arrangement.version());
        if ("ACCEPTED".equals(status)) {
            deliveries.openAgreement(task.id()).ifPresent(agreement -> refreshAgreement(agreement, task));
        }
        return invitationView(deliveries.lockInvitation(invitation.id()).orElseThrow());
    }

    private DeliveryViews.DraftView saveDraftOnce(AuthenticatedUser principal, String taskId,
                                                 DeliveryRequests.DraftRequest request) {
        TaskRow task = lockedTask(principal, taskId, request.expectedTaskRevision());
        List<String> unitIds = checkedUnits(principal.userId(), task, request.unitIds(), request.baseAgreementVersion());
        List<DeliveryViews.ArtifactView> artifacts = normalizeArtifacts(principal, request.artifacts(), false, null);
        DraftRow existing = deliveries.findDraft(taskId, principal.userId()).orElse(null);
        Instant now = clock.instant();
        if (existing == null) {
            if (request.expectedRevision() != 0) {
                throw new ApiException(ApiCode.RESOURCE_CHANGED);
            }
            String id = UUID.randomUUID().toString();
            try {
                deliveries.insertDraft(id, taskId, principal.userId(), write(unitIds), request.summary().strip(),
                        write(artifacts), request.baseAgreementVersion(), 1, now);
            } catch (DataIntegrityViolationException ex) {
                throw new ApiException(ApiCode.RESOURCE_CHANGED);
            }
            return draftView(deliveries.findDraft(taskId, principal.userId()).orElseThrow());
        }
        if (existing.revision() != request.expectedRevision()) {
            throw new ApiException(ApiCode.RESOURCE_CHANGED);
        }
        if (deliveries.updateDraft(existing.id(), existing.revision(), write(unitIds), request.summary().strip(),
                write(artifacts), request.baseAgreementVersion(), now) != 1) {
            throw new ApiException(ApiCode.RESOURCE_CHANGED);
        }
        return draftView(deliveries.findDraft(taskId, principal.userId()).orElseThrow());
    }

    private DeliveryViews.SubmissionView submitOnce(AuthenticatedUser principal, String taskId,
                                                   DeliveryRequests.SubmitRequest request) {
        TaskRow task = lockedTask(principal, taskId, request.expectedTaskRevision());
        if (tasks.handoverBlocked(task.id())) {
            throw new ApiException(ApiCode.PROJECT_READ_ONLY, "交接尚未重签，不能开始或提交");
        }
        if ("DONE".equals(task.status()) || "REVIEW_PENDING".equals(task.status())) {
            throw new ApiException(ApiCode.RESOURCE_CHANGED);
        }
        requireBeforeSubmit(task.id());
        if (deliveries.hasPending(task.id())) {
            throw ApiException.validation("已有待验收提交");
        }
        List<String> unitIds = checkedUnits(principal.userId(), task, request.unitIds(), request.agreementVersion());
        if (request.fromDraftRevision() != null) {
            DraftRow draft = deliveries.findDraft(task.id(), principal.userId()).orElseThrow(ApiException::notFound);
            if (draft.revision() != request.fromDraftRevision()) {
                throw new ApiException(ApiCode.DRAFT_CONFLICT);
            }
        }
        String submissionId = UUID.randomUUID().toString();
        List<DeliveryViews.ArtifactView> artifacts = normalizeArtifacts(principal, request.artifacts(), true, submissionId);
        List<DeliveryViews.CriterionSnapshotView> criteria = snapshotCriteria(task, unitIds);
        Map<String, String> unitDue = new LinkedHashMap<>();
        for (String unitId : unitIds) {
            UnitRow unit = deliveries.lockUnit(unitId).orElseThrow();
            unitDue.put(unitId, unit.dueAt().toString());
        }
        AgreementRow active = deliveries.activeAgreement(task.id()).orElse(null);
        try {
            deliveries.insertSubmission(submissionId, task.id(), deliveries.nextSequence(task.id()), principal.userId(),
                    request.summary().strip(), write(artifacts), write(unitIds), write(criteria), task.dueAt(),
                    write(unitDue), active == null ? null : active.planJson(),
                    active == null ? null : active.version(), request.fromDraftRevision(), clock.instant());
        } catch (DataIntegrityViolationException ex) {
            throw new ApiException(ApiCode.RESOURCE_CHANGED, "已有待验收提交");
        }
        if (tasks.setStatus(task.id(), task.revision(), "REVIEW_PENDING", clock.instant()) != 1) {
            throw new ApiException(ApiCode.RESOURCE_CHANGED);
        }
        return submissionView(deliveries.lockSubmission(submissionId).orElseThrow());
    }

    private DeliveryViews.ReviewResult reviewOnce(AuthenticatedUser principal, String submissionId, int expectedRevision,
                                                 List<String> unitIds, List<DeliveryRequests.CheckInput> checks,
                                                 List<String> returnReasons, String selfCheckReason) {
        SubmissionRow current = deliveries.lockSubmission(submissionId).orElseThrow(ApiException::notFound);
        TaskRow task = lockedTask(principal, current.taskId(), null);
        SubmissionRow submission = deliveries.lockSubmission(submissionId).orElseThrow();
        if (submission.revision() != expectedRevision || !"PENDING".equals(submission.status())) {
            throw new ApiException(submission.revision() != expectedRevision
                    ? ApiCode.RESOURCE_CHANGED : ApiCode.SUBMISSION_ALREADY_REVIEWED);
        }
        List<String> submittedUnits = read(submission.unitIdsJson(), STRINGS);
        if (!new LinkedHashSet<>(submittedUnits).equals(new LinkedHashSet<>(unitIds))) {
            throw ApiException.validation("验收范围必须等于本次提交的交付项");
        }
        ArrangementRow arrangement = "ASSESSED".equals(task.mode())
                ? deliveries.arrangement(task.id()).orElseThrow(ApiException::notFound) : null;
        String kind = reviewKind(principal, task, arrangement, selfCheckReason);
        List<DeliveryViews.CriterionSnapshotView> snapshot = read(submission.criteriaJson(), CRITERIA);
        List<AcceptanceMatch.Required> required = snapshot.stream()
                .map(item -> new AcceptanceMatch.Required(item.unitId(), item.id())).toList();
        List<AcceptanceMatch.Check> matched = checks.stream()
                .map(check -> new AcceptanceMatch.Check(blankToNull(check.unitId()), check.criterionId(), check.met()))
                .toList();
        AcceptanceMatch.Result result = AcceptanceMatch.match(required, matched, returnReasons);
        String acceptanceId = UUID.randomUUID().toString();
        Instant now = clock.instant();
        List<DeliveryViews.CheckView> checkViews = matched.stream()
                .map(check -> new DeliveryViews.CheckView(check.unitId(), check.criterionId(), check.met())).toList();
        deliveries.insertAcceptance(acceptanceId, submission.id(), principal.userId(), kind, write(checkViews),
                result.decision(), String.join("\n", returnReasons), selfCheckReason == null ? null : selfCheckReason.strip(), now);
        for (String unitId : result.passedUnitIds()) {
            deliveries.insertUnitAcceptance(unitId, submission.id(), acceptanceId, true);
            deliveries.markPassed(unitId);
        }
        if (deliveries.finishSubmission(submission.id(), submission.revision(), "REVIEWED") != 1) {
            throw new ApiException(ApiCode.RESOURCE_CHANGED);
        }
        boolean done = "PASSED".equals(result.decision()) && ("ORDINARY".equals(task.mode()) || allUnitsPassed(task.id()));
        String next = done ? "DONE" : "IN_PROGRESS";
        if (tasks.setStatus(task.id(), tasks.findTask(task.id()).orElseThrow().revision(), next, now) != 1) {
            throw new ApiException(ApiCode.RESOURCE_CHANGED);
        }
        TaskRow updated = tasks.findTask(task.id()).orElseThrow();
        SubmissionRow stored = deliveries.lockSubmission(submission.id()).orElseThrow();
        return new DeliveryViews.ReviewResult(acceptanceView(deliveries.findAcceptance(acceptanceId).orElseThrow()),
                submissionView(stored), updated.status(), updated.revision());
    }

    private DeliveryViews.SubmissionView withdrawSubmissionOnce(AuthenticatedUser principal, String submissionId,
                                                               DeliveryRequests.SubmissionWithdrawalRequest request) {
        SubmissionRow current = deliveries.lockSubmission(submissionId).orElseThrow(ApiException::notFound);
        TaskRow task = lockedTask(principal, current.taskId(), null);
        SubmissionRow submission = deliveries.lockSubmission(submissionId).orElseThrow();
        if (!principal.userId().equals(submission.submittedBy())) {
            throw new ApiException(ApiCode.FORBIDDEN);
        }
        if (submission.revision() != request.expectedRevision() || !"PENDING".equals(submission.status())) {
            throw new ApiException(ApiCode.RESOURCE_CHANGED);
        }
        DraftRow draft = deliveries.findDraft(task.id(), principal.userId()).orElse(null);
        if (draft != null && (submission.fromDraftRevision() == null
                || draft.revision() != submission.fromDraftRevision())) {
            throw new ApiException(ApiCode.DRAFT_CONFLICT, "已有较新草稿，撤回不会覆盖它");
        }
        if (deliveries.finishSubmission(submission.id(), submission.revision(), "WITHDRAWN") != 1) {
            throw new ApiException(ApiCode.RESOURCE_CHANGED);
        }
        Instant now = clock.instant();
        if (draft == null) {
            deliveries.insertDraft(UUID.randomUUID().toString(), task.id(), principal.userId(), submission.unitIdsJson(),
                    submission.summary(), submission.artifactsJson(), submission.agreementVersion(), 1, now);
        } else {
            deliveries.updateDraft(draft.id(), draft.revision(), submission.unitIdsJson(), submission.summary(),
                    submission.artifactsJson(), submission.agreementVersion(), now);
        }
        if ("REVIEW_PENDING".equals(task.status())
                && tasks.setStatus(task.id(), task.revision(), "IN_PROGRESS", now) != 1) {
            throw new ApiException(ApiCode.RESOURCE_CHANGED);
        }
        return submissionView(deliveries.lockSubmission(submission.id()).orElseThrow());
    }

    private DeliveryViews.ContributionView confirmContributionOnce(AuthenticatedUser principal, String taskId,
                                                                  String unitId,
                                                                  DeliveryRequests.ContributionRequest request) {
        TaskRow task = lockedTask(principal, taskId, request.expectedRevision());
        if (!"ASSESSED".equals(task.mode())) {
            throw ApiException.validation("普通任务没有份额确认");
        }
        UnitRow unit = deliveries.lockUnit(unitId).orElseThrow(ApiException::notFound);
        if (!taskId.equals(unit.taskId()) || !unit.passed()) {
            throw ApiException.validation("只有已通过的交付项可以确认本人贡献");
        }
        if (!deliveries.hasSignedShare(unitId, principal.userId())) {
            throw new ApiException(ApiCode.FORBIDDEN, "只能确认本人的份额");
        }
        AcceptanceRow acceptance = deliveries.findAcceptance(request.acceptanceId()).orElseThrow(ApiException::notFound);
        SubmissionRow submission = deliveries.lockSubmission(request.submissionId()).orElseThrow(ApiException::notFound);
        if (!acceptance.submissionId().equals(submission.id()) || !submission.taskId().equals(taskId)) {
            throw ApiException.validation("验收记录与提交不匹配");
        }
        String id = UUID.randomUUID().toString();
        Instant now = clock.instant();
        try {
            deliveries.insertContribution(id, unitId, principal.userId(), acceptance.id(), submission.id(),
                    request.statement().strip(), now);
        } catch (DataIntegrityViolationException ex) {
            throw new ApiException(ApiCode.RESOURCE_CHANGED, "这份贡献已经确认过");
        }
        tasks.setStatus(task.id(), task.revision(), task.status(), now);
        return new DeliveryViews.ContributionView(id, unitId, principal.userId(), acceptance.id(), submission.id(),
                request.statement().strip(), now);
    }

    private void maybeApply(ProposalRow proposal, Instant now) {
        if (!readyToApply(proposal)) {
            if (deliveries.bumpProposal(proposal.id(), proposal.revision(), now) != 1) {
                throw new ApiException(ApiCode.RESOURCE_CHANGED);
            }
            return;
        }
        TaskRow task = tasks.lockTask(proposal.taskId()).orElseThrow();
        if (!sourceValid(proposal, task)) {
            throw new ApiException(ApiCode.SOURCE_CHANGED);
        }
        AgreementRow active = deliveries.activeAgreement(task.id()).orElseThrow();
        StoredPlan plan = readPlan(active.planJson());
        List<Map<String, Object>> changes = read(proposal.changesJson(), CHANGES);
        List<StoredUnit> units = new ArrayList<>(plan.units());
        int version = deliveries.nextAgreementVersion(task.id());
        for (Map<String, Object> change : changes) {
            String unitId = change.get("unitId").toString();
            @SuppressWarnings("unchecked")
            Map<String, Object> fields = (Map<String, Object>) change.get("fields");
            UnitRow unit = deliveries.lockUnit(unitId).orElseThrow();
            String title = fields.get("title") == null ? unit.title() : fields.get("title").toString();
            Instant dueAt = fields.get("dueAt") == null ? unit.dueAt() : Instant.parse(fields.get("dueAt").toString());
            BigDecimal points = fields.get("points") == null ? unit.points() : ShareLedger.points(fields.get("points").toString());
            if (deliveries.updateUnit(unit.id(), unit.revision(), title, dueAt, points) != 1) {
                throw new ApiException(ApiCode.RESOURCE_CHANGED);
            }
            List<StoredShare> shares;
            if (fields.get("shares") != null) {
                shares = writeSharesFromMaps(unit.id(), version, fields.get("shares"));
            } else {
                shares = writeSharesFromRows(unit.id(), version, deliveries.shares(unit.id(), active.version()));
            }
            List<StoredCriterion> criteria = fields.get("criteria") == null
                    ? storedCriteria(tasks.criteria(task.id()), unit.id())
                    : replaceCriteriaFromMaps(task.id(), unit, fields.get("criteria"));
            for (int i = 0; i < units.size(); i++) {
                if (units.get(i).id().equals(unitId)) {
                    units.set(i, new StoredUnit(unitId, unit.clientKey(), title, ShareLedger.text(points), shares, criteria));
                }
            }
        }
        for (StoredUnit unit : units) {
            boolean changed = changes.stream().anyMatch(change -> unit.id().equals(change.get("unitId")));
            if (!changed) {
                writeSharesFromRows(unit.id(), version, deliveries.shares(unit.id(), active.version()));
            }
        }
        deliveries.supersedeStatus(task.id(), "ACTIVE", now);
        String agreementId = UUID.randomUUID().toString();
        deliveries.insertAgreement(agreementId, task.id(), version, "ACTIVE", task.revision(),
                write(new StoredPlan(plan.ownerId(), plan.dueAt(), units, plan.reviewerPolicy())), proposal.id(), now);
        deliveries.signShares(task.id(), version);
        if (tasks.bindAgreement(task.id(), task.revision(), version, now) != 1) {
            throw new ApiException(ApiCode.RESOURCE_CHANGED);
        }
        if (deliveries.touchProposal(proposal.id(), proposal.revision(), "APPLIED", now) != 1) {
            throw new ApiException(ApiCode.RESOURCE_CHANGED);
        }
    }

    private boolean readyToApply(ProposalRow proposal) {
        if (!"PENDING".equals(proposal.status()) || !sourceValid(proposal, tasks.findTask(proposal.taskId()).orElseThrow())) {
            return false;
        }
        List<String> affected = read(proposal.affectedUsersJson(), STRINGS);
        List<ResponseRow> responses = deliveries.responses(proposal.id());
        for (String userId : affected) {
            boolean accepted = responses.stream().anyMatch(row -> "CONFIRMATION".equals(row.kind())
                    && userId.equals(row.userId()) && "ACCEPT".equals(row.decision()));
            if (!accepted) {
                return false;
            }
        }
        return responses.stream().filter(row -> "OPINION".equals(row.kind()))
                .allMatch(row -> "AGREE".equals(row.decision()));
    }

    private void refreshAgreement(AgreementRow agreement, TaskRow task) {
        StoredPlan plan = readPlan(agreement.planJson());
        List<DeliveryRepository.AckRow> acks = deliveries.acks(agreement.id());
        boolean changes = acks.stream().anyMatch(ack -> "REQUEST_CHANGE".equals(ack.decision()));
        Set<String> required = requiredAck(plan);
        boolean all = required.stream().allMatch(userId -> acks.stream()
                .anyMatch(ack -> userId.equals(ack.userId()) && "ACCEPT".equals(ack.decision())));
        ArrangementRow arrangement = deliveries.arrangement(task.id()).orElseThrow();
        boolean reviewerReady = "SELF_CHECK".equals(arrangement.kind()) || "ACCEPTED".equals(arrangement.status());
        String next = changes ? "CHANGES_REQUESTED" : all && reviewerReady ? "ACTIVE" : "PENDING";
        if (deliveries.setAgreementStatus(agreement.id(), agreement.revision(), next, clock.instant()) != 1) {
            throw new ApiException(ApiCode.RESOURCE_CHANGED);
        }
        if ("ACTIVE".equals(next)) {
            deliveries.signShares(task.id(), agreement.version());
            TaskRow fresh = tasks.findTask(task.id()).orElseThrow();
            if (tasks.bindAgreement(fresh.id(), fresh.revision(), agreement.version(), clock.instant()) != 1) {
                throw new ApiException(ApiCode.RESOURCE_CHANGED);
            }
            if (agreement.sourceChangeId() != null) {
                tasks.setHandoverBlocked(task.id(), false);
            }
        }
    }

    private Map<String, Object> normalizeChange(TaskRow task, UnitRow unit, int version, DeliveryRequests.ChangeFields fields) {
        if (fields.title() == null && fields.points() == null && fields.shares() == null
                && fields.criteria() == null && fields.dueAt() == null) {
            throw ApiException.validation("调整至少要有一个实质变化");
        }
        Map<String, Object> stored = new LinkedHashMap<>();
        boolean changed = false;
        if (fields.title() != null) {
            String title = fields.title().strip();
            if (title.isEmpty()) {
                throw ApiException.validation("交付项标题无效");
            }
            if (!title.equals(unit.title())) {
                changed = true;
            }
            stored.put("title", title);
        }
        BigDecimal points = unit.points();
        if (fields.points() != null) {
            points = ShareLedger.points(fields.points());
            if (fields.shares() == null) {
                throw new ApiException(ApiCode.POINTS_INVALID, "点数变化必须同时给出守恒的份额");
            }
            if (points.compareTo(unit.points()) != 0) {
                changed = true;
            }
            stored.put("points", ShareLedger.text(points));
        }
        if (fields.shares() != null) {
            List<ShareLedger.Share> shares = ShareLedger.normalizeShares(points, fields.shares());
            for (ShareLedger.Share share : shares) {
                if (!memberIds(task.projectId()).contains(share.userId())) {
                    throw ApiException.validation("份额人员必须是当前成员");
                }
            }
            stored.put("shares", shares.stream().map(share -> Map.of(
                    "userId", share.userId(), "points", ShareLedger.text(share.points()),
                    "responsibility", share.responsibility())).toList());
            changed = true;
        }
        if (fields.criteria() != null) {
            if (fields.criteria().isEmpty()) {
                throw ApiException.validation("标准不能改成空");
            }
            List<String> texts = fields.criteria().stream().map(item -> item.text().strip()).toList();
            stored.put("criteria", texts.stream().map(text -> Map.of("text", text)).toList());
            changed = true;
        }
        if (fields.dueAt() != null) {
            if (!fields.dueAt().equals(unit.dueAt())) {
                changed = true;
            }
            stored.put("dueAt", fields.dueAt().toString());
        }
        if (!changed) {
            throw ApiException.validation("调整至少要有一个实质变化");
        }
        return Map.of("unitId", unit.id(), "baseUnitRevision", unit.revision(), "fields", stored);
    }

    private String reviewKind(AuthenticatedUser principal, TaskRow task, ArrangementRow arrangement, String selfCheckReason) {
        if (selfCheckReason != null) {
            if (selfCheckReason.isBlank()) {
                throw ApiException.validation("自检必须填写理由");
            }
            if (!project(task).ownerId().equals(principal.userId())) {
                throw new ApiException(ApiCode.FORBIDDEN, "只有参与的组长可以自检");
            }
            if ("ASSESSED".equals(task.mode()) && (arrangement == null || !"SELF_CHECK".equals(arrangement.kind()))) {
                throw ApiException.validation("当前约定不是组长自检");
            }
            Set<String> contributors = contributors(task);
            String kind = ReviewerQualification.resolve(principal.userId(), project(task).ownerId(), contributors,
                    memberIds(task.projectId()), "SELF_CHECK");
            if (!"SELF_CHECK".equals(kind)) {
                throw ApiException.validation("当前有可独立验收的成员，不能自检");
            }
            return "SELF_CHECK";
        }
        if (contributors(task).contains(principal.userId())) {
            throw new ApiException(ApiCode.SELF_REVIEW_FORBIDDEN);
        }
        if ("ASSESSED".equals(task.mode())) {
            if (arrangement == null || !"INDEPENDENT".equals(arrangement.kind())
                    || !"ACCEPTED".equals(arrangement.status())
                    || !principal.userId().equals(arrangement.userId())) {
                throw new ApiException(ApiCode.FORBIDDEN, "只有已接受邀请的独立验收人可以验收");
            }
        } else if (!deliveries.acceptedInvitation(task.id(), principal.userId())) {
            throw new ApiException(ApiCode.FORBIDDEN, "只有已接受邀请的独立验收人可以验收");
        }
        return "INDEPENDENT";
    }

    private List<String> checkedUnits(String userId, TaskRow task, List<String> unitIds, Integer agreementVersion) {
        if ("ORDINARY".equals(task.mode())) {
            if (!unitIds.isEmpty() || agreementVersion != null) {
                throw ApiException.validation("普通任务提交整个任务，不能选择分项");
            }
            if (!userId.equals(task.ownerId())) {
                throw new ApiException(ApiCode.FORBIDDEN);
            }
            return List.of();
        }
        if (agreementVersion == null || unitIds.isEmpty()) {
            throw ApiException.validation("正式提交必须选择本人的交付项并固定约定版本");
        }
        AgreementRow active = deliveries.activeAgreement(task.id()).orElseThrow(() -> new ApiException(ApiCode.AGREEMENT_REQUIRED));
        if (active.version() != agreementVersion) {
            throw new ApiException(ApiCode.SOURCE_CHANGED);
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String unitId : unitIds) {
            if (!seen.add(unitId)) {
                throw ApiException.validation("交付项不能重复");
            }
            UnitRow unit = deliveries.lockUnit(unitId).orElseThrow(ApiException::notFound);
            if (!task.id().equals(unit.taskId()) || unit.passed() || !deliveries.hasSignedShare(unitId, userId)) {
                throw new ApiException(ApiCode.UNIT_NOT_ASSIGNED);
            }
        }
        return List.copyOf(unitIds);
    }

    private List<DeliveryViews.ArtifactView> normalizeArtifacts(AuthenticatedUser principal,
                                                               List<DeliveryRequests.ArtifactInput> inputs,
                                                               boolean attach, String submissionId) {
        if (inputs == null) {
            return List.of();
        }
        List<DeliveryViews.ArtifactView> artifacts = new ArrayList<>();
        for (DeliveryRequests.ArtifactInput input : inputs) {
            if ("LINK".equals(input.kind())) {
                if (input.fileId() != null || input.url() == null || !input.url().matches("https?://\\S+")) {
                    throw ApiException.validation("链接材料必须是http/https地址，且不能带文件编号");
                }
                artifacts.add(new DeliveryViews.ArtifactView("LINK", input.url(), null, input.title().strip()));
            } else {
                if (input.fileId() == null || input.fileId().isBlank() || (input.url() != null && !input.url().isBlank())) {
                    throw ApiException.validation("文件材料必须引用已就绪的fileId");
                }
                files.metadata(principal, input.fileId());
                if (attach) {
                    files.attachReference(input.fileId(), "SUBMISSION", submissionId, 1);
                }
                artifacts.add(new DeliveryViews.ArtifactView("FILE", null, input.fileId(), input.title().strip()));
            }
        }
        return List.copyOf(artifacts);
    }

    private List<DeliveryViews.CriterionSnapshotView> snapshotCriteria(TaskRow task, List<String> unitIds) {
        List<DeliveryViews.CriterionSnapshotView> snapshot = new ArrayList<>();
        for (CriterionRow criterion : tasks.criteria(task.id())) {
            if ("ORDINARY".equals(task.mode()) && criterion.unitId() == null) {
                snapshot.add(new DeliveryViews.CriterionSnapshotView(criterion.id(), criterion.text(), null));
            } else if (unitIds.contains(criterion.unitId())) {
                snapshot.add(new DeliveryViews.CriterionSnapshotView(criterion.id(), criterion.text(), criterion.unitId()));
            }
        }
        return snapshot;
    }

    private void requireBeforeSubmit(String taskId) {
        for (DependencyRow dependency : tasks.dependenciesOf(taskId)) {
            if (!"BEFORE_SUBMIT".equals(dependency.gate())) {
                continue;
            }
            TaskRow predecessor = tasks.findTask(dependency.dependsOnTaskId()).orElseThrow(ApiException::notFound);
            if (!"DONE".equals(predecessor.status())) {
                throw new ApiException(ApiCode.DEPENDENCY_BLOCKED);
            }
        }
    }

    private TaskRow lockedTask(AuthenticatedUser principal, String taskId, Integer expectedRevision) {
        TaskRow found = tasks.findTask(taskId).orElseThrow(ApiException::notFound);
        ProjectRow project = teams.lockProject(found.projectId()).orElseThrow(ApiException::notFound);
        requireSameSchool(principal, project);
        if (teams.membership(project.id(), principal.userId()).isEmpty()) {
            throw new ApiException(ApiCode.FORBIDDEN);
        }
        if (!"ACTIVE".equals(project.status()) && !"CLOSING".equals(project.status())) {
            throw new ApiException(ApiCode.PROJECT_READ_ONLY);
        }
        TaskRow task = tasks.lockTask(taskId).orElseThrow(ApiException::notFound);
        if (expectedRevision != null && task.revision() != expectedRevision) {
            throw new ApiException(ApiCode.RESOURCE_CHANGED);
        }
        return task;
    }

    private ProposalRow lockedProposal(AuthenticatedUser principal, String proposalId, int expectedRevision) {
        ProposalRow found = deliveries.lockProposal(proposalId).orElseThrow(ApiException::notFound);
        lockedTask(principal, found.taskId(), null);
        ProposalRow proposal = deliveries.lockProposal(proposalId).orElseThrow();
        if (!"PENDING".equals(proposal.status())) {
            throw new ApiException(ApiCode.SOURCE_CHANGED, "这份调整已经结束");
        }
        if (proposal.revision() != expectedRevision) {
            throw new ApiException(ApiCode.RESOURCE_CHANGED);
        }
        return proposal;
    }

    private void requireAffected(ProposalRow proposal, String userId) {
        if (!read(proposal.affectedUsersJson(), STRINGS).contains(userId)) {
            throw new ApiException(ApiCode.FORBIDDEN, "只有受影响的本人可以回应");
        }
    }

    private boolean sourceValid(ProposalRow proposal, TaskRow task) {
        AgreementRow active = deliveries.activeAgreement(task.id()).orElse(null);
        if (active == null || active.version() != proposal.baseAgreementVersion() || deliveries.hasPending(task.id())) {
            return false;
        }
        for (Map<String, Object> change : read(proposal.changesJson(), CHANGES)) {
            UnitRow unit = deliveries.lockUnit(change.get("unitId").toString()).orElse(null);
            if (unit == null || unit.passed() || unit.revision() != ((Number) change.get("baseUnitRevision")).intValue()) {
                return false;
            }
        }
        return true;
    }

    private DeliveryViews.AgreementView agreementView(AgreementRow agreement) {
        StoredPlan plan = readPlan(agreement.planJson());
        List<DeliveryViews.PlanUnitView> units = plan.units().stream().map(unit -> new DeliveryViews.PlanUnitView(
                unit.id(), unit.clientKey(), unit.title(), unit.points(),
                unit.shares().stream().map(share -> new DeliveryViews.PlanShareView(
                        share.userId(), share.points(), share.responsibility())).toList(),
                unit.criteria().stream().map(criterion -> new DeliveryViews.PlanCriterionView(
                        criterion.id(), criterion.text())).toList())).toList();
        return new DeliveryViews.AgreementView(agreement.id(), agreement.taskId(), agreement.version(),
                agreement.status(), new DeliveryViews.PlanView(plan.ownerId(), Instant.parse(plan.dueAt()), units,
                new DeliveryViews.PolicyView(plan.reviewerPolicy().userId(), plan.reviewerPolicy().kind())),
                deliveries.acks(agreement.id()).stream().map(ack -> new DeliveryViews.AckView(
                        ack.userId(), ack.decision(), ack.at())).toList(),
                agreement.revision(), agreement.createdAt());
    }

    private DeliveryViews.ProposalView proposalView(ProposalRow proposal, TaskRow task, String userId) {
        ProjectRow project = project(task);
        return new DeliveryViews.ProposalView(proposal.id(), proposal.taskId(), proposal.baseAgreementVersion(),
                List.copyOf(read(proposal.changesJson(), CHANGES)), proposal.reason(),
                read(proposal.affectedUsersJson(), STRINGS),
                deliveries.responses(proposal.id()).stream().filter(row -> "CONFIRMATION".equals(row.kind()))
                        .map(row -> new DeliveryViews.ConfirmationView(row.userId(), row.decision(), row.at())).toList(),
                deliveries.responses(proposal.id()).stream().filter(row -> "OPINION".equals(row.kind()))
                        .map(row -> new DeliveryViews.OpinionView(row.id(), row.userId(), row.comment(),
                                row.proposalText(), row.decision())).toList(),
                proposal.status(), proposal.revision(), "PENDING".equals(proposal.status()) && sourceValid(proposal, task),
                project.ownerId().equals(userId) || proposal.initiatorId().equals(userId));
    }

    private DeliveryViews.DraftView draftView(DraftRow draft) {
        return new DeliveryViews.DraftView(draft.id(), draft.taskId(), draft.userId(), draft.summary(),
                read(draft.artifactsJson(), ARTIFACTS), read(draft.unitIdsJson(), STRINGS), "SAVED",
                draft.updatedAt(), draft.revision());
    }

    private DeliveryViews.SubmissionView submissionView(SubmissionRow submission) {
        AcceptanceRow acceptance = deliveries.acceptanceOf(submission.id()).orElse(null);
        return new DeliveryViews.SubmissionView(submission.id(), submission.taskId(), submission.sequence(),
                submission.submittedBy(), submission.summary(), read(submission.artifactsJson(), ARTIFACTS),
                read(submission.unitIdsJson(), STRINGS), submission.submittedAt(), submission.dueSnapshot(),
                read(submission.criteriaJson(), CRITERIA), submission.status(), submission.revision(),
                acceptance == null ? Map.of() : acceptanceView(acceptance));
    }

    private DeliveryViews.AcceptanceView acceptanceView(AcceptanceRow acceptance) {
        return new DeliveryViews.AcceptanceView(acceptance.id(), acceptance.submissionId(), acceptance.reviewerId(),
                acceptance.kind(), read(acceptance.checksJson(), CHECKS), acceptance.reason(), acceptance.decision(),
                acceptance.acceptedAt());
    }

    private DeliveryViews.InvitationView invitationView(InvitationRow invitation) {
        return new DeliveryViews.InvitationView(invitation.id(), invitation.taskId(), invitation.recipientId(),
                invitation.status(), invitation.arrangementVersion(), invitation.expiresAt(), invitation.revision());
    }

    private List<StoredShare> writeShares(String unitId, int version, List<ShareLedger.Share> shares, boolean signed) {
        List<StoredShare> stored = new ArrayList<>();
        for (ShareLedger.Share share : shares) {
            deliveries.insertShare(UUID.randomUUID().toString(), unitId, share.userId(), share.points(),
                    share.responsibility(), version, signed);
            stored.add(new StoredShare(share.userId(), ShareLedger.text(share.points()), share.responsibility()));
        }
        return stored;
    }

    private List<StoredShare> writeSharesFromRows(String unitId, int version, List<ShareRow> shares) {
        return writeSharesFromRows(unitId, version, shares, true);
    }

    private List<StoredShare> writeSharesFromRows(String unitId, int version, List<ShareRow> shares, boolean signed) {
        List<StoredShare> stored = new ArrayList<>();
        for (ShareRow share : shares) {
            deliveries.insertShare(UUID.randomUUID().toString(), unitId, share.userId(), share.points(),
                    share.responsibility(), version, signed);
            stored.add(new StoredShare(share.userId(), ShareLedger.text(share.points()), share.responsibility()));
        }
        return stored;
    }

    @SuppressWarnings("unchecked")
    private List<StoredShare> writeSharesFromMaps(String unitId, int version, Object raw) {
        List<StoredShare> stored = new ArrayList<>();
        for (Map<String, Object> share : (List<Map<String, Object>>) raw) {
            BigDecimal points = ShareLedger.points(share.get("points").toString());
            deliveries.insertShare(UUID.randomUUID().toString(), unitId, share.get("userId").toString(), points,
                    share.get("responsibility").toString(), version, true);
            stored.add(new StoredShare(share.get("userId").toString(), ShareLedger.text(points),
                    share.get("responsibility").toString()));
        }
        return stored;
    }

    private List<StoredCriterion> writeCriteria(String taskId, String unitId, int position, List<String> texts) {
        List<StoredCriterion> stored = new ArrayList<>();
        int index = 0;
        for (String text : texts) {
            String id = UUID.randomUUID().toString();
            deliveries.insertCriterion(id, taskId, unitId, text, position * 100 + index);
            stored.add(new StoredCriterion(id, text));
            index++;
        }
        return stored;
    }

    private List<StoredCriterion> replaceCriteria(String taskId, String unitId, int position, List<String> texts) {
        deliveries.deleteUnitCriteria(unitId);
        return writeCriteria(taskId, unitId, position, texts);
    }

    @SuppressWarnings("unchecked")
    private List<StoredCriterion> replaceCriteriaFromMaps(String taskId, UnitRow unit, Object raw) {
        List<String> texts = new ArrayList<>();
        for (Map<String, Object> criterion : (List<Map<String, Object>>) raw) {
            texts.add(criterion.get("text").toString());
        }
        return replaceCriteria(taskId, unit.id(), unit.position(), texts);
    }

    private AgreementRow currentAgreement(String taskId) {
        return deliveries.activeAgreement(taskId).orElseGet(() -> deliveries.openAgreement(taskId).orElse(null));
    }

    private int versionOf(UnitRow unit) {
        AgreementRow current = currentAgreement(unit.taskId());
        return current == null ? 1 : current.version();
    }

    private boolean hasAnySigned(String unitId) {
        return deliveries.shares(unitId, versionOf(deliveries.lockUnit(unitId).orElseThrow())).stream().anyMatch(ShareRow::signed)
                || deliveries.sharesIn(List.of(unitId)).stream().anyMatch(ShareRow::signed);
    }

    private boolean sameContent(UnitRow unit, ShareLedger.Unit incoming, int version) {
        if (!unit.title().equals(incoming.title()) || unit.points().compareTo(incoming.points()) != 0
                || !unit.dueAt().equals(currentDue(unit))) {
            return false;
        }
        List<ShareRow> shares = deliveries.shares(unit.id(), version);
        if (shares.size() != incoming.shares().size()) {
            return false;
        }
        for (int i = 0; i < shares.size(); i++) {
            ShareLedger.Share expected = incoming.shares().get(i);
            ShareRow actual = shares.get(i);
            if (!actual.userId().equals(expected.userId()) || actual.points().compareTo(expected.points()) != 0
                    || !actual.responsibility().equals(expected.responsibility())) {
                return false;
            }
        }
        List<String> texts = tasks.criteria(unit.taskId()).stream()
                .filter(criterion -> unit.id().equals(criterion.unitId())).map(CriterionRow::text).toList();
        return texts.equals(incoming.criteria());
    }

    private Instant currentDue(UnitRow unit) {
        return unit.dueAt();
    }

    private List<StoredShare> storedShares(List<ShareRow> shares) {
        return shares.stream().map(share -> new StoredShare(share.userId(), ShareLedger.text(share.points()),
                share.responsibility())).toList();
    }

    private List<StoredCriterion> storedCriteria(List<CriterionRow> criteria, String unitId) {
        return criteria.stream().filter(criterion -> unitId.equals(criterion.unitId()))
                .map(criterion -> new StoredCriterion(criterion.id(), criterion.text())).toList();
    }

    private boolean allUnitsPassed(String taskId) {
        return deliveries.units(taskId).stream().allMatch(UnitRow::passed);
    }

    private boolean accepted(String agreementId, String userId) {
        return deliveries.acks(agreementId).stream()
                .anyMatch(ack -> userId.equals(ack.userId()) && "ACCEPT".equals(ack.decision()));
    }

    private Set<String> requiredAck(StoredPlan plan) {
        Set<String> required = new LinkedHashSet<>();
        required.add(plan.ownerId());
        for (StoredUnit unit : plan.units()) {
            for (StoredShare share : unit.shares()) {
                required.add(share.userId());
            }
        }
        return required;
    }

    private Set<String> contributors(ShareLedger.Plan plan) {
        Set<String> contributors = new LinkedHashSet<>();
        for (ShareLedger.Unit unit : plan.units()) {
            for (ShareLedger.Share share : unit.shares()) {
                contributors.add(share.userId());
            }
        }
        return contributors;
    }

    private Set<String> contributors(TaskRow task) {
        if ("ORDINARY".equals(task.mode())) {
            return task.ownerId() == null ? Set.of() : Set.of(task.ownerId());
        }
        AgreementRow current = currentAgreement(task.id());
        if (current == null) {
            return Set.of();
        }
        Set<String> contributors = new LinkedHashSet<>();
        for (StoredUnit unit : readPlan(current.planJson()).units()) {
            for (StoredShare share : unit.shares()) {
                contributors.add(share.userId());
            }
        }
        return contributors;
    }

    private void requireParticipants(ShareLedger.Plan plan, Set<String> members) {
        if (!members.contains(plan.ownerId())) {
            throw ApiException.validation("统筹负责人必须是当前成员");
        }
        for (ShareLedger.Unit unit : plan.units()) {
            for (ShareLedger.Share share : unit.shares()) {
                if (!members.contains(share.userId())) {
                    throw ApiException.validation("份额人员必须是当前成员");
                }
            }
        }
    }

    private void requireLeader(TaskRow task, String userId) {
        if (!project(task).ownerId().equals(userId)) {
            throw new ApiException(ApiCode.FORBIDDEN);
        }
    }

    private void requireMember(AuthenticatedUser principal, TaskRow task) {
        ProjectRow project = teams.findProject(task.projectId()).orElseThrow(ApiException::notFound);
        requireSameSchool(principal, project);
        if (teams.membership(project.id(), principal.userId()).isEmpty()) {
            throw new ApiException(ApiCode.FORBIDDEN);
        }
    }

    private ProjectRow project(TaskRow task) {
        return teams.findProject(task.projectId()).orElseThrow(ApiException::notFound);
    }

    private Set<String> memberIds(String projectId) {
        Set<String> members = new LinkedHashSet<>();
        for (ProjectMemberRow member : teams.members(projectId)) {
            if ("ACTIVE".equals(member.status())) {
                members.add(member.userId());
            }
        }
        return members;
    }

    private void requireSameSchool(AuthenticatedUser principal, ProjectRow project) {
        SquareRow square = squares.findById(project.squareId()).orElseThrow(ApiException::notFound);
        if (!square.schoolId().equals(principal.schoolId())) {
            throw ApiException.notFound();
        }
    }

    private String name(String userId) {
        UserRow user = users.findById(userId).orElse(null);
        return user == null ? "" : user.displayName();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private <T> T write(AuthenticatedUser principal, String method, String path, String key, Object body,
                        Class<T> type, java.util.function.Supplier<T> action) {
        return idempotency.execute(new IdempotencyRequest(principal, method, path, key, body, List.of()), type,
                () -> policy.requireStudent(principal), action);
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private <T> T read(String value, TypeReference<T> type) {
        try {
            return json.readValue(value, type);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private StoredPlan readPlan(String value) {
        try {
            return json.readValue(value, StoredPlan.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    public record TaskFacts(List<TaskViews.UnitView> units, Object reviewerAssignment, String agreementStatus,
                            Integer agreementVersion) {
    }

    public record StoredPlan(String ownerId, String dueAt, List<StoredUnit> units, StoredPolicy reviewerPolicy) {
    }

    public record StoredUnit(String id, String clientKey, String title, String points, List<StoredShare> shares,
                             List<StoredCriterion> criteria) {
    }

    public record StoredShare(String userId, String points, String responsibility) {
    }

    public record StoredCriterion(String id, String text) {
    }

    public record StoredPolicy(String userId, String kind) {
    }
}
