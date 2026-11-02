package cn.gulupai.server.teaching.api;

import cn.gulupai.server.common.api.ApiResponse;
import cn.gulupai.server.common.api.ListEnvelope;
import cn.gulupai.server.common.security.AuthenticatedUser;
import cn.gulupai.server.course.api.SquareView;
import cn.gulupai.server.teaching.application.TeacherService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class TeachingController {

    private final TeacherService teacherService;

    public TeachingController(TeacherService teacherService) {
        this.teacherService = teacherService;
    }

    @GetMapping("/me/admin-workspaces")
    public ApiResponse<ListEnvelope<SquareView>> adminWorkspaces(
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return ApiResponse.ok(ListEnvelope.full(teacherService.adminWorkspaces(principal)));
    }

    @GetMapping("/me/teaching-workspaces")
    public ApiResponse<TeachingViews.TeachingWorkspacesView> teachingWorkspaces(
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return ApiResponse.ok(teacherService.teachingWorkspaces(principal));
    }

    @GetMapping("/squares/{squareId}/admin-grants")
    public ApiResponse<ListEnvelope<TeachingViews.GrantView>> grants(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String squareId) {
        return ApiResponse.ok(ListEnvelope.full(teacherService.grants(principal, squareId)));
    }

    @GetMapping("/squares/{squareId}/teaching-progress")
    public ApiResponse<ListEnvelope<TeachingViews.TeachingProgressView>> teachingProgress(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String squareId) {
        return ApiResponse.ok(ListEnvelope.full(teacherService.teachingProgress(principal, squareId)));
    }

    @PostMapping("/squares/{squareId}/admin-grants")
    public ApiResponse<TeachingViews.GrantView> grant(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String squareId,
            @Valid @RequestBody TeachingRequests.GrantRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(teacherService.grant(principal, squareId, request, idempotencyKey,
                servletRequest.getRequestURI()));
    }

    @PostMapping("/admin-grants/{grantId}/revocations")
    public ApiResponse<TeachingViews.GrantView> revokeGrant(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String grantId,
            @Valid @RequestBody TeachingRequests.GrantRevocationRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(teacherService.revokeGrant(principal, grantId, request, idempotencyKey,
                servletRequest.getRequestURI()));
    }

    @PostMapping("/squares/{squareId}/teacher-claims")
    public ApiResponse<TeachingViews.TeacherClaimView> claim(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String squareId,
            @Valid @RequestBody TeachingRequests.TeacherClaimRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(teacherService.claim(principal, squareId, request, idempotencyKey,
                servletRequest.getRequestURI()));
    }

    @PostMapping("/squares/{squareId}/teacher-relinquishments")
    public ApiResponse<TeachingViews.RelinquishmentView> relinquish(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String squareId,
            @Valid @RequestBody TeachingRequests.RelinquishRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(teacherService.relinquish(principal, squareId, request, idempotencyKey,
                servletRequest.getRequestURI()));
    }

    @PostMapping("/squares/{squareId}/teacher-transfers")
    public ApiResponse<TeachingViews.TeacherTransferView> createTransfer(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String squareId,
            @Valid @RequestBody TeachingRequests.TeacherTransferRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(teacherService.createTransfer(principal, squareId, request,
                idempotencyKey, servletRequest.getRequestURI()));
    }

    @PostMapping("/teacher-transfers/{transferId}/responses")
    public ApiResponse<TeachingViews.TeacherTransferView> respondTransfer(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String transferId,
            @Valid @RequestBody TeachingRequests.TransferResponseRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(teacherService.respondTransfer(principal, transferId, request,
                idempotencyKey, servletRequest.getRequestURI()));
    }
}
