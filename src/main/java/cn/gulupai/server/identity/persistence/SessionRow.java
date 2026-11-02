package cn.gulupai.server.identity.persistence;

import java.time.Instant;

public record SessionRow(
        String id,
        String userId,
        String deviceLabel,
        String refreshHash,
        Instant expiresAt,
        Instant revokedAt,
        int revision
) {
    public boolean revoked() {
        return revokedAt != null;
    }
}
