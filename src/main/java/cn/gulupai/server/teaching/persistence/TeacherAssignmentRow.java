package cn.gulupai.server.teaching.persistence;

import java.time.Instant;

public record TeacherAssignmentRow(
        String id,
        String squareId,
        String userId,
        boolean active,
        Instant startedAt,
        Instant endedAt,
        int revision
) {
}
