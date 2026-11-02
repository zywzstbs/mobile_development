package cn.gulupai.server.course.api;

import cn.gulupai.server.common.validation.Utf16Length;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public record CreateAssignmentRequest(
        @NotBlank @Utf16Length(max = 120) String name,
        @NotBlank @Utf16Length(max = 5000) String requirements,
        @NotNull Instant hardDueAt,
        @NotNull @Min(1) @Max(20) Integer minMembers,
        @NotNull @Min(1) @Max(20) Integer maxMembers,
        @Size(max = 10) List<@Valid SourceReferenceRequest> sourceReferences
) {
}
