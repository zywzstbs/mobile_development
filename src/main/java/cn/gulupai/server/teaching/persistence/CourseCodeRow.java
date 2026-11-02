package cn.gulupai.server.teaching.persistence;

import java.time.Instant;

public record CourseCodeRow(
        String id,
        String squareId,
        String issuedBy,
        String status,
        Instant expiresAt,
        String codeCiphertext,
        String keyId,
        int revision
) {
}
