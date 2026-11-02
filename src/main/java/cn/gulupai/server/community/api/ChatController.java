package cn.gulupai.server.community.api;

import cn.gulupai.server.common.api.ApiResponse;
import cn.gulupai.server.common.security.AuthenticatedUser;
import cn.gulupai.server.community.application.ChatService;
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
public class ChatController {

    private final ChatService chat;

    public ChatController(ChatService chat) {
        this.chat = chat;
    }

    @GetMapping("/projects/{projectId}/chat-messages")
    public ApiResponse<ChatViews.Page> messages(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String projectId,
            @RequestParam(required = false) Long afterSequence,
            @RequestParam(required = false) Long beforeSequence,
            @RequestParam(required = false) Long aroundSequence,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String afterChangeCursor) {
        return ApiResponse.ok(chat.messages(principal, projectId, afterSequence, beforeSequence,
                aroundSequence, q, limit, afterChangeCursor));
    }

    @PostMapping("/projects/{projectId}/chat-messages")
    public ApiResponse<ChatViews.Message> send(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String projectId,
            @Valid @RequestBody ChatRequests.Send request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(chat.send(principal, projectId, request, key,
                servletRequest.getRequestURI()));
    }

    @GetMapping("/projects/{projectId}/chat-message-resolutions")
    public ApiResponse<ChatViews.Resolution> resolve(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String projectId,
            @RequestParam String clientMessageId) {
        return ApiResponse.ok(chat.resolve(principal, projectId, clientMessageId));
    }

    @PostMapping("/chat-messages/{messageId}/withdrawals")
    public ApiResponse<ChatViews.Message> withdraw(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String messageId,
            @Valid @RequestBody ChatRequests.Withdraw request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(chat.withdraw(principal, messageId, request, key,
                servletRequest.getRequestURI()));
    }

    @GetMapping("/projects/{projectId}/me/read-cursor")
    public ApiResponse<ChatViews.Cursor> cursor(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String projectId) {
        return ApiResponse.ok(chat.cursor(principal, projectId));
    }

    @PutMapping("/projects/{projectId}/me/read-cursor")
    public ApiResponse<ChatViews.Cursor> saveCursor(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String projectId,
            @Valid @RequestBody ChatRequests.CursorPut request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(chat.saveCursor(principal, projectId, request, key,
                servletRequest.getRequestURI()));
    }
}
