package cn.gulupai.server.course.persistence;

import java.time.Instant;

public record SquareRow(
        String id,
        String verifiedCourseId,
        String schoolId,
        String termId,
        String name,
        String teacherName,
        String slotsJson,
        String canonicalKey,
        String squareNo,
        String status,
        int authorizationEpoch,
        int revision,
        Instant createdAt,
        Instant updatedAt
) {
}
