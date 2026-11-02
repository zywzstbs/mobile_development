package cn.gulupai.server.notice.api;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;

public final class NoticeViews {
    private NoticeViews() {
    }

    public record Ref(String type, String id, int version) {
    }

    public record Receipt(int contentVersion, Instant readAt, Instant acknowledgedAt) {
    }

    public record Announcement(String id, String squareId, String assignmentId, String authorId,
                               String authorName, String title, String content, Instant createdAt,
                               int revision, String publisherRole, String category, String importance,
                               int contentVersion, int displayRevision, String recipientSnapshotId,
                               int recipientCount, boolean requiresAcknowledgement,
                               Instant acknowledgeDueAt, Instant expiresAt, String status,
                               String changeSummary, Receipt myReceipt, List<Ref> refs) {
        public Announcement {
            refs = List.copyOf(refs);
        }
    }

    public record Preview(String previewToken, Instant expiresAt, String publisherRole,
                          int recipientCount, Scope scope) {
    }

    public record Scope(String squareId, String assignmentId) {
    }

    public record ReceiptRow(String userId, String displayName, Instant readAt,
                             Instant acknowledgedAt) {
    }

    public record ReceiptPage(int contentVersion, int recipientCount, int readCount,
                              int acknowledgedCount, List<ReceiptRow> items) {
        public ReceiptPage {
            items = List.copyOf(items);
        }
    }

    public record ReminderPreview(String previewToken, Instant expiresAt, int recipientCount) {
    }

    public record Reminder(String announcementId, int contentVersion, int recordedCount) {
    }

    public record Notification(String id, String sourceType, String sourceId, String category,
                               String title, String body, int eventVersion, Instant visibleAt,
                               Instant readAt, Instant createdAt) {
    }

    public record Preferences(int revision, String channel, String chatDefault, String quietStart,
                              String quietEnd, String timezone) {
    }
}
