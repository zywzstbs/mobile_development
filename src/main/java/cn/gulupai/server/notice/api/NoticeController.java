package cn.gulupai.server.notice.api;

import cn.gulupai.server.common.api.ApiResponse;
import cn.gulupai.server.common.api.ListEnvelope;
import cn.gulupai.server.common.security.AuthenticatedUser;
import cn.gulupai.server.notice.application.NoticeOutboxWorker;
import cn.gulupai.server.notice.application.NoticeService;
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

import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class NoticeController {

    private final NoticeService notices;
    private final NoticeOutboxWorker outbox;

    public NoticeController(NoticeService notices, NoticeOutboxWorker outbox) {
        this.notices = notices;
        this.outbox = outbox;
    }

    @GetMapping("/squares/{squareId}/announcements")
    public ApiResponse<ListEnvelope<NoticeViews.Announcement>> announcements(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String squareId) {
        return ApiResponse.ok(ListEnvelope.full(notices.announcements(principal, squareId)));
    }

    @PostMapping("/squares/{squareId}/announcements")
    public ApiResponse<NoticeViews.Announcement> create(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String squareId,
            @Valid @RequestBody NoticeRequests.Create request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(notices.create(principal, squareId, request, key,
                servletRequest.getRequestURI()));
    }

    @PostMapping("/squares/{squareId}/announcement-previews")
    public ApiResponse<NoticeViews.Preview> preview(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String squareId,
            @Valid @RequestBody NoticeRequests.Preview request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(notices.preview(principal, squareId, request, key,
                servletRequest.getRequestURI()));
    }

    @GetMapping("/announcements/{announcementId}")
    public ApiResponse<NoticeViews.Announcement> announcement(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String announcementId) {
        return ApiResponse.ok(notices.announcement(principal, announcementId));
    }

    @PostMapping("/announcements/{announcementId}/revisions")
    public ApiResponse<NoticeViews.Announcement> revise(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String announcementId,
            @Valid @RequestBody NoticeRequests.Revision request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(notices.revise(principal, announcementId, request, key,
                servletRequest.getRequestURI()));
    }

    @PostMapping("/announcements/{announcementId}/revocations")
    public ApiResponse<NoticeViews.Announcement> revoke(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String announcementId,
            @Valid @RequestBody NoticeRequests.Revoke request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(notices.revoke(principal, announcementId, request, key,
                servletRequest.getRequestURI()));
    }

    @PostMapping("/announcements/{announcementId}/reads")
    public ApiResponse<NoticeViews.Receipt> read(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String announcementId,
            @Valid @RequestBody NoticeRequests.Receipt request) {
        return ApiResponse.ok(notices.read(principal, announcementId, request));
    }

    @PostMapping("/announcements/{announcementId}/acknowledgements")
    public ApiResponse<NoticeViews.Receipt> acknowledge(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String announcementId,
            @Valid @RequestBody NoticeRequests.Receipt request) {
        return ApiResponse.ok(notices.acknowledge(principal, announcementId, request));
    }

    @GetMapping("/announcements/{announcementId}/receipts")
    public ApiResponse<NoticeViews.ReceiptPage> receipts(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String announcementId,
            @RequestParam int contentVersion,
            @RequestParam(required = false) String filter) {
        return ApiResponse.ok(notices.receipts(principal, announcementId, contentVersion, filter));
    }

    @PostMapping("/announcements/{announcementId}/reminder-previews")
    public ApiResponse<NoticeViews.ReminderPreview> reminderPreview(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String announcementId,
            @Valid @RequestBody NoticeRequests.ReminderPreview request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(notices.reminderPreview(principal, announcementId, request, key,
                servletRequest.getRequestURI()));
    }

    @PostMapping("/announcements/{announcementId}/reminders")
    public ApiResponse<NoticeViews.Reminder> reminder(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String announcementId,
            @Valid @RequestBody NoticeRequests.Reminder request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(notices.reminder(principal, announcementId, request, key,
                servletRequest.getRequestURI()));
    }

    @GetMapping("/me/notifications")
    public ApiResponse<ListEnvelope<NoticeViews.Notification>> notifications(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(defaultValue = "false") boolean unreadOnly) {
        return ApiResponse.ok(ListEnvelope.full(notices.notifications(principal, unreadOnly)));
    }

    @PostMapping("/me/notifications/read-actions")
    public ApiResponse<Map<String, Object>> readNotifications(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody NoticeRequests.ReadAction request) {
        notices.readNotifications(principal, request);
        outbox.drain();
        return ApiResponse.ok(Map.of("updated", true));
    }

    @GetMapping("/me/notification-preferences")
    public ApiResponse<NoticeViews.Preferences> preferences(
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return ApiResponse.ok(notices.preferences(principal));
    }

    @PutMapping("/me/notification-preferences")
    public ApiResponse<NoticeViews.Preferences> savePreferences(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody NoticeRequests.Preferences request) {
        return ApiResponse.ok(notices.savePreferences(principal, request));
    }
}
