package cn.gulupai.server.teaching.persistence;

import java.time.Instant;

public record JoinRequestRow(
        String id,
        String squareId,
        String userId,
        String status,
        String message,
        String proofRefsJson,
        String decidedBy,
        String decisionReason,
        int revision,
        Instant createdAt
) {
}
