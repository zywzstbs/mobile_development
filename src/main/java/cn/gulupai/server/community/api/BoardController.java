package cn.gulupai.server.community.api;

import cn.gulupai.server.common.api.ApiResponse;
import cn.gulupai.server.common.api.ListEnvelope;
import cn.gulupai.server.common.security.AuthenticatedUser;
import cn.gulupai.server.community.application.BoardService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class BoardController {

    private final BoardService board;

    public BoardController(BoardService board) {
        this.board = board;
    }

    @GetMapping("/projects/{projectId}/board-topics")
    public ApiResponse<ListEnvelope<BoardViews.Topic>> topics(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String projectId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String q) {
        return ApiResponse.ok(board.topics(principal, projectId, status, q));
    }

    @PostMapping("/projects/{projectId}/board-topics")
    public ApiResponse<BoardViews.Topic> createTopic(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String projectId,
            @Valid @RequestBody BoardRequests.CreateTopic request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(board.createTopic(principal, projectId, request, key,
                servletRequest.getRequestURI()));
    }

    @GetMapping("/board-topics/{topicId}")
    public ApiResponse<BoardViews.Topic> topic(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String topicId) {
        return ApiResponse.ok(board.topic(principal, topicId));
    }

    @GetMapping("/board-topics/{topicId}/replies")
    public ApiResponse<ListEnvelope<BoardViews.Reply>> replies(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String topicId) {
        return ApiResponse.ok(board.replies(principal, topicId));
    }

    @PostMapping("/board-topics/{topicId}/replies")
    public ApiResponse<BoardViews.Reply> createReply(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String topicId,
            @Valid @RequestBody BoardRequests.CreateReply request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(board.createReply(principal, topicId, request, key,
                servletRequest.getRequestURI()));
    }

    @PostMapping("/board-topics/{contentId}/revisions")
    public ApiResponse<BoardViews.Topic> reviseTopic(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String contentId,
            @Valid @RequestBody BoardRequests.RevisionRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(board.reviseTopic(principal, contentId, request, key,
                servletRequest.getRequestURI()));
    }

    @PostMapping("/board-topics/{contentId}/withdrawals")
    public ApiResponse<BoardViews.Topic> withdrawTopic(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String contentId,
            @Valid @RequestBody BoardRequests.WithdrawRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(board.withdrawTopic(principal, contentId, request, key,
                servletRequest.getRequestURI()));
    }

    @GetMapping("/board-topics/{contentId}/revisions")
    public ApiResponse<ListEnvelope<BoardViews.Revision>> topicRevisions(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String contentId) {
        return ApiResponse.ok(board.revisions(principal, "TOPIC", contentId));
    }

    @PostMapping("/board-replies/{contentId}/revisions")
    public ApiResponse<BoardViews.Reply> reviseReply(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String contentId,
            @Valid @RequestBody BoardRequests.ReplyRevisionRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(board.reviseReply(principal, contentId, request, key,
                servletRequest.getRequestURI()));
    }

    @PostMapping("/board-replies/{contentId}/withdrawals")
    public ApiResponse<BoardViews.Reply> withdrawReply(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String contentId,
            @Valid @RequestBody BoardRequests.WithdrawRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(board.withdrawReply(principal, contentId, request, key,
                servletRequest.getRequestURI()));
    }

    @GetMapping("/board-replies/{contentId}/revisions")
    public ApiResponse<ListEnvelope<BoardViews.Revision>> replyRevisions(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String contentId) {
        return ApiResponse.ok(board.revisions(principal, "REPLY", contentId));
    }

    @PostMapping("/board-topics/{topicId}/status-actions")
    public ApiResponse<BoardViews.Topic> status(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String topicId,
            @Valid @RequestBody BoardRequests.StatusRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(board.statusAction(principal, topicId, request, key,
                servletRequest.getRequestURI()));
    }

    @PutMapping("/board-topics/{topicId}/pin")
    public ApiResponse<BoardViews.Topic> pin(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String topicId,
            @Valid @RequestBody BoardRequests.PinRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(board.pin(principal, topicId, request, key,
                servletRequest.getRequestURI()));
    }

    @PutMapping("/board-topics/{topicId}/me/following")
    public ApiResponse<BoardViews.Topic> follow(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String topicId,
            @Valid @RequestBody BoardRequests.FollowRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(board.follow(principal, topicId, request, key,
                servletRequest.getRequestURI()));
    }
}
