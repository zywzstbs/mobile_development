package cn.gulupai.server.course.api;

import cn.gulupai.server.common.validation.Utf16Length;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.Instant;

public record PatchAssignmentRequest(
        @NotNull @PositiveOrZero @Max(999_999_999) Integer expectedRevision,
        @NotBlank @Utf16Length(min = 1, max = 100) String name,
        @NotBlank @Utf16Length(min = 1, max = 10000) String requirements,
        @NotNull Instant hardDueAt,
        @NotNull @Min(1) @Max(20) Integer minMembers,
        @NotNull @Min(1) @Max(20) Integer maxMembers
) {
}
