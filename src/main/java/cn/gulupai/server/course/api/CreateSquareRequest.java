package cn.gulupai.server.course.api;

import cn.gulupai.server.common.validation.OpaqueId;
import cn.gulupai.server.common.validation.Utf16Length;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CreateSquareRequest(
        @NotBlank @OpaqueId String schoolId,
        @NotBlank @OpaqueId String termId,
        @NotBlank @Utf16Length(max = 80) String teacherName,
        @NotEmpty @Size(max = 50) List<@Valid CourseSlotRequest> slots,
        @OpaqueId String courseNameId,
        @Utf16Length(max = 160) String customCourseName,
        @NotBlank @Utf16Length(max = 256) String candidateCheckToken
) {
}
