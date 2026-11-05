package cn.gulupai.server.course.api;

import cn.gulupai.server.common.api.ApiResponse;
import cn.gulupai.server.common.security.AuthenticatedUser;
import cn.gulupai.server.course.application.AssignmentService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class AssignmentController {

    private final AssignmentService assignmentService;

    public AssignmentController(AssignmentService assignmentService) {
        this.assignmentService = assignmentService;
    }

    @GetMapping("/assignments/{assignmentId}")
    public ApiResponse<AssignmentView> assignment(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String assignmentId) {
        return ApiResponse.ok(assignmentService.get(principal, assignmentId));
    }

    @PatchMapping("/assignments/{assignmentId}")
    public ApiResponse<AssignmentView> edit(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String assignmentId,
            @Valid @RequestBody PatchAssignmentRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(assignmentService.edit(principal, assignmentId, request, idempotencyKey,
                servletRequest.getRequestURI()));
    }

    @PostMapping("/assignments/{assignmentId}/confirmations")
    public ApiResponse<AssignmentView> confirm(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String assignmentId,
            @Valid @RequestBody ConfirmAssignmentRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(assignmentService.confirm(principal, assignmentId, request,
                idempotencyKey, servletRequest.getRequestURI()));
    }
}
