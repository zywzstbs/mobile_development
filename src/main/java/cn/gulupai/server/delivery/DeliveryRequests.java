package cn.gulupai.server.delivery;

import cn.gulupai.server.common.validation.Utf16Length;
import cn.gulupai.server.task.api.TaskRequests;
import cn.gulupai.server.task.api.TaskRequests.CriterionInput;
import cn.gulupai.server.task.api.TaskRequests.ShareInput;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public final class DeliveryRequests {

    private DeliveryRequests() {
    }

    public record AgreementResponseRequest(
            @NotNull @Min(1) @Max(999_999_999) Integer expectedRevision,
            @NotBlank @Pattern(regexp = "ACCEPT|REQUEST_CHANGE") String decision,
            @Utf16Length(max = 500) String comment
    ) {
    }

    public record AgreementCreateRequest(
            @NotNull @Min(1) @Max(999_999_999) Integer baseTaskRevision,
            @NotNull @Valid TaskRequests.AssessedPlanInput plan
    ) {
    }

    public record ChangeFields(
            @Utf16Length(max = 120) String title,
            @Utf16Length(max = 20) String points,
            @Size(max = 20) List<@Valid ShareInput> shares,
            @Size(max = 20) List<@Valid CriterionInput> criteria,
            Instant dueAt
    ) {
    }

    public record UnitChange(
            @NotBlank @Utf16Length(max = 120) String unitId,
            @NotNull @Min(1) @Max(999_999_999) Integer baseUnitRevision,
            @NotNull @Valid ChangeFields fields
    ) {
    }

    public record ProposalCreateRequest(
            @NotNull @Min(1) Integer baseAgreementVersion,
            @NotNull @Size(min = 1, max = 20) List<@Valid UnitChange> changes,
            @Utf16Length(max = 500) String reason
    ) {
    }

    public record ProposalResponseRequest(
            @NotNull @Min(1) @Max(999_999_999) Integer expectedRevision,
            @NotBlank @Pattern(regexp = "ACCEPT|REQUEST_CHANGE") String decision,
            @Utf16Length(max = 500) String comment
    ) {
    }

    public record OpinionConfirmationRequest(
            @NotNull @Min(1) @Max(999_999_999) Integer expectedRevision,
            @NotBlank @Utf16Length(max = 120) String opinionId,
            @NotBlank @Pattern(regexp = "AGREE|CONTINUE") String decision,
            @Utf16Length(max = 500) String comment
    ) {
    }

    public record OpinionReplyRequest(
            @NotNull @Min(1) @Max(999_999_999) Integer expectedRevision,
            @NotBlank @Utf16Length(max = 120) String opinionId,
            @NotBlank @Utf16Length(max = 2000) String proposalText
    ) {
    }

    public record WithdrawalRequest(
            @NotNull @Min(1) @Max(999_999_999) Integer expectedRevision,
            @Utf16Length(max = 500) String reason
    ) {
    }

    public record SubmissionWithdrawalRequest(
            @NotNull @Min(1) @Max(999_999_999) Integer expectedRevision
    ) {
    }

    public record InvitationResponseRequest(
            @NotNull @Min(1) @Max(999_999_999) Integer expectedRevision,
            @NotBlank @Pattern(regexp = "ACCEPT|DECLINE") String decision,
            @Utf16Length(max = 500) String reason
    ) {
    }

    public record InvitationCreateRequest(
            @NotBlank @Utf16Length(max = 120) String recipientId,
            @NotNull @Min(1) @Max(999_999_999) Integer expectedTaskRevision
    ) {
    }

    public record ArtifactInput(
            @NotBlank @Pattern(regexp = "LINK|FILE") String kind,
            @Utf16Length(max = 2048) String url,
            @Utf16Length(max = 120) String fileId,
            @NotBlank @Utf16Length(max = 120) String title
    ) {
    }

    public record CheckInput(
            @Utf16Length(max = 120) String unitId,
            @NotBlank @Utf16Length(max = 120) String criterionId,
            @NotNull Boolean met
    ) {
    }

    public record ReviewRequest(
            @NotNull @Min(1) @Max(999_999_999) Integer expectedRevision,
            @NotNull List<@Utf16Length(max = 120) String> unitIds,
            @NotNull @Size(max = 40) List<@Valid CheckInput> checks,
            @NotNull @Size(max = 40) List<@Utf16Length(max = 500) String> returnReasons
    ) {
    }

    public record SelfCheckRequest(
            @NotNull @Min(1) @Max(999_999_999) Integer expectedRevision,
            @NotNull List<@Utf16Length(max = 120) String> unitIds,
            @NotNull @Size(max = 40) List<@Valid CheckInput> checks,
            @NotNull @Size(max = 40) List<@Utf16Length(max = 500) String> returnReasons,
            @NotBlank @Utf16Length(max = 500) String selfCheckReason
    ) {
    }

    public record SubmitRequest(
            @NotNull @Min(1) @Max(999_999_999) Integer expectedTaskRevision,
            @NotNull List<@Utf16Length(max = 120) String> unitIds,
            @NotBlank @Utf16Length(max = 2000) String summary,
            @Size(max = 10) List<@Valid ArtifactInput> artifacts,
            @Min(1) Integer agreementVersion,
            @Min(0) @Max(999_999_999) Integer fromDraftRevision
    ) {
    }

    public record DraftRequest(
            @NotNull @Min(0) @Max(999_999_999) Integer expectedRevision,
            @NotNull List<@Utf16Length(max = 120) String> unitIds,
            @NotBlank @Utf16Length(max = 2000) String summary,
            @Size(max = 10) List<@Valid ArtifactInput> artifacts,
            @Min(1) Integer baseAgreementVersion,
            @Min(1) @Max(999_999_999) Integer expectedTaskRevision
    ) {
    }

    public record ContributionRequest(
            @NotNull @Min(1) @Max(999_999_999) Integer expectedRevision,
            @NotBlank @Utf16Length(max = 120) String submissionId,
            @NotBlank @Utf16Length(max = 120) String acceptanceId,
            @NotBlank @Utf16Length(max = 500) String statement
    ) {
    }

    public record RenewalRequest(
            @NotNull @Min(1) @Max(999_999_999) Integer expectedRevision,
            @Utf16Length(max = 500) String reason
    ) {
    }
}
