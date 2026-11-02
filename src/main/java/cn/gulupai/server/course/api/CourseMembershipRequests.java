package cn.gulupai.server.course.api;

import cn.gulupai.server.common.validation.Utf16Length;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;

public final class CourseMembershipRequests {

    private CourseMembershipRequests() {
    }

    public record ProofReferenceRequest(
            @NotBlank @Pattern(regexp = "LINK") String kind,
            @NotBlank @Utf16Length(max = 2048)
            @Pattern(regexp = "https?://.+", message = "材料链接必须为http/https URL") String url,
            @NotBlank @Utf16Length(max = 120) String title
    ) {
    }

    public record CourseCodeRequest(
            @NotNull @PositiveOrZero @Max(999_999_999) Integer expectedRevision,
            @Min(300) @Max(86400) Integer expiresInSeconds
    ) {
    }

    public record CodeRevocationRequest(
            @NotNull @PositiveOrZero @Max(999_999_999) Integer expectedRevision,
            @NotBlank @Utf16Length(max = 500) String reason
    ) {
    }

    public record CodeResolutionRequest(
            @NotBlank @Utf16Length(max = 256) String code
    ) {
    }

    public record MembershipConfirmationRequest(
            @NotBlank @Utf16Length(max = 256) String confirmationToken,
            @NotNull Boolean confirm
    ) {
    }

    public record JoinRequestCreateRequest(
            @Utf16Length(max = 500) String message,
            @Size(max = 3) List<@Valid ProofReferenceRequest> proofRefs
    ) {
    }

    public record JoinRequestDecisionRequest(
            @NotNull @PositiveOrZero @Max(999_999_999) Integer expectedRevision,
            @NotBlank @Pattern(regexp = "APPROVE|REJECT") String decision,
            @NotBlank @Utf16Length(max = 500) String reason
    ) {
    }
}
