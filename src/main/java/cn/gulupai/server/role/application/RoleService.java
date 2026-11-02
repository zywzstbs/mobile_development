package cn.gulupai.server.role.application;

import cn.gulupai.server.common.api.ApiCode;
import cn.gulupai.server.common.api.ApiException;
import cn.gulupai.server.common.audit.AuditService;
import cn.gulupai.server.common.idempotency.IdempotencyRequest;
import cn.gulupai.server.common.idempotency.IdempotencyService;
import cn.gulupai.server.common.security.AuthenticatedUser;
import cn.gulupai.server.common.security.CurrentUserPolicy;
import cn.gulupai.server.course.persistence.SquareRepository;
import cn.gulupai.server.course.persistence.SquareRow;
import cn.gulupai.server.role.api.RoleRequests;
import cn.gulupai.server.role.api.RoleViews;
import cn.gulupai.server.role.persistence.RoleRepository;
import cn.gulupai.server.role.persistence.RoleRepository.AckRow;
import cn.gulupai.server.role.persistence.RoleRepository.MemberLock;
import cn.gulupai.server.role.persistence.RoleRepository.NegotiationRow;
import cn.gulupai.server.team.api.TeamViews;
import cn.gulupai.server.team.application.TeamService;
import cn.gulupai.server.team.persistence.ProjectMemberRow;
import cn.gulupai.server.team.persistence.ProjectRow;
import cn.gulupai.server.team.persistence.RoleRow;
import cn.gulupai.server.team.persistence.TeamRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 岗位定义与协商。协商只改 membership.role_slot_id，不写任务、份额或成绩。
 * 锁顺序：project，再 membership id 升序，再 negotiation。
 */
@Service
public class RoleService {

    static final String PLAN = "PLAN";
    static final String PROPOSAL = "PROPOSAL";
    static final String SWAP = "SWAP";
    static final String READY = "READY";
    static final String PROJECT_NOT_EDITABLE = "PROJECT_NOT_EDITABLE";
    static final String LEADER_CHANGED = "LEADER_CHANGED";
    static final String MEMBERSHIP_CHANGED = "MEMBERSHIP_CHANGED";
    static final String ROLE_CHANGED = "ROLE_CHANGED";
    static final String ROLE_CAPACITY_CONFLICT = "ROLE_CAPACITY_CONFLICT";

    private final RoleRepository roleRepo;
    private final TeamRepository teamRepo;
    private final TeamService teamService;
    private final SquareRepository squareRepository;
    private final CurrentUserPolicy policy;
    private final IdempotencyService idempotencyService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public RoleService(RoleRepository roleRepo, TeamRepository teamRepo, TeamService teamService,
                       SquareRepository squareRepository, CurrentUserPolicy policy,
                       IdempotencyService idempotencyService, AuditService auditService,
                       ObjectMapper objectMapper, Clock clock) {
        this.roleRepo = roleRepo;
        this.teamRepo = teamRepo;
        this.teamService = teamService;
        this.squareRepository = squareRepository;
        this.policy = policy;
        this.idempotencyService = idempotencyService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<RoleViews.PlanView> myPlans(AuthenticatedUser principal) {
        policy.requireStudent(principal);
        return roleRepo.negotiationsForUser(principal.userId(), PLAN).stream()
                .map(row -> planView(row, principal)).toList();
    }

    @Transactional(readOnly = true)
    public List<RoleViews.ProposalView> myProposals(AuthenticatedUser principal) {
        policy.requireStudent(principal);
        return roleRepo.negotiationsForUser(principal.userId(), PROPOSAL).stream()
                .map(row -> proposalView(row, principal)).toList();
    }

    @Transactional(readOnly = true)
    public List<RoleViews.SwapView> mySwaps(AuthenticatedUser principal) {
        policy.requireStudent(principal);
        return roleRepo.negotiationsForUser(principal.userId(), SWAP).stream()
                .map(row -> swapView(row, principal)).toList();
    }

    @Transactional(readOnly = true)
    public List<RoleViews.PlanView> projectPlans(AuthenticatedUser principal, String projectId) {
        return projectNegotiations(principal, projectId, PLAN).stream()
                .map(row -> planView(row, principal)).toList();
    }

    @Transactional(readOnly = true)
    public List<RoleViews.ProposalView> projectProposals(AuthenticatedUser principal, String projectId) {
        return projectNegotiations(principal, projectId, PROPOSAL).stream()
                .map(row -> proposalView(row, principal)).toList();
    }

    @Transactional(readOnly = true)
    public List<RoleViews.SwapView> projectSwaps(AuthenticatedUser principal, String projectId) {
        return projectNegotiations(principal, projectId, SWAP).stream()
                .map(row -> swapView(row, principal)).toList();
    }

    @Transactional(readOnly = true)
    public RoleViews.RoleSlotView roleSlots(AuthenticatedUser principal, String projectId) {
        policy.requireStudent(principal);
        ProjectRow project = requireVisibleProject(principal, projectId);
        requireMember(projectId, principal.userId());
        return slotView(principal, project);
    }

    @Transactional(readOnly = true)
    public RoleViews.PlanView plan(AuthenticatedUser principal, String planId) {
        return planView(requireReadable(principal, planId, PLAN), principal);
    }

    @Transactional(readOnly = true)
    public RoleViews.ProposalView proposal(AuthenticatedUser principal, String proposalId) {
        return proposalView(requireReadable(principal, proposalId, PROPOSAL), principal);
    }

    @Transactional(readOnly = true)
    public RoleViews.SwapView swap(AuthenticatedUser principal, String swapId) {
        return swapView(requireReadable(principal, swapId, SWAP), principal);
    }

    public RoleViews.RoleSlotView replaceSlots(AuthenticatedUser principal, String projectId,
                                               RoleRequests.PutRoleSlotsRequest request,
                                               String idempotencyKey, String path) {
        policy.requireStudent(principal);
        return idempotencyService.execute(
                new IdempotencyRequest(principal, "PUT", path, idempotencyKey, request, List.of()),
                RoleViews.RoleSlotView.class,
                () -> policy.requireStudent(principal),
                () -> replaceSlotsOnce(principal, projectId, request));
    }

    public TeamViews.ProjectView selectMyRole(AuthenticatedUser principal, String projectId,
                                              RoleRequests.SelectRoleRequest request,
                                              String idempotencyKey, String path) {
        policy.requireStudent(principal);
        return idempotencyService.execute(
                new IdempotencyRequest(principal, "PUT", path, idempotencyKey, request, List.of()),
                TeamViews.ProjectView.class,
                () -> policy.requireStudent(principal),
                () -> selectMyRoleOnce(principal, projectId, request));
    }

    public RoleViews.PlanView createPlan(AuthenticatedUser principal, String projectId,
                                         RoleRequests.CreatePlanRequest request,
                                         String idempotencyKey, String path) {
        policy.requireStudent(principal);
        return idempotencyService.execute(
                new IdempotencyRequest(principal, "POST", path, idempotencyKey, request, List.of()),
                RoleViews.PlanView.class,
                () -> policy.requireStudent(principal),
                () -> createPlanOnce(principal, projectId, request));
    }

    public RoleViews.ProposalView createProposal(AuthenticatedUser principal, String projectId,
                                                 RoleRequests.CreateProposalRequest request,
                                                 String idempotencyKey, String path) {
        policy.requireStudent(principal);
        return idempotencyService.execute(
                new IdempotencyRequest(principal, "POST", path, idempotencyKey, request, List.of()),
                RoleViews.ProposalView.class,
                () -> policy.requireStudent(principal),
                () -> createProposalOnce(principal, projectId, request));
    }

    public RoleViews.SwapView createSwap(AuthenticatedUser principal, String projectId,
                                         RoleRequests.CreateSwapRequest request,
                                         String idempotencyKey, String path) {
        policy.requireStudent(principal);
        return idempotencyService.execute(
                new IdempotencyRequest(principal, "POST", path, idempotencyKey, request, List.of()),
                RoleViews.SwapView.class,
                () -> policy.requireStudent(principal),
                () -> createSwapOnce(principal, projectId, request));
    }

    public RoleViews.PlanView respondPlan(AuthenticatedUser principal, String planId,
                                          RoleRequests.PlanResponseRequest request,
                                          String idempotencyKey, String path) {
        return respond(principal, planId, PLAN, request.expectedRevision(), request.decision(),
                blankToEmpty(request.comment()), request.expectedProjectRevision(), idempotencyKey, path,
                RoleViews.PlanView.class, row -> planView(row, principal));
    }

    public RoleViews.ProposalView respondProposal(AuthenticatedUser principal, String proposalId,
                                                  RoleRequests.ProposalResponseRequest request,
                                                  String idempotencyKey, String path) {
        return respond(principal, proposalId, PROPOSAL, request.expectedRevision(), request.decision(),
                blankToEmpty(request.reason()), request.expectedProjectRevision(), idempotencyKey, path,
                RoleViews.ProposalView.class, row -> proposalView(row, principal));
    }

    public RoleViews.SwapView respondSwap(AuthenticatedUser principal, String swapId,
                                          RoleRequests.PlanResponseRequest request,
                                          String idempotencyKey, String path) {
        return respond(principal, swapId, SWAP, request.expectedRevision(), request.decision(),
                blankToEmpty(request.comment()), request.expectedProjectRevision(), idempotencyKey, path,
                RoleViews.SwapView.class, row -> swapView(row, principal));
    }

    public RoleViews.PlanView withdrawPlan(AuthenticatedUser principal, String planId,
                                           RoleRequests.WithdrawRequest request,
                                           String idempotencyKey, String path) {
        return withdraw(principal, planId, PLAN, request, idempotencyKey, path,
                RoleViews.PlanView.class, row -> planView(row, principal));
    }

    public RoleViews.ProposalView withdrawProposal(AuthenticatedUser principal, String proposalId,
                                                   RoleRequests.WithdrawRequest request,
                                                   String idempotencyKey, String path) {
        return withdraw(principal, proposalId, PROPOSAL, request, idempotencyKey, path,
                RoleViews.ProposalView.class, row -> proposalView(row, principal));
    }

    public RoleViews.SwapView withdrawSwap(AuthenticatedUser principal, String swapId,
                                           RoleRequests.WithdrawRequest request,
                                           String idempotencyKey, String path) {
        return withdraw(principal, swapId, SWAP, request, idempotencyKey, path,
                RoleViews.SwapView.class, row -> swapView(row, principal));
    }

    private RoleViews.RoleSlotView replaceSlotsOnce(AuthenticatedUser principal, String projectId,
                                                    RoleRequests.PutRoleSlotsRequest request) {
        ProjectRow locked = teamRepo.lockProject(projectId).orElseThrow(ApiException::notFound);
        requireSameSchool(principal, locked);
        requireLeader(locked, principal.userId());
        requireActive(locked);
        if (locked.revision() != request.expectedRevision()) {
            throw new ApiException(ApiCode.RESOURCE_CHANGED);
        }
        boolean upgrade = "QUICK".equals(locked.mode());
        if (upgrade) {
            if (request.completeDetails() == null) {
                throw ApiException.validation("快速转详细必须同时提供completeDetails和leaderRoleKey");
            }
            if (request.leaderRoleKey() == null || request.leaderRoleKey().isBlank()) {
                throw ApiException.validation("快速转详细必须提供leaderRoleKey");
            }
        } else if (!"DETAILED".equals(locked.mode())) {
            throw ApiException.validation("当前项目模式不能维护岗位");
        } else {
            if (request.completeDetails() != null) {
                throw ApiException.validation("详细项目修改岗位不能传completeDetails");
            }
            if (request.leaderRoleKey() != null && !request.leaderRoleKey().isBlank()) {
                throw ApiException.validation("详细项目修改岗位不能传leaderRoleKey");
            }
        }
        List<PreparedRole> prepared = prepareRoles(locked, request.roles());
        int sum = prepared.stream().mapToInt(PreparedRole::total).sum();
        requireCapacitySum(locked, sum);
        final String leaderRoleId = upgrade
                ? resolveLeaderRole(prepared, request.leaderRoleKey().strip()) : null;
        if (upgrade) {
            PreparedRole leaderRole = prepared.stream().filter(role -> role.id().equals(leaderRoleId))
                    .findFirst().orElseThrow();
            if (leaderRole.total() < 1) {
                throw ApiException.validation("组长岗位至少保留1个席位");
            }
        }
        persistRoles(locked, prepared);
        if (upgrade) {
            if (roleRepo.upgradeToDetailed(projectId, request.expectedRevision(),
                    request.completeDetails().name().strip(), request.completeDetails().intro().strip(),
                    sum) != 1) {
                throw new ApiException(ApiCode.RESOURCE_CHANGED);
            }
            ProjectMemberRow leader = teamRepo.membership(projectId, principal.userId())
                    .orElseThrow(() -> new ApiException(ApiCode.FORBIDDEN));
            MemberLock lockedLeader = roleRepo.lockMember(leader.id()).orElseThrow(ApiException::notFound);
            if (roleRepo.updateMemberRole(lockedLeader.id(), lockedLeader.revision(), leaderRoleId) != 1) {
                throw new ApiException(ApiCode.SOURCE_CHANGED);
            }
        } else if (roleRepo.updateCapacity(projectId, request.expectedRevision(), sum) != 1) {
            throw new ApiException(ApiCode.RESOURCE_CHANGED);
        }
        auditService.record(principal.userId(), "ROLE_SLOTS_REPLACE", "PROJECT", projectId,
                request.expectedRevision(), request.expectedRevision() + 1, null);
        return slotView(principal, teamRepo.findProject(projectId).orElseThrow());
    }

    private TeamViews.ProjectView selectMyRoleOnce(AuthenticatedUser principal, String projectId,
                                                   RoleRequests.SelectRoleRequest request) {
        ProjectRow locked = teamRepo.lockProject(projectId).orElseThrow(ApiException::notFound);
        requireSameSchool(principal, locked);
        requireActive(locked);
        if (!"DETAILED".equals(locked.mode())) {
            throw ApiException.validation("快速项目不能选择岗位");
        }
        if (request.roleSlotId() == null || request.roleSlotId().isBlank()) {
            throw ApiException.validation("必须提供roleSlotId");
        }
        if (locked.revision() != request.expectedProjectRevision()) {
            throw new ApiException(ApiCode.RESOURCE_CHANGED);
        }
        ProjectMemberRow mine = teamRepo.membership(projectId, principal.userId())
                .orElseThrow(() -> new ApiException(ApiCode.FORBIDDEN));
        MemberLock member = roleRepo.lockMember(mine.id()).orElseThrow(ApiException::notFound);
        if (member.revision() != request.expectedRevision() || !"ACTIVE".equals(member.status())) {
            throw new ApiException(ApiCode.RESOURCE_CHANGED);
        }
        RoleRow role = teamRepo.role(projectId, request.roleSlotId())
                .orElseThrow(() -> ApiException.validation("岗位不属于当前项目"));
        if (role.id().equals(member.roleSlotId())) {
            return teamService.project(principal, projectId);
        }
        int occupied = teamRepo.roleFilled(projectId, role.id())
                + teamRepo.roleReserved(projectId, role.id(), clock.instant());
        if (occupied >= role.total()) {
            throw new ApiException(ApiCode.ROLE_FULL);
        }
        if (roleRepo.updateMemberRole(member.id(), member.revision(), role.id()) != 1) {
            throw new ApiException(ApiCode.SOURCE_CHANGED);
        }
        teamRepo.bumpRevision(projectId);
        auditService.record(principal.userId(), "ROLE_SELF_SELECT", "MEMBERSHIP", member.id(),
                member.revision(), member.revision() + 1, null);
        return teamService.project(principal, projectId);
    }

    private RoleViews.PlanView createPlanOnce(AuthenticatedUser principal, String projectId,
                                              RoleRequests.CreatePlanRequest request) {
        ProjectRow locked = lockNegotiableProject(principal, projectId, request.expectedRevision());
        lockMembers(request.assignments().stream().map(RoleRequests.PlanAssignment::membershipId).toList());
        List<FrozenParty> parties = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (RoleRequests.PlanAssignment assignment : request.assignments()) {
            if (!seen.add(assignment.membershipId())) {
                throw ApiException.validation("调岗计划不能重复包含同一成员");
            }
            parties.add(freezeParty(locked, assignment.membershipId(),
                    assignment.expectedMembershipRevision(), assignment.targetRoleSlotId()));
        }
        ensureCapacity(locked, parties);
        NegotiationRow row = insertNegotiation(locked, principal, PLAN, parties,
                blankToEmpty(request.reason()), "");
        auditService.record(principal.userId(), "ROLE_PLAN_CREATE", "ROLE_PLAN", row.id(), null, 1, null);
        return planView(row, principal);
    }

    private RoleViews.ProposalView createProposalOnce(AuthenticatedUser principal, String projectId,
                                                      RoleRequests.CreateProposalRequest request) {
        ProjectRow locked = lockNegotiableProject(principal, projectId, request.expectedRevision());
        lockMembers(List.of(request.membershipId()));
        FrozenParty party = freezeParty(locked, request.membershipId(),
                request.expectedMembershipRevision(), request.roleSlotId());
        List<FrozenParty> parties = List.of(party);
        ensureCapacity(locked, parties);
        NegotiationRow row = insertNegotiation(locked, principal, PROPOSAL, parties, "",
                request.message().strip());
        auditService.record(principal.userId(), "ROLE_PROPOSAL_CREATE", "ROLE_PROPOSAL", row.id(),
                null, 1, null);
        return proposalView(row, principal);
    }

    private RoleViews.SwapView createSwapOnce(AuthenticatedUser principal, String projectId,
                                              RoleRequests.CreateSwapRequest request) {
        ProjectRow locked = lockNegotiableProject(principal, projectId, request.expectedRevision());
        if (request.firstMembershipId().equals(request.secondMembershipId())) {
            throw ApiException.validation("双人换岗必须是两名不同成员");
        }
        lockMembers(List.of(request.firstMembershipId(), request.secondMembershipId()));
        MemberLock first = requireFrozenMember(locked, request.firstMembershipId(),
                request.expectedFirstMembershipRevision());
        MemberLock second = requireFrozenMember(locked, request.secondMembershipId(),
                request.expectedSecondMembershipRevision());
        if (first.roleSlotId() == null || second.roleSlotId() == null
                || first.roleSlotId().equals(second.roleSlotId())) {
            throw ApiException.validation("双方必须已有且不同的岗位");
        }
        FrozenParty firstParty = freezeParty(locked, first, second.roleSlotId());
        FrozenParty secondParty = freezeParty(locked, second, first.roleSlotId());
        List<FrozenParty> parties = List.of(firstParty, secondParty);
        ensureCapacity(locked, parties);
        NegotiationRow row = insertNegotiation(locked, principal, SWAP, parties,
                blankToEmpty(request.reason()), "");
        auditService.record(principal.userId(), "ROLE_SWAP_CREATE", "ROLE_SWAP", row.id(), null, 1, null);
        return swapView(row, principal);
    }

    private <T> T respond(AuthenticatedUser principal, String id, String kind, int expectedRevision,
                          String decision, String note, int expectedProjectRevision,
                          String idempotencyKey, String path, Class<T> type, java.util.function.Function<NegotiationRow, T> view) {
        policy.requireStudent(principal);
        return idempotencyService.execute(
                new IdempotencyRequest(principal, "POST", path, idempotencyKey,
                        new ResponseBody(expectedRevision, decision, note, expectedProjectRevision), List.of()),
                type,
                () -> policy.requireStudent(principal),
                () -> {
                    NegotiationRow current = roleRepo.findNegotiation(id).orElseThrow(ApiException::notFound);
                    if (!kind.equals(current.kind())) {
                        throw ApiException.notFound();
                    }
                    ProjectRow project = teamRepo.lockProject(current.projectId()).orElseThrow(ApiException::notFound);
                    requireSameSchool(principal, project);
                    if (project.revision() != expectedProjectRevision) {
                        throw new ApiException(ApiCode.RESOURCE_CHANGED);
                    }
                    FrozenPlan frozen = readPlan(current);
                    lockParties(frozen);
                    NegotiationRow locked = roleRepo.lockNegotiation(id).orElseThrow(ApiException::notFound);
                    if (!"PENDING".equals(locked.status()) || locked.revision() != expectedRevision) {
                        throw new ApiException(ApiCode.RESOURCE_CHANGED);
                    }
                    FrozenPlan plan = readPlan(locked);
                    FrozenParty party = plan.parties().stream()
                            .filter(item -> item.userId().equals(principal.userId()))
                            .findFirst().orElse(null);
                    if (party == null) {
                        throw new ApiException(ApiCode.FORBIDDEN);
                    }
                    if (roleRepo.ack(locked.id(), principal.userId()).isPresent()) {
                        throw new ApiException(ApiCode.RESOURCE_CHANGED);
                    }
                    Instant now = clock.instant();
                    if ("DECLINE".equals(decision)) {
                        roleRepo.insertAck(UUID.randomUUID().toString(), locked.id(), principal.userId(),
                                "DECLINE", note, now);
                        if (roleRepo.finishNegotiation(locked.id(), expectedRevision, "DECLINED", now) != 1) {
                            throw new ApiException(ApiCode.RESOURCE_CHANGED);
                        }
                        auditService.record(principal.userId(), "ROLE_NEGOTIATION_DECLINE", "ROLE_NEGOTIATION",
                                locked.id(), expectedRevision, expectedRevision + 1, note);
                        return view.apply(roleRepo.findNegotiation(locked.id()).orElseThrow());
                    }
                    String availability = availability(project, locked, plan);
                    if (!READY.equals(availability)) {
                        throw unavailable(availability);
                    }
                    roleRepo.insertAck(UUID.randomUUID().toString(), locked.id(), principal.userId(),
                            "ACCEPT", note, now);
                    long accepts = roleRepo.acks(locked.id()).stream()
                            .filter(ack -> "ACCEPT".equals(ack.decision())).count();
                    if (accepts == plan.parties().size()) {
                        applyParties(plan);
                        if (roleRepo.finishNegotiation(locked.id(), expectedRevision, "APPLIED", now) != 1) {
                            throw new ApiException(ApiCode.RESOURCE_CHANGED);
                        }
                        teamRepo.bumpRevision(project.id());
                        auditService.record(principal.userId(), "ROLE_NEGOTIATION_APPLY", "ROLE_NEGOTIATION",
                                locked.id(), expectedRevision, expectedRevision + 1, null);
                    }
                    return view.apply(roleRepo.findNegotiation(locked.id()).orElseThrow());
                });
    }

    private <T> T withdraw(AuthenticatedUser principal, String id, String kind,
                           RoleRequests.WithdrawRequest request, String idempotencyKey, String path,
                           Class<T> type, java.util.function.Function<NegotiationRow, T> view) {
        policy.requireStudent(principal);
        return idempotencyService.execute(
                new IdempotencyRequest(principal, "POST", path, idempotencyKey, request, List.of()),
                type,
                () -> policy.requireStudent(principal),
                () -> {
                    NegotiationRow current = roleRepo.findNegotiation(id).orElseThrow(ApiException::notFound);
                    if (!kind.equals(current.kind())) {
                        throw ApiException.notFound();
                    }
                    ProjectRow project = teamRepo.lockProject(current.projectId()).orElseThrow(ApiException::notFound);
                    requireSameSchool(principal, project);
                    requireLeader(project, principal.userId());
                    NegotiationRow locked = roleRepo.lockNegotiation(id).orElseThrow(ApiException::notFound);
                    if (!"PENDING".equals(locked.status()) || locked.revision() != request.expectedRevision()) {
                        throw new ApiException(ApiCode.RESOURCE_CHANGED);
                    }
                    Instant now = clock.instant();
                    if (roleRepo.finishNegotiation(locked.id(), request.expectedRevision(), "WITHDRAWN", now) != 1) {
                        throw new ApiException(ApiCode.RESOURCE_CHANGED);
                    }
                    auditService.record(principal.userId(), "ROLE_NEGOTIATION_WITHDRAW", "ROLE_NEGOTIATION",
                            locked.id(), request.expectedRevision(), request.expectedRevision() + 1,
                            request.reason().strip());
                    return view.apply(roleRepo.findNegotiation(locked.id()).orElseThrow());
                });
    }

    private void applyParties(FrozenPlan plan) {
        List<FrozenParty> ordered = plan.parties().stream()
                .sorted(Comparator.comparing(FrozenParty::membershipId)).toList();
        for (FrozenParty party : ordered) {
            if (roleRepo.updateMemberRole(party.membershipId(), party.membershipRevision(),
                    party.targetRole().id()) != 1) {
                throw new ApiException(ApiCode.SOURCE_CHANGED);
            }
        }
    }

    private void lockParties(FrozenPlan plan) {
        plan.parties().stream()
                .map(FrozenParty::membershipId)
                .distinct()
                .sorted()
                .forEach(id -> roleRepo.lockMember(id).orElseThrow(ApiException::notFound));
    }

    private ProjectRow lockNegotiableProject(AuthenticatedUser principal, String projectId, int expectedRevision) {
        ProjectRow locked = teamRepo.lockProject(projectId).orElseThrow(ApiException::notFound);
        requireSameSchool(principal, locked);
        requireLeader(locked, principal.userId());
        requireActive(locked);
        if (!"DETAILED".equals(locked.mode())) {
            throw ApiException.validation("快速项目不能发起岗位协商");
        }
        if (locked.revision() != expectedRevision) {
            throw new ApiException(ApiCode.RESOURCE_CHANGED);
        }
        return locked;
    }

    private FrozenParty freezeParty(ProjectRow project, String membershipId, int expectedRevision, String targetRoleId) {
        MemberLock member = requireFrozenMember(project, membershipId, expectedRevision);
        return freezeParty(project, member, targetRoleId);
    }

    private FrozenParty freezeParty(ProjectRow project, MemberLock member, String targetRoleId) {
        if (member.roleSlotId() != null && member.roleSlotId().equals(targetRoleId)) {
            throw ApiException.validation("目标岗位与当前岗位相同");
        }
        RoleRow target = teamRepo.role(project.id(), targetRoleId)
                .orElseThrow(() -> ApiException.validation("目标岗位不属于当前项目"));
        RoleViews.RoleSnapshot original = member.roleSlotId() == null ? null
                : snapshot(project.id(), teamRepo.role(project.id(), member.roleSlotId())
                .orElseThrow(() -> ApiException.validation("当前岗位已不存在")));
        return new FrozenParty(member.userId(), member.displayName(), member.id(), member.revision(),
                original, snapshot(project.id(), target));
    }

    private void lockMembers(List<String> ids) {
        ids.stream().distinct().sorted().forEach(id ->
                roleRepo.lockMember(id).orElseThrow(ApiException::notFound));
    }

    private MemberLock requireFrozenMember(ProjectRow project, String membershipId, int expectedRevision) {
        MemberLock member = roleRepo.findMember(membershipId).orElseThrow(ApiException::notFound);
        if (!project.id().equals(member.projectId()) || !"ACTIVE".equals(member.status())) {
            throw ApiException.validation("成员不属于当前项目");
        }
        if (member.revision() != expectedRevision) {
            throw new ApiException(ApiCode.RESOURCE_CHANGED);
        }
        return member;
    }

    private void ensureCapacity(ProjectRow project, List<FrozenParty> parties) {
        if (ROLE_CAPACITY_CONFLICT.equals(simulate(project, parties))) {
            throw new ApiException(ApiCode.ROLE_FULL);
        }
    }

    private NegotiationRow insertNegotiation(ProjectRow project, AuthenticatedUser principal, String kind,
                                            List<FrozenParty> parties, String reason, String message) {
        Instant now = clock.instant();
        String id = UUID.randomUUID().toString();
        String json = writePlan(new FrozenPlan(parties));
        NegotiationRow row = new NegotiationRow(id, project.id(), kind, "PENDING", project.revision(),
                project.ownerId(), principal.userId(), json, reason, message, 1, now);
        roleRepo.insertNegotiation(row);
        return row;
    }

    private List<PreparedRole> prepareRoles(ProjectRow project, List<RoleRequests.RoleInput> inputs) {
        Map<String, RoleRow> existing = new LinkedHashMap<>();
        for (RoleRow role : teamRepo.roles(project.id())) {
            existing.put(role.id(), role);
        }
        Set<String> seenIds = new HashSet<>();
        Set<String> seenKeys = new HashSet<>();
        List<PreparedRole> prepared = new ArrayList<>();
        int index = 0;
        for (RoleRequests.RoleInput input : inputs) {
            String id = input.id() == null ? "" : input.id().strip();
            String clientKey = input.clientKey() == null ? "" : input.clientKey().strip();
            if (!clientKey.isEmpty() && !seenKeys.add(clientKey)) {
                throw ApiException.validation("同一次请求的clientKey不能重复");
            }
            boolean created;
            String roleId;
            if (!id.isEmpty()) {
                if (!existing.containsKey(id)) {
                    throw ApiException.validation("非空岗位id必须是当前项目已有岗位，不能当作新增");
                }
                if (!seenIds.add(id)) {
                    throw ApiException.validation("岗位id重复");
                }
                if (!clientKey.isEmpty() && existing.containsKey(clientKey) && !clientKey.equals(id)) {
                    throw ApiException.validation("clientKey不能覆盖其他岗位身份");
                }
                roleId = id;
                created = false;
            } else {
                if (clientKey.isEmpty()) {
                    throw ApiException.validation("新增岗位必须提供clientKey");
                }
                if (existing.containsKey(clientKey)) {
                    throw ApiException.validation("clientKey不能覆盖已有岗位身份");
                }
                roleId = UUID.randomUUID().toString();
                created = true;
            }
            if (!created) {
                int occupied = teamRepo.roleFilled(project.id(), roleId)
                        + teamRepo.roleReserved(project.id(), roleId, clock.instant());
                if (input.total() < occupied) {
                    throw ApiException.validation("岗位人数不能低于已占成员和预留");
                }
            }
            prepared.add(new PreparedRole(roleId, clientKey, input.roleName().strip(), input.duty().strip(),
                    input.total(), index, created));
            index++;
        }
        Set<String> kept = new HashSet<>();
        for (PreparedRole role : prepared) {
            if (!role.created()) {
                kept.add(role.id());
            }
        }
        for (RoleRow role : existing.values()) {
            if (!kept.contains(role.id()) && roleRepo.roleReferenced(role.id())) {
                throw ApiException.validation("不能删除仍被成员或预留引用的岗位");
            }
        }
        return prepared;
    }

    private void persistRoles(ProjectRow project, List<PreparedRole> prepared) {
        roleRepo.shiftRolePositions(project.id());
        int temp = 20_000;
        for (PreparedRole role : prepared) {
            if (role.created()) {
                roleRepo.insertRole(role.id(), project.id(), role.roleName(), role.duty(), role.total(), temp);
            } else {
                roleRepo.updateRole(role.id(), project.id(), role.roleName(), role.duty(), role.total(), temp);
            }
            temp++;
        }
        Set<String> kept = new HashSet<>();
        for (PreparedRole role : prepared) {
            kept.add(role.id());
            roleRepo.updateRole(role.id(), project.id(), role.roleName(), role.duty(), role.total(), role.position());
        }
        for (RoleRow role : teamRepo.roles(project.id())) {
            if (!kept.contains(role.id())) {
                roleRepo.deleteRole(role.id(), project.id());
            }
        }
    }

    private void requireCapacitySum(ProjectRow project, int sum) {
        TeamRepository.AssignmentBrief assignment = teamRepo.findAssignment(project.assignmentId());
        if (assignment == null || sum < assignment.minMembers() || sum > assignment.maxMembers()) {
            throw ApiException.validation("岗位总人数必须位于作业人数范围");
        }
        int occupied = teamRepo.memberCount(project.id()) + teamRepo.reservedCount(project.id(), clock.instant());
        if (sum < occupied) {
            throw ApiException.validation("容量不能低于当前成员和有效预留");
        }
    }

    private String resolveLeaderRole(List<PreparedRole> prepared, String leaderRoleKey) {
        String match = null;
        for (PreparedRole role : prepared) {
            if (leaderRoleKey.equals(role.clientKey()) || leaderRoleKey.equals(role.id())) {
                if (match != null && !match.equals(role.id())) {
                    throw ApiException.validation("leaderRoleKey指向不唯一");
                }
                match = role.id();
            }
        }
        if (match == null) {
            throw ApiException.validation("leaderRoleKey没有指向本次岗位");
        }
        return match;
    }

    private String availability(ProjectRow project, NegotiationRow row, FrozenPlan plan) {
        if (!"PENDING".equals(row.status())) {
            return READY;
        }
        if (!"ACTIVE".equals(project.status())) {
            return PROJECT_NOT_EDITABLE;
        }
        if (!project.ownerId().equals(row.leaderId())) {
            return LEADER_CHANGED;
        }
        for (FrozenParty party : plan.parties()) {
            MemberLock member = roleRepo.findMember(party.membershipId()).orElse(null);
            if (member == null || !project.id().equals(member.projectId())
                    || !"ACTIVE".equals(member.status())
                    || member.revision() != party.membershipRevision()
                    || !party.userId().equals(member.userId())) {
                return MEMBERSHIP_CHANGED;
            }
            String originalId = party.originalRole() == null ? null : party.originalRole().id();
            if (originalId == null ? member.roleSlotId() != null : !originalId.equals(member.roleSlotId())) {
                return ROLE_CHANGED;
            }
            if (teamRepo.role(project.id(), party.targetRole().id()).isEmpty()) {
                return ROLE_CHANGED;
            }
        }
        return simulate(project, plan.parties());
    }

    private String simulate(ProjectRow project, List<FrozenParty> parties) {
        Map<String, Integer> filled = new HashMap<>();
        Map<String, Integer> reserved = new HashMap<>();
        Map<String, Integer> totals = new HashMap<>();
        for (RoleRow role : teamRepo.roles(project.id())) {
            filled.put(role.id(), teamRepo.roleFilled(project.id(), role.id()));
            reserved.put(role.id(), teamRepo.roleReserved(project.id(), role.id(), clock.instant()));
            totals.put(role.id(), role.total());
        }
        for (FrozenParty party : parties) {
            if (party.originalRole() != null) {
                filled.merge(party.originalRole().id(), -1, Integer::sum);
            }
            filled.merge(party.targetRole().id(), 1, Integer::sum);
        }
        for (Map.Entry<String, Integer> entry : totals.entrySet()) {
            int occupied = filled.getOrDefault(entry.getKey(), 0) + reserved.getOrDefault(entry.getKey(), 0);
            if (occupied > entry.getValue()) {
                return ROLE_CAPACITY_CONFLICT;
            }
        }
        return READY;
    }

    private ApiException unavailable(String availability) {
        return switch (availability) {
            case ROLE_CAPACITY_CONFLICT -> new ApiException(ApiCode.ROLE_FULL);
            case PROJECT_NOT_EDITABLE -> new ApiException(ApiCode.PROJECT_READ_ONLY);
            default -> new ApiException(ApiCode.SOURCE_CHANGED);
        };
    }

    private List<NegotiationRow> projectNegotiations(AuthenticatedUser principal, String projectId, String kind) {
        policy.requireStudent(principal);
        ProjectRow project = requireVisibleProject(principal, projectId);
        requireMember(projectId, principal.userId());
        boolean leader = project.ownerId().equals(principal.userId());
        return roleRepo.negotiationsForProject(projectId, kind).stream()
                .filter(row -> leader || involves(row, principal.userId()))
                .toList();
    }

    private NegotiationRow requireReadable(AuthenticatedUser principal, String id, String kind) {
        policy.requireStudent(principal);
        NegotiationRow row = roleRepo.findNegotiation(id).orElseThrow(ApiException::notFound);
        if (!kind.equals(row.kind())) {
            throw ApiException.notFound();
        }
        ProjectRow project = requireVisibleProject(principal, row.projectId());
        boolean leader = project.ownerId().equals(principal.userId());
        if (!leader && !involves(row, principal.userId())) {
            throw new ApiException(ApiCode.FORBIDDEN);
        }
        return row;
    }

    private boolean involves(NegotiationRow row, String userId) {
        if (userId.equals(row.initiatorId())) {
            return true;
        }
        return readPlan(row).parties().stream().anyMatch(party -> userId.equals(party.userId()));
    }

    private RoleViews.PlanView planView(NegotiationRow row, AuthenticatedUser principal) {
        ProjectRow project = teamRepo.findProject(row.projectId()).orElseThrow(ApiException::notFound);
        FrozenPlan plan = readPlan(row);
        String availability = availability(project, row, plan);
        boolean pending = "PENDING".equals(row.status());
        boolean party = plan.parties().stream().anyMatch(item -> item.userId().equals(principal.userId()));
        boolean confirmed = roleRepo.ack(row.id(), principal.userId())
                .map(ack -> "ACCEPT".equals(ack.decision())).orElse(false);
        RoleViews.Plan body = new RoleViews.Plan(row.id(), row.projectId(), displayName(project),
                row.initiatorId(), row.reason(), parties(plan), confirmations(row.id()), row.status(),
                row.revision(), row.createdAt());
        return new RoleViews.PlanView(body, project.revision(), availability, confirmed,
                pending && party && !confirmed && READY.equals(availability),
                pending && party && roleRepo.ack(row.id(), principal.userId()).isEmpty(),
                pending && project.ownerId().equals(principal.userId()),
                teamRepo.membership(project.id(), principal.userId()).isPresent());
    }

    private RoleViews.SwapView swapView(NegotiationRow row, AuthenticatedUser principal) {
        RoleViews.PlanView plan = planView(row, principal);
        RoleViews.Swap swap = new RoleViews.Swap(plan.plan().id(), plan.plan().projectId(),
                plan.plan().projectName(), plan.plan().authorId(), plan.plan().reason(),
                plan.plan().parties(), plan.plan().confirmations(), plan.plan().status(),
                plan.plan().revision(), plan.plan().createdAt());
        return new RoleViews.SwapView(swap, plan.projectRevision(), plan.availability(), plan.myConfirmed(),
                plan.canConfirm(), plan.canDecline(), plan.canWithdraw(), plan.canOpenProject());
    }

    private RoleViews.ProposalView proposalView(NegotiationRow row, AuthenticatedUser principal) {
        ProjectRow project = teamRepo.findProject(row.projectId()).orElseThrow(ApiException::notFound);
        FrozenPlan plan = readPlan(row);
        FrozenParty party = plan.parties().getFirst();
        String availability = availability(project, row, plan);
        boolean pending = "PENDING".equals(row.status());
        boolean recipient = party.userId().equals(principal.userId());
        boolean responded = roleRepo.ack(row.id(), principal.userId()).isPresent();
        RoleViews.Proposal body = new RoleViews.Proposal(row.id(), row.projectId(), displayName(project),
                row.initiatorId(), party.userId(), party.membershipId(), party.targetRole(), row.message(),
                row.status(), row.revision(), row.createdAt());
        MemberLock live = roleRepo.findMember(party.membershipId()).orElse(null);
        Object currentRole = live == null || live.roleSlotId() == null
                ? Map.of() : snapshot(project.id(), teamRepo.role(project.id(), live.roleSlotId()).orElse(null));
        if (currentRole == null) {
            currentRole = Map.of();
        }
        return new RoleViews.ProposalView(body, project.revision(), availability, currentRole,
                pending && recipient && !responded && READY.equals(availability),
                pending && recipient && !responded,
                pending && project.ownerId().equals(principal.userId()),
                teamRepo.membership(project.id(), principal.userId()).isPresent());
    }

    private List<RoleViews.Party> parties(FrozenPlan plan) {
        List<RoleViews.Party> parties = new ArrayList<>();
        for (FrozenParty party : plan.parties()) {
            parties.add(new RoleViews.Party(party.userId(), party.displayName(), party.membershipId(),
                    party.membershipRevision(), party.originalRole() == null ? Map.of() : party.originalRole(),
                    party.targetRole()));
        }
        return parties;
    }

    private List<RoleViews.Confirmation> confirmations(String negotiationId) {
        List<RoleViews.Confirmation> confirmations = new ArrayList<>();
        for (AckRow ack : roleRepo.acks(negotiationId)) {
            confirmations.add(new RoleViews.Confirmation(ack.userId(), ack.decision(), ack.at()));
        }
        return confirmations;
    }

    private RoleViews.RoleSlotView slotView(AuthenticatedUser principal, ProjectRow project) {
        List<TeamViews.RoleView> roles = teamRepo.roles(project.id()).stream()
                .map(role -> new TeamViews.RoleView(role.id(), null, role.roleName(), role.duty(), role.total(),
                        teamRepo.roleFilled(project.id(), role.id()),
                        teamRepo.roleReserved(project.id(), role.id(), clock.instant())))
                .toList();
        List<TeamViews.MembershipView> members = teamRepo.members(project.id()).stream()
                .filter(member -> "ACTIVE".equals(member.status()))
                .map(this::membershipView).toList();
        List<RoleViews.Blocker> blockers = "ACTIVE".equals(project.status())
                ? List.of()
                : List.of(new RoleViews.Blocker(PROJECT_NOT_EDITABLE, "项目当前不可编辑"));
        return new RoleViews.RoleSlotView(teamService.project(principal, project.id()), roles, members, blockers);
    }

    private TeamViews.MembershipView membershipView(ProjectMemberRow row) {
        return new TeamViews.MembershipView(row.id(), row.id(), row.projectId(), row.userId(),
                row.displayName(), row.roleSlotId(), row.projectRole(), row.status(), row.color(),
                row.colorRevision(), row.joinedAt(), row.revision());
    }

    private RoleViews.RoleSnapshot snapshot(String projectId, RoleRow role) {
        if (role == null) {
            return null;
        }
        return new RoleViews.RoleSnapshot(role.id(), role.roleName(), role.duty(), role.total(),
                teamRepo.roleFilled(projectId, role.id()),
                teamRepo.roleReserved(projectId, role.id(), clock.instant()));
    }

    private ProjectRow requireVisibleProject(AuthenticatedUser principal, String projectId) {
        ProjectRow project = teamRepo.findProject(projectId).orElseThrow(ApiException::notFound);
        requireSameSchool(principal, project);
        return project;
    }

    private void requireSameSchool(AuthenticatedUser principal, ProjectRow project) {
        SquareRow square = squareRepository.findById(project.squareId()).orElseThrow(ApiException::notFound);
        if (!square.schoolId().equals(principal.schoolId())) {
            throw ApiException.notFound();
        }
    }

    private void requireMember(String projectId, String userId) {
        if (teamRepo.membership(projectId, userId).isEmpty()) {
            throw new ApiException(ApiCode.FORBIDDEN);
        }
    }

    private void requireLeader(ProjectRow project, String userId) {
        if (!project.ownerId().equals(userId)) {
            throw new ApiException(ApiCode.FORBIDDEN);
        }
    }

    private void requireActive(ProjectRow project) {
        if (!"ACTIVE".equals(project.status())) {
            throw new ApiException(ApiCode.PROJECT_READ_ONLY);
        }
    }

    private String displayName(ProjectRow project) {
        if (project.name() != null && !project.name().isBlank()) {
            return project.name();
        }
        return "未命名项目-" + project.id().substring(Math.max(0, project.id().length() - 6));
    }

    private String writePlan(FrozenPlan plan) {
        try {
            return objectMapper.writeValueAsString(plan);
        } catch (Exception ex) {
            throw new IllegalStateException("无法保存岗位方案", ex);
        }
    }

    private FrozenPlan readPlan(NegotiationRow row) {
        try {
            return objectMapper.readValue(row.planJson(), FrozenPlan.class);
        } catch (Exception ex) {
            throw new IllegalStateException("无法读取岗位方案", ex);
        }
    }

    private static String blankToEmpty(String value) {
        return value == null ? "" : value.strip();
    }

    private record PreparedRole(String id, String clientKey, String roleName, String duty, int total,
                                int position, boolean created) {
    }

    private record FrozenPlan(List<FrozenParty> parties) {
    }

    private record FrozenParty(String userId, String displayName, String membershipId, int membershipRevision,
                               RoleViews.RoleSnapshot originalRole, RoleViews.RoleSnapshot targetRole) {
    }

    private record ResponseBody(int expectedRevision, String decision, String note, int expectedProjectRevision) {
    }
}
