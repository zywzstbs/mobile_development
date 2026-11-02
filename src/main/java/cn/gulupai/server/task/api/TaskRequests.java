package cn.gulupai.server.task.api;

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

public final class TaskRequests {

    private TaskRequests() {
    }

    public record CriterionInput(@NotBlank @Utf16Length(max = 500) String text) {
    }

    public record DependencyInput(
            @NotBlank @Utf16Length(max = 120) String taskId,
            @NotBlank @Pattern(regexp = "BEFORE_START|BEFORE_SUBMIT") String gate
    ) {
    }

    public record CreateTaskRequest(
            @NotBlank @Utf16Length(max = 120) String title,
            @Utf16Length(max = 2000) String description,
            @NotBlank @Pattern(regexp = "ORDINARY|ASSESSED") String mode,
            @NotBlank @Utf16Length(max = 120) String stageId,
            @Size(max = 20) List<@Valid CriterionInput> criteria,
            @Size(max = 50) List<@Valid DependencyInput> dependencies,
            Instant dueAt,
            @Valid AssessedPlanInput assessedPlan
    ) {
    }

    public record AssessedPlanInput(
            @Utf16Length(max = 120) String ownerId,
            Instant dueAt,
            @Size(max = 20) List<@Valid UnitInput> units,
            @Valid ReviewerPolicyInput reviewerPolicy
    ) {
    }

    public record UnitInput(
            @Utf16Length(max = 120) String clientKey,
            @Utf16Length(max = 120) String title,
            @Utf16Length(max = 20) String points,
            @Size(max = 20) List<@Valid ShareInput> shares,
            @Size(max = 20) List<@Valid CriterionInput> criteria
    ) {
    }

    public record ShareInput(
            @Utf16Length(max = 120) String userId,
            @Utf16Length(max = 20) String points,
            @Utf16Length(max = 500) String responsibility
    ) {
    }

    public record ReviewerPolicyInput(
            @Utf16Length(max = 120) String userId,
            @Utf16Length(max = 20) String kind
    ) {
    }

    public record MoveStageRequest(
            @NotNull @Min(1) @Max(999_999_999) Integer expectedRevision,
            @NotNull @Min(1) @Max(999_999_999) Integer expectedProjectRevision,
            @NotBlank @Utf16Length(max = 120) String stageId
    ) {
    }

    public record ClaimRequest(
            @NotNull @Min(1) @Max(999_999_999) Integer expectedRevision,
            Boolean startNow
    ) {
    }

    public record StartRequest(
            @NotNull @Min(1) @Max(999_999_999) Integer expectedRevision
    ) {
    }

    public record ColorRequest(
            @NotNull @Min(0) @Max(999_999_999) Integer expectedRevision,
            @NotBlank String color
    ) {
    }

    public record StageInput(
            @Pattern(regexp = "[A-Za-z0-9_-]{0,120}") String id,
            @Pattern(regexp = "[A-Za-z0-9_-]{0,120}") String clientKey,
            @NotBlank @Utf16Length(max = 40) String name,
            @Min(0) @Max(11) Integer order
    ) {
    }

    public record ReplaceStagesRequest(
            @NotNull @Min(1) @Max(999_999_999) Integer expectedRevision,
            @NotNull @Size(min = 1, max = 12) List<@Valid StageInput> stages
    ) {
    }

    public record ReplaceDependenciesRequest(
            @NotNull @Min(1) @Max(999_999_999) Integer expectedRevision,
            @NotNull @Size(max = 50) List<@Valid DependencyInput> dependencies
    ) {
    }
}
