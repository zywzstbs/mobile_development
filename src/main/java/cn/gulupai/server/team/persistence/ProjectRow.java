package cn.gulupai.server.team.persistence;

import java.time.Instant;

public record ProjectRow(
        String id,
        String assignmentId,
        String squareId,
        String ownerId,
        String mode,
        String name,
        String intro,
        int capacity,
        String status,
        int closingCycle,
        int authorizationEpoch,
        String discussionTime,
        boolean recruitmentOpen,
        String recruitmentDescription,
        String professionalDirection,
        int weeklyHours,
        boolean acceptBeginners,
        int revision,
        Instant createdAt,
        Instant updatedAt
) {
}
