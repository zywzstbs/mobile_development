package cn.gulupai.server.platform.api;

import cn.gulupai.server.common.api.ApiCode;
import cn.gulupai.server.common.api.ApiException;
import cn.gulupai.server.common.api.ApiResponse;
import cn.gulupai.server.common.security.AuthenticatedUser;
import cn.gulupai.server.platform.application.AdminTexts;
import cn.gulupai.server.platform.application.PlatformReadService;
import cn.gulupai.server.platform.application.PlatformWriteService;
import jakarta.servlet.http.HttpServletRequest;
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
@RequestMapping("/api/v1/admin")
public class PlatformAdminController {

    public static final String OPERATION_HEADER = "X-Operation-Id";

    private final PlatformReadService readService;
    private final PlatformWriteService writeService;

    public PlatformAdminController(PlatformReadService readService, PlatformWriteService writeService) {
        this.readService = readService;
        this.writeService = writeService;
    }

    @GetMapping("/dashboard")
    public ApiResponse<PlatformViews.DashboardView> dashboard(@AuthenticationPrincipal AuthenticatedUser principal) {
        return ApiResponse.ok(readService.dashboard(principal));
    }

    @GetMapping("/users")
    public ApiResponse<PlatformViews.PageView<PlatformViews.AccountItem>> users(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String cursor) {
        return ApiResponse.ok(readService.users(principal, limit, query, status, cursor));
    }

    @GetMapping("/users/{userId}")
    public ApiResponse<PlatformViews.AccountDetail> user(@AuthenticationPrincipal AuthenticatedUser principal,
                                                         @PathVariable String userId) {
        return ApiResponse.ok(readService.user(principal, userId));
    }

    @PostMapping("/users/{userId}/status-changes")
    public ApiResponse<PlatformViews.AdminOperationReceipt> changeStatus(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String userId,
            @RequestBody PlatformRequests.StatusChangeRequest request,
            @RequestHeader(value = OPERATION_HEADER, required = false) String operationId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(writeService.changeStatus(principal, userId, request, operation(operationId),
                idempotencyKey, servletRequest.getRequestURI()));
    }

    @PostMapping("/users/{userId}/password-reset-requests")
    public ApiResponse<Void> passwordReset(@AuthenticationPrincipal AuthenticatedUser principal,
                                           @PathVariable String userId,
                                           @RequestBody PlatformRequests.PasswordResetRequest request) {
        writeService.rejectPasswordReset(principal, userId, request);
        throw new ApiException(ApiCode.ENDPOINT_NOT_IMPLEMENTED);
    }

    @GetMapping("/teacher-applications")
    public ApiResponse<PlatformViews.PageView<PlatformViews.TeacherApplicationItem>> teacherApplications(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor) {
        return ApiResponse.ok(readService.teacherApplications(principal, status, limit, cursor));
    }

    @GetMapping("/teacher-applications/{applicationId}")
    public ApiResponse<PlatformViews.TeacherApplicationDetail> teacherApplication(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String applicationId) {
        return ApiResponse.ok(readService.teacherApplication(principal, applicationId));
    }

    @PostMapping("/teacher-applications/{applicationId}/decisions")
    public ApiResponse<PlatformViews.AdminOperationReceipt> decideTeacher(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String applicationId,
            @RequestBody PlatformRequests.DecisionRequest request,
            @RequestHeader(value = OPERATION_HEADER, required = false) String operationId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(writeService.decideTeacher(principal, applicationId, request, operation(operationId),
                idempotencyKey, servletRequest.getRequestURI()));
    }

    @GetMapping("/courses")
    public ApiResponse<PlatformViews.PageView<PlatformViews.CourseItem>> courses(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String schoolId,
            @RequestParam(required = false) String termId,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String cursor) {
        return ApiResponse.ok(readService.courses(principal, limit, schoolId, termId, query, cursor));
    }

    @GetMapping("/courses/{squareId}")
    public ApiResponse<PlatformViews.CourseDetail> course(@AuthenticationPrincipal AuthenticatedUser principal,
                                                          @PathVariable String squareId) {
        return ApiResponse.ok(readService.course(principal, squareId));
    }

    @PostMapping("/course-claims/{claimId}/decisions")
    public ApiResponse<PlatformViews.AdminOperationReceipt> decideClaim(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String claimId,
            @RequestBody PlatformRequests.DecisionRequest request,
            @RequestHeader(value = OPERATION_HEADER, required = false) String operationId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(writeService.decideClaim(principal, claimId, request, operation(operationId),
                idempotencyKey, servletRequest.getRequestURI()));
    }

    @PostMapping("/admin-grants/{grantId}/revocations")
    public ApiResponse<PlatformViews.AdminOperationReceipt> revokeAssistant(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String grantId,
            @RequestBody PlatformRequests.RevocationRequest request,
            @RequestHeader(value = OPERATION_HEADER, required = false) String operationId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(writeService.revokeAssistant(principal, grantId, request, operation(operationId),
                idempotencyKey, servletRequest.getRequestURI()));
    }

    @GetMapping("/audit-logs")
    public ApiResponse<PlatformViews.PageView<PlatformViews.AuditItem>> audits(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor) {
        return ApiResponse.ok(readService.audits(principal, limit, cursor));
    }

    @GetMapping("/super-admins")
    public ApiResponse<PlatformViews.SuperAdminList> superAdmins(@AuthenticationPrincipal AuthenticatedUser principal) {
        return ApiResponse.ok(readService.superAdmins(principal));
    }

    @PostMapping("/super-admins")
    public ApiResponse<PlatformViews.AdminOperationReceipt> grantSuperAdmin(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestBody PlatformRequests.SuperAdminGrantRequest request,
            @RequestHeader(value = OPERATION_HEADER, required = false) String operationId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(writeService.grantSuperAdmin(principal, request, operation(operationId),
                idempotencyKey, servletRequest.getRequestURI()));
    }

    @PostMapping("/super-admins/{userId}/revocations")
    public ApiResponse<PlatformViews.AdminOperationReceipt> revokeSuperAdmin(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String userId,
            @RequestBody PlatformRequests.RevocationRequest request,
            @RequestHeader(value = OPERATION_HEADER, required = false) String operationId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(writeService.revokeSuperAdmin(principal, userId, request, operation(operationId),
                idempotencyKey, servletRequest.getRequestURI()));
    }

    private static String operation(String header) {
        if (header == null || header.isBlank()) {
            throw new ApiException(ApiCode.REAUTH_REQUIRED);
        }
        return AdminTexts.requireId(header, "operationId");
    }
}
