package cn.gulupai.server.support;

import cn.gulupai.server.common.api.ApiResponse;
import cn.gulupai.server.common.api.ListEnvelope;
import cn.gulupai.server.common.security.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
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

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class HelpSupportController {

    private final HelpSupportService service;

    public HelpSupportController(HelpSupportService service) {
        this.service = service;
    }

    @GetMapping("/help/content-pack")
    public ApiResponse<Map<String, Object>> pack(@RequestParam(required = false) String knownVersion,
                                                 @RequestParam(required = false) String appVersion) {
        return ApiResponse.ok(service.contentPack(knownVersion));
    }

    @GetMapping("/help/articles")
    public ApiResponse<ListEnvelope<Map<String, Object>>> articles(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(required = false) String squareId,
            @RequestParam(required = false) String q) {
        return ApiResponse.ok(ListEnvelope.full(service.articles(principal, squareId, q)));
    }

    @GetMapping("/help/articles/{articleId}")
    public ApiResponse<Map<String, Object>> article(@AuthenticationPrincipal AuthenticatedUser principal,
                                                    @PathVariable String articleId) {
        return ApiResponse.ok(service.article(principal, articleId));
    }

    @PutMapping("/help/articles/{articleId}/me/feedback")
    public ApiResponse<Map<String, Object>> feedback(@AuthenticationPrincipal AuthenticatedUser principal,
                                                     @PathVariable String articleId,
                                                     @RequestBody Map<String, Object> body) {
        return ApiResponse.ok(service.feedback(principal, articleId,
                Boolean.TRUE.equals(body.get("helpful"))));
    }

    @PutMapping("/help/articles/{articleId}")
    public ApiResponse<Map<String, Object>> updateArticle(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String articleId,
            @RequestBody Map<String, Object> body) {
        return ApiResponse.ok(service.updateArticle(principal, articleId, body));
    }

    @GetMapping("/support/routes")
    public ApiResponse<ListEnvelope<Map<String, Object>>> routes() {
        return ApiResponse.ok(ListEnvelope.full(service.routes()));
    }

    @PostMapping("/me/support-tickets")
    public ApiResponse<Map<String, Object>> createTicket(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            HttpServletRequest request) {
        return ApiResponse.ok(service.createTicket(principal, body, key, request.getRequestURI()));
    }

    @GetMapping("/me/support-tickets")
    public ApiResponse<ListEnvelope<Map<String, Object>>> myTickets(
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return ApiResponse.ok(ListEnvelope.full(service.myTickets(principal)));
    }

    @GetMapping("/support-tickets/{ticketId}")
    public ApiResponse<Map<String, Object>> ticket(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String ticketId) {
        return ApiResponse.ok(service.ticketDetail(principal, ticketId));
    }

    @PostMapping("/support-tickets/{ticketId}/replies")
    public ApiResponse<Map<String, Object>> reply(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String ticketId,
            @RequestBody Map<String, Object> body) {
        return ApiResponse.ok(service.reply(principal, ticketId, body));
    }

    @GetMapping("/support-workspace/tickets")
    public ApiResponse<ListEnvelope<Map<String, Object>>> workspace(
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return ApiResponse.ok(ListEnvelope.full(service.workspace(principal)));
    }

    @PostMapping("/support-tickets/{ticketId}/acceptances")
    public ApiResponse<Map<String, Object>> accept(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String ticketId) {
        return ApiResponse.ok(service.accept(principal, ticketId));
    }

    @PostMapping("/support-tickets/{ticketId}/status-actions")
    public ApiResponse<Map<String, Object>> status(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String ticketId,
            @RequestBody Map<String, Object> body) {
        if ("REOPEN".equals(body.get("action"))) {
            return ApiResponse.ok(service.reopen(principal, ticketId));
        }
        return ApiResponse.ok(service.status(principal, ticketId, body));
    }

    @PostMapping("/support-tickets/{ticketId}/resolution-decisions")
    public ApiResponse<Map<String, Object>> resolution(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String ticketId,
            @RequestBody Map<String, Object> body) {
        return ApiResponse.ok(service.resolution(principal, ticketId, body));
    }

    @PostMapping("/support-tickets/{ticketId}/transfers")
    public ApiResponse<Map<String, Object>> transfer(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String ticketId,
            @RequestBody Map<String, Object> body) {
        return ApiResponse.ok(service.transfer(principal, ticketId, body));
    }

    @PostMapping("/support-tickets/{ticketId}/closures")
    public ApiResponse<Map<String, Object>> close(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String ticketId) {
        return ApiResponse.ok(service.closure(principal, ticketId));
    }

    @PostMapping("/me/content-reports")
    public ApiResponse<Map<String, Object>> report(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            HttpServletRequest request) {
        return ApiResponse.ok(service.report(principal, body, key, request.getRequestURI()));
    }

    @GetMapping("/me/content-reports")
    public ApiResponse<ListEnvelope<Map<String, Object>>> myReports(
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return ApiResponse.ok(ListEnvelope.full(service.myReports(principal)));
    }

    @GetMapping("/content-reports/{reportId}")
    public ApiResponse<Map<String, Object>> reportDetail(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String reportId) {
        return ApiResponse.ok(service.reportDetail(principal, reportId));
    }

    @PostMapping("/content-reports/{reportId}/handling-actions")
    public ApiResponse<Map<String, Object>> handle(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String reportId,
            @RequestBody Map<String, Object> body) {
        return ApiResponse.ok(service.handleReport(principal, reportId, body));
    }
}
