package cn.gulupai.server.role.api;

import cn.gulupai.server.common.validation.Utf16Length;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

public final class RoleRequests {

    private RoleRequests() {
    }

    public record RoleInput(
            @Pattern(regexp = "[A-Za-z0-9_-]{0,120}") String id,
            @Pattern(regexp = "[A-Za-z0-9_-]{0,120}") String clientKey,
            @NotBlank @Utf16Length(max = 60) String roleName,
            @NotBlank @Utf16Length(max = 1000) String duty,
            @NotNull @Min(1) @Max(20) Integer total
    ) {
    }

    public record CompleteDetails(
            @NotBlank @Utf16Length(max = 120) String name,
            @NotBlank @Utf16Length(max = 2000) String intro
    ) {
    }

    public record PutRoleSlotsRequest(
            @NotNull @Min(1) @Max(999_999_999) Integer expectedRevision,
            @NotNull @Size(min = 1, max = 20) List<@Valid RoleInput> roles,
            @Valid CompleteDetails completeDetails,
            @Utf16Length(max = 120) String leaderRoleKey
    ) {
    }

    public record SelectRoleRequest(
            @NotNull @Min(1) @Max(999_999_999) Integer expectedRevision,
            @NotNull @Min(1) @Max(999_999_999) Integer expectedProjectRevision,
            @Utf16Length(max = 120) String roleSlotId
    ) {
    }

    public record PlanAssignment(
            @NotBlank @Utf16Length(max = 120) String membershipId,
            @NotNull @Min(1) @Max(999_999_999) Integer expectedMembershipRevision,
            @NotBlank @Utf16Length(max = 120) String targetRoleSlotId
    ) {
    }

    public record CreatePlanRequest(
            @NotNull @Min(1) @Max(999_999_999) Integer expectedRevision,
            @NotNull @Size(min = 2, max = 20) List<@Valid PlanAssignment> assignments,
            @Utf16Length(max = 500) String reason
    ) {
    }

    public record CreateProposalRequest(
            @NotNull @Min(1) @Max(999_999_999) Integer expectedRevision,
            @NotBlank @Utf16Length(max = 120) String membershipId,
            @NotNull @Min(1) @Max(999_999_999) Integer expectedMembershipRevision,
            @NotBlank @Utf16Length(max = 120) String roleSlotId,
            @NotBlank @Utf16Length(max = 500) String message
    ) {
    }

    public record CreateSwapRequest(
            @NotNull @Min(1) @Max(999_999_999) Integer expectedRevision,
            @NotBlank @Utf16Length(max = 120) String firstMembershipId,
            @NotBlank @Utf16Length(max = 120) String secondMembershipId,
            @NotNull @Min(1) @Max(999_999_999) Integer expectedFirstMembershipRevision,
            @NotNull @Min(1) @Max(999_999_999) Integer expectedSecondMembershipRevision,
            @Utf16Length(max = 500) String reason
    ) {
    }

    public record PlanResponseRequest(
            @NotNull @Min(1) @Max(999_999_999) Integer expectedRevision,
            @NotBlank @Pattern(regexp = "ACCEPT|DECLINE") String decision,
            @Utf16Length(max = 500) String comment,
            @NotNull @Min(1) @Max(999_999_999) Integer expectedProjectRevision
    ) {
    }

    public record ProposalResponseRequest(
            @NotNull @Min(1) @Max(999_999_999) Integer expectedRevision,
            @NotBlank @Pattern(regexp = "ACCEPT|DECLINE") String decision,
            @Utf16Length(max = 500) String reason,
            @NotNull @Min(1) @Max(999_999_999) Integer expectedProjectRevision
    ) {
    }

    public record WithdrawRequest(
            @NotNull @Min(1) @Max(999_999_999) Integer expectedRevision,
            @NotBlank @Utf16Length(min = 1, max = 500) String reason
    ) {
    }
}
