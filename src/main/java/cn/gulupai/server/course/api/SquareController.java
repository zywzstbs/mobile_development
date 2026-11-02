package cn.gulupai.server.course.api;

import cn.gulupai.server.common.api.ApiResponse;
import cn.gulupai.server.common.api.ListEnvelope;
import cn.gulupai.server.common.security.AuthenticatedUser;
import cn.gulupai.server.course.application.AssignmentService;
import cn.gulupai.server.course.application.SquareService;
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

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class SquareController {

    private final SquareService squareService;
    private final AssignmentService assignmentService;

    public SquareController(SquareService squareService, AssignmentService assignmentService) {
        this.squareService = squareService;
        this.assignmentService = assignmentService;
    }

    @GetMapping("/squares")
    public ApiResponse<ListEnvelope<SquareView>> squares(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam String schoolId,
            @RequestParam String termId,
            @RequestParam(required = false) String q) {
        List<SquareView> items = squareService.list(principal, schoolId, termId, q);
        return ApiResponse.ok(ListEnvelope.full(items));
    }

    @GetMapping("/squares/{squareId}")
    public ApiResponse<SquareView> square(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String squareId) {
        return ApiResponse.ok(squareService.get(principal, squareId));
    }

    @PostMapping("/squares/matches")
    public ApiResponse<CourseMatchView> match(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody CourseMatchRequest request) {
        return ApiResponse.ok(squareService.match(principal, request));
    }

    @PostMapping("/squares")
    public ApiResponse<SquareView> create(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody CreateSquareRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(squareService.create(principal, request, idempotencyKey,
                servletRequest.getRequestURI()));
    }

    @PostMapping("/square-number-resolutions")
    public ApiResponse<SquareView> resolveNumber(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody SquareNumberRequest request) {
        return ApiResponse.ok(squareService.resolveByNo(principal, request.squareNo().strip()));
    }

    @GetMapping("/squares/{squareId}/assignments")
    public ApiResponse<ListEnvelope<AssignmentView>> assignments(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String squareId) {
        List<AssignmentView> items = squareService.listAssignments(principal, squareId);
        return ApiResponse.ok(ListEnvelope.full(items));
    }

    @PostMapping("/squares/{squareId}/assignments")
    public ApiResponse<AssignmentView> createAssignment(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String squareId,
            @Valid @RequestBody CreateAssignmentRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(assignmentService.create(principal, squareId, request,
                idempotencyKey, servletRequest.getRequestURI()));
    }
}
