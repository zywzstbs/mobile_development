package cn.gulupai.server.identity.api;

import cn.gulupai.server.common.validation.Utf16Length;
import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
        @NotBlank @Utf16Length(max = 120) String loginId,
        @NotBlank @Utf16Length(max = 256) String password,
        @Utf16Length(max = 80) String deviceLabel
) {
}
