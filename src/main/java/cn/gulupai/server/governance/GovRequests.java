package cn.gulupai.server.governance;

import cn.gulupai.server.common.validation.Utf16Length;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public final class GovRequests {

    private GovRequests() {
    }

    public record LinkRef(
            @NotBlank @Pattern(regexp = "LINK") String kind,
            @NotBlank @Utf16Length(max = 2048) String url,
            @NotBlank @Utf16Length(max = 120) String title
    ) {
    }

    public record CreateChange(
            @NotBlank @Pattern(regexp = "EXIT|TRANSFER") String kind,
            @Utf16Length(max = 500) String reason,
            @NotNull @Min(1) @Max(999_999_999) Integer expectedMembershipRevision,
            @Utf16Length(max = 120) String targetProjectId
    ) {
    }

    public record RevisionReason(
            @NotNull @Min(1) @Max(999_999_999) Integer expectedRevision,
            @Utf16Length(max = 500) String reason
    ) {
    }

    public record Execute(
            @NotNull @Min(1) @Max(999_999_999) Integer expectedRevision,
            @NotNull @Min(1) Integer planVersion
    ) {
    }

    public record HandoverResponse(
            @NotNull @Min(1) @Max(999_999_999) Integer expectedRevision,
            @NotNull @Min(1) Integer planVersion,
            @NotNull List<@Utf16Length(max = 120) String> itemIds,
            @NotBlank @Pattern(regexp = "ACCEPT|DECLINE") String decision,
            @Utf16Length(max = 500) String reason
    ) {
    }

    public record PersonnelDecision(
            @NotNull @Min(1) @Max(999_999_999) Integer expectedRevision,
            @NotBlank @Pattern(regexp = "ALLOW_HANDOVER|RETURN") String decision,
            @NotBlank @Utf16Length(max = 2000) String placementPlan,
            @NotBlank @Utf16Length(max = 500) String reason,
            @NotNull Boolean allowBelowMinimum
    ) {
    }

    public record TargetInvite(
            @Utf16Length(max = 120) String roleSlotId,
            @NotBlank @Utf16Length(max = 500) String message,
            @NotNull @Min(1) @Max(999_999_999) Integer expectedRevision
    ) {
    }

    public record TargetResponse(
            @NotNull @Min(1) @Max(999_999_999) Integer expectedRevision,
            @NotBlank @Pattern(regexp = "ACCEPT|DECLINE") String decision,
            @Utf16Length(max = 500) String reason
    ) {
    }

    public record PlanItem(
            @Utf16Length(max = 120) String taskId,
            @Min(1) @Max(999_999_999) Integer sourceTaskRevision,
            @NotBlank @Utf16Length(max = 120) String recipientId,
            @NotBlank @Utf16Length(max = 500) String responsibility
    ) {
    }

    public record SavePlan(
            @NotNull @Min(1) @Max(999_999_999) Integer expectedRevision,
            @NotNull List<@Valid PlanItem> items,
            @Utf16Length(max = 120) String nextLeaderId,
            @Utf16Length(max = 2000) String notes
    ) {
    }

    public record MaterialResponse(
            @NotBlank @Utf16Length(max = 4000) String content,
            @Size(max = 10) List<@Valid LinkRef> evidenceRefs,
            @NotNull @Min(1) @Max(999_999_999) Integer expectedRevision
    ) {
    }

    public record MaterialReview(
            @NotNull @Min(1) Integer responseVersion,
            @NotBlank @Pattern(regexp = "ACCEPT|RETURN") String decision,
            @Utf16Length(max = 500) String reason,
            @NotNull @Min(1) @Max(999_999_999) Integer expectedRevision
    ) {
    }

    public record CaseRevision(@NotNull @Min(1) @Max(999_999_999) Integer expectedRevision) {
    }

    public record CloseCase(
            @NotNull @Min(1) @Max(999_999_999) Integer expectedRevision,
            @NotBlank @Utf16Length(max = 500) String reason
    ) {
    }

    public record CaseHandoff(
            @NotNull @Min(1) @Max(999_999_999) Integer expectedCaseRevision,
            @NotBlank @Utf16Length(max = 120) String recipientTeacherId,
            @NotBlank @Utf16Length(max = 2000) String facts,
            @NotBlank @Utf16Length(max = 2000) String unresolvedQuestions,
            @NotBlank @Utf16Length(max = 2000) String decisionsNeeded
    ) {
    }

    public record MaterialCreate(
            @NotNull @Min(1) @Max(999_999_999) Integer expectedCaseRevision,
            @NotBlank @Utf16Length(max = 120) String recipientId,
            @NotBlank @Utf16Length(max = 2000) String requirements,
            @NotNull Instant dueAt
    ) {
    }

    public record CaseUpdate(
            @NotNull @Min(1) @Max(999_999_999) Integer expectedRevision,
            @NotBlank @Utf16Length(max = 2000) String facts,
            @NotBlank @Utf16Length(max = 500) String nextAction,
            @Size(max = 10) List<@Valid LinkRef> evidenceRefs
    ) {
    }

    public record HandoffDecision(
            @NotNull @Min(1) @Max(999_999_999) Integer expectedRevision,
            @NotBlank @Pattern(regexp = "ACCEPT|DECLINE") String decision,
            @Utf16Length(max = 500) String reason
    ) {
    }

    public record StartVote(
            @NotBlank @Utf16Length(max = 120) String targetUserId,
            @NotBlank @Utf16Length(max = 2000) String facts,
            @NotBlank @Utf16Length(max = 2000) String communicationHistory,
            @Size(max = 10) List<@Valid LinkRef> evidenceRefs
    ) {
    }

    public record Statement(
            @NotBlank @Utf16Length(max = 2000) String statement,
            @NotNull @Min(1) @Max(999_999_999) Integer expectedRevision
    ) {
    }

    public record Ballot(
            @NotNull @Min(0) @Max(999_999_999) Integer expectedRevision,
            @NotBlank @Pattern(regexp = "AGREE|DISAGREE|ABSTAIN") String choice
    ) {
    }
}
