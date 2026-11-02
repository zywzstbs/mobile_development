package cn.gulupai.server.team.persistence;

import java.time.Instant;

public record ProjectMemberRow(
        String id,
        String projectId,
        String userId,
        String displayName,
        String roleSlotId,
        String projectRole,
        String status,
        String color,
        int colorRevision,
        Instant joinedAt,
        int revision
) {
}
