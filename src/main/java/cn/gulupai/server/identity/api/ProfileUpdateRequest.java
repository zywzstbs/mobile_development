package cn.gulupai.server.identity.api;

import cn.gulupai.server.common.validation.Utf16Length;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;

public record ProfileUpdateRequest(
        @NotNull @PositiveOrZero @Max(999_999_999) Integer expectedRevision,
        @NotBlank @Utf16Length(max = 80) String displayName,
        @NotNull @Size(max = 12) List<@NotBlank @Utf16Length(max = 80) String> skills,
        @NotNull @Min(0) @Max(168) Integer weeklyHours,
        @Utf16Length(max = 2000) String availability,
        @NotBlank @Utf16Length(max = 2000) String collaborationGoal,
        @Utf16Length(max = 2000) String intro
) {
}
