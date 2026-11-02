package cn.gulupai.server.profile.api;

import cn.gulupai.server.common.validation.Utf16Length;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;

public final class ProfileRequests {

    private ProfileRequests() {
    }

    public record ArtifactInput(
            @NotBlank @Pattern(regexp = "LINK|FILE") String kind,
            @Utf16Length(max = 2000) String url,
            @NotBlank @Utf16Length(max = 200) String title
    ) {
    }

    public record WorkRequest(
            @NotNull @PositiveOrZero @Max(999_999_999) Integer expectedRevision,
            @NotBlank @Utf16Length(max = 120) String title,
            @Utf16Length(max = 2000) String description,
            @NotBlank @Utf16Length(max = 200) String role,
            @NotBlank @Utf16Length(max = 2000) String myContribution,
            @NotBlank String sourceProjectId,
            @NotBlank @Pattern(regexp = "PRIVATE|TEAM|CLASS") String audience,
            @Size(max = 10) List<@Valid ArtifactInput> artifacts
    ) {
    }

    public record WorkWithdrawRequest(
            @NotNull @PositiveOrZero @Max(999_999_999) Integer expectedRevision
    ) {
    }

    public record ObservationInput(
            @NotBlank @Pattern(regexp = "DELIVERY|COMMUNICATION|REWORK") String dimension,
            @NotBlank @Pattern(regexp = "POSITIVE|NEEDS_IMPROVEMENT|NOT_OBSERVED") String level,
            @NotBlank @Utf16Length(max = 1000) String evidenceText
    ) {
    }

    public record PeerReviewRequest(
            @NotBlank String targetUserId,
            @NotNull @PositiveOrZero @Max(999_999_999) Integer expectedRevision,
            @NotNull Boolean observedEnough,
            @NotNull @Size(min = 3, max = 3) List<@Valid ObservationInput> observations,
            @Utf16Length(max = 500) String comment
    ) {
    }

    public record PeerReviewWithdrawRequest(
            @NotNull @PositiveOrZero @Max(999_999_999) Integer expectedRevision,
            @NotBlank @Utf16Length(max = 500) String reason
    ) {
    }

    public record AiReferenceRequest(
            @NotNull @PositiveOrZero @Max(999_999_999) Integer expectedProjectRevision,
            @NotBlank String sourceProjectId,
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,80}") String clientRecordId,
            @NotBlank @Pattern(regexp = "TOPIC_ANALYSIS|TASK_PLAN|DOCUMENT_REVIEW|RECRUITMENT") String scene,
            @NotNull @Utf16Length(max = 120) String model,
            @NotBlank @Utf16Length(max = 120) String title,
            @NotBlank @Utf16Length(max = 16000) String text
    ) {
    }
}
