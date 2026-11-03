package cn.gulupai.server.support;

import cn.gulupai.server.common.validation.OpaqueId;
import cn.gulupai.server.common.validation.Utf16Length;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 帮助、求助和举报的明确请求体。未知字段由 Jackson 拒绝为 FIELD_NOT_ALLOWED。
 */
public final class SupportRequests {

    static final String ID = "[A-Za-z0-9_-]{1,120}";
    static final String TOKEN = "[A-Z][A-Z0-9_]{0,39}";
    static final String APP_VERSION = "\\d{1,4}\\.\\d{1,4}\\.\\d{1,4}";
    static final String APP_RANGE = "\\d{1,4}\\.\\d{1,4}\\.(\\d{1,4}|\\*)";

    private SupportRequests() {
    }

    public record TicketContext(
            @NotBlank @Pattern(regexp = TOKEN) String pageType,
            @OpaqueId String resourceId,
            @NotBlank @Pattern(regexp = TOKEN) String errorCode,
            @NotBlank @Pattern(regexp = APP_VERSION) String appVersion
    ) {
    }

    public record CreateTicket(
            @NotBlank @OpaqueId String clientRequestId,
            @NotBlank @OpaqueId String routeId,
            @NotBlank @Pattern(regexp = "OPERATION|COURSE_REQUIREMENT") String category,
            @NotBlank @Utf16Length(min = 1, max = 60) String title,
            @NotBlank @Utf16Length(min = 1, max = 3000) String content,
            @JsonProperty(required = true) @OpaqueId String squareId,
            @NotNull @Valid TicketContext context,
            @Size(max = 3) List<@OpaqueId String> attachmentFileIds
    ) {
    }

    public record Reply(
            @NotNull @Min(1) @Max(999_999_999) Integer expectedRevision,
            @NotBlank @Utf16Length(min = 1, max = 3000) String content,
            @Size(max = 3) List<@OpaqueId String> attachmentFileIds
    ) {
    }

    public record ExpectedRevision(@NotNull @Min(1) @Max(999_999_999) Integer expectedRevision) {
    }

    public record StatusAction(
            @NotNull @Min(1) @Max(999_999_999) Integer expectedRevision,
            @NotBlank @Pattern(regexp = "REQUEST_INFORMATION|ANSWERED") String action,
            @Utf16Length(max = 500) String reason
    ) {
    }

    public record Resolution(
            @NotNull @Min(1) @Max(999_999_999) Integer expectedRevision,
            @NotNull Boolean resolved,
            @NotNull @Utf16Length(max = 1000) String note
    ) {
    }

    public record Transfer(
            @NotNull @Min(1) @Max(999_999_999) Integer expectedRevision,
            @NotBlank @OpaqueId String targetRouteId,
            @NotBlank @Utf16Length(min = 1, max = 500) String reason
    ) {
    }

    public record Closure(
            @NotNull @Min(1) @Max(999_999_999) Integer expectedRevision,
            @NotBlank @Utf16Length(min = 1, max = 500) String reason,
            @OpaqueId String relatedTicketId,
            @NotBlank @Utf16Length(min = 1, max = 500) String nextAction
    ) {
    }

    public record Feedback(
            @NotNull @Min(1) @Max(999_999_999) Integer contentVersion,
            @NotNull Boolean helpful,
            @JsonProperty(required = true) @Pattern(regexp = TOKEN) String reasonCode
    ) {
    }

    public record ArticleEntry(
            @NotBlank @Pattern(regexp = TOKEN) String type,
            @OpaqueId String id
    ) {
    }

    public record ArticleBody(
            @NotBlank @Utf16Length(min = 1, max = 120) String title,
            @NotBlank @Pattern(regexp = TOKEN) String category,
            @NotNull @Size(max = 8) List<@Pattern(regexp = "STUDENT|TEACHER|CLASS_ADMIN") String> roles,
            @NotBlank @Pattern(regexp = "GENERAL|COURSE") String scope,
            @OpaqueId String squareId,
            @NotBlank @Pattern(regexp = APP_RANGE) String appVersionRange,
            @NotNull @Size(max = 20) List<@Utf16Length(max = 500) String> prerequisites,
            @NotNull @Size(max = 20) List<@NotBlank @Utf16Length(min = 1, max = 500) String> steps,
            @NotNull @Size(max = 20) List<@Utf16Length(max = 500) String> outcomes,
            @NotBlank @Utf16Length(min = 1, max = 500) String failureAction,
            @NotNull @Valid ArticleEntry entry
    ) {
    }

    public record ArticleUpdate(
            @NotNull @Min(1) @Max(999_999_999) Integer expectedRevision,
            @NotNull @Valid ArticleBody article
    ) {
    }

    public record ReportSource(
            @NotBlank @Pattern(regexp = "BOARD_TOPIC|BOARD_REPLY|CHAT_MESSAGE") String type,
            @NotBlank @OpaqueId String id,
            @NotNull @Min(1) @Max(999_999_999) Integer version
    ) {
    }

    public record CreateReport(
            @NotBlank @OpaqueId String clientRequestId,
            @NotNull @Valid ReportSource source,
            @NotBlank @Utf16Length(min = 1, max = 1000) String selectedExcerpt,
            @NotBlank @Utf16Length(min = 1, max = 1000) String reason
    ) {
    }

    public record ReportAction(
            @NotNull @Min(1) @Max(999_999_999) Integer expectedRevision,
            @NotBlank @Pattern(regexp = "ACCEPT|RECORD_RESULT|CLOSE") String action,
            @NotBlank @Utf16Length(min = 1, max = 1000) String note
    ) {
    }
}
