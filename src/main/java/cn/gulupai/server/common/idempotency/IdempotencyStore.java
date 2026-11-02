package cn.gulupai.server.common.idempotency;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

@Repository
public class IdempotencyStore {

    private final JdbcTemplate jdbcTemplate;

    private final RowMapper<ReceiptRow> mapper = (rs, rowNum) -> new ReceiptRow(
            rs.getString("id"),
            rs.getString("body_hash"),
            rs.getInt("http_status"),
            rs.getString("business_code"),
            rs.getString("result_ciphertext"),
            rs.getString("key_id"),
            rs.getObject("expires_at", OffsetDateTime.class).toInstant());

    public IdempotencyStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 必须在事务内调用；事务结束自动释放。 */
    public boolean tryLock(long lockKey) {
        Boolean acquired = jdbcTemplate.queryForObject(
                "SELECT pg_try_advisory_xact_lock(?)", Boolean.class, lockKey);
        return Boolean.TRUE.equals(acquired);
    }

    public Optional<ReceiptRow> find(String userId, String method, String path, String key) {
        return jdbcTemplate.query("""
                        SELECT id, body_hash, http_status, business_code, result_ciphertext, key_id, expires_at
                        FROM operation_receipt
                        WHERE user_id = ? AND method = ? AND path = ? AND key = ?
                        """, mapper, userId, method, path, key)
                .stream().findFirst();
    }

    public void delete(String id) {
        jdbcTemplate.update("DELETE FROM operation_receipt WHERE id = ?", id);
    }

    public void insert(String id, String userId, String method, String path, String key,
                       String bodyHash, int httpStatus, String businessCode,
                       String resultCiphertext, String keyId, String resourceRefsJson,
                       Instant expiresAt) {
        jdbcTemplate.update("""
                INSERT INTO operation_receipt
                    (id, user_id, method, path, key, body_hash, http_status, business_code,
                     result_ciphertext, key_id, resource_refs_json, expires_at, revision)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, 1)
                """, id, userId, method, path, key, bodyHash, httpStatus, businessCode,
                resultCiphertext, keyId, resourceRefsJson,
                OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC));
    }
}
