package cn.gulupai.server.course.api;

import cn.gulupai.server.common.validation.Utf16Length;
import jakarta.validation.constraints.NotBlank;

public record SquareNumberRequest(
        @NotBlank @Utf16Length(max = 120) String squareNo
) {
}
