package cn.gulupai.server.team.api;

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

public final class TeamRequests {

    private TeamRequests() {
    }

    public record RoleInput(
            @Utf16Length(max = 120) String clientKey,
            @NotBlank @Utf16Length(max = 60) String roleName,
            @NotBlank @Utf16Length(max = 1000) String duty,
            @NotNull @Min(1) @Max(20) Integer total
    ) {
    }

    public record CreateProjectRequest(
            @NotBlank String squareId,
            @NotBlank String assignmentId,
            @NotBlank @Pattern(regexp = "QUICK|DETAILED") String mode,
            @Utf16Length(max = 120) String name,
            @Utf16Length(max = 2000) String intro,
            @NotNull @Min(1) @Max(20) Integer capacity,
            @Utf16Length(max = 200) String discussionTime,
            @Size(max = 20) List<@Valid RoleInput> roles,
            @Utf16Length(max = 120) String leaderRoleKey
    ) {
    }

    public record PatchProjectRequest(
            @NotNull @PositiveOrZero @Max(999_999_999) Integer expectedRevision,
            @NotBlank @Utf16Length(max = 120) String name,
            @Utf16Length(max = 2000) String intro,
            @Utf16Length(max = 200) String discussionTime,
            @NotNull @Min(1) @Max(20) Integer capacity
    ) {
    }

    public record RecruitmentUpdateRequest(
            @NotNull @PositiveOrZero @Max(999_999_999) Integer expectedRevision,
            @NotNull Boolean open,
            @Utf16Length(max = 2000) String description,
            @NotBlank @Utf16Length(max = 200) String professionalDirection,
            @NotNull @Min(0) @Max(168) Integer weeklyHours,
            @NotNull Boolean acceptBeginners
    ) {
    }

    public record ApplicationCreateRequest(
            @NotBlank @Utf16Length(max = 500) String message,
            @Utf16Length(max = 120) String roleSlotId
    ) {
    }

    public record ApplicationDecisionRequest(
            @NotNull @PositiveOrZero @Max(999_999_999) Integer expectedRevision,
            @NotBlank @Pattern(regexp = "APPROVE|REJECT") String decision,
            @Utf16Length(max = 500) String reason
    ) {
    }

    public record WithdrawRequest(
            @NotNull @PositiveOrZero @Max(999_999_999) Integer expectedRevision,
            @Utf16Length(max = 500) String reason
    ) {
    }

    public record InviteRequest(
            @NotNull @PositiveOrZero @Max(999_999_999) Integer expectedProjectRevision,
            @NotBlank String recipientId,
            @NotBlank @Utf16Length(max = 500) String message,
            @Utf16Length(max = 120) String roleSlotId
    ) {
    }

    public record InvitationResponseRequest(
            @NotNull @PositiveOrZero @Max(999_999_999) Integer expectedRevision,
            @NotBlank @Pattern(regexp = "ACCEPT|DECLINE") String decision,
            @Utf16Length(max = 500) String reason
    ) {
    }

    public record InvitationRevokeRequest(
            @NotNull @PositiveOrZero @Max(999_999_999) Integer expectedRevision,
            @NotBlank @Utf16Length(max = 500) String reason
    ) {
    }

    public record CodeActionRequest(
            @NotNull @PositiveOrZero @Max(999_999_999) Integer expectedRevision,
            @NotBlank @Pattern(regexp = "ENABLE|DISABLE|ROTATE") String action
    ) {
    }

    public record CodeResolveRequest(
            @NotBlank @Utf16Length(max = 256) String code
    ) {
    }
}
