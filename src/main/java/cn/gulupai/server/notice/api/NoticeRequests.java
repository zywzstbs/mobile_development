package cn.gulupai.server.notice.api;

import cn.gulupai.server.common.validation.Utf16Length;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public final class NoticeRequests {
    private NoticeRequests() {
    }

    public record RefInput(@NotBlank String type, @NotBlank String id,
                           @NotNull @PositiveOrZero Integer version) {
    }

    public record Create(
            @NotBlank @Utf16Length(max = 120) String title,
            @NotBlank @Utf16Length(max = 5000) String content,
            @Pattern(regexp = "TEACHER|CLASS_ASSISTANT") String publisherRole,
            String previewToken
    ) {
    }

    public record Preview(
            @NotNull @PositiveOrZero @Max(999_999_999) Integer expectedRevision,
            @Pattern(regexp = "TEACHER|CLASS_ASSISTANT") String publisherRole,
            String assignmentId,
            @NotBlank @Utf16Length(max = 120) String title,
            @NotBlank @Utf16Length(max = 5000) String content,
            @NotBlank @Pattern(regexp = "COURSE_INFO|ASSIGNMENT|EXAM|MATERIAL|OTHER") String category,
            @NotBlank @Pattern(regexp = "NORMAL|IMPORTANT") String importance,
            @NotNull Boolean requiresAcknowledgement,
            Instant acknowledgeDueAt,
            Instant expiresAt,
            @Size(max = 5) List<RefInput> refs
    ) {
    }

    public record Revision(
            @NotNull @PositiveOrZero @Max(999_999_999) Integer expectedRevision,
            @NotBlank @Pattern(regexp = "SUBSTANTIVE|FORMAT_ONLY") String changeKind,
            @NotBlank @Utf16Length(max = 120) String title,
            @NotBlank @Utf16Length(max = 5000) String content,
            @Utf16Length(max = 500) String changeSummary,
            String previewToken
    ) {
    }

    public record Revoke(
            @NotNull @PositiveOrZero @Max(999_999_999) Integer expectedRevision,
            @NotBlank @Utf16Length(max = 500) String reason
    ) {
    }

    public record Receipt(
            @NotNull @PositiveOrZero Integer contentVersion
    ) {
    }

    public record ReceiptFilter(
            @NotNull @PositiveOrZero Integer contentVersion,
            @Pattern(regexp = "ALL|UNREAD|UNACKNOWLEDGED") String filter
    ) {
    }

    public record ReminderPreview(
            @NotNull @PositiveOrZero @Max(999_999_999) Integer expectedRevision,
            @NotNull @PositiveOrZero Integer contentVersion
    ) {
    }

    public record Reminder(
            @NotNull @PositiveOrZero Integer contentVersion,
            @NotBlank String previewToken
    ) {
    }

    public record ReadAction(
            @Size(max = 100) List<String> notificationIds,
            String expectedVisibleThroughId
    ) {
    }

    public record Preferences(
            @NotNull @PositiveOrZero @Max(999_999_999) Integer expectedRevision,
            @NotBlank @Pattern(regexp = "IN_APP|SYSTEM") String channel,
            @NotBlank @Pattern(regexp = "ALL|MENTIONS|MUTE") String chatDefault,
            @NotBlank @Pattern(regexp = "([01][0-9]|2[0-3]):[0-5][0-9]") String quietStart,
            @NotBlank @Pattern(regexp = "([01][0-9]|2[0-3]):[0-5][0-9]") String quietEnd,
            @NotBlank @Utf16Length(max = 80) String timezone
    ) {
    }
}
