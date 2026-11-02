package cn.gulupai.server.task.api;

import cn.gulupai.server.common.api.ApiResponse;
import cn.gulupai.server.common.api.ListEnvelope;
import cn.gulupai.server.common.security.AuthenticatedUser;
import cn.gulupai.server.task.application.TaskService;
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
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class TaskController {

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    @GetMapping("/projects/{projectId}/board")
    public ApiResponse<TaskViews.BoardView> board(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String projectId) {
        return ApiResponse.ok(taskService.board(principal, projectId));
    }

    @GetMapping("/projects/{projectId}/tasks")
    public ApiResponse<ListEnvelope<TaskViews.TaskView>> tasks(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String projectId) {
        return ApiResponse.ok(ListEnvelope.full(taskService.tasks(principal, projectId)));
    }

    @GetMapping("/tasks/{taskId}")
    public ApiResponse<TaskViews.TaskView> task(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String taskId) {
        return ApiResponse.ok(taskService.task(principal, taskId));
    }

    @PatchMapping("/tasks/{taskId}")
    public ApiResponse<TaskViews.TaskView> moveStage(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String taskId,
            @Valid @RequestBody TaskRequests.MoveStageRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(taskService.moveStage(principal, taskId, request, idempotencyKey,
                servletRequest.getRequestURI()));
    }

    @PostMapping("/projects/{projectId}/tasks")
    public ApiResponse<TaskViews.TaskView> create(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String projectId,
            @Valid @RequestBody TaskRequests.CreateTaskRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(taskService.create(principal, projectId, request, idempotencyKey,
                servletRequest.getRequestURI()));
    }

    @PostMapping("/tasks/{taskId}/claims")
    public ApiResponse<TaskViews.TaskView> claim(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String taskId,
            @Valid @RequestBody TaskRequests.ClaimRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(taskService.claim(principal, taskId, request, idempotencyKey,
                servletRequest.getRequestURI()));
    }

    @PostMapping("/tasks/{taskId}/starts")
    public ApiResponse<TaskViews.TaskView> start(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String taskId,
            @Valid @RequestBody TaskRequests.StartRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(taskService.start(principal, taskId, request, idempotencyKey,
                servletRequest.getRequestURI()));
    }

    @PutMapping("/projects/{projectId}/me/color")
    public ApiResponse<TaskViews.ColorResult> color(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String projectId,
            @Valid @RequestBody TaskRequests.ColorRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(taskService.color(principal, projectId, request, idempotencyKey,
                servletRequest.getRequestURI()));
    }

    @PutMapping("/projects/{projectId}/stages")
    public ApiResponse<TaskViews.StagesResult> stages(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String projectId,
            @Valid @RequestBody TaskRequests.ReplaceStagesRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(taskService.replaceStages(principal, projectId, request, idempotencyKey,
                servletRequest.getRequestURI()));
    }

    @PutMapping("/tasks/{taskId}/dependencies")
    public ApiResponse<TaskViews.TaskView> dependencies(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String taskId,
            @Valid @RequestBody TaskRequests.ReplaceDependenciesRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(taskService.replaceDependencies(principal, taskId, request, idempotencyKey,
                servletRequest.getRequestURI()));
    }
}
