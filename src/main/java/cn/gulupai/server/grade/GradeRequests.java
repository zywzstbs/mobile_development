package cn.gulupai.server.grade;

import cn.gulupai.server.common.validation.Utf16Length;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

public final class GradeRequests {

    private GradeRequests() {
    }

    public record LinkRef(
            @NotBlank @Pattern(regexp = "LINK") String kind,
            @NotBlank @Utf16Length(max = 2048) String url,
            @NotBlank @Utf16Length(max = 120) String title
    ) {
    }

    public record SourceRef(
            @NotBlank @Utf16Length(max = 40) String type,
            @NotBlank @Utf16Length(max = 120) String id,
            @NotNull @Min(1) @Max(999_999_999) Integer version
    ) {
    }

    public record RulePreview(
            @NotBlank @Utf16Length(max = 32) String processWeight,
            @Utf16Length(max = 500) String description,
            @Utf16Length(max = 500) String reason,
            @NotNull @Min(1) @Max(999_999_999) Integer expectedAssignmentRevision
    ) {
    }

    public record ConfirmRule(@NotBlank @Utf16Length(max = 200) String previewToken) {
    }

    public record Points(
            @NotBlank @Utf16Length(max = 32) String responsibilityPoints,
            @NotBlank @Utf16Length(max = 32) String acceptedPoints
    ) {
    }

    public record Correction(
            @NotBlank @Utf16Length(max = 64) String expectedSourceKey,
            @NotBlank @Utf16Length(max = 120) String taskId,
            @NotBlank @Utf16Length(max = 120) String unitId,
            @NotNull @Valid Points correction,
            @NotBlank @Utf16Length(min = 1, max = 500) String reason,
            @NotNull @Size(min = 1, max = 10) List<@Valid LinkRef> evidenceRefs
    ) {
    }

    public record Settle(
            @NotBlank @Utf16Length(max = 64) String expectedSourceKey,
            @NotNull @Min(1) @Max(999_999_999) Integer ruleVersion
    ) {
    }

    public record Draft(
            @NotNull @Min(0) @Max(999_999_999) Integer expectedRevision,
            @NotNull @Min(0) @Max(100) Integer q,
            @NotNull @Min(-100) @Max(100) Integer delta,
            @Utf16Length(max = 500) String deltaReason,
            @NotNull @Size(min = 1, max = 10) List<@Valid LinkRef> evidenceRefs
    ) {
    }

    public record Candidate(
            @NotBlank @Utf16Length(max = 120) String studentId,
            @NotBlank @Pattern(regexp = "FIRST|REVISION|BLOCKED|UNCHANGED") String classification,
            @NotBlank @Utf16Length(max = 64) String basisDigest
    ) {
    }

    public record AppealLink(
            @NotBlank @Utf16Length(max = 120) String studentId,
            @NotBlank @Utf16Length(max = 120) String appealId
    ) {
    }

    public record PublicationPreview(
            @NotNull @Size(min = 1, max = 200) List<@NotBlank @Utf16Length(max = 120) String> studentIds,
            @Size(max = 200) List<@Valid AppealLink> appealLinks,
            @Size(max = 200) List<@Valid Candidate> expectedCandidates
    ) {
    }

    public record Publish(@NotBlank @Utf16Length(max = 200) String previewToken) {
    }

    public record Appeal(
            @NotBlank @Utf16Length(min = 1, max = 2000) String issue,
            @Size(max = 10) List<@Valid SourceRef> sourceRefs,
            @Size(max = 10) List<@Valid LinkRef> evidenceRefs
    ) {
    }

    public record AppealResponse(
            @NotNull @Min(1) @Max(999_999_999) Integer expectedRevision,
            @NotBlank @Pattern(regexp = "MAINTAIN|REVISE") String decision,
            @NotBlank @Utf16Length(min = 1, max = 500) String reason,
            @NotBlank @Utf16Length(max = 120) String expectedCurrentGradeSnapshotId
    ) {
    }

    public record Summary(
            @NotNull @Min(0) @Max(999_999_999) Integer expectedRevision,
            @NotBlank @Utf16Length(max = 120) String sourceGradeSnapshotId,
            @NotBlank @Utf16Length(min = 1, max = 2000) String summary
    ) {
    }

    public record Consent(
            @NotNull @Min(1) @Max(999_999_999) Integer expectedRevision,
            @NotNull @Min(1) @Max(999_999_999) Integer expectedContentVersion,
            @NotBlank @Pattern(regexp = "SHOW|HIDE") String decision
    ) {
    }

    public record Withdraw(
            @NotNull @Min(1) @Max(999_999_999) Integer expectedRevision,
            @NotBlank @Utf16Length(min = 1, max = 500) String reason
    ) {
    }
}
