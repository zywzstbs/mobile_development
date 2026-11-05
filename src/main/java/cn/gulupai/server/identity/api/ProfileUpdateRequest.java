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
        @NotBlank @Utf16Length(min = 1, max = 24) String displayName,
        @NotNull @Size(max = 8) List<@NotBlank @Utf16Length(max = 20) String> skills,
        @NotNull @Min(0) @Max(60) Integer weeklyHours,
        @Utf16Length(max = 180) String availability,
        @Utf16Length(max = 180) String collaborationGoal,
        @Utf16Length(max = 300) String intro
) {
}
