package cn.gulupai.server.identity.api;

import java.time.Instant;

public record SessionView(
        String accessToken,
        String refreshToken,
        String tokenType,
        int expiresIn,
        Instant refreshExpiresAt,
        UserView user
) {
}
