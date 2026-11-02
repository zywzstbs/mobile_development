package cn.gulupai.server.teaching.persistence;

import java.time.Instant;

public record ClassGrantRow(
        String id,
        String squareId,
        String userId,
        String capabilitiesJson,
        String status,
        Instant expiresAt,
        String grantedBy,
        int revision,
        Instant updatedAt
) {
}
