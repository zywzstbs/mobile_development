package cn.gulupai.server.merge;

import cn.gulupai.server.common.validation.Utf16Length;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

public final class MergeRequests {

    private MergeRequests() {
    }

    public record LinkRef(
            @NotBlank @Pattern(regexp = "LINK") String kind,
            @NotBlank @Utf16Length(max = 2048) String url,
            @NotBlank @Utf16Length(max = 120) String title
    ) {
    }

    public record Create(
            @NotBlank @Utf16Length(max = 120) String sourceSquareId,
            @NotBlank @Utf16Length(max = 120) String targetSquareId,
            @NotNull @Min(1) @Max(999_999_999) Integer expectedSourceRevision,
            @NotNull @Min(1) @Max(999_999_999) Integer expectedTargetRevision,
            @Utf16Length(max = 500) String reason,
            @Size(max = 10) List<@Valid LinkRef> evidenceRefs
    ) {
    }

    public record Cancel(
            @NotBlank @Utf16Length(max = 500) String reason,
            @NotNull @Min(1) @Max(999_999_999) Integer expectedRevision
    ) {
    }

    public record Execute(@NotNull @Min(1) @Max(999_999_999) Integer expectedRevision) {
    }

    public record Mapping(
            @NotBlank @Utf16Length(max = 120) String fromAssignmentId,
            @NotNull Boolean keepSeparate
    ) {
    }

    public record FactCheck(
            @NotBlank @Pattern(regexp = "CONFIRM|RETURN") String decision,
            @NotBlank @Utf16Length(max = 500) String reason,
            @NotNull List<@Valid Mapping> assignmentMappings,
            @NotNull @Min(1) @Max(999_999_999) Integer expectedRevision
    ) {
    }

    public record Ballot(
            @NotBlank @Utf16Length(max = 120) String squareId,
            @NotNull @Min(0) @Max(999_999_999) Integer expectedRevision,
            @NotBlank @Pattern(regexp = "AGREE|DISAGREE|ABSTAIN") String choice
    ) {
    }
}
