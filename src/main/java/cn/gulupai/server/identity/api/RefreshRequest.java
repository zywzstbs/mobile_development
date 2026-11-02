package cn.gulupai.server.identity.api;

import cn.gulupai.server.common.validation.Utf16Length;
import jakarta.validation.constraints.NotBlank;

public record RefreshRequest(
        @NotBlank @Utf16Length(max = 256) String refreshToken
) {
}
