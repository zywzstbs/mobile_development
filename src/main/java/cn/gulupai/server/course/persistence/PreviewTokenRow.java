package cn.gulupai.server.course.persistence;

import java.time.Instant;

public record PreviewTokenRow(
        String id,
        String userId,
        String kind,
        String bodyHash,
        String payloadJson,
        Instant expiresAt
) {
}
