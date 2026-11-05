package cn.gulupai.server.team.application;

import cn.gulupai.server.common.api.ApiCode;
import cn.gulupai.server.common.api.ApiException;
import cn.gulupai.server.common.api.ListEnvelope;
import cn.gulupai.server.common.audit.AuditService;
import cn.gulupai.server.common.idempotency.IdempotencyCipher;
import cn.gulupai.server.common.idempotency.IdempotencyRequest;
import cn.gulupai.server.common.idempotency.IdempotencyService;
import cn.gulupai.server.common.pagination.CursorService;
import cn.gulupai.server.common.security.AuthenticatedUser;
import cn.gulupai.server.common.security.CurrentUserPolicy;
import cn.gulupai.server.common.security.Hashing;
import cn.gulupai.server.course.persistence.SquareRepository;
import cn.gulupai.server.course.persistence.SquareRow;
import cn.gulupai.server.course.persistence.VerifiedCourseRepository;
import cn.gulupai.server.identity.domain.Role;
import cn.gulupai.server.identity.persistence.UserRepository;
import cn.gulupai.server.identity.persistence.UserRow;
import cn.gulupai.server.team.api.TeamRequests;
import cn.gulupai.server.team.api.TeamViews;
import cn.gulupai.server.team.persistence.ApplicationRow;
import cn.gulupai.server.team.persistence.InvitationRow;
import cn.gulupai.server.team.persistence.ProjectCodeRow;
import cn.gulupai.server.team.persistence.ProjectMemberRow;
import cn.gulupai.server.team.persistence.ProjectRow;
import cn.gulupai.server.team.persistence.RoleRow;
import cn.gulupai.server.team.persistence.StageRow;
import cn.gulupai.server.team.persistence.TeamRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class TeamService {

    private static final String[] COLORS = {
            "#236B56", "#355F9B", "#79518C", "#9C4F2E",
            "#466E39", "#82565E", "#6A5C37", "#3F6C73"};
    private static final Duration INVITATION_TTL = Duration.ofHours(24);
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };
    private static final TypeReference<List<TeamViews.TodoView>> TODO_LIST = new TypeReference<>() {
    };

    private final TeamRepository repo;
    private final SquareRepository squareRepository;
    private final VerifiedCourseRepository verifiedCourseRepository;
    private final UserRepository userRepository;
    private final CurrentUserPolicy policy;
    private final IdempotencyService idempotencyService;
    private final IdempotencyCipher cipher;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final WorkSourceQueryService workSources;
    private final CursorService cursorService;
    private final TodoQueryService todoQuery;

    public TeamService(TeamRepository repo,
                       SquareRepository squareRepository,
                       VerifiedCourseRepository verifiedCourseRepository,
                       UserRepository userRepository,
                       CurrentUserPolicy policy,
                       IdempotencyService idempotencyService,
                       IdempotencyCipher cipher,
                       AuditService auditService,
                       ObjectMapper objectMapper,
                       Clock clock,
                       WorkSourceQueryService workSources,
                       CursorService cursorService,
                       TodoQueryService todoQuery) {
        this.repo = repo;
        this.squareRepository = squareRepository;
        this.verifiedCourseRepository = verifiedCourseRepository;
        this.userRepository = userRepository;
        this.policy = policy;
        this.idempotencyService = idempotencyService;
        this.cipher = cipher;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.workSources = workSources;
        this.cursorService = cursorService;
        this.todoQuery = todoQuery;
    }

    public TeamViews.ProjectView createProject(AuthenticatedUser principal,
                                               TeamRequests.CreateProjectRequest request,
                                               String idempotencyKey, String path) {
        policy.requireActiveBusinessUser(principal);
        policy.requireRole(principal, Role.STUDENT);
        SquareRow square = requireSquare(principal, request.squareId());
        TeamRepository.AssignmentBrief assignment = repo.findAssignment(request.assignmentId());
        if (assignment == null || !assignment.squareId().equals(square.id())) {
            throw ApiException.validation("作业与课程广场不一致");
        }
        requireQualification(principal, square);
        PreparedProject prepared = prepareProject(request, assignment);
        return idempotencyService.execute(
                new IdempotencyRequest(principal, "POST", path, idempotencyKey, request, List.of()),
                TeamViews.ProjectView.class,
                () -> policy.requireActiveBusinessUser(principal),
                () -> createProjectOnce(principal, square, assignment, prepared));
    }

    private TeamViews.ProjectView createProjectOnce(AuthenticatedUser principal, SquareRow square,
                                                    TeamRepository.AssignmentBrief assignment,
                                                    PreparedProject prepared) {
        TeamRepository.AssignmentBrief locked = repo.lockAssignment(assignment.id());
        if (locked == null || !locked.squareId().equals(square.id())) {
            throw new ApiException(ApiCode.RESOURCE_CHANGED);
        }
        if (prepared.capacity() < locked.minMembers() || prepared.capacity() > locked.maxMembers()) {
            throw new ApiException(ApiCode.RESOURCE_CHANGED);
        }
        if (repo.assignmentSlot(assignment.id(), principal.userId())) {
            throw new ApiException(ApiCode.ALREADY_IN_ASSIGNMENT_TEAM);
        }
        String projectId = UUID.randomUUID().toString();
        ProjectRow project = new ProjectRow(projectId, assignment.id(), square.id(), principal.userId(),
                prepared.mode(), prepared.name(), prepared.intro(), prepared.capacity(), "ACTIVE",
                0, 1, prepared.discussionTime(), true, "", "", 0, true, 1,
                clock.instant(), clock.instant());
        repo.insertProject(project);
        repo.insertStage(UUID.randomUUID().toString(), projectId, "默认阶段", 0);
        Map<String, String> roleIds = new LinkedHashMap<>();
        for (PreparedRole role : prepared.roles()) {
            String id = UUID.randomUUID().toString();
            repo.insertRole(id, projectId, role.roleName(), role.duty(), role.total(), role.position());
            if (role.clientKey() != null) {
                roleIds.put(role.clientKey(), id);
            }
            roleIds.put(role.roleName(), id);
        }
        String leaderRoleId = prepared.leaderRoleKey() == null ? null
                : roleIds.get(prepared.leaderRoleKey());
        String membershipId = UUID.randomUUID().toString();
        repo.insertMembership(membershipId, projectId, principal.userId(), leaderRoleId,
                "LEADER", COLORS[0], clock.instant());
        repo.insertAssignmentSlot(UUID.randomUUID().toString(), assignment.id(), principal.userId(),
                projectId, membershipId);
        String code = Hashing.newToken();
        repo.insertCode(projectId, Hashing.sha256Hex(code), cipher.encrypt(code), cipher.keyId(), 1);
        auditService.record(principal.userId(), "PROJECT_CREATE", "PROJECT", projectId, null, 1, null);
        return projectView(repo.findProject(projectId).orElseThrow(), principal);
    }

    private PreparedProject prepareProject(TeamRequests.CreateProjectRequest request,
                                           TeamRepository.AssignmentBrief assignment) {
        String mode = request.mode();
        int capacity = request.capacity();
        if (capacity < assignment.minMembers() || capacity > assignment.maxMembers()) {
            throw ApiException.validation("capacity 必须位于作业 minMembers～maxMembers");
        }
        String name = request.name() == null ? null : request.name().strip();
        String intro = request.intro() == null ? null : request.intro().strip();
        List<TeamRequests.RoleInput> roleInputs = request.roles() == null ? List.of() : request.roles();
        List<PreparedRole> roles = new ArrayList<>();
        if ("DETAILED".equals(mode)) {
            if (name == null || name.isEmpty() || intro == null || intro.isEmpty()) {
                throw ApiException.validation("DETAILED 项目必须填写name和intro");
            }
            if (roleInputs.isEmpty()) {
                throw ApiException.validation("DETAILED 项目必须提供roles");
            }
            int sum = 0;
            int position = 0;
            for (TeamRequests.RoleInput role : roleInputs) {
                if (role == null) {
                    throw ApiException.validation("roles 不能包含null");
                }
                String roleName = role.roleName().strip();
                String duty = role.duty().strip();
                if (roleName.isEmpty() || roleName.length() > 60 || duty.isEmpty()) {
                    throw ApiException.validation("岗位名称/职责无效");
                }
                int total = role.total();
                if (total < 1 || total > 20) {
                    throw ApiException.validation("岗位人数无效");
                }
                sum += total;
                roles.add(new PreparedRole(role.clientKey(), roleName, duty, total, position++));
            }
            if (sum != capacity) {
                throw ApiException.validation("DETAILED 岗位总人数必须等于capacity");
            }
            String key = request.leaderRoleKey();
            if (key == null || key.isBlank()
                    || roles.stream().noneMatch(role -> key.equals(role.clientKey())
                    || key.equals(role.roleName()))) {
                throw ApiException.validation("leaderRoleKey 必须指向本次岗位");
            }
        } else {
            if (name != null && name.length() > 120) {
                throw ApiException.validation("项目名不能超过120个字符");
            }
            if (intro != null && intro.length() > 2000) {
                throw ApiException.validation("项目简介不能超过2000个字符");
            }
        }
        return new PreparedProject(mode, name, intro == null ? "" : intro, capacity,
                request.discussionTime() == null ? "" : request.discussionTime().strip(),
                List.copyOf(roles), request.leaderRoleKey());
    }

    @Transactional(readOnly = true)
    public TeamViews.ProjectView project(AuthenticatedUser principal, String projectId) {
        policy.requireActiveBusinessUser(principal);
        ProjectRow project = requireProject(projectId);
        SquareRow square = requireSquare(principal, project.squareId());
        TeamViews.ProjectView view = projectView(project, principal);
        if (view.myMembership() == null && !project.recruitmentOpen()) {
            throw new ApiException(ApiCode.FORBIDDEN);
        }
        return view;
    }

    @Transactional(readOnly = true)
    public List<TeamViews.ProjectView> myProjects(AuthenticatedUser principal) {
        policy.requireActiveBusinessUser(principal);
        return repo.myProjects(principal.userId()).stream()
                .filter(project -> project.squareId() != null)
                .map(project -> projectView(project, principal)).toList();
    }

    @Transactional(readOnly = true)
    public List<TeamViews.MembershipView> members(AuthenticatedUser principal, String projectId) {
        policy.requireActiveBusinessUser(principal);
        ProjectRow project = requireProject(projectId);
        requireSquare(principal, project.squareId());
        if (repo.membership(projectId, principal.userId()).isEmpty()) {
            throw new ApiException(ApiCode.FORBIDDEN);
        }
        return repo.members(projectId).stream().filter(m -> "ACTIVE".equals(m.status()))
                .map(this::membershipView).toList();
    }

    public TeamViews.ProjectView patchProject(AuthenticatedUser principal, String projectId,
                                              TeamRequests.PatchProjectRequest request,
                                              String idempotencyKey, String path) {
        policy.requireActiveBusinessUser(principal);
        ProjectRow project = requireProject(projectId);
        requireLeader(project, principal.userId());
        return idempotencyService.execute(
                new IdempotencyRequest(principal, "PATCH", path, idempotencyKey, request, List.of()),
                TeamViews.ProjectView.class,
                () -> policy.requireActiveBusinessUser(principal),
                () -> {
                    ProjectRow locked = repo.lockProject(projectId).orElseThrow(ApiException::notFound);
                    if (locked.revision() != request.expectedRevision()) {
                        throw new ApiException(ApiCode.RESOURCE_CHANGED);
                    }
                    if (request.capacity() < repo.memberCount(projectId)
                            + repo.reservedCount(projectId, clock.instant())) {
                        throw new ApiException(ApiCode.RESOURCE_CHANGED, "容量不能低于当前成员和有效预留");
                    }
                    if ("DETAILED".equals(locked.mode())) {
                        int sum = repo.roles(projectId).stream().mapToInt(RoleRow::total).sum();
                        if (sum != request.capacity()) {
                            throw ApiException.validation("DETAILED 岗位总和必须等于capacity");
                        }
                    }
                    if (repo.updateProject(projectId, request.expectedRevision(), request.name().strip(),
                            request.intro() == null ? "" : request.intro().strip(),
                            request.discussionTime() == null ? "" : request.discussionTime().strip(),
                            request.capacity()) != 1) {
                        throw new ApiException(ApiCode.RESOURCE_CHANGED);
                    }
                    auditService.record(principal.userId(), "PROJECT_PATCH", "PROJECT", projectId,
                            request.expectedRevision(), request.expectedRevision() + 1, null);
                    return projectView(repo.findProject(projectId).orElseThrow(), principal);
                });
    }

    @Transactional(readOnly = true)
    public TeamViews.ProjectView recruitment(AuthenticatedUser principal, String projectId) {
        policy.requireActiveBusinessUser(principal);
        ProjectRow project = requireProject(projectId);
        requireSquare(principal, project.squareId());
        return projectView(project, principal);
    }

    public TeamViews.ProjectView updateRecruitment(AuthenticatedUser principal, String projectId,
                                                   TeamRequests.RecruitmentUpdateRequest request,
                                                   String idempotencyKey, String path) {
        policy.requireActiveBusinessUser(principal);
        ProjectRow project = requireProject(projectId);
        requireLeader(project, principal.userId());
        return idempotencyService.execute(
                new IdempotencyRequest(principal, "PUT", path, idempotencyKey, request, List.of()),
                TeamViews.ProjectView.class,
                () -> policy.requireActiveBusinessUser(principal),
                () -> {
                    if (repo.updateRecruitment(projectId, request.expectedRevision(), request.open(),
                            request.description() == null ? "" : request.description().strip(),
                            request.professionalDirection().strip(), request.weeklyHours(),
                            request.acceptBeginners()) != 1) {
                        throw new ApiException(ApiCode.RESOURCE_CHANGED);
                    }
                    return projectView(repo.findProject(projectId).orElseThrow(), principal);
                });
    }

    @Transactional(readOnly = true)
    public List<TeamViews.ProjectView> recruitments(AuthenticatedUser principal, String assignmentId,
                                                    String role, String mode, String direction, String q) {
        policy.requireActiveBusinessUser(principal);
        TeamRepository.AssignmentBrief assignment = repo.findAssignment(assignmentId);
        if (assignment == null) {
            throw ApiException.notFound();
        }
        SquareRow square = squareRepository.findById(assignment.squareId())
                .orElseThrow(ApiException::notFound);
        if (!square.schoolId().equals(principal.schoolId())) {
            throw new ApiException(ApiCode.FORBIDDEN);
        }
        String query = q == null ? "" : q.strip();
        if (query.length() > 80) {
            throw ApiException.validation("q 不能超过80个字符");
        }
        return repo.projectsForAssignment(assignmentId).stream()
                .filter(project -> project.recruitmentOpen() && "ACTIVE".equals(project.status()))
                .map(project -> projectView(project, principal))
                .filter(view -> role == null || role.isBlank()
                        || view.roles().stream().anyMatch(r -> r.roleName().equals(role)))
                .filter(view -> mode == null || mode.isBlank() || view.mode().equals(mode))
                .filter(view -> direction == null || direction.isBlank()
                        || view.recruitment().professionalDirection().contains(direction))
                .filter(view -> query.isEmpty()
                        || view.displayName().contains(query) || view.intro().contains(query))
                .filter(view -> view.availableSeats() > 0)
                .toList();
    }

    public TeamViews.ApplicationView apply(AuthenticatedUser principal, String projectId,
                                            TeamRequests.ApplicationCreateRequest request,
                                            String idempotencyKey, String path) {
        policy.requireActiveBusinessUser(principal);
        policy.requireRole(principal, Role.STUDENT);
        ProjectRow project = requireProject(projectId);
        SquareRow square = requireSquare(principal, project.squareId());
        requireQualification(principal, square);
        return idempotencyService.execute(
                new IdempotencyRequest(principal, "POST", path, idempotencyKey, request, List.of()),
                TeamViews.ApplicationView.class,
                () -> policy.requireActiveBusinessUser(principal),
                () -> applyOnce(principal, project, request));
    }

    private TeamViews.ApplicationView applyOnce(AuthenticatedUser principal, ProjectRow project,
                                                TeamRequests.ApplicationCreateRequest request) {
        ProjectRow current = requireProject(project.id());
        validateApply(current, principal, request);
        String id = UUID.randomUUID().toString();
        repo.insertApplication(id, project.id(), principal.userId(), request.roleSlotId(),
                request.message().strip());
        auditService.record(principal.userId(), "TEAM_APPLICATION_CREATE", "TEAM_APPLICATION", id,
                null, 1, null);
        return applicationView(repo.findApplication(id).orElseThrow(), principal);
    }

    private void validateApply(ProjectRow project, AuthenticatedUser principal,
                               TeamRequests.ApplicationCreateRequest request) {
        if (!project.recruitmentOpen() || !"ACTIVE".equals(project.status())) {
            throw new ApiException(ApiCode.RESOURCE_CHANGED, "项目当前不开放申请");
        }
        if (repo.membership(project.id(), principal.userId()).isPresent()
                || repo.assignmentSlot(project.assignmentId(), principal.userId())) {
            throw new ApiException(ApiCode.ALREADY_IN_ASSIGNMENT_TEAM);
        }
        if (repo.pendingApplication(project.id(), principal.userId()).isPresent()
                || repo.pendingInvitation(project.id(), principal.userId()).isPresent()) {
            throw new ApiException(ApiCode.APPLICATION_EXISTS);
        }
        int available = project.capacity() - repo.memberCount(project.id())
                - repo.reservedCount(project.id(), clock.instant());
        if (available <= 0) {
            throw new ApiException(ApiCode.ROLE_FULL);
        }
        validateRole(project, request.roleSlotId());
    }

    @Transactional(readOnly = true)
    public List<TeamViews.ApplicationView> projectApplications(AuthenticatedUser principal, String projectId) {
        policy.requireActiveBusinessUser(principal);
        ProjectRow project = requireProject(projectId);
        requireLeader(project, principal.userId());
        return repo.applicationsForProject(projectId).stream()
                .map(row -> applicationView(row, principal)).toList();
    }

    @Transactional(readOnly = true)
    public List<TeamViews.ApplicationView> myApplications(AuthenticatedUser principal) {
        policy.requireActiveBusinessUser(principal);
        return repo.applicationsForApplicant(principal.userId()).stream()
                .map(row -> applicationView(row, principal)).toList();
    }

    public TeamViews.ApplicationView decideApplication(AuthenticatedUser principal, String applicationId,
                                                       TeamRequests.ApplicationDecisionRequest request,
                                                       String idempotencyKey, String path) {
        policy.requireActiveBusinessUser(principal);
        ApplicationRow application = repo.findApplication(applicationId).orElseThrow(ApiException::notFound);
        ProjectRow project = requireProject(application.projectId());
        requireLeader(project, principal.userId());
        if ("REJECT".equals(request.decision())
                && (request.reason() == null || request.reason().isBlank())) {
            throw ApiException.validation("拒绝申请必须填写reason");
        }
        return idempotencyService.execute(
                new IdempotencyRequest(principal, "POST", path, idempotencyKey, request, List.of()),
                TeamViews.ApplicationView.class,
                () -> policy.requireActiveBusinessUser(principal),
                () -> decideApplicationOnce(principal, applicationId, request));
    }

    private TeamViews.ApplicationView decideApplicationOnce(AuthenticatedUser principal, String applicationId,
                                                           TeamRequests.ApplicationDecisionRequest request) {
        ApplicationRow application = repo.findApplication(applicationId).orElseThrow(ApiException::notFound);
        ProjectRow project = repo.lockProject(application.projectId()).orElseThrow(ApiException::notFound);
        requireLeader(project, principal.userId());
        if (application.revision() != request.expectedRevision() || !"PENDING".equals(application.status())) {
            throw new ApiException(ApiCode.RESOURCE_CHANGED);
        }
        if ("REJECT".equals(request.decision())) {
            if (repo.updateApplication(applicationId, request.expectedRevision(), "REJECTED",
                    request.reason(), null) != 1) {
                throw new ApiException(ApiCode.RESOURCE_CHANGED);
            }
            auditService.record(principal.userId(), "TEAM_APPLICATION_REJECT", "TEAM_APPLICATION",
                    applicationId, application.revision(), application.revision() + 1, request.reason());
            return applicationView(repo.findApplication(applicationId).orElseThrow(), principal);
        }
        requireCapacity(project, application.roleSlotId());
        String invitationId = UUID.randomUUID().toString();
        Instant expiresAt = clock.instant().plus(INVITATION_TTL);
        repo.insertInvitation(invitationId, project.id(), application.applicantId(),
                application.roleSlotId(), "申请已通过，请确认加入", expiresAt);
        repo.insertReservation(UUID.randomUUID().toString(), project.id(), application.applicantId(),
                application.roleSlotId(), invitationId, expiresAt);
        if (repo.updateApplication(applicationId, request.expectedRevision(), "APPROVED",
                request.reason(), invitationId) != 1) {
            throw new ApiException(ApiCode.RESOURCE_CHANGED);
        }
        repo.bumpRevision(project.id());
        auditService.record(principal.userId(), "TEAM_APPLICATION_APPROVE", "TEAM_APPLICATION",
                applicationId, application.revision(), application.revision() + 1, request.reason());
        return applicationView(repo.findApplication(applicationId).orElseThrow(), principal);
    }

    public TeamViews.ApplicationView withdraw(AuthenticatedUser principal, String applicationId,
                                              TeamRequests.WithdrawRequest request,
                                              String idempotencyKey, String path) {
        policy.requireActiveBusinessUser(principal);
        ApplicationRow application = repo.findApplication(applicationId).orElseThrow(ApiException::notFound);
        if (!application.applicantId().equals(principal.userId())) {
            throw new ApiException(ApiCode.FORBIDDEN);
        }
        return idempotencyService.execute(
                new IdempotencyRequest(principal, "POST", path, idempotencyKey, request, List.of()),
                TeamViews.ApplicationView.class,
                () -> policy.requireActiveBusinessUser(principal),
                () -> {
                    if (repo.updateApplication(applicationId, request.expectedRevision(), "WITHDRAWN",
                            request.reason(), null) != 1) {
                        throw new ApiException(ApiCode.RESOURCE_CHANGED);
                    }
                    return applicationView(repo.findApplication(applicationId).orElseThrow(), principal);
                });
    }

    public TeamViews.InvitationView invite(AuthenticatedUser principal, String projectId,
                                            TeamRequests.InviteRequest request,
                                            String idempotencyKey, String path) {
        policy.requireActiveBusinessUser(principal);
        ProjectRow project = requireProject(projectId);
        requireLeader(project, principal.userId());
        UserRow recipient = userRepository.findById(request.recipientId()).orElseThrow(ApiException::notFound);
        SquareRow square = requireSquare(principal, project.squareId());
        if (!recipient.schoolId().equals(square.schoolId())) {
            throw ApiException.notFound();
        }
        requireQualification(recipient, square);
        return idempotencyService.execute(
                new IdempotencyRequest(principal, "POST", path, idempotencyKey, request, List.of()),
                TeamViews.InvitationView.class,
                () -> policy.requireActiveBusinessUser(principal),
                () -> inviteOnce(principal, project, recipient, request));
    }

    private TeamViews.InvitationView inviteOnce(AuthenticatedUser principal, ProjectRow project,
                                                UserRow recipient, TeamRequests.InviteRequest request) {
        ProjectRow locked = repo.lockProject(project.id()).orElseThrow(ApiException::notFound);
        if (locked.revision() != request.expectedProjectRevision()) {
            throw new ApiException(ApiCode.RESOURCE_CHANGED);
        }
        if (repo.membership(project.id(), recipient.id()).isPresent()
                || repo.assignmentSlot(project.assignmentId(), recipient.id())) {
            throw new ApiException(ApiCode.ALREADY_IN_ASSIGNMENT_TEAM);
        }
        if (repo.pendingInvitation(project.id(), recipient.id()).isPresent()
                || repo.pendingApplication(project.id(), recipient.id()).isPresent()) {
            throw new ApiException(ApiCode.APPLICATION_EXISTS);
        }
        requireCapacity(locked, request.roleSlotId());
        String invitationId = UUID.randomUUID().toString();
        Instant expiresAt = clock.instant().plus(INVITATION_TTL);
        repo.insertInvitation(invitationId, project.id(), recipient.id(), request.roleSlotId(),
                request.message().strip(), expiresAt);
        repo.insertReservation(UUID.randomUUID().toString(), project.id(), recipient.id(),
                request.roleSlotId(), invitationId, expiresAt);
        repo.bumpRevision(project.id());
        auditService.record(principal.userId(), "TEAM_INVITATION_CREATE", "TEAM_INVITATION",
                invitationId, null, 1, null);
        return invitationView(repo.findInvitation(invitationId).orElseThrow(), principal);
    }

    @Transactional(readOnly = true)
    public List<TeamViews.InvitationView> projectInvitations(AuthenticatedUser principal, String projectId) {
        policy.requireActiveBusinessUser(principal);
        ProjectRow project = requireProject(projectId);
        requireLeader(project, principal.userId());
        return repo.invitationsForProject(projectId).stream()
                .map(row -> invitationView(row, principal)).toList();
    }

    @Transactional(readOnly = true)
    public List<TeamViews.InvitationView> myInvitations(AuthenticatedUser principal) {
        policy.requireActiveBusinessUser(principal);
        return repo.invitationsForRecipient(principal.userId()).stream()
                .map(row -> invitationView(row, principal)).toList();
    }

    @Transactional(readOnly = true)
    public TeamViews.InvitationView invitation(AuthenticatedUser principal, String invitationId) {
        policy.requireActiveBusinessUser(principal);
        InvitationRow invitation = repo.findInvitation(invitationId).orElseThrow(ApiException::notFound);
        ProjectRow project = requireProject(invitation.projectId());
        if (!invitation.recipientId().equals(principal.userId())
                && !project.ownerId().equals(principal.userId())) {
            throw new ApiException(ApiCode.FORBIDDEN);
        }
        return invitationView(invitation, principal);
    }

    public TeamViews.InvitationView respondInvitation(AuthenticatedUser principal, String invitationId,
                                                      TeamRequests.InvitationResponseRequest request,
                                                      String idempotencyKey, String path) {
        policy.requireActiveBusinessUser(principal);
        InvitationRow invitation = repo.findInvitation(invitationId).orElseThrow(ApiException::notFound);
        if (!invitation.recipientId().equals(principal.userId())) {
            throw new ApiException(ApiCode.FORBIDDEN);
        }
        if ("DECLINE".equals(request.decision())
                && (request.reason() == null || request.reason().isBlank())) {
            throw ApiException.validation("拒绝邀请必须填写reason");
        }
        return idempotencyService.execute(
                new IdempotencyRequest(principal, "POST", path, idempotencyKey, request, List.of()),
                TeamViews.InvitationView.class,
                () -> policy.requireActiveBusinessUser(principal),
                () -> respondInvitationOnce(principal, invitationId, request));
    }

    private TeamViews.InvitationView respondInvitationOnce(AuthenticatedUser principal, String invitationId,
                                                           TeamRequests.InvitationResponseRequest request) {
        InvitationRow invitation = repo.findInvitation(invitationId).orElseThrow(ApiException::notFound);
        if (invitation.revision() != request.expectedRevision() || !"PENDING".equals(invitation.status())) {
            throw new ApiException(ApiCode.RESOURCE_CHANGED);
        }
        if (!invitation.expiresAt().isAfter(clock.instant())) {
            repo.updateInvitation(invitationId, request.expectedRevision(), "EXPIRED", "邀请已过期");
            repo.closeReservation(invitationId, "EXPIRED");
            throw new ApiException(ApiCode.INVITATION_EXPIRED);
        }
        ProjectRow project = repo.lockProject(invitation.projectId()).orElseThrow(ApiException::notFound);
        if ("DECLINE".equals(request.decision())) {
            if (repo.updateInvitation(invitationId, request.expectedRevision(), "DECLINED",
                    request.reason()) != 1) {
                throw new ApiException(ApiCode.RESOURCE_CHANGED);
            }
            repo.closeReservation(invitationId, "RELEASED");
            repo.bumpRevision(project.id());
            return invitationView(repo.findInvitation(invitationId).orElseThrow(), principal);
        }
        if (repo.assignmentSlot(project.assignmentId(), principal.userId())) {
            throw new ApiException(ApiCode.ALREADY_IN_ASSIGNMENT_TEAM);
        }
        requireCapacity(project, invitation.roleSlotId());
        String membershipId = UUID.randomUUID().toString();
        int colorIndex = repo.memberCount(project.id()) % COLORS.length;
        repo.insertMembership(membershipId, project.id(), principal.userId(),
                invitation.roleSlotId(), "MEMBER", COLORS[colorIndex], clock.instant());
        repo.bumpAuthorizationEpoch(project.id());
        repo.insertAssignmentSlot(UUID.randomUUID().toString(), project.assignmentId(),
                principal.userId(), project.id(), membershipId);
        repo.consumeReservation(invitationId);
        if (repo.updateInvitation(invitationId, request.expectedRevision(), "ACCEPTED", null) != 1) {
            throw new ApiException(ApiCode.RESOURCE_CHANGED);
        }
        repo.closeOtherPending(project.assignmentId(), principal.userId(), invitationId);
        repo.bumpRevision(project.id());
        auditService.record(principal.userId(), "TEAM_INVITATION_ACCEPT", "TEAM_INVITATION",
                invitationId, invitation.revision(), invitation.revision() + 1, null);
        return invitationView(repo.findInvitation(invitationId).orElseThrow(), principal,
                membershipView(repo.membership(project.id(), principal.userId()).orElseThrow()));
    }

    public TeamViews.InvitationView revokeInvitation(AuthenticatedUser principal, String invitationId,
                                                     TeamRequests.InvitationRevokeRequest request,
                                                     String idempotencyKey, String path) {
        policy.requireActiveBusinessUser(principal);
        InvitationRow invitation = repo.findInvitation(invitationId).orElseThrow(ApiException::notFound);
        ProjectRow project = requireProject(invitation.projectId());
        requireLeader(project, principal.userId());
        return idempotencyService.execute(
                new IdempotencyRequest(principal, "POST", path, idempotencyKey, request, List.of()),
                TeamViews.InvitationView.class,
                () -> policy.requireActiveBusinessUser(principal),
                () -> {
                    if (repo.updateInvitation(invitationId, request.expectedRevision(), "REVOKED",
                            request.reason()) != 1) {
                        throw new ApiException(ApiCode.RESOURCE_CHANGED);
                    }
                    repo.closeReservation(invitationId, "RELEASED");
                    repo.bumpRevision(project.id());
                    return invitationView(repo.findInvitation(invitationId).orElseThrow(), principal);
                });
    }

    @Transactional
    public TeamViews.InviteCandidatesView inviteCandidates(AuthenticatedUser principal, String projectId,
                                                           int expectedProjectRevision, String q,
                                                           String cursor) {
        policy.requireActiveBusinessUser(principal);
        ProjectRow project = requireProject(projectId);
        requireLeader(project, principal.userId());
        if (project.revision() != expectedProjectRevision) {
            throw new ApiException(ApiCode.RESOURCE_CHANGED);
        }
        String query = q == null ? "" : q.strip();
        if (query.isEmpty() || query.length() > 80) {
            throw ApiException.validation("q 必须为1～80个字符");
        }
        int epoch = project.authorizationEpoch();
        String scope = "INVITE_CANDIDATES:" + projectId;
        String queryHash = Hashing.sha256Hex(query + "|" + expectedProjectRevision + "|" + epoch);
        List<TeamViews.InviteCandidateView> all = candidateList(project, query);
        List<String> ids = all.stream().map(TeamViews.InviteCandidateView::userId).sorted().toList();

        CursorService.CursorData data;
        List<TeamViews.InviteCandidateView> page;
        if (cursor == null || cursor.isBlank()) {
            data = new CursorService.CursorData(ids, 0, ids.size());
            int end = Math.min(ids.size(), 20);
            page = all.stream().sorted(Comparator.comparing(TeamViews.InviteCandidateView::userId))
                    .toList().subList(0, end);
            String next = end < ids.size()
                    ? cursorService.create(principal.userId(), scope, queryHash, epoch, ids, end)
                    : null;
            return new TeamViews.InviteCandidatesView(page, next, next != null, ids.size(),
                    project.revision());
        }

        data = cursorService.resolve(cursor, principal.userId(), scope, queryHash, epoch);
        int position = data.position();
        int end = Math.min(data.total(), position + 20);
        Map<String, TeamViews.InviteCandidateView> current = new java.util.HashMap<>();
        all.forEach(item -> current.put(item.userId(), item));
        List<TeamViews.InviteCandidateView> ordered = new ArrayList<>();
        for (String id : data.ids().subList(position, end)) {
            TeamViews.InviteCandidateView item = current.get(id);
            if (item == null) {
                // 快照里的候选已无法按当前授权重建，不能静默漏页或改变 total。
                throw new ApiException(ApiCode.CURSOR_EXPIRED);
            }
            ordered.add(item);
        }
        String next = end < data.total()
                ? cursorService.create(principal.userId(), scope, queryHash, epoch, data.ids(), end,
                        data.expiresAt() == null ? clock.instant().plus(Duration.ofMinutes(15))
                                : data.expiresAt())
                : null;
        return new TeamViews.InviteCandidatesView(ordered, next, next != null, data.total(),
                project.revision());
    }

    private List<TeamViews.InviteCandidateView> candidateList(ProjectRow project, String query) {
        Map<String, TeamViews.InviteCandidateView> merged = new LinkedHashMap<>();
        for (TeamRepository.CandidateRow candidate : repo.applicationCandidates(project.id(), query)) {
            merged.put(candidate.userId(), new TeamViews.InviteCandidateView(candidate.userId(),
                    candidate.displayName(), candidate.studentNo(), skills(candidate.skillsJson()),
                    "APPLICATION", candidate.applicationId(), true, null, null));
        }
        SquareRow square = squareRepository.findById(project.squareId()).orElse(null);
        if (square != null && square.verifiedCourseId() != null) {
            for (TeamRepository.CandidateRow candidate : repo.courseCandidates(
                    square.verifiedCourseId(), project.id(), query)) {
                if (merged.containsKey(candidate.userId())) {
                    continue; // 同用户有有效申请时保留申请语境，APPLICATION 优先。
                }
                boolean eligible = candidate.blockedReason() == null;
                merged.put(candidate.userId(), new TeamViews.InviteCandidateView(candidate.userId(),
                        candidate.displayName(), candidate.studentNo(), skills(candidate.skillsJson()),
                        "COURSE", null, eligible, candidate.blockedReason(),
                        blockedReasonText(candidate.blockedReason())));
            }
        }
        return merged.values().stream()
                .sorted(Comparator.comparing(TeamViews.InviteCandidateView::userId))
                .toList();
    }

    private String blockedReasonText(String reasonCode) {
        if (reasonCode == null) return null;
        return switch (reasonCode) {
            case "ALREADY_MEMBER" -> "已是项目成员";
            case "ALREADY_RESERVED" -> "已有有效席位预留";
            case "APPLICATION_PENDING" -> "已有待处理申请";
            case "INVITATION_PENDING" -> "已有待回应邀请";
            default -> "当前不可邀请";
        };
    }

    @Transactional(readOnly = true)
    public List<TeamViews.WorkSourceProjectView> workSourceProjects(AuthenticatedUser principal) {
        policy.requireActiveBusinessUser(principal);
        return workSources.sources(principal.userId());
    }

    @Transactional(readOnly = true)
    public TeamViews.ProjectCodeView projectCode(AuthenticatedUser principal, String projectId) {
        policy.requireActiveBusinessUser(principal);
        ProjectRow project = requireProject(projectId);
        requireSquare(principal, project.squareId());
        if (repo.membership(projectId, principal.userId()).isEmpty()
                && !project.ownerId().equals(principal.userId())) {
            throw new ApiException(ApiCode.FORBIDDEN);
        }
        return codeView(project, principal, repo.latestCode(projectId).orElse(null));
    }

    public TeamViews.ProjectCodeView codeAction(AuthenticatedUser principal, String projectId,
                                                TeamRequests.CodeActionRequest request,
                                                String idempotencyKey, String path) {
        policy.requireActiveBusinessUser(principal);
        ProjectRow project = requireProject(projectId);
        requireLeader(project, principal.userId());
        return idempotencyService.execute(
                new IdempotencyRequest(principal, "POST", path, idempotencyKey, request, List.of()),
                TeamViews.ProjectCodeView.class,
                () -> policy.requireActiveBusinessUser(principal),
                () -> codeActionOnce(principal, projectId, request));
    }

    private TeamViews.ProjectCodeView codeActionOnce(AuthenticatedUser principal, String projectId,
                                                    TeamRequests.CodeActionRequest request) {
        ProjectRow project = repo.lockProject(projectId).orElseThrow(ApiException::notFound);
        if (project.revision() != request.expectedRevision()) {
            throw new ApiException(ApiCode.RESOURCE_CHANGED);
        }
        ProjectCodeRow latest = repo.latestCode(projectId).orElse(null);
        switch (request.action()) {
            case "DISABLE" -> {
                repo.disableActiveCode(projectId);
            }
            case "ENABLE" -> {
                if (latest == null) {
                    createCode(projectId);
                } else if ("DISABLED".equals(latest.status())) {
                    repo.updateCodeStatus(latest.id(), "ACTIVE");
                }
            }
            case "ROTATE" -> {
                repo.disableActiveCode(projectId);
                createCode(projectId);
            }
            default -> throw ApiException.validation("action 无效");
        }
        repo.bumpRevision(projectId);
        ProjectRow updated = repo.findProject(projectId).orElseThrow();
        return codeView(updated, principal, repo.latestCode(projectId).orElse(null));
    }

    private void createCode(String projectId) {
        String code = Hashing.newToken();
        repo.insertCode(projectId, Hashing.sha256Hex(code), cipher.encrypt(code),
                cipher.keyId(), repo.nextCodeVersion(projectId));
    }

    @Transactional
    public TeamViews.CodeResolutionView resolveCode(AuthenticatedUser principal, String code) {
        policy.requireActiveBusinessUser(principal);
        ProjectCodeRow row = repo.codeByHash(Hashing.sha256Hex(code))
                .orElseThrow(() -> new ApiException(ApiCode.CODE_INVALID));
        if (!"ACTIVE".equals(row.status())) {
            throw new ApiException(ApiCode.CODE_DISABLED);
        }
        ProjectRow project = requireProject(row.projectId());
        SquareRow square = squareRepository.findById(project.squareId()).orElseThrow(ApiException::notFound);
        if (!square.schoolId().equals(principal.schoolId())) {
            throw ApiException.notFound();
        }
        TeamViews.ProjectView view = projectView(project, principal);
        return new TeamViews.CodeResolutionView(project.id(), view, view.canApply(), null);
    }

    @Transactional(readOnly = true)
    public TeamViews.DashboardView dashboard(AuthenticatedUser principal) {
        policy.requireActiveBusinessUser(principal);
        List<TeamViews.TodoView> todos = todoQuery.todos(principal.userId());
        int actionCount = (int) todos.stream().filter(item -> "ACTION".equals(item.state())).count();
        int waitingCount = (int) todos.stream().filter(item -> "WAITING".equals(item.state())).count();
        int pendingApplications = repo.pendingApplicationCount(principal.userId());
        int pendingInvitations = repo.pendingInvitationCount(principal.userId(), clock.instant());
        return new TeamViews.DashboardView(actionCount, waitingCount,
                pendingApplications, pendingInvitations, repo.myProjects(principal.userId()).size(),
                todos);
    }

    @Transactional(readOnly = true)
    public List<TeamViews.TodoView> todos(AuthenticatedUser principal, String state) {
        policy.requireActiveBusinessUser(principal);
        if (state != null && !state.isBlank() && !"ACTION".equals(state) && !"WAITING".equals(state)) {
            throw ApiException.validation("state 取值无效");
        }
        return todoQuery.todos(principal.userId()).stream()
                .filter(todo -> state == null || state.isBlank() || state.equals(todo.state()))
                .toList();
    }

    private TeamViews.ProjectView projectView(ProjectRow project, AuthenticatedUser principal) {
        SquareRow square = squareRepository.findById(project.squareId()).orElseThrow(ApiException::notFound);
        TeamRepository.AssignmentBrief assignment = repo.findAssignment(project.assignmentId());
        UserRow leader = userRepository.findById(project.ownerId()).orElse(null);
        ProjectMemberRow myMembership = repo.membership(project.id(), principal.userId()).orElse(null);
        int memberCount = repo.memberCount(project.id());
        int reservedCount = repo.reservedCount(project.id(), clock.instant());
        int available = project.capacity() - memberCount - reservedCount;
        boolean qualified = hasQualification(principal, square);
        boolean canApply = qualified && project.recruitmentOpen() && "ACTIVE".equals(project.status())
                && myMembership == null && available > 0
                && !repo.assignmentSlot(project.assignmentId(), principal.userId())
                && repo.pendingApplication(project.id(), principal.userId()).isEmpty()
                && repo.pendingInvitation(project.id(), principal.userId()).isEmpty();
        ApplicationRow pendingApplication = repo.pendingApplication(project.id(), principal.userId())
                .orElse(null);
        InvitationRow pendingInvitation = repo.pendingInvitation(project.id(), principal.userId())
                .orElse(null);
        List<TeamViews.RoleView> roles = repo.roles(project.id()).stream()
                .map(role -> new TeamViews.RoleView(role.id(), null, role.roleName(), role.duty(),
                        role.total(), repo.roleFilled(project.id(), role.id()),
                        repo.roleReserved(project.id(), role.id(), clock.instant())))
                .toList();
        List<TeamViews.StageView> stages = repo.stages(project.id()).stream()
                .map(stage -> new TeamViews.StageView(stage.id(), stage.name())).toList();
        String ineligible = !qualified ? "NOT_COURSE_MEMBER"
                : !project.recruitmentOpen() ? "RECRUITMENT_CLOSED"
                : available <= 0 ? "FULL" : null;
        return new TeamViews.ProjectView(
                project.id(), project.id(), project.squareId(), square.squareNo(), square.name(),
                project.assignmentId(), assignment == null ? "" : assignment.name(), project.mode(),
                project.name(), displayName(project), project.intro() == null ? "" : project.intro(),
                project.capacity(), project.ownerId(), leader == null ? "" : leader.displayName(),
                project.status(), assignment == null ? null : assignment.hardDueAt(),
                project.discussionTime(),
                new TeamViews.RecruitmentSettings(project.recruitmentOpen(),
                        project.recruitmentDescription(), project.professionalDirection(),
                        project.weeklyHours(), project.acceptBeginners()),
                roles, stages, project.revision(), memberCount, reservedCount,
                myMembership == null ? "" : myMembership.projectRole(),
                myMembership == null ? null : membershipView(myMembership),
                false, project.closingCycle(), canApply, canApply, ineligible, Math.max(0, available),
                pendingApplication == null ? null : pendingApplication.status(),
                pendingInvitation == null ? null : pendingInvitation.id());
    }

    private TeamViews.ApplicationView applicationView(ApplicationRow application, AuthenticatedUser principal) {
        ProjectRow project = repo.findProject(application.projectId()).orElseThrow(ApiException::notFound);
        UserRow applicant = userRepository.findById(application.applicantId()).orElse(null);
        TeamViews.Application app = new TeamViews.Application(application.id(), application.projectId(),
                application.applicantId(), application.roleSlotId(), application.message(),
                application.status(), application.invitationId(), application.reason(),
                application.revision(), application.createdAt());
        TeamViews.ApplicantProfile profile = new TeamViews.ApplicantProfile(application.applicantId(),
                applicant == null ? "" : applicant.displayName(), applicant == null ? List.of() : applicant.skills());
        String next = "PENDING".equals(application.status()) ? "等待组长决定"
                : "APPROVED".equals(application.status()) ? "等待本人接受邀请"
                : application.reason();
        return new TeamViews.ApplicationView(app, projectView(project, principal), profile,
                application.invitationId(), next);
    }

    private TeamViews.InvitationView invitationView(InvitationRow invitation, AuthenticatedUser principal) {
        return invitationView(invitation, principal, null);
    }

    private TeamViews.InvitationView invitationView(InvitationRow invitation, AuthenticatedUser principal,
                                                    TeamViews.MembershipView membership) {
        ProjectRow project = repo.findProject(invitation.projectId()).orElseThrow(ApiException::notFound);
        TeamViews.Invitation inv = new TeamViews.Invitation(invitation.id(), invitation.projectId(),
                invitation.recipientId(), invitation.roleSlotId(), invitation.message(),
                invitation.status(), invitation.expiresAt(), invitation.reason(),
                invitation.revision(), invitation.createdAt());
        boolean canRespond = inv.recipientId().equals(principal.userId())
                && "PENDING".equals(inv.status()) && inv.expiresAt().isAfter(clock.instant());
        return new TeamViews.InvitationView(inv, projectView(project, principal), canRespond, membership);
    }

    private TeamViews.MembershipView membershipView(ProjectMemberRow row) {
        return new TeamViews.MembershipView(row.id(), row.id(), row.projectId(), row.userId(),
                row.displayName(), row.roleSlotId(), row.projectRole(), row.status(), row.color(),
                row.colorRevision(), row.joinedAt(), row.revision());
    }

    private TeamViews.ProjectCodeView codeView(ProjectRow project, AuthenticatedUser principal,
                                               ProjectCodeRow code) {
        boolean canManage = project.ownerId().equals(principal.userId());
        String plaintext = code == null ? null : cipher.decrypt(code.codeCiphertext());
        return new TeamViews.ProjectCodeView(project.id(), displayName(project), plaintext,
                canManage, canManage, code != null && "ACTIVE".equals(code.status()),
                code == null ? 0 : code.revision());
    }

    private void validateRole(ProjectRow project, String roleSlotId) {
        if ("DETAILED".equals(project.mode())) {
            if (roleSlotId == null || roleSlotId.isBlank()) {
                throw ApiException.validation("DETAILED 申请/邀请必须提供roleSlotId");
            }
            RoleRow role = repo.role(project.id(), roleSlotId)
                    .orElseThrow(() -> ApiException.validation("岗位不属于当前项目"));
            if (repo.roleFilled(project.id(), role.id())
                    + repo.roleReserved(project.id(), role.id(), clock.instant()) >= role.total()) {
                throw new ApiException(ApiCode.ROLE_FULL);
            }
        } else if (roleSlotId != null && !roleSlotId.isBlank()) {
            throw ApiException.validation("QUICK 项目不接收roleSlotId");
        }
    }

    private void requireCapacity(ProjectRow project, String roleSlotId) {
        int available = project.capacity() - repo.memberCount(project.id())
                - repo.reservedCount(project.id(), clock.instant());
        if (available <= 0) {
            throw new ApiException(ApiCode.ROLE_FULL);
        }
        if ("DETAILED".equals(project.mode())) {
            if (roleSlotId == null || roleSlotId.isBlank()) {
                throw ApiException.validation("DETAILED 必须提供roleSlotId");
            }
            RoleRow role = repo.role(project.id(), roleSlotId)
                    .orElseThrow(() -> ApiException.validation("岗位不属于当前项目"));
            if (repo.roleFilled(project.id(), role.id())
                    + repo.roleReserved(project.id(), role.id(), clock.instant()) >= role.total()) {
                throw new ApiException(ApiCode.ROLE_FULL);
            }
        }
    }

    private void requireQualification(AuthenticatedUser principal, SquareRow square) {
        if (!hasQualification(principal, square)) {
            throw new ApiException(ApiCode.FORBIDDEN, "当前用户没有该课程资格");
        }
    }

    private void requireQualification(UserRow user, SquareRow square) {
        boolean enrolled = square.verifiedCourseId() != null
                && verifiedCourseRepository.isValidEnrollment(square.verifiedCourseId(), user.id());
        boolean member = squareRepository.findMembership(square.id(), user.id())
                .map(row -> "ACTIVE".equals(row.status())).orElse(false);
        if (!enrolled && !member) {
            throw new ApiException(ApiCode.FORBIDDEN, "被邀请人没有该课程资格");
        }
    }

    private boolean hasQualification(AuthenticatedUser principal, SquareRow square) {
        boolean enrolled = square.verifiedCourseId() != null
                && verifiedCourseRepository.isValidEnrollment(square.verifiedCourseId(), principal.userId());
        boolean member = squareRepository.findMembership(square.id(), principal.userId())
                .map(row -> "ACTIVE".equals(row.status())).orElse(false);
        return enrolled || member;
    }

    private ProjectRow requireProject(String projectId) {
        return repo.findProject(projectId).orElseThrow(ApiException::notFound);
    }

    private SquareRow requireSquare(AuthenticatedUser principal, String squareId) {
        SquareRow square = squareRepository.findById(squareId).orElseThrow(ApiException::notFound);
        if (!square.schoolId().equals(principal.schoolId())) {
            throw ApiException.notFound();
        }
        return square;
    }

    private void requireLeader(ProjectRow project, String userId) {
        if (!project.ownerId().equals(userId)) {
            throw new ApiException(ApiCode.FORBIDDEN);
        }
    }

    private String displayName(ProjectRow project) {
        if (project.name() != null && !project.name().isBlank()) {
            return project.name();
        }
        return "未命名项目-" + project.id().substring(Math.max(0, project.id().length() - 6));
    }

    private List<String> skills(String json) {
        try {
            return objectMapper.readValue(json, STRING_LIST);
        } catch (Exception ex) {
            return List.of();
        }
    }

    private record PreparedRole(String clientKey, String roleName, String duty, int total, int position) {
    }

    private record PreparedProject(String mode, String name, String intro, int capacity,
                                   String discussionTime, List<PreparedRole> roles,
                                   String leaderRoleKey) {
    }
}
