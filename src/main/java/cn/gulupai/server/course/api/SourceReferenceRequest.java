package cn.gulupai.server.course.api;

import cn.gulupai.server.common.validation.Utf16Length;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record SourceReferenceRequest(
        @NotBlank @Pattern(regexp = "LINK") String kind,
        @NotBlank @Utf16Length(max = 2048)
        @Pattern(regexp = "https?://.+", message = "材料链接必须为http/https URL") String url,
        @NotBlank @Utf16Length(max = 120) String title
) {
}
