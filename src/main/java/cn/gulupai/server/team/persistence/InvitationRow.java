package cn.gulupai.server.team.persistence;

import java.time.Instant;

public record InvitationRow(
        String id,
        String projectId,
        String recipientId,
        String roleSlotId,
        String message,
        String status,
        Instant expiresAt,
        String reason,
        int revision,
        Instant createdAt
) {
}
