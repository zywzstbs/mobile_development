package cn.gulupai.server.delivery;

import cn.gulupai.server.common.api.ApiResponse;
import cn.gulupai.server.common.api.ListEnvelope;
import cn.gulupai.server.common.security.AuthenticatedUser;
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
public class DeliveryController {

    private final DeliveryService deliveryService;

    public DeliveryController(DeliveryService deliveryService) {
        this.deliveryService = deliveryService;
    }

    @GetMapping("/agreements/{agreementId}")
    public ApiResponse<DeliveryViews.AgreementView> agreement(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String agreementId) {
        return ApiResponse.ok(deliveryService.agreement(principal, agreementId));
    }

    @PostMapping("/agreements/{agreementId}/responses")
    public ApiResponse<DeliveryViews.AgreementView> respond(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String agreementId,
            @Valid @RequestBody DeliveryRequests.AgreementResponseRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(deliveryService.respond(principal, agreementId, request, idempotencyKey,
                servletRequest.getRequestURI()));
    }

    @PostMapping("/tasks/{taskId}/agreements")
    public ApiResponse<DeliveryViews.AgreementView> replace(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String taskId,
            @Valid @RequestBody DeliveryRequests.AgreementCreateRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(deliveryService.replaceAgreement(principal, taskId, request, idempotencyKey,
                servletRequest.getRequestURI()));
    }

    @GetMapping("/change-proposals/{proposalId}")
    public ApiResponse<DeliveryViews.ProposalView> proposal(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String proposalId) {
        return ApiResponse.ok(deliveryService.proposal(principal, proposalId));
    }

    @GetMapping("/tasks/{taskId}/change-proposals")
    public ApiResponse<ListEnvelope<DeliveryViews.ProposalView>> proposals(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String taskId) {
        return ApiResponse.ok(ListEnvelope.full(deliveryService.proposals(principal, taskId)));
    }

    @PostMapping("/tasks/{taskId}/change-proposals")
    public ApiResponse<DeliveryViews.ProposalView> propose(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String taskId,
            @Valid @RequestBody DeliveryRequests.ProposalCreateRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(deliveryService.propose(principal, taskId, request, idempotencyKey,
                servletRequest.getRequestURI()));
    }

    @PostMapping("/change-proposals/{proposalId}/responses")
    public ApiResponse<DeliveryViews.ProposalView> respondProposal(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String proposalId,
            @Valid @RequestBody DeliveryRequests.ProposalResponseRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(deliveryService.respondProposal(principal, proposalId, request, idempotencyKey,
                servletRequest.getRequestURI()));
    }

    @PostMapping("/change-proposals/{proposalId}/opinion-confirmations")
    public ApiResponse<DeliveryViews.ProposalView> confirmOpinion(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String proposalId,
            @Valid @RequestBody DeliveryRequests.OpinionConfirmationRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(deliveryService.confirmOpinion(principal, proposalId, request, idempotencyKey,
                servletRequest.getRequestURI()));
    }

    @PostMapping("/change-proposals/{proposalId}/opinion-responses")
    public ApiResponse<DeliveryViews.ProposalView> replyOpinion(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String proposalId,
            @Valid @RequestBody DeliveryRequests.OpinionReplyRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(deliveryService.replyOpinion(principal, proposalId, request, idempotencyKey,
                servletRequest.getRequestURI()));
    }

    @PostMapping("/change-proposals/{proposalId}/withdrawals")
    public ApiResponse<DeliveryViews.ProposalView> withdrawProposal(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String proposalId,
            @Valid @RequestBody DeliveryRequests.WithdrawalRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(deliveryService.withdrawProposal(principal, proposalId, request, idempotencyKey,
                servletRequest.getRequestURI()));
    }

    @PostMapping("/tasks/{taskId}/handover-agreement-renewals")
    public ApiResponse<DeliveryViews.AgreementView> renew(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String taskId,
            @Valid @RequestBody DeliveryRequests.RenewalRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(deliveryService.renew(principal, taskId, request, idempotencyKey,
                servletRequest.getRequestURI()));
    }

    @PostMapping("/tasks/{taskId}/reviewer-invitations")
    public ApiResponse<DeliveryViews.InvitationView> invite(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String taskId,
            @Valid @RequestBody DeliveryRequests.InvitationCreateRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(deliveryService.invite(principal, taskId, request, idempotencyKey,
                servletRequest.getRequestURI()));
    }

    @PostMapping("/reviewer-invitations/{invitationId}/responses")
    public ApiResponse<DeliveryViews.InvitationView> respondInvitation(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String invitationId,
            @Valid @RequestBody DeliveryRequests.InvitationResponseRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(deliveryService.respondInvitation(principal, invitationId, request, idempotencyKey,
                servletRequest.getRequestURI()));
    }

    @GetMapping("/tasks/{taskId}/me/draft")
    public ApiResponse<DeliveryViews.DraftView> draft(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String taskId) {
        return ApiResponse.ok(deliveryService.draft(principal, taskId));
    }

    @PutMapping("/tasks/{taskId}/me/draft")
    public ApiResponse<DeliveryViews.DraftView> saveDraft(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String taskId,
            @Valid @RequestBody DeliveryRequests.DraftRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(deliveryService.saveDraft(principal, taskId, request, idempotencyKey,
                servletRequest.getRequestURI()));
    }

    @GetMapping("/tasks/{taskId}/submissions")
    public ApiResponse<ListEnvelope<DeliveryViews.SubmissionView>> submissions(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String taskId) {
        return ApiResponse.ok(ListEnvelope.full(deliveryService.submissions(principal, taskId)));
    }

    @PostMapping("/tasks/{taskId}/submissions")
    public ApiResponse<DeliveryViews.SubmissionView> submit(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String taskId,
            @Valid @RequestBody DeliveryRequests.SubmitRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(deliveryService.submit(principal, taskId, request, idempotencyKey,
                servletRequest.getRequestURI()));
    }

    @PostMapping("/submissions/{submissionId}/acceptances")
    public ApiResponse<DeliveryViews.ReviewResult> accept(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String submissionId,
            @Valid @RequestBody DeliveryRequests.ReviewRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(deliveryService.accept(principal, submissionId, request, idempotencyKey,
                servletRequest.getRequestURI()));
    }

    @PostMapping("/submissions/{submissionId}/self-checks")
    public ApiResponse<DeliveryViews.ReviewResult> selfCheck(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String submissionId,
            @Valid @RequestBody DeliveryRequests.SelfCheckRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(deliveryService.selfCheck(principal, submissionId, request, idempotencyKey,
                servletRequest.getRequestURI()));
    }

    @PostMapping("/submissions/{submissionId}/withdrawals")
    public ApiResponse<DeliveryViews.SubmissionView> withdraw(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String submissionId,
            @Valid @RequestBody DeliveryRequests.SubmissionWithdrawalRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(deliveryService.withdrawSubmission(principal, submissionId, request, idempotencyKey,
                servletRequest.getRequestURI()));
    }

    @PostMapping("/tasks/{taskId}/units/{unitId}/me/contribution-confirmations")
    public ApiResponse<DeliveryViews.ContributionView> confirm(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String taskId,
            @PathVariable String unitId, @Valid @RequestBody DeliveryRequests.ContributionRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(deliveryService.confirmContribution(principal, taskId, unitId, request,
                idempotencyKey, servletRequest.getRequestURI()));
    }
}
