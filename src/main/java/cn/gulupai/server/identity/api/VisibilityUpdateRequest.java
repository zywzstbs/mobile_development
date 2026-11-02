package cn.gulupai.server.identity.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;

public record VisibilityUpdateRequest(
        @NotNull @PositiveOrZero @Max(999_999_999) Integer expectedRevision,
        @NotBlank @Pattern(regexp = "PRIVATE|TEAM|CLASS", message = "worksAudience 取值无效")
        String worksAudience,
        @NotNull Boolean teacherSummaryVisible,
        @NotNull Boolean cooperationSummaryVisible
) {
}
