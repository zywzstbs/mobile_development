package cn.gulupai.server.teaching.persistence;

import java.time.Instant;

public record TeacherClaimRow(
        String id,
        String squareId,
        String userId,
        String status,
        String message,
        String proofRefsJson,
        int revision,
        Instant createdAt
) {
}
