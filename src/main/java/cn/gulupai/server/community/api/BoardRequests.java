package cn.gulupai.server.community.api;

import cn.gulupai.server.common.validation.Utf16Length;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;

public final class BoardRequests {
    private BoardRequests() {
    }

    public record RefInput(@NotBlank String type, @NotBlank String id,
                           @NotNull @PositiveOrZero Integer version) {
    }

    public record CreateTopic(
            @NotBlank @Utf16Length(max = 120) String title,
            @NotBlank @Utf16Length(max = 5000) String content,
            @NotBlank @Pattern(regexp = "QUESTION|SUGGESTION|MATERIAL|CONCLUSION") String tag,
            @Size(max = 5) List<@Valid RefInput> refs,
            @Size(max = 20) List<@NotBlank String> mentions
    ) {
    }

    public record CreateReply(
            @NotNull @PositiveOrZero @Max(999_999_999) Integer expectedTopicRevision,
            @NotBlank @Utf16Length(max = 5000) String content,
            @Utf16Length(max = 120) String replyToId,
            @Size(max = 20) List<@NotBlank String> mentions
    ) {
    }

    public record RevisionRequest(
            @NotNull @PositiveOrZero @Max(999_999_999) Integer expectedRevision,
            @NotBlank @Utf16Length(max = 120) String title,
            @NotBlank @Utf16Length(max = 5000) String content
    ) {
    }

    public record ReplyRevisionRequest(
            @NotNull @PositiveOrZero @Max(999_999_999) Integer expectedRevision,
            @NotBlank @Utf16Length(max = 5000) String content
    ) {
    }

    public record WithdrawRequest(
            @NotNull @PositiveOrZero @Max(999_999_999) Integer expectedRevision
    ) {
    }

    public record StatusRequest(
            @NotNull @PositiveOrZero @Max(999_999_999) Integer expectedRevision,
            @NotBlank @Pattern(regexp = "RESOLVE|REOPEN|CLOSE|SET_HELPFUL") String action,
            @Utf16Length(max = 120) String helpfulReplyId,
            @NotBlank @Utf16Length(max = 500) String note
    ) {
    }

    public record PinRequest(
            @NotNull @PositiveOrZero @Max(999_999_999) Integer expectedRevision,
            @NotNull Boolean pinned
    ) {
    }

    public record FollowRequest(
            @NotNull @PositiveOrZero @Max(999_999_999) Integer expectedRevision,
            @NotNull Boolean following
    ) {
    }
}
