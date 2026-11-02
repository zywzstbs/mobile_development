package cn.gulupai.server.course.persistence;

import java.time.Instant;

public record AssignmentRow(
        String id,
        String squareId,
        String name,
        String requirements,
        String origin,
        String confirmationStatus,
        Instant hardDueAt,
        int minMembers,
        int maxMembers,
        boolean gradingEnabled,
        String sourceReferencesJson,
        int revision,
        Instant createdAt
) {
}
