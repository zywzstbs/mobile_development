package cn.gulupai.server.teaching.persistence;

import java.time.Instant;

public record TeacherTransferRow(
        String id,
        String squareId,
        String fromUserId,
        String toUserId,
        String status,
        int sourceRevision,
        String reason,
        int revision,
        Instant createdAt
) {
}
