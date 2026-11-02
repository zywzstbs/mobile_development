package cn.gulupai.server.course.api;

import cn.gulupai.server.common.validation.OpaqueId;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CourseSlotRequest(
        @NotNull @Min(1) @Max(7) Integer weekday,
        @NotBlank @OpaqueId String periodId,
        @NotBlank @Pattern(regexp = "ALL|ODD|EVEN|CUSTOM") String weekPattern,
        @NotNull @Min(1) Integer startWeek,
        @NotNull @Min(1) Integer endWeek,
        @Size(max = 100) List<@Min(1) Integer> customWeeks
) {
}
