package cn.gulupai.server.course.persistence;

import java.time.Instant;

public record MembershipRow(
        String id,
        String squareId,
        String userId,
        String status,
        Instant joinedAt,
        Instant leftAt,
        int revision
) {
}
