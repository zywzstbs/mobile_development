package cn.gulupai.server.common.idempotency;

import java.time.Instant;

public record ReceiptRow(
        String id,
        String bodyHash,
        int httpStatus,
        String businessCode,
        String resultCiphertext,
        String keyId,
        Instant expiresAt
) {
}
