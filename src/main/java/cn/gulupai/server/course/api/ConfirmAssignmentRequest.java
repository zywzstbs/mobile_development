package cn.gulupai.server.course.api;

import cn.gulupai.server.common.validation.Utf16Length;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;

public record ConfirmAssignmentRequest(
        @NotNull @PositiveOrZero @Max(999_999_999) Integer expectedRevision,
        @NotBlank @Pattern(regexp = "CONFIRM_NEW") String decision,
        @Utf16Length(max = 500) String reason
) {
}
