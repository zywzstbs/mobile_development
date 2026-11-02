package cn.gulupai.server.course.api;

import cn.gulupai.server.common.api.ApiResponse;
import cn.gulupai.server.common.api.ListEnvelope;
import cn.gulupai.server.common.security.AuthenticatedUser;
import cn.gulupai.server.course.application.CourseCodeService;
import cn.gulupai.server.course.application.MembershipService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class CourseMembershipController {

    private final CourseCodeService codeService;
    private final MembershipService membershipService;

    public CourseMembershipController(CourseCodeService codeService, MembershipService membershipService) {
        this.codeService = codeService;
        this.membershipService = membershipService;
    }

    @PostMapping("/squares/{squareId}/join-codes")
    public ApiResponse<CourseMembershipViews.CourseCodeView> issueCode(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String squareId,
            @Valid @RequestBody CourseMembershipRequests.CourseCodeRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(codeService.issue(principal, squareId, request, idempotencyKey,
                servletRequest.getRequestURI()));
    }

    @GetMapping("/squares/{squareId}/join-codes/current")
    public ApiResponse<CourseMembershipViews.CourseCodeView> currentCode(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String squareId) {
        return ApiResponse.ok(codeService.current(principal, squareId));
    }

    @PostMapping("/square-join-codes/{codeId}/revocations")
    public ApiResponse<CourseMembershipViews.CourseCodeView> revokeCode(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String codeId,
            @Valid @RequestBody CourseMembershipRequests.CodeRevocationRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(codeService.revoke(principal, codeId, request, idempotencyKey,
                servletRequest.getRequestURI()));
    }

    @PostMapping("/square-code-resolutions")
    public ApiResponse<CourseMembershipViews.CodeResolutionView> resolveCode(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody CourseMembershipRequests.CodeResolutionRequest request) {
        return ApiResponse.ok(codeService.resolve(principal, request.code()));
    }

    @PostMapping("/squares/{squareId}/membership-confirmations")
    public ApiResponse<CourseMembershipViews.MembershipConfirmationView> confirmMembership(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String squareId,
            @Valid @RequestBody CourseMembershipRequests.MembershipConfirmationRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(membershipService.confirm(principal, squareId, request,
                idempotencyKey, servletRequest.getRequestURI()));
    }

    @PostMapping("/squares/{squareId}/join-requests")
    public ApiResponse<CourseMembershipViews.JoinRequestView> createJoinRequest(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String squareId,
            @Valid @RequestBody CourseMembershipRequests.JoinRequestCreateRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(membershipService.createJoinRequest(principal, squareId, request,
                idempotencyKey, servletRequest.getRequestURI()));
    }

    @GetMapping("/me/square-membership-requests")
    public ApiResponse<ListEnvelope<CourseMembershipViews.JoinRequestView>> myRequests(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return ApiResponse.ok(membershipService.myRequests(principal, status, cursor, limit));
    }

    @GetMapping("/squares/{squareId}/join-requests")
    public ApiResponse<ListEnvelope<CourseMembershipViews.JoinRequestView>> squareRequests(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String squareId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return ApiResponse.ok(membershipService.squareRequests(principal, squareId, status, cursor, limit));
    }

    @GetMapping("/square-membership-requests/{requestId}")
    public ApiResponse<CourseMembershipViews.JoinRequestView> requestDetail(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String requestId) {
        return ApiResponse.ok(membershipService.requestDetail(principal, requestId));
    }

    @PostMapping("/square-membership-requests/{requestId}/decisions")
    public ApiResponse<CourseMembershipViews.JoinRequestDecisionView> decide(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String requestId,
            @Valid @RequestBody CourseMembershipRequests.JoinRequestDecisionRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(membershipService.decide(principal, requestId, request, idempotencyKey,
                servletRequest.getRequestURI()));
    }
}
