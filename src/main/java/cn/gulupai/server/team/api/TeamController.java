package cn.gulupai.server.team.api;

import cn.gulupai.server.common.api.ApiResponse;
import cn.gulupai.server.common.api.ListEnvelope;
import cn.gulupai.server.common.security.AuthenticatedUser;
import cn.gulupai.server.team.application.TeamService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class TeamController {

    private final TeamService teamService;

    public TeamController(TeamService teamService) {
        this.teamService = teamService;
    }

    @GetMapping("/assignments/{assignmentId}/recruitments")
    public ApiResponse<ListEnvelope<TeamViews.ProjectView>> recruitments(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String assignmentId,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String mode,
            @RequestParam(required = false) String professionalDirection,
            @RequestParam(required = false) String q) {
        List<TeamViews.ProjectView> items = teamService.recruitments(principal, assignmentId,
                role, mode, professionalDirection, q);
        return ApiResponse.ok(ListEnvelope.full(items));
    }

    @GetMapping("/invitations/{invitationId}")
    public ApiResponse<TeamViews.InvitationView> invitation(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String invitationId) {
        return ApiResponse.ok(teamService.invitation(principal, invitationId));
    }

    @GetMapping("/me/applications")
    public ApiResponse<ListEnvelope<TeamViews.ApplicationView>> myApplications(
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return ApiResponse.ok(ListEnvelope.full(teamService.myApplications(principal)));
    }

    @GetMapping("/me/invitations")
    public ApiResponse<ListEnvelope<TeamViews.InvitationView>> myInvitations(
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return ApiResponse.ok(ListEnvelope.full(teamService.myInvitations(principal)));
    }

    @GetMapping("/me/projects")
    public ApiResponse<ListEnvelope<TeamViews.ProjectView>> myProjects(
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return ApiResponse.ok(ListEnvelope.full(teamService.myProjects(principal)));
    }

    @GetMapping("/me/work-source-projects")
    public ApiResponse<ListEnvelope<TeamViews.WorkSourceProjectView>> workSourceProjects(
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return ApiResponse.ok(ListEnvelope.full(teamService.workSourceProjects(principal)));
    }

    @GetMapping("/projects/{projectId}")
    public ApiResponse<TeamViews.ProjectView> project(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String projectId) {
        return ApiResponse.ok(teamService.project(principal, projectId));
    }

    @GetMapping("/projects/{projectId}/applications")
    public ApiResponse<ListEnvelope<TeamViews.ApplicationView>> projectApplications(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String projectId) {
        return ApiResponse.ok(ListEnvelope.full(teamService.projectApplications(principal, projectId)));
    }

    @GetMapping("/projects/{projectId}/invitations")
    public ApiResponse<ListEnvelope<TeamViews.InvitationView>> projectInvitations(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String projectId) {
        return ApiResponse.ok(ListEnvelope.full(teamService.projectInvitations(principal, projectId)));
    }

    @GetMapping("/projects/{projectId}/invite-candidates")
    public ApiResponse<TeamViews.InviteCandidatesView> inviteCandidates(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String projectId,
            @RequestParam int expectedProjectRevision,
            @RequestParam String q,
            @RequestParam(required = false) String cursor) {
        return ApiResponse.ok(teamService.inviteCandidates(principal, projectId,
                expectedProjectRevision, q, cursor));
    }

    @GetMapping("/projects/{projectId}/join-code")
    public ApiResponse<TeamViews.ProjectCodeView> projectCode(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String projectId) {
        return ApiResponse.ok(teamService.projectCode(principal, projectId));
    }

    @GetMapping("/projects/{projectId}/members")
    public ApiResponse<ListEnvelope<TeamViews.MembershipView>> members(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String projectId) {
        return ApiResponse.ok(ListEnvelope.full(teamService.members(principal, projectId)));
    }

    @GetMapping("/projects/{projectId}/recruitment")
    public ApiResponse<TeamViews.ProjectView> recruitment(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String projectId) {
        return ApiResponse.ok(teamService.recruitment(principal, projectId));
    }

    @PatchMapping("/projects/{projectId}")
    public ApiResponse<TeamViews.ProjectView> patchProject(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String projectId,
            @Valid @RequestBody TeamRequests.PatchProjectRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(teamService.patchProject(principal, projectId, request,
                idempotencyKey, servletRequest.getRequestURI()));
    }

    @PostMapping("/projects")
    public ApiResponse<TeamViews.ProjectView> createProject(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody TeamRequests.CreateProjectRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(teamService.createProject(principal, request, idempotencyKey,
                servletRequest.getRequestURI()));
    }

    @PostMapping("/projects/{projectId}/applications")
    public ApiResponse<TeamViews.ApplicationView> apply(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String projectId,
            @Valid @RequestBody TeamRequests.ApplicationCreateRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(teamService.apply(principal, projectId, request, idempotencyKey,
                servletRequest.getRequestURI()));
    }

    @PostMapping("/applications/{applicationId}/decisions")
    public ApiResponse<TeamViews.ApplicationView> decideApplication(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String applicationId,
            @Valid @RequestBody TeamRequests.ApplicationDecisionRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(teamService.decideApplication(principal, applicationId, request,
                idempotencyKey, servletRequest.getRequestURI()));
    }

    @PostMapping("/applications/{applicationId}/withdrawals")
    public ApiResponse<TeamViews.ApplicationView> withdraw(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String applicationId,
            @Valid @RequestBody TeamRequests.WithdrawRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(teamService.withdraw(principal, applicationId, request,
                idempotencyKey, servletRequest.getRequestURI()));
    }

    @PostMapping("/projects/{projectId}/invitations")
    public ApiResponse<TeamViews.InvitationView> invite(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String projectId,
            @Valid @RequestBody TeamRequests.InviteRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(teamService.invite(principal, projectId, request, idempotencyKey,
                servletRequest.getRequestURI()));
    }

    @PostMapping("/invitations/{invitationId}/responses")
    public ApiResponse<TeamViews.InvitationView> respondInvitation(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String invitationId,
            @Valid @RequestBody TeamRequests.InvitationResponseRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(teamService.respondInvitation(principal, invitationId, request,
                idempotencyKey, servletRequest.getRequestURI()));
    }

    @PostMapping("/invitations/{invitationId}/revocations")
    public ApiResponse<TeamViews.InvitationView> revokeInvitation(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String invitationId,
            @Valid @RequestBody TeamRequests.InvitationRevokeRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(teamService.revokeInvitation(principal, invitationId, request,
                idempotencyKey, servletRequest.getRequestURI()));
    }

    @PostMapping("/project-code-resolutions")
    public ApiResponse<TeamViews.CodeResolutionView> resolveCode(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody TeamRequests.CodeResolveRequest request) {
        return ApiResponse.ok(teamService.resolveCode(principal, request.code()));
    }

    @PostMapping("/projects/{projectId}/code-actions")
    public ApiResponse<TeamViews.ProjectCodeView> codeAction(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String projectId,
            @Valid @RequestBody TeamRequests.CodeActionRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(teamService.codeAction(principal, projectId, request, idempotencyKey,
                servletRequest.getRequestURI()));
    }

    @PutMapping("/projects/{projectId}/recruitment")
    public ApiResponse<TeamViews.ProjectView> updateRecruitment(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String projectId,
            @Valid @RequestBody TeamRequests.RecruitmentUpdateRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(teamService.updateRecruitment(principal, projectId, request,
                idempotencyKey, servletRequest.getRequestURI()));
    }

    @GetMapping("/me/dashboard")
    public ApiResponse<TeamViews.DashboardView> dashboard(
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return ApiResponse.ok(teamService.dashboard(principal));
    }

    @GetMapping("/me/todos")
    public ApiResponse<ListEnvelope<TeamViews.TodoView>> todos(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(required = false) String state) {
        return ApiResponse.ok(ListEnvelope.full(teamService.todos(principal, state)));
    }
}
