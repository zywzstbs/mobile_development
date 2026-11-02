package cn.gulupai.server.team.persistence;

import java.time.Instant;

public record ApplicationRow(
        String id,
        String projectId,
        String applicantId,
        String roleSlotId,
        String message,
        String status,
        String invitationId,
        String reason,
        int revision,
        Instant createdAt
) {
}
