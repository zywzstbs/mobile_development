package cn.gulupai.server.governance;

import cn.gulupai.server.common.api.ApiCode;
import cn.gulupai.server.common.api.ApiException;
import cn.gulupai.server.common.idempotency.IdempotencyRequest;
import cn.gulupai.server.common.idempotency.IdempotencyService;
import cn.gulupai.server.common.idempotency.ReplayProjector;
import cn.gulupai.server.common.security.AuthenticatedUser;
import cn.gulupai.server.common.security.CurrentUserPolicy;
import cn.gulupai.server.course.persistence.SquareRepository;
import cn.gulupai.server.course.persistence.SquareRow;
import cn.gulupai.server.governance.GovRepository.AckRow;
import cn.gulupai.server.governance.GovRepository.CaseRow;
import cn.gulupai.server.governance.GovRepository.ChangeRow;
import cn.gulupai.server.governance.GovRepository.HandoffRow;
import cn.gulupai.server.governance.GovRepository.ItemRow;
import cn.gulupai.server.governance.GovRepository.MaterialResponseRow;
import cn.gulupai.server.governance.GovRepository.MaterialReviewRow;
import cn.gulupai.server.governance.GovRepository.MaterialRow;
import cn.gulupai.server.governance.GovRepository.VoteRow;
import cn.gulupai.server.identity.persistence.UserRepository;
import cn.gulupai.server.task.persistence.TaskRepository;
import cn.gulupai.server.task.persistence.TaskRepository.TaskRow;
import cn.gulupai.server.team.persistence.ProjectMemberRow;
import cn.gulupai.server.team.persistence.ProjectRow;
import cn.gulupai.server.team.persistence.TeamRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

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
 * 退出、转组、交接和移出。执行前重验教师核对、任务版本、席位和全部接任确认。
 */
@Service
public class GovService {

    private static final Duration STATEMENT = Duration.ofHours(48);
    private static final Duration VOTING = Duration.ofHours(72);
    private static final Duration INVITE = Duration.ofHours(72);
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };
    private static final TypeReference<List<Object>> OBJECTS = new TypeReference<>() {
    };

    private final GovRepository governance;
    private final TeamRepository teams;
    private final TaskRepository tasks;
    private final SquareRepository squares;
    private final UserRepository users;
    private final CurrentUserPolicy policy;
    private final IdempotencyService idempotency;
    private final ObjectMapper json;
    private final Clock clock;
    private final PersonnelCaseAccessPolicy caseAccess;

    public GovService(GovRepository governance, TeamRepository teams, TaskRepository tasks, SquareRepository squares,
                      UserRepository users, CurrentUserPolicy policy,
                      IdempotencyService idempotency, ObjectMapper json, Clock clock,
                      PersonnelCaseAccessPolicy caseAccess) {
        this.governance = governance;
        this.teams = teams;
        this.tasks = tasks;
        this.squares = squares;
        this.users = users;
        this.policy = policy;
        this.idempotency = idempotency;
        this.json = json;
        this.clock = clock;
        this.caseAccess = caseAccess;
    }

    public GovViews.MemberChange change(AuthenticatedUser principal, String id) {
        policy.requireStudent(principal);
        ChangeRow row = governance.findChange(id).orElseThrow(ApiException::notFound);
        requireReader(principal, teams.findProject(row.projectId()).orElseThrow());
        return changeView(row, principal);
    }

    public List<GovViews.MemberChange> projectChanges(AuthenticatedUser principal, String projectId) {
        ProjectRow project = readableProject(principal, projectId);
        return governance.changesForProject(project.id()).stream().map(row -> changeView(row, principal)).toList();
    }

    public List<GovViews.MemberChange> squareChanges(AuthenticatedUser principal, String squareId) {
        requireStaff(principal, squareId);
        return governance.changesForSquare(squareId).stream().map(row -> changeView(row, principal)).toList();
    }

    public List<GovViews.MemberChange> incoming(AuthenticatedUser principal, String projectId) {
        readableProject(principal, projectId);
        return governance.incoming(projectId).stream().map(row -> changeView(row, principal)).toList();
    }

    public GovViews.MemberChange createChange(AuthenticatedUser principal, String projectId, GovRequests.CreateChange request,
                                              String key, String path) {
        policy.requireStudent(principal);
        return write(principal, "POST", path, key, request, GovViews.MemberChange.class, () -> createChangeOnce(principal, projectId, request));
    }

    public GovViews.MemberChange savePlan(AuthenticatedUser principal, String changeId, GovRequests.SavePlan request,
                                          String key, String path) {
        policy.requireStudent(principal);
        return write(principal, "PUT", path, key, request, GovViews.MemberChange.class, () -> savePlanOnce(principal, changeId, request));
    }

    public GovViews.MemberChange respondHandover(AuthenticatedUser principal, String changeId,
                                                GovRequests.HandoverResponse request, String key, String path) {
        policy.requireStudent(principal);
        return write(principal, "POST", path, key, request, GovViews.MemberChange.class, () -> respondOnce(principal, changeId, request));
    }

    public GovViews.MemberChange decide(AuthenticatedUser principal, String changeId, GovRequests.PersonnelDecision request,
                                       String key, String path) {
        policy.requireActiveBusinessUser(principal);
        return write(principal, "POST", path, key, request, GovViews.MemberChange.class,
                () -> requireChangeDecision(principal, changeId),
                () -> decideOnce(principal, changeId, request));
    }

    public GovViews.MemberChange execute(AuthenticatedUser principal, String changeId, GovRequests.Execute request,
                                        String key, String path) {
        policy.requireStudent(principal);
        return write(principal, "POST", path, key, request, GovViews.MemberChange.class, () -> executeOnce(principal, changeId, request));
    }

    public GovViews.MemberChange cancel(AuthenticatedUser principal, String changeId, GovRequests.RevisionReason request,
                                       String key, String path) {
        policy.requireStudent(principal);
        return write(principal, "POST", path, key, request, GovViews.MemberChange.class, () -> cancelOnce(principal, changeId, request));
    }

    public GovViews.MemberChange inviteTarget(AuthenticatedUser principal, String changeId, GovRequests.TargetInvite request,
                                              String key, String path) {
        policy.requireStudent(principal);
        return write(principal, "POST", path, key, request, GovViews.MemberChange.class, () -> inviteOnce(principal, changeId, request));
    }

    public GovViews.MemberChange respondTarget(AuthenticatedUser principal, String changeId, GovRequests.TargetResponse request,
                                              String key, String path) {
        policy.requireStudent(principal);
        return write(principal, "POST", path, key, request, GovViews.MemberChange.class, () -> respondTargetOnce(principal, changeId, request));
    }

    public GovViews.MemberChange cancelTarget(AuthenticatedUser principal, String changeId, GovRequests.RevisionReason request,
                                              String key, String path) {
        policy.requireStudent(principal);
        return write(principal, "POST", path, key, request, GovViews.MemberChange.class, () -> cancelTargetOnce(principal, changeId, request));
    }

    public GovViews.Vote vote(AuthenticatedUser principal, String id) {
        policy.requireStudent(principal);
        VoteRow vote = governance.lockVote(id).orElseThrow(ApiException::notFound);
        settle(vote);
        requireReader(principal, teams.findProject(vote.projectId()).orElseThrow());
        return voteView(governance.lockVote(id).orElseThrow(), principal.userId());
    }

    public List<GovViews.Vote> votes(AuthenticatedUser principal, String projectId) {
        readableProject(principal, projectId);
        return governance.votes(projectId).stream().map(vote -> {
            settle(vote);
            return voteView(governance.lockVote(vote.id()).orElseThrow(), principal.userId());
        }).toList();
    }

    public GovViews.Vote startVote(AuthenticatedUser principal, String projectId, GovRequests.StartVote request,
                                  String key, String path) {
        policy.requireStudent(principal);
        return write(principal, "POST", path, key, request, GovViews.Vote.class, () -> startVoteOnce(principal, projectId, request));
    }

    public GovViews.Vote statement(AuthenticatedUser principal, String voteId, GovRequests.Statement request,
                                  String key, String path) {
        policy.requireStudent(principal);
        return write(principal, "POST", path, key, request, GovViews.Vote.class, () -> statementOnce(principal, voteId, request));
    }

    public GovViews.Vote ballot(AuthenticatedUser principal, String voteId, GovRequests.Ballot request,
                               String key, String path) {
        policy.requireStudent(principal);
        return write(principal, "PUT", path, key, request, GovViews.Vote.class, () -> ballotOnce(principal, voteId, request));
    }

    public GovViews.Vote cancelVote(AuthenticatedUser principal, String voteId, GovRequests.RevisionReason request,
                                   String key, String path) {
        policy.requireStudent(principal);
        return write(principal, "POST", path, key, request, GovViews.Vote.class, () -> cancelVoteOnce(principal, voteId, request));
    }

    public GovViews.Vote decideVote(AuthenticatedUser principal, String voteId, GovRequests.PersonnelDecision request,
                                   String key, String path) {
        policy.requireActiveBusinessUser(principal);
        return write(principal, "POST", path, key, request, GovViews.Vote.class,
                () -> requireVoteDecision(principal, voteId),
                () -> decideVoteOnce(principal, voteId, request));
    }

    public GovViews.CaseView personnelCase(AuthenticatedUser principal, String id) {
        policy.requireActiveBusinessUser(principal);
        CaseRow row = governance.findCase(id).orElseThrow(ApiException::notFound);
        return caseView(row, principal, false);
    }

    public List<GovViews.CaseView> squareCases(AuthenticatedUser principal, String squareId) {
        requireStaff(principal, squareId);
        return governance.casesForSquare(squareId).stream().map(row -> caseView(row, principal, true)).toList();
    }

    public GovViews.CaseView acceptCase(AuthenticatedUser principal, String id, GovRequests.CaseRevision request,
                                       String key, String path) {
        policy.requireActiveBusinessUser(principal);
        return write(principal, "POST", path, key, request, GovViews.CaseView.class,
                () -> requireCurrentTeacher(principal, governance.findCase(id).orElseThrow()),
                () -> acceptCaseOnce(principal, id, request), caseViewReplay(principal));
    }

    public GovViews.CaseView closeCase(AuthenticatedUser principal, String id, GovRequests.CloseCase request,
                                      String key, String path) {
        policy.requireActiveBusinessUser(principal);
        return write(principal, "POST", path, key, request, GovViews.CaseView.class,
                () -> requireHandleOrQualifiedAssignee(principal, id), () -> closeCaseOnce(principal, id, request),
                caseViewReplay(principal));
    }

    public GovViews.CaseView updateCase(AuthenticatedUser principal, String id, GovRequests.CaseUpdate request,
                                       String key, String path) {
        policy.requireActiveBusinessUser(principal);
        return write(principal, "POST", path, key, request, GovViews.CaseView.class,
                () -> requireHandleOrQualifiedAssignee(principal, id), () -> updateCaseOnce(principal, id, request),
                caseViewReplay(principal));
    }

    public GovViews.CaseView recover(AuthenticatedUser principal, String id, GovRequests.CloseCase request,
                                    String key, String path) {
        policy.requireActiveBusinessUser(principal);
        return write(principal, "POST", path, key, request, GovViews.CaseView.class,
                () -> requireCurrentTeacher(principal, governance.findCase(id).orElseThrow()),
                () -> recoverOnce(principal, id, request), caseViewReplay(principal));
    }

    public GovViews.Material requestMaterial(AuthenticatedUser principal, String caseId, GovRequests.MaterialCreate request,
                                            String key, String path) {
        policy.requireActiveBusinessUser(principal);
        return write(principal, "POST", path, key, request, GovViews.Material.class,
                () -> requireHandleOrQualifiedAssignee(principal, caseId),
                () -> requestMaterialOnce(principal, caseId, request), materialReplay(principal));
    }

    public GovViews.Material material(AuthenticatedUser principal, String id) {
        policy.requireActiveBusinessUser(principal);
        MaterialRow row = governance.findMaterial(id).orElseThrow(ApiException::notFound);
        CaseRow caseRow = governance.findCase(row.caseId()).orElseThrow(ApiException::notFound);
        if (!caseAccess.canReadMaterial(caseRow, row, principal.userId())) {
            throw new ApiException(ApiCode.FORBIDDEN);
        }
        return materialView(row, caseRow, principal);
    }

    public List<GovViews.Material> myMaterials(AuthenticatedUser principal) {
        policy.requireStudent(principal);
        return governance.materialsForUser(principal.userId()).stream()
                .map(row -> materialView(row, governance.findCase(row.caseId()).orElseThrow(ApiException::notFound),
                        principal))
                .toList();
    }

    public GovViews.Material respondMaterial(AuthenticatedUser principal, String id, GovRequests.MaterialResponse request,
                                            String key, String path) {
        policy.requireStudent(principal);
        return write(principal, "POST", path, key, request, GovViews.Material.class,
                () -> requireMaterialRecipient(principal, id), () -> respondMaterialOnce(principal, id, request),
                materialReplay(principal));
    }

    public GovViews.Material reviewMaterial(AuthenticatedUser principal, String id, GovRequests.MaterialReview request,
                                           String key, String path) {
        policy.requireActiveBusinessUser(principal);
        return write(principal, "POST", path, key, request, GovViews.Material.class,
                () -> requireMaterialHandle(principal, id), () -> reviewMaterialOnce(principal, id, request),
                materialReplay(principal));
    }

    public GovViews.Material withdrawMaterial(AuthenticatedUser principal, String id, GovRequests.RevisionReason request,
                                             String key, String path) {
        policy.requireStudent(principal);
        return write(principal, "POST", path, key, request, GovViews.Material.class,
                () -> requireMaterialRecipient(principal, id), () -> withdrawMaterialOnce(principal, id, request),
                materialReplay(principal));
    }

    public List<GovViews.Person> handoffCandidates(AuthenticatedUser principal, String caseId) {
        CaseRow row = governance.findCase(caseId).orElseThrow(ApiException::notFound);
        requireCaseRead(principal, row);
        return governance.handoffCandidates(caseId, row.squareId(), row.projectId(),
                        principal.userId(), clock.instant()).stream()
                .filter(candidate -> !candidate.userId().equals(row.assigneeId()))
                .map(candidate -> new GovViews.Person(candidate.userId(), candidate.displayName()))
                .toList();
    }

    public List<GovViews.Person> materialRecipients(AuthenticatedUser principal, String caseId) {
        CaseRow row = governance.findCase(caseId).orElseThrow(ApiException::notFound);
        requireCaseRead(principal, row);
        ProjectMemberRow subject = teams.membership(row.projectId(), row.subjectUserId()).orElse(null);
        return subject == null ? List.of() : List.of(new GovViews.Person(subject.userId(), subject.displayName()));
    }

    public GovViews.Handoff createHandoff(AuthenticatedUser principal, String caseId, GovRequests.CaseHandoff request,
                                         String key, String path) {
        policy.requireActiveBusinessUser(principal);
        return write(principal, "POST", path, key, request, GovViews.Handoff.class,
                () -> requireCaseRead(principal, governance.findCase(caseId).orElseThrow()), () -> createHandoffOnce(principal, caseId, request));
    }

    public GovViews.Handoff handoff(AuthenticatedUser principal, String id) {
        policy.requireActiveBusinessUser(principal);
        HandoffRow row = governance.findHandoff(id).orElseThrow(ApiException::notFound);
        CaseRow caseRow = governance.findCase(row.caseId()).orElseThrow(ApiException::notFound);
        if (!caseAccess.canReadHandoff(caseRow, row, principal.userId())) {
            throw new ApiException(ApiCode.FORBIDDEN);
        }
        return handoffView(row, caseRow, principal);
    }

    public List<GovViews.Handoff> myHandoffs(AuthenticatedUser principal) {
        policy.requireActiveBusinessUser(principal);
        List<GovViews.Handoff> visible = new ArrayList<>();
        for (HandoffRow row : governance.handoffsForUser(principal.userId())) {
            CaseRow caseRow = governance.findCase(row.caseId()).orElse(null);
            if (caseRow != null && caseAccess.canReadHandoff(caseRow, row, principal.userId())) {
                visible.add(handoffView(row, caseRow, principal));
            }
        }
        return visible;
    }

    public GovViews.Handoff respondHandoff(AuthenticatedUser principal, String id, GovRequests.HandoffDecision request,
                                          String key, String path) {
        policy.requireActiveBusinessUser(principal);
        return write(principal, "POST", path, key, request, GovViews.Handoff.class,
                () -> requireHandoffRead(principal, id), () -> respondHandoffOnce(principal, id, request),
                handoffReplay(principal));
    }

    public GovViews.Handoff cancelHandoff(AuthenticatedUser principal, String id, GovRequests.RevisionReason request,
                                         String key, String path) {
        policy.requireActiveBusinessUser(principal);
        return write(principal, "POST", path, key, request, GovViews.Handoff.class,
                () -> requireHandoffRead(principal, id), () -> cancelHandoffOnce(principal, id, request),
                handoffReplay(principal));
    }

    public List<GovViews.MemberChange> transferTargets(AuthenticatedUser principal, String projectId) {
        return incoming(principal, projectId);
    }

    public List<GovRepository.MembershipHistory> membershipHistory(AuthenticatedUser principal) {
        policy.requireStudent(principal);
        return governance.membershipHistory(principal.userId());
    }

    private GovViews.MemberChange createChangeOnce(AuthenticatedUser principal, String projectId,
                                                  GovRequests.CreateChange request) {
        ProjectRow project = teams.lockProject(projectId).orElseThrow(ApiException::notFound);
        requireSameSchool(principal, project);
        ProjectMemberRow member = teams.membership(projectId, principal.userId()).orElseThrow(
                () -> new ApiException(ApiCode.FORBIDDEN));
        if (member.revision() != request.expectedMembershipRevision()) {
            throw new ApiException(ApiCode.RESOURCE_CHANGED);
        }
        if (teams.memberCount(projectId) < 2) {
            throw ApiException.validation("唯一成员不能退出或转组");
        }
        String target = blank(request.targetProjectId());
        if ("TRANSFER".equals(request.kind())) {
            if (target == null) {
                throw ApiException.validation("转组必须指定目标项目");
            }
            ProjectRow destination = teams.findProject(target).orElseThrow(ApiException::notFound);
            if (destination.id().equals(projectId) || !destination.assignmentId().equals(project.assignmentId())) {
                throw ApiException.validation("转组目标必须是同一作业的另一个项目");
            }
        } else if (target != null) {
            throw ApiException.validation("退出不能携带目标项目");
        }
        String id = UUID.randomUUID().toString();
        Instant now = clock.instant();
        ChangeRow row = new ChangeRow(id, projectId, principal.userId(), principal.userId(), request.kind(),
                "WAIT_PERSONNEL", target, 0, "{}", "{}", member.revision(),
                request.reason() == null ? "" : request.reason().strip(), null, "", "NONE", null, null, 1, now);
        try {
            governance.insertChange(row, now);
        } catch (DataIntegrityViolationException ex) {
            throw ApiException.validation("已有未结束的成员变更");
        }
        governance.insertCase(new CaseRow(UUID.randomUUID().toString(), project.squareId(), projectId,
                principal.userId(), null, "成员变更 " + request.kind(), "OPEN", id, 1), now);
        governance.event(UUID.randomUUID().toString(), id, "OPEN", principal.userId(), row.reason(), now);
        return changeView(governance.findChange(id).orElseThrow(), principal);
    }

    private GovViews.MemberChange savePlanOnce(AuthenticatedUser principal, String changeId, GovRequests.SavePlan request) {
        ChangeRow change = lockedOpen(changeId, request.expectedRevision());
        ProjectRow project = teams.lockProject(change.projectId()).orElseThrow();
        if (!project.ownerId().equals(principal.userId())) {
            throw new ApiException(ApiCode.FORBIDDEN);
        }
        boolean leaderLeaving = project.ownerId().equals(change.targetUserId());
        String nextLeader = blank(request.nextLeaderId());
        if (leaderLeaving && (nextLeader == null || nextLeader.equals(change.targetUserId())
                || teams.membership(project.id(), nextLeader).isEmpty())) {
            throw ApiException.validation("组长离任必须由另一名当前成员接任");
        }
        if (!leaderLeaving && nextLeader != null) {
            throw ApiException.validation("非组长离任不能指定新组长");
        }
        List<GovViews.Outstanding> outstanding = outstanding(change.targetUserId(), project.id());
        Set<String> covered = new LinkedHashSet<>();
        int version = change.planVersion() + 1;
        for (GovRequests.PlanItem item : request.items()) {
            if (item.taskId() == null || item.sourceTaskRevision() == null) {
                throw ApiException.validation("任务接任必须带任务和来源版本");
            }
            if (!covered.add(item.taskId())) {
                throw ApiException.validation("同一任务不能重复接任");
            }
            TaskRow task = tasks.findTask(item.taskId()).orElseThrow(ApiException::notFound);
            if (!project.id().equals(task.projectId()) || task.revision() != item.sourceTaskRevision()) {
                throw new ApiException(ApiCode.SOURCE_CHANGED, "来源任务已变化");
            }
            if (item.recipientId().equals(change.targetUserId())
                    || teams.membership(project.id(), item.recipientId()).isEmpty()) {
                throw ApiException.validation("接任人必须是其他当前成员");
            }
            governance.insertItem(new ItemRow(UUID.randomUUID().toString(), change.id(), version, "TASK",
                    task.id(), task.revision(), item.recipientId(), item.responsibility().strip()));
        }
        for (GovViews.Outstanding task : outstanding) {
            if (!covered.contains(task.id())) {
                throw ApiException.validation("交接方案必须覆盖全部未完责任");
            }
        }
        if (leaderLeaving) {
            governance.insertItem(new ItemRow(UUID.randomUUID().toString(), change.id(), version, "LEADER",
                    null, null, nextLeader, "接任组长"));
        }
        String notes = request.notes() == null ? "" : request.notes().strip();
        ChangeRow next = copy(change, change.status(), version, change.planJson(), change.decisionJson(), nextLeader,
                notes, change.targetInviteStatus(), change.targetInviteExpiresAt(), change.removalVoteId());
        if (governance.updateChange(next, clock.instant()) != 1) {
            throw new ApiException(ApiCode.RESOURCE_CHANGED);
        }
        governance.event(UUID.randomUUID().toString(), change.id(), "PLAN", principal.userId(), notes, clock.instant());
        return changeView(governance.findChange(change.id()).orElseThrow(), principal);
    }

    private GovViews.MemberChange respondOnce(AuthenticatedUser principal, String changeId,
                                              GovRequests.HandoverResponse request) {
        ChangeRow change = lockedOpen(changeId, request.expectedRevision());
        if (change.planVersion() != request.planVersion()) {
            throw new ApiException(ApiCode.SOURCE_CHANGED, "交接方案版本已变化");
        }
        List<ItemRow> items = governance.items(change.id(), change.planVersion());
        List<String> ids = new ArrayList<>(request.itemIds());
        if (ids.isEmpty()) {
            ItemRow leader = items.stream().filter(item -> "LEADER".equals(item.kind())).findFirst()
                    .orElseThrow(() -> ApiException.validation("没有可确认的组长交接项"));
            if (!principal.userId().equals(leader.recipientId())) {
                throw ApiException.validation("空列表只表示接任组长确认本人的组长项");
            }
            ids = List.of(leader.id());
        }
        boolean changed = false;
        for (String itemId : ids) {
            ItemRow item = items.stream().filter(row -> row.id().equals(itemId)).findFirst()
                    .orElseThrow(() -> ApiException.validation("交接项不属于这一版方案"));
            if (!principal.userId().equals(item.recipientId())) {
                throw new ApiException(ApiCode.FORBIDDEN, "只能确认指定给自己的交接项");
            }
            boolean same = governance.acks(change.id(), change.planVersion()).stream()
                    .anyMatch(ack -> ack.itemId().equals(itemId) && ack.userId().equals(principal.userId())
                            && ack.decision().equals(request.decision()));
            if (same) {
                continue;
            }
            governance.ack(change.id(), change.planVersion(), itemId, principal.userId(), request.decision(),
                    clock.instant());
            changed = true;
        }
        if (changed && governance.updateChange(change, clock.instant()) != 1) {
            throw new ApiException(ApiCode.RESOURCE_CHANGED);
        }
        return changeView(governance.findChange(change.id()).orElseThrow(), principal);
    }

    private GovViews.MemberChange decideOnce(AuthenticatedUser principal, String changeId,
                                             GovRequests.PersonnelDecision request) {
        // 锁顺序：只读预查关联 → 锁 case → 锁 member_change → 重查关联/版本/状态。
        ChangeRow preview = governance.findChange(changeId).orElseThrow(ApiException::notFound);
        CaseRow linked = linkedCaseForChange(changeId, preview.projectId());
        CaseRow caseRow = governance.lockCase(linked.id()).orElseThrow(ApiException::notFound);
        ChangeRow change = lockedOpen(changeId, request.expectedRevision());
        if (!changeId.equals(caseRow.memberChangeId()) || !change.projectId().equals(caseRow.projectId())) {
            throw new ApiException(ApiCode.SOURCE_CHANGED, "人员决定与事项关联已变化");
        }
        if (!"WAIT_PERSONNEL".equals(change.status()) && !"RETURNED".equals(change.status())) {
            throw new ApiException(ApiCode.SOURCE_CHANGED, "当前状态不能作人员核对决定");
        }
        requireAssignee(principal, caseRow);
        String decision = write(Map.of("decision", request.decision(), "placementPlan", request.placementPlan().strip(),
                "reason", request.reason().strip(), "allowBelowMinimum", request.allowBelowMinimum()));
        String status = "RETURN".equals(request.decision()) ? "RETURNED" : change.status();
        ChangeRow next = copy(change, status, change.planVersion(), change.planJson(), decision, change.nextLeaderId(),
                change.notes(), change.targetInviteStatus(), change.targetInviteExpiresAt(), change.removalVoteId());
        if (governance.updateChange(next, clock.instant()) != 1) {
            throw new ApiException(ApiCode.RESOURCE_CHANGED);
        }
        governance.event(UUID.randomUUID().toString(), change.id(), request.decision(), principal.userId(),
                request.reason(), clock.instant());
        return changeView(governance.findChange(change.id()).orElseThrow(), principal);
    }

    private GovViews.MemberChange executeOnce(AuthenticatedUser principal, String changeId, GovRequests.Execute request) {
        ChangeRow change = lockedOpen(changeId, request.expectedRevision());
        if (change.planVersion() != request.planVersion() || change.planVersion() == 0) {
            throw new ApiException(ApiCode.SOURCE_CHANGED);
        }
        ProjectRow project = teams.lockProject(change.projectId()).orElseThrow();
        Map<String, Object> decision = read(change.decisionJson(), MAP);
        if (!"ALLOW_HANDOVER".equals(decision.get("decision"))) {
            throw ApiException.validation("教师尚未允许交接");
        }
        List<ItemRow> items = governance.items(change.id(), change.planVersion());
        List<AckRow> acks = governance.acks(change.id(), change.planVersion());
        boolean leaderLeaving = project.ownerId().equals(change.targetUserId());
        if (leaderLeaving) {
            ItemRow leader = items.stream().filter(item -> "LEADER".equals(item.kind())).findFirst()
                    .orElseThrow(() -> ApiException.validation("缺少组长接任"));
            if (!accepted(acks, leader.id(), leader.recipientId())) {
                throw ApiException.validation("新组长尚未单独接受组长交接");
            }
            if (!principal.userId().equals(leader.recipientId()) && !principal.userId().equals(project.ownerId())) {
                throw new ApiException(ApiCode.FORBIDDEN);
            }
        } else if (!principal.userId().equals(project.ownerId())) {
            throw new ApiException(ApiCode.FORBIDDEN);
        }
        for (ItemRow item : items) {
            if (!accepted(acks, item.id(), item.recipientId())) {
                throw ApiException.validation("还有接任没有被本人接受");
            }
            if ("TASK".equals(item.kind())) {
                if (governance.pendingForUser(change.targetUserId())) {
                    throw ApiException.validation("还有待验收提交，不能执行交接");
                }
                TaskRow task = tasks.lockTask(item.taskId()).orElseThrow();
                if (task.revision() != item.sourceTaskRevision()) {
                    throw new ApiException(ApiCode.SOURCE_CHANGED, "来源任务已变化");
                }
            }
        }
        if (governance.unconfirmedContribution(change.targetUserId())) {
            throw ApiException.validation("已验收的贡献还没有本人确认");
        }
        Instant now = clock.instant();
        ProjectMemberRow member = teams.membership(project.id(), change.targetUserId()).orElseThrow(
                () -> new ApiException(ApiCode.SOURCE_CHANGED));
        if (member.revision() != change.sourceMembershipRevision()) {
            throw new ApiException(ApiCode.SOURCE_CHANGED, "成员关系已变化");
        }
        if ("TRANSFER".equals(change.kind())) {
            if (!"ACCEPTED".equals(change.targetInviteStatus())
                    || change.targetInviteExpiresAt() == null || !change.targetInviteExpiresAt().isAfter(now)) {
                throw ApiException.validation("目标预留已失效");
            }
            ProjectRow target = teams.lockProject(change.targetProjectId()).orElseThrow();
            if (teams.memberCount(target.id()) >= target.capacity()) {
                throw ApiException.validation("目标项目已满");
            }
            String membershipId = UUID.randomUUID().toString();
            teams.insertMembership(membershipId, target.id(), change.targetUserId(), null, "MEMBER", "#236B56", now);
            teams.bumpAuthorizationEpoch(target.id());
            teams.moveAssignmentSlot(project.assignmentId(), change.targetUserId(), target.id(), membershipId);
        } else {
            teams.releaseAssignmentSlot(project.assignmentId(), change.targetUserId());
        }
        if (teams.leaveMembership(member.id(), member.revision(),
                "REMOVAL".equals(change.kind()) ? "REMOVED" : "LEFT", now) != 1) {
            throw new ApiException(ApiCode.RESOURCE_CHANGED);
        }
        teams.bumpAuthorizationEpoch(project.id());
        if (leaderLeaving) {
            teams.setOwner(project.id(), change.nextLeaderId());
        }
        for (ItemRow item : items) {
            if ("TASK".equals(item.kind())) {
                tasks.setHandoverBlocked(item.taskId(), true);
            }
        }
        ChangeRow done = copy(change, "EXECUTED", change.planVersion(), change.planJson(), change.decisionJson(),
                change.nextLeaderId(), change.notes(), change.targetInviteStatus(), change.targetInviteExpiresAt(),
                change.removalVoteId());
        if (governance.updateChange(done, now) != 1) {
            throw new ApiException(ApiCode.RESOURCE_CHANGED);
        }
        governance.event(UUID.randomUUID().toString(), change.id(), "EXECUTE", principal.userId(), "", now);
        return changeView(governance.findChange(change.id()).orElseThrow(), principal);
    }

    private GovViews.MemberChange cancelOnce(AuthenticatedUser principal, String changeId, GovRequests.RevisionReason request) {
        ChangeRow change = lockedOpen(changeId, request.expectedRevision());
        if (!principal.userId().equals(change.initiatorId())
                && !teams.findProject(change.projectId()).orElseThrow().ownerId().equals(principal.userId())) {
            throw new ApiException(ApiCode.FORBIDDEN);
        }
        if (request.reason() == null || request.reason().isBlank()) {
            throw ApiException.validation("取消必须填写理由");
        }
        ChangeRow next = copy(change, "CANCELLED", change.planVersion(), change.planJson(), change.decisionJson(),
                change.nextLeaderId(), change.notes(), change.targetInviteStatus(), change.targetInviteExpiresAt(),
                change.removalVoteId());
        governance.updateChange(next, clock.instant());
        return changeView(governance.findChange(change.id()).orElseThrow(), principal);
    }

    private GovViews.MemberChange inviteOnce(AuthenticatedUser principal, String changeId, GovRequests.TargetInvite request) {
        ChangeRow change = lockedOpen(changeId, request.expectedRevision());
        if (!"TRANSFER".equals(change.kind())) {
            throw ApiException.validation("只有转组可以邀请目标项目");
        }
        ProjectRow target = teams.lockProject(change.targetProjectId()).orElseThrow();
        if (!target.ownerId().equals(principal.userId())) {
            throw new ApiException(ApiCode.FORBIDDEN);
        }
        if (teams.memberCount(target.id()) + teams.reservedCount(target.id(), clock.instant()) >= target.capacity()) {
            throw ApiException.validation("目标项目已满");
        }
        Instant expires = clock.instant().plus(INVITE);
        ChangeRow next = copy(change, change.status(), change.planVersion(), change.planJson(), change.decisionJson(),
                change.nextLeaderId(), change.notes(), "PENDING", expires, change.removalVoteId());
        governance.updateChange(next, clock.instant());
        return changeView(governance.findChange(change.id()).orElseThrow(), principal);
    }

    private GovViews.MemberChange respondTargetOnce(AuthenticatedUser principal, String changeId,
                                                   GovRequests.TargetResponse request) {
        ChangeRow change = lockedOpen(changeId, request.expectedRevision());
        if (!principal.userId().equals(change.targetUserId()) || !"PENDING".equals(change.targetInviteStatus())) {
            throw new ApiException(ApiCode.FORBIDDEN);
        }
        if (change.targetInviteExpiresAt() != null && !change.targetInviteExpiresAt().isAfter(clock.instant())) {
            throw new ApiException(ApiCode.INVITATION_EXPIRED);
        }
        String status = "ACCEPT".equals(request.decision()) ? "ACCEPTED" : "CANCELLED";
        ChangeRow next = copy(change, change.status(), change.planVersion(), change.planJson(), change.decisionJson(),
                change.nextLeaderId(), change.notes(), status, change.targetInviteExpiresAt(), change.removalVoteId());
        governance.updateChange(next, clock.instant());
        return changeView(governance.findChange(change.id()).orElseThrow(), principal);
    }

    private GovViews.MemberChange cancelTargetOnce(AuthenticatedUser principal, String changeId,
                                                  GovRequests.RevisionReason request) {
        ChangeRow change = lockedOpen(changeId, request.expectedRevision());
        ProjectRow target = teams.findProject(change.targetProjectId()).orElseThrow(ApiException::notFound);
        if (!target.ownerId().equals(principal.userId())) {
            throw new ApiException(ApiCode.FORBIDDEN);
        }
        ChangeRow next = copy(change, change.status(), change.planVersion(), change.planJson(), change.decisionJson(),
                change.nextLeaderId(), change.notes(), "CANCELLED", change.targetInviteExpiresAt(), change.removalVoteId());
        governance.updateChange(next, clock.instant());
        return changeView(governance.findChange(change.id()).orElseThrow(), principal);
    }

    private GovViews.Vote startVoteOnce(AuthenticatedUser principal, String projectId, GovRequests.StartVote request) {
        ProjectRow project = teams.lockProject(projectId).orElseThrow(ApiException::notFound);
        requireSameSchool(principal, project);
        if (!project.ownerId().equals(principal.userId())) {
            throw new ApiException(ApiCode.FORBIDDEN);
        }
        if (principal.userId().equals(request.targetUserId())
                || teams.membership(projectId, request.targetUserId()).isEmpty()) {
            throw ApiException.validation("不能对自己发起移出，对象必须是当前成员");
        }
        List<String> electorate = new ArrayList<>();
        for (ProjectMemberRow member : teams.members(projectId)) {
            if ("ACTIVE".equals(member.status()) && !member.userId().equals(request.targetUserId())) {
                electorate.add(member.userId());
            }
        }
        if (electorate.size() < 2) {
            throw ApiException.validation("冻结后的选民不足，不能发起移出");
        }
        Instant now = clock.instant();
        String id = UUID.randomUUID().toString();
        VoteRow vote = new VoteRow(id, projectId, request.targetUserId(), principal.userId(), "STATEMENT",
                request.facts().strip(), request.communicationHistory().strip(), "", now.plus(STATEMENT),
                now.plus(STATEMENT).plus(VOTING), write(electorate), "{}", null, 1, now);
        try {
            governance.insertVote(vote, now);
        } catch (DataIntegrityViolationException ex) {
            throw ApiException.validation("该成员已有未结束的移出表决");
        }
        return voteView(governance.lockVote(id).orElseThrow(), principal.userId());
    }

    private GovViews.Vote statementOnce(AuthenticatedUser principal, String voteId, GovRequests.Statement request) {
        VoteRow vote = governance.lockVote(voteId).orElseThrow(ApiException::notFound);
        if (vote.revision() != request.expectedRevision() || !"STATEMENT".equals(vote.status())) {
            throw new ApiException(ApiCode.RESOURCE_CHANGED);
        }
        if (!principal.userId().equals(vote.targetUserId())) {
            throw new ApiException(ApiCode.FORBIDDEN);
        }
        if (!clock.instant().isBefore(vote.statementEndsAt())) {
            throw ApiException.validation("陈述期已结束");
        }
        VoteRow next = new VoteRow(vote.id(), vote.projectId(), vote.targetUserId(), vote.initiatorId(), vote.status(),
                vote.facts(), vote.communication(), request.statement().strip(), vote.statementEndsAt(),
                vote.votingEndsAt(), vote.electorateJson(), vote.decisionJson(), vote.memberChangeId(),
                vote.revision(), vote.createdAt());
        governance.updateVote(next, clock.instant());
        return voteView(governance.lockVote(voteId).orElseThrow(), principal.userId());
    }

    private GovViews.Vote ballotOnce(AuthenticatedUser principal, String voteId, GovRequests.Ballot request) {
        VoteRow vote = governance.lockVote(voteId).orElseThrow(ApiException::notFound);
        settle(vote);
        vote = governance.lockVote(voteId).orElseThrow();
        if (!"VOTING".equals(vote.status())) {
            throw new ApiException(ApiCode.SOURCE_CHANGED, "当前不能投票");
        }
        List<String> electorate = read(vote.electorateJson(), STRINGS);
        if (!electorate.contains(principal.userId())) {
            throw new ApiException(ApiCode.FORBIDDEN);
        }
        if (teams.membership(vote.projectId(), principal.userId()).isEmpty()) {
            disqualify(vote);
            throw new ApiException(ApiCode.SOURCE_CHANGED, "选民资格已变化，原表决作废");
        }
        var existing = governance.ballot(vote.id(), principal.userId());
        if (existing.isEmpty()) {
            if (request.expectedRevision() != 0) {
                throw new ApiException(ApiCode.RESOURCE_CHANGED);
            }
            governance.insertBallot(vote.id(), principal.userId(), request.choice(), 1, clock.instant());
        } else {
            if (existing.get().revision() != request.expectedRevision()) {
                throw new ApiException(ApiCode.RESOURCE_CHANGED);
            }
            if (!existing.get().decision().equals(request.choice())) {
                governance.updateBallot(vote.id(), principal.userId(), request.choice(), existing.get().revision(),
                        clock.instant());
            }
        }
        settle(governance.lockVote(voteId).orElseThrow());
        return voteView(governance.lockVote(voteId).orElseThrow(), principal.userId());
    }

    private GovViews.Vote cancelVoteOnce(AuthenticatedUser principal, String voteId, GovRequests.RevisionReason request) {
        VoteRow vote = governance.lockVote(voteId).orElseThrow(ApiException::notFound);
        if (vote.revision() != request.expectedRevision()) {
            throw new ApiException(ApiCode.RESOURCE_CHANGED);
        }
        if (!principal.userId().equals(vote.initiatorId())) {
            throw new ApiException(ApiCode.FORBIDDEN);
        }
        if (request.reason() == null || request.reason().isBlank()) {
            throw ApiException.validation("取消必须填写理由");
        }
        VoteRow next = new VoteRow(vote.id(), vote.projectId(), vote.targetUserId(), vote.initiatorId(), "CANCELLED",
                vote.facts(), vote.communication(), vote.statement(), vote.statementEndsAt(), vote.votingEndsAt(),
                vote.electorateJson(), vote.decisionJson(), vote.memberChangeId(), vote.revision(), vote.createdAt());
        governance.updateVote(next, clock.instant());
        return voteView(governance.lockVote(voteId).orElseThrow(), principal.userId());
    }

    private GovViews.Vote decideVoteOnce(AuthenticatedUser principal, String voteId, GovRequests.PersonnelDecision request) {
        // 锁顺序：预查 vote→member_change→case 关联 → 锁 case → 锁 vote → 重查状态。
        VoteRow preview = governance.findVote(voteId).orElseThrow(ApiException::notFound);
        if (preview.memberChangeId() == null) {
            throw new ApiException(ApiCode.SOURCE_CHANGED, "该表决没有对应的当前人员事项");
        }
        ChangeRow change = governance.findChange(preview.memberChangeId())
                .orElseThrow(() -> new ApiException(ApiCode.SOURCE_CHANGED, "移出表决关联的成员变更不存在"));
        if (!change.projectId().equals(preview.projectId())) {
            throw new ApiException(ApiCode.SOURCE_CHANGED, "移出表决与成员变更项目不一致");
        }
        CaseRow linked = linkedCaseForChange(change.id(), change.projectId());
        CaseRow caseRow = governance.lockCase(linked.id()).orElseThrow(ApiException::notFound);
        VoteRow vote = governance.lockVote(voteId).orElseThrow(ApiException::notFound);
        if (vote.revision() != request.expectedRevision() || !"PASSED".equals(vote.status())) {
            throw new ApiException(ApiCode.SOURCE_CHANGED);
        }
        if (!change.id().equals(vote.memberChangeId()) || !vote.projectId().equals(caseRow.projectId())
                || !change.id().equals(caseRow.memberChangeId())) {
            throw new ApiException(ApiCode.SOURCE_CHANGED, "人员决定与事项关联已变化");
        }
        if ("CLOSED".equals(caseRow.status())) {
            throw new ApiException(ApiCode.SOURCE_CHANGED, "事项已结案");
        }
        requireAssignee(principal, caseRow);
        String body = write(Map.of("decision", request.decision(), "placementPlan", request.placementPlan(),
                "reason", request.reason(), "allowBelowMinimum", request.allowBelowMinimum()));
        VoteRow next = new VoteRow(vote.id(), vote.projectId(), vote.targetUserId(), vote.initiatorId(), vote.status(),
                vote.facts(), vote.communication(), vote.statement(), vote.statementEndsAt(), vote.votingEndsAt(),
                vote.electorateJson(), body, vote.memberChangeId(), vote.revision(), vote.createdAt());
        if (governance.updateVote(next, clock.instant()) != 1) {
            throw new ApiException(ApiCode.RESOURCE_CHANGED);
        }
        return voteView(governance.lockVote(voteId).orElseThrow(), principal.userId());
    }

    private void settle(VoteRow vote) {
        if (!"STATEMENT".equals(vote.status()) && !"VOTING".equals(vote.status())) {
            return;
        }
        Instant now = clock.instant();
        for (String userId : read(vote.electorateJson(), STRINGS)) {
            if (teams.membership(vote.projectId(), userId).isEmpty()) {
                disqualify(vote);
                return;
            }
        }
        String status = vote.status();
        if ("STATEMENT".equals(status) && !now.isBefore(vote.statementEndsAt())) {
            status = "VOTING";
        }
        if ("VOTING".equals(status) && !now.isBefore(vote.votingEndsAt())) {
            List<GovRepository.BallotRow> ballots = governance.ballots(vote.id());
            long agree = ballots.stream().filter(row -> "AGREE".equals(row.decision())).count();
            int electorate = read(vote.electorateJson(), STRINGS).size();
            boolean passed = agree >= 2 && agree * 2 > electorate;
            if (passed && vote.memberChangeId() == null) {
                openRemoval(vote);
                return;
            }
            status = passed ? "PASSED" : "FAILED";
        }
        if (!status.equals(vote.status())) {
            governance.updateVote(new VoteRow(vote.id(), vote.projectId(), vote.targetUserId(), vote.initiatorId(),
                    status, vote.facts(), vote.communication(), vote.statement(), vote.statementEndsAt(),
                    vote.votingEndsAt(), vote.electorateJson(), vote.decisionJson(), vote.memberChangeId(),
                    vote.revision(), vote.createdAt()), now);
        }
    }

    private void disqualify(VoteRow vote) {
        governance.deleteBallots(vote.id());
        governance.updateVote(new VoteRow(vote.id(), vote.projectId(), vote.targetUserId(), vote.initiatorId(),
                "QUALIFICATION_REVIEW", vote.facts(), vote.communication(), vote.statement(), vote.statementEndsAt(),
                vote.votingEndsAt(), vote.electorateJson(), vote.decisionJson(), vote.memberChangeId(),
                vote.revision(), vote.createdAt()), clock.instant());
    }

    private void openRemoval(VoteRow vote) {
        ProjectRow project = teams.findProject(vote.projectId()).orElseThrow();
        ProjectMemberRow member = teams.membership(project.id(), vote.targetUserId()).orElse(null);
        if (member == null) {
            return;
        }
        String id = UUID.randomUUID().toString();
        Instant now = clock.instant();
        governance.insertChange(new ChangeRow(id, project.id(), vote.targetUserId(), vote.initiatorId(), "REMOVAL",
                "WAIT_PERSONNEL", null, 0, "{}", "{}", member.revision(), "移出表决通过", null, "",
                "NONE", null, vote.id(), 1, now), now);
        // 表决触发的移出同样拥有本业务对象唯一的人员事项；否则接续教师无法办理关联人员决定。
        governance.insertCase(new CaseRow(UUID.randomUUID().toString(), project.squareId(), project.id(),
                vote.targetUserId(), null, "移出表决", "OPEN", id, 1), now);
        governance.updateVote(new VoteRow(vote.id(), vote.projectId(), vote.targetUserId(), vote.initiatorId(),
                "PASSED", vote.facts(), vote.communication(), vote.statement(), vote.statementEndsAt(),
                vote.votingEndsAt(), vote.electorateJson(), vote.decisionJson(), id, vote.revision(), vote.createdAt()), now);
    }

    private GovViews.CaseView acceptCaseOnce(AuthenticatedUser principal, String id, GovRequests.CaseRevision request) {
        CaseRow row = lockedCase(principal, id, request.expectedRevision());
        requireCurrentTeacher(principal, row);
        if (row.assigneeId() != null) {
            if (row.assigneeId().equals(principal.userId())) {
                return caseView(row, principal, false);
            }
            throw new ApiException(ApiCode.RESOURCE_CHANGED, "已有处理人");
        }
        int updated = governance.updateCase(new CaseRow(row.id(), row.squareId(), row.projectId(),
                row.subjectUserId(), principal.userId(), row.title(), "IN_PROGRESS", row.memberChangeId(),
                row.revision()), clock.instant());
        if (updated != 1) {
            throw new ApiException(ApiCode.RESOURCE_CHANGED);
        }
        return caseView(governance.findCase(id).orElseThrow(ApiException::notFound), principal, false);
    }

    private GovViews.CaseView closeCaseOnce(AuthenticatedUser principal, String id, GovRequests.CloseCase request) {
        CaseRow row = lockedCase(principal, id, request.expectedRevision());
        requireAssignee(principal, row);
        if ("CLOSED".equals(row.status())) {
            throw new ApiException(ApiCode.SOURCE_CHANGED, "事项已结案");
        }
        // case 先锁；随后按 case_id 确定性取消全部 PENDING 交接。
        governance.cancelPendingHandoffs(row.id());
        int updated = governance.updateCase(new CaseRow(row.id(), row.squareId(), row.projectId(),
                row.subjectUserId(), row.assigneeId(), row.title(), "CLOSED", row.memberChangeId(),
                row.revision()), clock.instant());
        if (updated != 1) {
            throw new ApiException(ApiCode.RESOURCE_CHANGED);
        }
        governance.record(UUID.randomUUID().toString(), row.id(), "STAFF", principal.userId(),
                write(Map.of("action", "CLOSE", "reason", request.reason())), clock.instant());
        return caseView(governance.findCase(id).orElseThrow(ApiException::notFound), principal, false);
    }

    private GovViews.CaseView updateCaseOnce(AuthenticatedUser principal, String id, GovRequests.CaseUpdate request) {
        CaseRow row = lockedCase(principal, id, request.expectedRevision());
        requireAssignee(principal, row);
        if (governance.updateCase(row, clock.instant()) != 1) {
            throw new ApiException(ApiCode.RESOURCE_CHANGED);
        }
        governance.record(UUID.randomUUID().toString(), row.id(), "STAFF", principal.userId(),
                write(Map.of("facts", request.facts(), "nextAction", request.nextAction())), clock.instant());
        return caseView(governance.findCase(id).orElseThrow(ApiException::notFound), principal, false);
    }

    private GovViews.CaseView recoverOnce(AuthenticatedUser principal, String id, GovRequests.CloseCase request) {
        CaseRow row = lockedCase(principal, id, request.expectedRevision());
        requireCurrentTeacher(principal, row);
        if ("CLOSED".equals(row.status())) {
            throw new ApiException(ApiCode.SOURCE_CHANGED, "已结案事项不能通过收回重新打开");
        }
        if (row.assigneeId() == null) {
            throw ApiException.validation("事项尚无人受理，请按验收流程接任");
        }
        if (!caseAccess.canRecoverCase(row, principal.userId())) {
            throw ApiException.validation("原处理人仍可正常办理本事项，不能收回");
        }
        // 收回与回应交接统一 case → handoff 顺序；撤回仍 PENDING 的交接。
        governance.cancelPendingHandoffs(row.id());
        int updated = governance.updateCase(new CaseRow(row.id(), row.squareId(), row.projectId(),
                row.subjectUserId(), principal.userId(), row.title(), "IN_PROGRESS", row.memberChangeId(),
                row.revision()), clock.instant());
        if (updated != 1) {
            throw new ApiException(ApiCode.RESOURCE_CHANGED);
        }
        governance.record(UUID.randomUUID().toString(), row.id(), "STAFF", principal.userId(),
                write(Map.of("action", "RECOVER", "previousAssigneeId", row.assigneeId(),
                        "reason", request.reason())), clock.instant());
        return caseView(governance.findCase(id).orElseThrow(ApiException::notFound), principal, false);
    }

    private GovViews.Material requestMaterialOnce(AuthenticatedUser principal, String caseId,
                                                 GovRequests.MaterialCreate request) {
        CaseRow row = lockedCase(principal, caseId, request.expectedCaseRevision());
        requireAssignee(principal, row);
        if (!request.recipientId().equals(row.subjectUserId())) {
            throw ApiException.validation("只能向事项本人要材料");
        }
        String id = UUID.randomUUID().toString();
        governance.insertMaterial(new MaterialRow(id, caseId, request.recipientId(), "OPEN",
                request.requirements().strip(), request.dueAt(), 0, "{}", 1), clock.instant());
        return materialView(governance.lockMaterial(id).orElseThrow(ApiException::notFound), row, principal);
    }

    private GovViews.Material respondMaterialOnce(AuthenticatedUser principal, String id,
                                                 GovRequests.MaterialResponse request) {
        // 子资源锁前先锁 case，保持 case 永远先于材料；锁后重核归属、身份和版本。
        MaterialRow preview = governance.findMaterial(id).orElseThrow(ApiException::notFound);
        CaseRow caseRow = governance.lockCase(preview.caseId()).orElseThrow(ApiException::notFound);
        MaterialRow row = governance.lockMaterial(id).orElseThrow(ApiException::notFound);
        requireMaterialCaseLink(row, caseRow);
        if (!caseAccess.activeVerifiedAccount(principal.userId())) {
            throw new ApiException(ApiCode.SESSION_EXPIRED);
        }
        boolean recipient = principal.userId().equals(row.recipientId());
        if (!recipient) {
            throw new ApiException(ApiCode.FORBIDDEN, MaterialActionPolicy.message(
                    MaterialActionPolicy.Blocker.NOT_RECIPIENT));
        }
        if (row.revision() != request.expectedRevision()) {
            throw new ApiException(ApiCode.RESOURCE_CHANGED);
        }
        MaterialResponseRow current = governance.materialResponse(row.id(), row.responseVersion())
                .orElse(null);
        MaterialActionPolicy.Decision decision = materialDecision(caseRow, row, current, true,
                principal.userId());
        if (!decision.canRespond()) {
            throw materialActionBlocked(decision.respondBlocker());
        }
        if (row.responseVersion() >= 999_999_999) {
            throw materialActionBlocked(MaterialActionPolicy.Blocker.VERSION_LIMIT);
        }
        int version = row.responseVersion() + 1;
        Instant now = clock.instant();
        String content = request.content().strip();
        String evidence = write(request.evidenceRefs() == null ? List.of() : request.evidenceRefs());
        governance.insertMaterialResponse(UUID.randomUUID().toString(), row.id(), version, principal.userId(),
                content, evidence, now, MaterialResponseTime.EXACT, MaterialResponseTime.SERVER_SUBMISSION);
        int updated = governance.markMaterialResponded(row.id(), row.revision(), row.responseVersion(), version,
                write(Map.of("content", content, "version", version)), now);
        if (updated != 1) {
            throw new ApiException(ApiCode.RESOURCE_CHANGED);
        }
        return materialView(governance.lockMaterial(id).orElseThrow(ApiException::notFound), caseRow, principal);
    }

    private GovViews.Material reviewMaterialOnce(AuthenticatedUser principal, String id, GovRequests.MaterialReview request) {
        MaterialRow preview = governance.findMaterial(id).orElseThrow(ApiException::notFound);
        CaseRow caseRow = governance.lockCase(preview.caseId()).orElseThrow(ApiException::notFound);
        MaterialRow row = governance.lockMaterial(id).orElseThrow(ApiException::notFound);
        requireMaterialCaseLink(row, caseRow);
        if (caseAccess.recused(caseRow.projectId(), principal.userId())) {
            throw new ApiException(ApiCode.RECUSAL_REQUIRED);
        }
        if (row.revision() != request.expectedRevision()) {
            throw new ApiException(ApiCode.RESOURCE_CHANGED);
        }
        if (row.responseVersion() != request.responseVersion()) {
            throw new ApiException(ApiCode.SOURCE_CHANGED, "材料版本已变化");
        }
        MaterialResponseRow current = governance.lockMaterialResponse(row.id(), request.responseVersion())
                .orElse(null);
        boolean recipient = principal.userId().equals(row.recipientId())
                && caseAccess.activeVerifiedAccount(principal.userId());
        MaterialActionPolicy.Decision decision = MaterialActionPolicy.evaluate(caseRow.status(), row.status(),
                row.responseVersion(), current != null, current != null && current.withdrawn(), recipient,
                caseAccess.isQualifiedAssignee(caseRow, principal.userId()));
        if (!decision.canReview()) {
            throw materialActionBlocked(decision.reviewBlocker());
        }
        String reason = request.reason() == null ? "" : request.reason().strip();
        if ("RETURN".equals(request.decision()) && reason.isEmpty()) {
            throw ApiException.validation("退回材料必须填写理由");
        }
        Instant now = clock.instant();
        String status = "ACCEPT".equals(request.decision()) ? "ACCEPTED" : "RETURNED";
        governance.insertMaterialReview(UUID.randomUUID().toString(), row.id(), request.responseVersion(),
                principal.userId(), request.decision(), reason, now);
        int updated = governance.markMaterialReviewed(row.id(), row.revision(), request.responseVersion(),
                status, now);
        if (updated != 1) {
            throw new ApiException(ApiCode.RESOURCE_CHANGED);
        }
        return materialView(governance.lockMaterial(id).orElseThrow(ApiException::notFound), caseRow, principal);
    }

    private GovViews.Material withdrawMaterialOnce(AuthenticatedUser principal, String id, GovRequests.RevisionReason request) {
        MaterialRow preview = governance.findMaterial(id).orElseThrow(ApiException::notFound);
        CaseRow caseRow = governance.lockCase(preview.caseId()).orElseThrow(ApiException::notFound);
        MaterialRow row = governance.lockMaterial(id).orElseThrow(ApiException::notFound);
        requireMaterialCaseLink(row, caseRow);
        if (!caseAccess.activeVerifiedAccount(principal.userId())) {
            throw new ApiException(ApiCode.SESSION_EXPIRED);
        }
        boolean recipient = principal.userId().equals(row.recipientId());
        if (!recipient) {
            throw new ApiException(ApiCode.FORBIDDEN, MaterialActionPolicy.message(
                    MaterialActionPolicy.Blocker.NOT_RECIPIENT));
        }
        if (row.revision() != request.expectedRevision()) {
            throw new ApiException(ApiCode.RESOURCE_CHANGED);
        }
        MaterialResponseRow current = governance.lockMaterialResponse(row.id(), row.responseVersion())
                .orElse(null);
        MaterialActionPolicy.Decision decision = materialDecision(caseRow, row, current, true,
                principal.userId());
        if (!decision.canWithdraw()) {
            throw materialActionBlocked(decision.withdrawBlocker());
        }
        // 撤回只占位当前版本；编号、作者、原真实/未知时间和历史审核事实保留。
        if (governance.withdrawMaterialResponse(row.id(), row.responseVersion()) != 1) {
            throw new ApiException(ApiCode.SOURCE_CHANGED, MaterialActionPolicy.message(
                    MaterialActionPolicy.Blocker.CURRENT_RESPONSE_WITHDRAWN));
        }
        int updated = governance.markMaterialWithdrawn(row.id(), row.revision(), row.responseVersion(),
                clock.instant());
        if (updated != 1) {
            throw new ApiException(ApiCode.RESOURCE_CHANGED);
        }
        return materialView(governance.lockMaterial(id).orElseThrow(ApiException::notFound), caseRow, principal);
    }

    private void requireMaterialCaseLink(MaterialRow row, CaseRow caseRow) {
        if (!row.caseId().equals(caseRow.id())) {
            throw new ApiException(ApiCode.SOURCE_CHANGED, "材料与事项关联已变化");
        }
    }

    private MaterialActionPolicy.Decision materialDecision(CaseRow caseRow, MaterialRow row,
                                                           MaterialResponseRow current, boolean recipient,
                                                           String userId) {
        return MaterialActionPolicy.evaluate(caseRow.status(), row.status(), row.responseVersion(),
                current != null && current.version() == row.responseVersion(),
                current != null && current.withdrawn(), recipient,
                caseAccess.isQualifiedAssignee(caseRow, userId));
    }

    private ApiException materialActionBlocked(MaterialActionPolicy.Blocker blocker) {
        String message = MaterialActionPolicy.message(blocker);
        if (MaterialActionPolicy.isPermissionBlocker(blocker)) {
            return new ApiException(ApiCode.FORBIDDEN, message);
        }
        return new ApiException(ApiCode.SOURCE_CHANGED, message);
    }

    private GovViews.Handoff createHandoffOnce(AuthenticatedUser principal, String caseId, GovRequests.CaseHandoff request) {
        CaseRow row = lockedCase(principal, caseId, request.expectedCaseRevision());
        requireHandle(principal, row);
        if (request.recipientTeacherId().equals(principal.userId())
                || !caseAccess.canReceiveHandoff(row, request.recipientTeacherId())) {
            throw ApiException.validation("接续人必须是本事项合法且有资格的教师");
        }
        String id = UUID.randomUUID().toString();
        try {
            governance.insertHandoff(new HandoffRow(id, caseId, principal.userId(),
                    request.recipientTeacherId(), "PENDING", row.revision(), request.facts(),
                    request.unresolvedQuestions(), request.decisionsNeeded(), 1), clock.instant());
        } catch (DataIntegrityViolationException ex) {
            throw ApiException.validation("该事项已有等待回应的交接");
        }
        return handoffView(governance.lockHandoff(id).orElseThrow(ApiException::notFound), row, principal);
    }

    private GovViews.Handoff respondHandoffOnce(AuthenticatedUser principal, String id, GovRequests.HandoffDecision request) {
        // 锁顺序：只读预查 caseId → 锁 case → 锁 handoff → 锁后重查全部条件。
        HandoffRow preview = governance.findHandoff(id).orElseThrow(ApiException::notFound);
        CaseRow caseRow = governance.lockCase(preview.caseId()).orElseThrow(ApiException::notFound);
        HandoffRow row = governance.lockHandoff(id).orElseThrow(ApiException::notFound);
        if (!principal.userId().equals(row.toUserId())) {
            throw new ApiException(ApiCode.FORBIDDEN);
        }
        if (!row.caseId().equals(caseRow.id())) {
            throw new ApiException(ApiCode.SOURCE_CHANGED, "交接与事项关联已变化");
        }
        if (row.revision() != request.expectedRevision()) {
            throw new ApiException(ApiCode.RESOURCE_CHANGED);
        }
        if (!caseAccess.canRespondHandoff(caseRow, row, principal.userId())) {
            throw new ApiException(ApiCode.FORBIDDEN, "交接已失效或当前没有接续资格");
        }
        String status = "ACCEPT".equals(request.decision()) ? "ACCEPTED" : "CANCELLED";
        if (governance.updateHandoff(row.id(), row.revision(), status) != 1) {
            throw new ApiException(ApiCode.RESOURCE_CHANGED);
        }
        if ("ACCEPTED".equals(status)) {
            if (governance.updateCase(new CaseRow(caseRow.id(), caseRow.squareId(), caseRow.projectId(),
                    caseRow.subjectUserId(), principal.userId(), caseRow.title(), caseRow.status(),
                    caseRow.memberChangeId(), caseRow.revision()), clock.instant()) != 1) {
                throw new ApiException(ApiCode.RESOURCE_CHANGED);
            }
        }
        CaseRow current = governance.findCase(caseRow.id()).orElseThrow(ApiException::notFound);
        return handoffView(governance.lockHandoff(id).orElseThrow(ApiException::notFound), current, principal);
    }

    private GovViews.Handoff cancelHandoffOnce(AuthenticatedUser principal, String id, GovRequests.RevisionReason request) {
        HandoffRow preview = governance.findHandoff(id).orElseThrow(ApiException::notFound);
        CaseRow locked = governance.lockCase(preview.caseId()).orElseThrow(ApiException::notFound);
        HandoffRow row = governance.lockHandoff(id).orElseThrow(ApiException::notFound);
        if (!principal.userId().equals(row.fromUserId())) {
            throw new ApiException(ApiCode.FORBIDDEN);
        }
        if (row.revision() != request.expectedRevision()) {
            throw new ApiException(ApiCode.RESOURCE_CHANGED);
        }
        if (!caseAccess.canHandleCase(locked, principal.userId())) {
            throw new ApiException(ApiCode.FORBIDDEN, "当前没有本事项处理资格");
        }
        if (governance.updateHandoff(row.id(), row.revision(), "CANCELLED") != 1) {
            // 不能在被并发接受/取消后用 0 行更新返回虚假 CANCELLED。
            throw new ApiException(ApiCode.RESOURCE_CHANGED);
        }
        return handoffView(governance.lockHandoff(id).orElseThrow(ApiException::notFound), locked, principal);
    }

    private List<GovViews.Outstanding> outstanding(String userId, String projectId) {
        List<GovViews.Outstanding> rows = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (TaskRow task : tasks.tasksForProject(projectId)) {
            if (userId.equals(task.ownerId()) && !"DONE".equals(task.status()) && seen.add(task.id())) {
                rows.add(new GovViews.Outstanding(task.id(), task.title(), task.revision()));
            }
        }
        for (GovViews.Outstanding task : governance.unfinishedShares(projectId, userId)) {
            if (seen.add(task.id())) {
                rows.add(task);
            }
        }
        return rows;
    }

    private GovViews.MemberChange changeView(ChangeRow row, AuthenticatedUser principal) {
        ProjectRow project = teams.findProject(row.projectId()).orElseThrow();
        List<ItemRow> items = row.planVersion() == 0 ? List.of() : governance.items(row.id(), row.planVersion());
        List<AckRow> acks = row.planVersion() == 0 ? List.of() : governance.acks(row.id(), row.planVersion());
        List<GovViews.HandoverItem> planItems = items.stream().map(item -> new GovViews.HandoverItem(
                item.id(), item.kind(), item.taskId(), item.sourceTaskRevision(), item.recipientId(),
                name(item.recipientId()), item.responsibility(), accepted(acks, item.id(), item.recipientId()))).toList();
        Object plan = row.planVersion() == 0 ? Map.of() : new GovViews.HandoverPlan(row.planVersion(), planItems,
                row.nextLeaderId() == null ? "" : row.nextLeaderId(), row.notes());
        Object decision = "{}".equals(row.decisionJson()) ? Map.of() : read(row.decisionJson(), MAP);
        boolean targetValid = "ACCEPTED".equals(row.targetInviteStatus()) && row.targetInviteExpiresAt() != null
                && row.targetInviteExpiresAt().isAfter(clock.instant());
        ProjectRow target = row.targetProjectId() == null ? null : teams.findProject(row.targetProjectId()).orElse(null);
        boolean leader = project.ownerId().equals(principal.userId());
        return new GovViews.MemberChange(row.id(), row.projectId(), row.projectId(), project.squareId(),
                project.assignmentId(), row.targetUserId(), row.kind(), row.reason(), row.status(),
                row.targetProjectId(), row.planVersion(), plan, decision,
                governance.events(row.id()).stream().map(event -> new GovViews.HistoryItem(
                        event.action(), event.actorId(), event.at(), event.reason())).toList(),
                row.revision(), row.createdAt(), row.initiatorId(), planValid(row, items, acks),
                canDecidePersonnel(row, principal), leader,
                outstanding(row.targetUserId(), project.id()),
                "NONE".equals(row.targetInviteStatus()) ? Map.of() : Map.of(
                        "status", row.targetInviteStatus(), "expiresAt", String.valueOf(row.targetInviteExpiresAt())),
                targetValid, leader && "TRANSFER".equals(row.kind()) && "NONE".equals(row.targetInviteStatus()),
                target != null && target.ownerId().equals(principal.userId()) && "PENDING".equals(row.targetInviteStatus()),
                principal.userId().equals(row.targetUserId()) && "PENDING".equals(row.targetInviteStatus()),
                target == null ? "" : target.name());
    }

    /** 关联人员决定按钮只对真正可办理的当前 handler 显示；数据异常时不显示可办理。 */
    private boolean canDecidePersonnel(ChangeRow row, AuthenticatedUser principal) {
        if (!"WAIT_PERSONNEL".equals(row.status()) && !"RETURNED".equals(row.status())) {
            return false;
        }
        List<CaseRow> cases = governance.casesForChange(row.id());
        if (cases.size() != 1) {
            return false;
        }
        CaseRow caseRow = cases.getFirst();
        return caseRow.projectId().equals(row.projectId())
                && caseAccess.canMakePersonnelDecision(caseRow, principal.userId());
    }

    private boolean planValid(ChangeRow row, List<ItemRow> items, List<AckRow> acks) {
        if (row.planVersion() == 0) {
            return false;
        }
        return items.stream().allMatch(item -> accepted(acks, item.id(), item.recipientId()))
                && items.stream().filter(item -> "TASK".equals(item.kind())).allMatch(item -> {
                    TaskRow task = tasks.findTask(item.taskId()).orElse(null);
                    return task != null && task.revision() == item.sourceTaskRevision();
                });
    }

    private boolean accepted(List<AckRow> acks, String itemId, String userId) {
        return acks.stream().anyMatch(ack -> ack.itemId().equals(itemId) && ack.userId().equals(userId)
                && "ACCEPT".equals(ack.decision()));
    }

    private GovViews.Vote voteView(VoteRow vote, String viewerId) {
        List<GovRepository.BallotRow> ballots = governance.ballots(vote.id());
        int eligible = read(vote.electorateJson(), STRINGS).size();
        int agree = (int) ballots.stream().filter(row -> "AGREE".equals(row.decision())).count();
        int disagree = (int) ballots.stream().filter(row -> "DISAGREE".equals(row.decision())).count();
        int abstain = (int) ballots.stream().filter(row -> "ABSTAIN".equals(row.decision())).count();
        GovRepository.BallotRow mine = ballots.stream().filter(row -> viewerId.equals(row.userId()))
                .findFirst().orElse(null);
        String result = switch (vote.status()) {
            case "PASSED" -> "PASSED";
            case "FAILED" -> "NOT_PASSED";
            default -> null;
        };
        return new GovViews.Vote(vote.id(), vote.projectId(), vote.targetUserId(), vote.status(), vote.facts(),
                vote.statement(), vote.statementEndsAt(), vote.votingEndsAt(), eligible, agree, disagree, abstain,
                Math.max(0, eligible - agree - disagree - abstain),
                mine == null ? null : new GovViews.MyBallot(mine.decision(), mine.revision()),
                result, vote.revision(), vote.memberChangeId());
    }

    private GovViews.CaseView caseView(CaseRow row, AuthenticatedUser principal, boolean list) {
        boolean staff = caseAccess.canReadCase(row, principal.userId());
        boolean subject = principal.userId().equals(row.subjectUserId())
                && caseAccess.activeVerifiedAccount(principal.userId());
        if (!staff && !subject) {
            throw new ApiException(ApiCode.FORBIDDEN);
        }
        if (teams.membership(row.projectId(), principal.userId()).isPresent() && !subject) {
            throw new ApiException(ApiCode.RECUSAL_REQUIRED);
        }
        if (list && !staff) {
            throw new ApiException(ApiCode.FORBIDDEN);
        }
        List<GovViews.Material> materials = governance.materialsForCase(row.id()).stream()
                .filter(item -> caseAccess.canReadMaterial(row, item, principal.userId()))
                .map(item -> materialView(item, row, principal)).toList();
        List<Object> records = staff ? governance.records(row.id()).stream().map(record -> (Object) Map.of(
                "kind", record.kind(), "authorId", record.authorId())).toList() : null;
        return new GovViews.CaseView(row.id(), row.squareId(), row.projectId(),
                staff || subject ? row.subjectUserId() : null, staff ? row.assigneeId() : null, row.title(),
                row.status(), row.revision(), materials, records);
    }

    /**
     * 材料投影由“当前调用者是否有权读取正文”决定，而不是是否为提交人。
     * 撤回版本只保留占位（content=null），历史和提交时间不伪造。
     */
    private GovViews.Material materialView(MaterialRow row, CaseRow caseRow, AuthenticatedUser principal) {
        List<MaterialResponseRow> rows = governance.materialResponses(row.id());
        List<GovViews.MaterialResponseItem> responses = rows.stream().map(this::materialResponseItem).toList();
        MaterialResponseRow currentRow = rows.stream()
                .filter(item -> item.version() == row.responseVersion()).findFirst().orElse(null);
        GovViews.MaterialResponseItem currentItem = currentRow == null ? null : materialResponseItem(currentRow);
        Object response = currentItem == null ? Map.of() : currentItem;
        boolean recipient = principal.userId().equals(row.recipientId())
                && caseAccess.activeVerifiedAccount(principal.userId());
        boolean handler = caseAccess.isQualifiedAssignee(caseRow, principal.userId());
        MaterialActionPolicy.Decision decision = MaterialActionPolicy.evaluate(caseRow.status(), row.status(),
                row.responseVersion(), currentRow != null, currentRow != null && currentRow.withdrawn(),
                recipient, handler);
        List<GovViews.MaterialReviewItem> reviews = governance.materialReviews(row.id()).stream()
                .map(item -> new GovViews.MaterialReviewItem(item.reviewerId(), item.decision(),
                        item.responseVersion(), List.of(), item.reason(), item.createdAt())).toList();
        return new GovViews.Material(row.id(), row.caseId(), row.recipientId(), name(row.recipientId()),
                caseRow.title(), row.status(), row.requirements(), row.dueAt(), row.responseVersion(), responses,
                reviews, response, decision.canRespond(), decision.canReview(), decision.canWithdraw(),
                row.revision());
    }

    /** 数组与兼容对象共用同一个 DTO，避免两处各写一份时间/撤回序列化。 */
    private GovViews.MaterialResponseItem materialResponseItem(MaterialResponseRow item) {
        return new GovViews.MaterialResponseItem(item.version(), item.authorId(),
                item.withdrawn() ? null : item.content(), evidence(item.evidenceJson()),
                item.createdAt(), item.createdAtConfidence(), item.withdrawn());
    }

    private List<Object> evidence(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        return read(json, OBJECTS);
    }

    private GovViews.Handoff handoffView(HandoffRow row, CaseRow caseRow, AuthenticatedUser principal) {
        boolean sourceValid = row.sourceRevision() == caseRow.revision()
                && !"CLOSED".equals(caseRow.status())
                && row.fromUserId().equals(caseRow.assigneeId());
        return new GovViews.Handoff(row.id(), row.caseId(), row.fromUserId(), row.toUserId(),
                row.fromUserId(), row.toUserId(), row.status(), row.sourceRevision(), row.facts(),
                row.unresolved(), row.decisionsNeeded(), name(row.fromUserId()), name(row.toUserId()),
                sourceValid, caseAccess.canRespondHandoff(caseRow, row, principal.userId()),
                "PENDING".equals(row.status()) && principal.userId().equals(row.fromUserId())
                        && caseAccess.canHandleCase(caseRow, principal.userId()),
                row.revision());
    }

    private CaseRow lockedCase(AuthenticatedUser principal, String id, int revision) {
        CaseRow row = governance.lockCase(id).orElseThrow(ApiException::notFound);
        requireCaseRead(principal, row);
        if (row.revision() != revision) {
            throw new ApiException(ApiCode.RESOURCE_CHANGED);
        }
        return row;
    }

    private void requireMaterialRecipient(AuthenticatedUser principal, String materialId) {
        MaterialRow row = governance.findMaterial(materialId).orElseThrow(ApiException::notFound);
        if (!principal.userId().equals(row.recipientId())
                || !caseAccess.activeVerifiedAccount(principal.userId())) {
            throw new ApiException(ApiCode.FORBIDDEN);
        }
    }

    private void requireMaterialHandle(AuthenticatedUser principal, String materialId) {
        MaterialRow row = governance.findMaterial(materialId).orElseThrow(ApiException::notFound);
        requireHandleOrQualifiedAssignee(principal, row.caseId());
    }

    /**
     * 写动作的回放允许“当前仍是合格 assignee”的已结案事项稳定返回；
     * 新执行仍由 action 内的 canHandleCase 拒绝 CLOSED。失权者仍在这里被拒。
     */
    private void requireHandleOrQualifiedAssignee(AuthenticatedUser principal, String caseId) {
        CaseRow row = governance.findCase(caseId).orElseThrow(ApiException::notFound);
        if (caseAccess.recused(row.projectId(), principal.userId())) {
            throw new ApiException(ApiCode.RECUSAL_REQUIRED);
        }
        if (!caseAccess.canHandleCase(row, principal.userId())
                && !caseAccess.isQualifiedAssignee(row, principal.userId())) {
            throw new ApiException(ApiCode.FORBIDDEN, "只有当前处理人可以继续");
        }
    }

    private void requireHandleOnCase(AuthenticatedUser principal, String caseId) {
        requireAssignee(principal, governance.findCase(caseId).orElseThrow(ApiException::notFound));
    }

    private void requireHandoffRead(AuthenticatedUser principal, String handoffId) {
        HandoffRow handoff = governance.findHandoff(handoffId).orElseThrow(ApiException::notFound);
        CaseRow row = governance.findCase(handoff.caseId()).orElseThrow(ApiException::notFound);
        if (!caseAccess.canReadHandoff(row, handoff, principal.userId())) {
            throw new ApiException(ApiCode.FORBIDDEN);
        }
    }

    private void requireChangeDecision(AuthenticatedUser principal, String changeId) {
        ChangeRow change = governance.findChange(changeId).orElseThrow(ApiException::notFound);
        if (!"WAIT_PERSONNEL".equals(change.status()) && !"RETURNED".equals(change.status())) {
            throw new ApiException(ApiCode.SOURCE_CHANGED, "当前状态不能作人员核对决定");
        }
        CaseRow caseRow = linkedCaseForChange(changeId, change.projectId());
        requireAssignee(principal, caseRow);
    }

    private void requireVoteDecision(AuthenticatedUser principal, String voteId) {
        VoteRow vote = governance.findVote(voteId).orElseThrow(ApiException::notFound);
        if (vote.memberChangeId() == null) {
            throw new ApiException(ApiCode.SOURCE_CHANGED, "该表决没有对应的当前人员事项");
        }
        ChangeRow change = governance.findChange(vote.memberChangeId())
                .orElseThrow(() -> new ApiException(ApiCode.SOURCE_CHANGED, "移出表决关联的成员变更不存在"));
        if (!change.projectId().equals(vote.projectId())) {
            throw new ApiException(ApiCode.SOURCE_CHANGED, "移出表决与成员变更项目不一致");
        }
        requireAssignee(principal, linkedCaseForChange(change.id(), change.projectId()));
    }

    /** 关联人员决定必须定位到本业务对象唯一的事项；多行/无行都显式拒绝，不回退为全局教师权限。 */
    private CaseRow linkedCaseForChange(String changeId, String projectId) {
        List<CaseRow> cases = governance.casesForChange(changeId);
        if (cases.isEmpty()) {
            throw new ApiException(ApiCode.FORBIDDEN, "该人员变更没有可办理的人员事项");
        }
        if (cases.size() > 1) {
            throw new ApiException(ApiCode.SOURCE_CHANGED, "同一人员变更关联多个事项，数据异常");
        }
        CaseRow row = cases.getFirst();
        ChangeRow change = governance.findChange(changeId).orElseThrow(ApiException::notFound);
        ProjectRow project = teams.findProject(projectId).orElseThrow(ApiException::notFound);
        if (!row.projectId().equals(projectId) || !row.squareId().equals(project.squareId())
                || !row.subjectUserId().equals(change.targetUserId())
                || !changeId.equals(row.memberChangeId())) {
            throw new ApiException(ApiCode.SOURCE_CHANGED, "人员变更与事项关联不一致");
        }
        return row;
    }

    private void requireAssignee(AuthenticatedUser principal, CaseRow row) {
        if (caseAccess.recused(row.projectId(), principal.userId())) {
            throw new ApiException(ApiCode.RECUSAL_REQUIRED);
        }
        if (!caseAccess.canHandleCase(row, principal.userId())) {
            throw new ApiException(ApiCode.FORBIDDEN, "只有当前处理人可以继续");
        }
    }

    private void requireHandle(AuthenticatedUser principal, CaseRow row) {
        requireAssignee(principal, row);
    }

    private void requireCaseRead(AuthenticatedUser principal, CaseRow row) {
        if (caseAccess.recused(row.projectId(), principal.userId())) {
            throw new ApiException(ApiCode.RECUSAL_REQUIRED);
        }
        if (!caseAccess.canReadCase(row, principal.userId())) {
            throw new ApiException(ApiCode.FORBIDDEN);
        }
    }

    private void requireCurrentTeacher(AuthenticatedUser principal, CaseRow row) {
        if (caseAccess.recused(row.projectId(), principal.userId())) {
            throw new ApiException(ApiCode.RECUSAL_REQUIRED);
        }
        if (!caseAccess.isSupervisingTeacher(row, principal.userId())) {
            throw new ApiException(ApiCode.FORBIDDEN, "人员核对只由当前教师执行");
        }
    }

    private void requireTeacher(AuthenticatedUser principal, String squareId) {
        if (!caseAccess.isSupervisingTeacher(squareId, principal.userId())) {
            throw new ApiException(ApiCode.FORBIDDEN, "人员核对只由当前教师执行");
        }
    }

    private void requireStaff(AuthenticatedUser principal, String squareId) {
        SquareRow square = squares.findById(squareId).orElseThrow(ApiException::notFound);
        if (!square.schoolId().equals(principal.schoolId())) {
            throw ApiException.notFound();
        }
        requireTeacher(principal, squareId);
    }

    private ReplayProjector<GovViews.CaseView> caseViewReplay(AuthenticatedUser principal) {
        return cached -> caseView(governance.findCase(cached.id()).orElseThrow(ApiException::notFound),
                principal, false);
    }

    private ReplayProjector<GovViews.Material> materialReplay(AuthenticatedUser principal) {
        return cached -> {
            MaterialRow row = governance.findMaterial(cached.id()).orElseThrow(ApiException::notFound);
            CaseRow caseRow = governance.findCase(row.caseId()).orElseThrow(ApiException::notFound);
            if (!caseAccess.canReadMaterial(caseRow, row, principal.userId())) {
                throw new ApiException(ApiCode.FORBIDDEN);
            }
            return materialView(row, caseRow, principal);
        };
    }

    private ReplayProjector<GovViews.Handoff> handoffReplay(AuthenticatedUser principal) {
        return cached -> {
            HandoffRow row = governance.findHandoff(cached.id()).orElseThrow(ApiException::notFound);
            CaseRow caseRow = governance.findCase(row.caseId()).orElseThrow(ApiException::notFound);
            if (!caseAccess.canReadHandoff(caseRow, row, principal.userId())) {
                throw new ApiException(ApiCode.FORBIDDEN);
            }
            return handoffView(row, caseRow, principal);
        };
    }

    private ProjectRow readableProject(AuthenticatedUser principal, String projectId) {
        policy.requireStudent(principal);
        ProjectRow project = teams.findProject(projectId).orElseThrow(ApiException::notFound);
        requireReader(principal, project);
        return project;
    }

    private void requireReader(AuthenticatedUser principal, ProjectRow project) {
        requireSameSchool(principal, project);
        if (teams.membership(project.id(), principal.userId()).isEmpty()
                && !caseAccess.isSupervisingTeacher(project.squareId(), principal.userId())) {
            throw new ApiException(ApiCode.FORBIDDEN);
        }
    }

    private void requireSameSchool(AuthenticatedUser principal, ProjectRow project) {
        SquareRow square = squares.findById(project.squareId()).orElseThrow(ApiException::notFound);
        if (!square.schoolId().equals(principal.schoolId())) {
            throw ApiException.notFound();
        }
    }

    private ChangeRow lockedOpen(String id, int revision) {
        ChangeRow row = governance.lockChange(id).orElseThrow(ApiException::notFound);
        if (row.revision() != revision) {
            throw new ApiException(ApiCode.RESOURCE_CHANGED);
        }
        if ("EXECUTED".equals(row.status()) || "CANCELLED".equals(row.status())) {
            throw new ApiException(ApiCode.SOURCE_CHANGED, "这份变更已经结束");
        }
        return row;
    }

    private ChangeRow copy(ChangeRow row, String status, int planVersion, String planJson, String decision,
                           String nextLeader, String notes, String invite, Instant expires, String voteId) {
        return new ChangeRow(row.id(), row.projectId(), row.targetUserId(), row.initiatorId(), row.kind(), status,
                row.targetProjectId(), planVersion, planJson, decision, row.sourceMembershipRevision(), row.reason(),
                nextLeader, notes, invite, expires, voteId, row.revision(), row.createdAt());
    }

    private String name(String userId) {
        return users.findById(userId).map(user -> user.displayName()).orElse("");
    }

    private String blank(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private <T> T write(AuthenticatedUser principal, String method, String path, String key, Object body,
                        Class<T> type, java.util.function.Supplier<T> action) {
        return write(principal, method, path, key, body, type,
                () -> policy.requireActiveBusinessUser(principal), action);
    }

    /**
     * 回放与新执行使用同一当前资格检查；缓存回执不能绕过撤权、回避或案件状态变化。
     */
    private <T> T write(AuthenticatedUser principal, String method, String path, String key, Object body,
                        Class<T> type, Runnable authorizationCheck,
                        java.util.function.Supplier<T> action) {
        return idempotency.execute(new IdempotencyRequest(principal, method, path, key, body, List.of()), type,
                authorizationCheck, action);
    }

    private <T> T write(AuthenticatedUser principal, String method, String path, String key, Object body,
                        Class<T> type, Runnable authorizationCheck, java.util.function.Supplier<T> action,
                        ReplayProjector<T> replayProjector) {
        return idempotency.execute(new IdempotencyRequest(principal, method, path, key, body, List.of()), type,
                authorizationCheck, action, replayProjector);
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
}
