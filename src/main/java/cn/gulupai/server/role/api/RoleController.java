package cn.gulupai.server.role.api;

import cn.gulupai.server.common.api.ApiResponse;
import cn.gulupai.server.common.api.ListEnvelope;
import cn.gulupai.server.common.security.AuthenticatedUser;
import cn.gulupai.server.role.application.RoleService;
import cn.gulupai.server.team.api.TeamViews;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class RoleController {

    private final RoleService roleService;

    public RoleController(RoleService roleService) {
        this.roleService = roleService;
    }

    @GetMapping("/me/role-plans")
    public ApiResponse<ListEnvelope<RoleViews.PlanView>> myPlans(
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return ApiResponse.ok(ListEnvelope.full(roleService.myPlans(principal)));
    }

    @GetMapping("/me/role-proposals")
    public ApiResponse<ListEnvelope<RoleViews.ProposalView>> myProposals(
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return ApiResponse.ok(ListEnvelope.full(roleService.myProposals(principal)));
    }

    @GetMapping("/me/role-swaps")
    public ApiResponse<ListEnvelope<RoleViews.SwapView>> mySwaps(
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return ApiResponse.ok(ListEnvelope.full(roleService.mySwaps(principal)));
    }

    @GetMapping("/projects/{projectId}/role-plans")
    public ApiResponse<ListEnvelope<RoleViews.PlanView>> projectPlans(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String projectId) {
        return ApiResponse.ok(ListEnvelope.full(roleService.projectPlans(principal, projectId)));
    }

    @GetMapping("/projects/{projectId}/role-proposals")
    public ApiResponse<ListEnvelope<RoleViews.ProposalView>> projectProposals(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String projectId) {
        return ApiResponse.ok(ListEnvelope.full(roleService.projectProposals(principal, projectId)));
    }

    @GetMapping("/projects/{projectId}/role-slots")
    public ApiResponse<RoleViews.RoleSlotView> roleSlots(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String projectId) {
        return ApiResponse.ok(roleService.roleSlots(principal, projectId));
    }

    @GetMapping("/projects/{projectId}/role-swaps")
    public ApiResponse<ListEnvelope<RoleViews.SwapView>> projectSwaps(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String projectId) {
        return ApiResponse.ok(ListEnvelope.full(roleService.projectSwaps(principal, projectId)));
    }

    @GetMapping("/role-plans/{planId}")
    public ApiResponse<RoleViews.PlanView> plan(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String planId) {
        return ApiResponse.ok(roleService.plan(principal, planId));
    }

    @GetMapping("/role-proposals/{proposalId}")
    public ApiResponse<RoleViews.ProposalView> proposal(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String proposalId) {
        return ApiResponse.ok(roleService.proposal(principal, proposalId));
    }

    @GetMapping("/role-swaps/{swapId}")
    public ApiResponse<RoleViews.SwapView> swap(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String swapId) {
        return ApiResponse.ok(roleService.swap(principal, swapId));
    }

    @PostMapping("/projects/{projectId}/role-plans")
    public ApiResponse<RoleViews.PlanView> createPlan(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String projectId,
            @Valid @RequestBody RoleRequests.CreatePlanRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(roleService.createPlan(principal, projectId, request, idempotencyKey,
                servletRequest.getRequestURI()));
    }

    @PostMapping("/projects/{projectId}/role-proposals")
    public ApiResponse<RoleViews.ProposalView> createProposal(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String projectId,
            @Valid @RequestBody RoleRequests.CreateProposalRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(roleService.createProposal(principal, projectId, request, idempotencyKey,
                servletRequest.getRequestURI()));
    }

    @PostMapping("/projects/{projectId}/role-swaps")
    public ApiResponse<RoleViews.SwapView> createSwap(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String projectId,
            @Valid @RequestBody RoleRequests.CreateSwapRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(roleService.createSwap(principal, projectId, request, idempotencyKey,
                servletRequest.getRequestURI()));
    }

    @PostMapping("/role-plans/{planId}/responses")
    public ApiResponse<RoleViews.PlanView> respondPlan(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String planId,
            @Valid @RequestBody RoleRequests.PlanResponseRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(roleService.respondPlan(principal, planId, request, idempotencyKey,
                servletRequest.getRequestURI()));
    }

    @PostMapping("/role-proposals/{proposalId}/responses")
    public ApiResponse<RoleViews.ProposalView> respondProposal(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String proposalId,
            @Valid @RequestBody RoleRequests.ProposalResponseRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(roleService.respondProposal(principal, proposalId, request, idempotencyKey,
                servletRequest.getRequestURI()));
    }

    @PostMapping("/role-swaps/{swapId}/responses")
    public ApiResponse<RoleViews.SwapView> respondSwap(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String swapId,
            @Valid @RequestBody RoleRequests.PlanResponseRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(roleService.respondSwap(principal, swapId, request, idempotencyKey,
                servletRequest.getRequestURI()));
    }

    @PostMapping("/role-plans/{planId}/withdrawals")
    public ApiResponse<RoleViews.PlanView> withdrawPlan(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String planId,
            @Valid @RequestBody RoleRequests.WithdrawRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(roleService.withdrawPlan(principal, planId, request, idempotencyKey,
                servletRequest.getRequestURI()));
    }

    @PostMapping("/role-proposals/{proposalId}/withdrawals")
    public ApiResponse<RoleViews.ProposalView> withdrawProposal(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String proposalId,
            @Valid @RequestBody RoleRequests.WithdrawRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(roleService.withdrawProposal(principal, proposalId, request, idempotencyKey,
                servletRequest.getRequestURI()));
    }

    @PostMapping("/role-swaps/{swapId}/withdrawals")
    public ApiResponse<RoleViews.SwapView> withdrawSwap(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String swapId,
            @Valid @RequestBody RoleRequests.WithdrawRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(roleService.withdrawSwap(principal, swapId, request, idempotencyKey,
                servletRequest.getRequestURI()));
    }

    @PutMapping("/projects/{projectId}/me/role")
    public ApiResponse<TeamViews.ProjectView> selectMyRole(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String projectId,
            @Valid @RequestBody RoleRequests.SelectRoleRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(roleService.selectMyRole(principal, projectId, request, idempotencyKey,
                servletRequest.getRequestURI()));
    }

    @PutMapping("/projects/{projectId}/role-slots")
    public ApiResponse<RoleViews.RoleSlotView> replaceSlots(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String projectId,
            @Valid @RequestBody RoleRequests.PutRoleSlotsRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(roleService.replaceSlots(principal, projectId, request, idempotencyKey,
                servletRequest.getRequestURI()));
    }
}
