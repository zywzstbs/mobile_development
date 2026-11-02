package cn.gulupai.server.dice.api;

import cn.gulupai.server.common.api.ApiResponse;
import cn.gulupai.server.common.security.AuthenticatedUser;
import cn.gulupai.server.dice.application.DiceService;
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

@RestController
@RequestMapping("/api/v1")
public class DiceController {

    private final DiceService dice;

    public DiceController(DiceService dice) {
        this.dice = dice;
    }

    @PostMapping("/tasks/{taskId}/dice-sessions")
    public ApiResponse<DiceViews.Session> create(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String taskId,
            @Valid @RequestBody DiceRequests.CreateSessionRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(dice.create(principal, taskId, request, key,
                servletRequest.getRequestURI()));
    }

    @GetMapping("/dice-sessions/{sessionId}")
    public ApiResponse<DiceViews.Session> session(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String sessionId) {
        return ApiResponse.ok(dice.session(principal, sessionId));
    }

    @PostMapping("/dice-sessions/{sessionId}/participations")
    public ApiResponse<DiceViews.Session> participate(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String sessionId,
            @Valid @RequestBody DiceRequests.ParticipationRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(dice.participate(principal, sessionId, request, key,
                servletRequest.getRequestURI()));
    }

    @PostMapping("/dice-sessions/{sessionId}/rolls")
    public ApiResponse<DiceViews.Session> roll(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String sessionId,
            @Valid @RequestBody DiceRequests.RollRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(dice.roll(principal, sessionId, request, key,
                servletRequest.getRequestURI()));
    }

    @PostMapping("/dice-sessions/{sessionId}/terminations")
    public ApiResponse<DiceViews.Session> terminate(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String sessionId,
            @Valid @RequestBody DiceRequests.TerminationRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(dice.terminate(principal, sessionId, request, key,
                servletRequest.getRequestURI()));
    }

    @PostMapping("/dice-sessions/{sessionId}/outcome-decisions")
    public ApiResponse<DiceViews.Outcome> outcome(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String sessionId,
            @Valid @RequestBody DiceRequests.OutcomeDecisionRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(dice.outcome(principal, sessionId, request, key,
                servletRequest.getRequestURI()));
    }
}
