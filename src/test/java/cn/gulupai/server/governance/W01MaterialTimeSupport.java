package cn.gulupai.server.governance;

import cn.gulupai.server.common.api.ApiCode;
import cn.gulupai.server.common.api.ApiException;
import cn.gulupai.server.common.idempotency.IdempotencyCipher;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * W01 测试共用夹具：真实 HTTP 生成材料提交与回执，JDBC 只用于制造升级前
 * UNKNOWN、修改回执创建时间或构造损坏证据等无法从生产入口制造的异常。
 */
abstract class W01MaterialTimeSupport extends GovMaterialScenarioSupport {

    protected static final String EVIDENCE_PREFIX = "operation_receipt:";

    @Autowired protected MaterialResponseTimeRecoveryService recoveryService;
    @Autowired protected MaterialTimeRecoveryAuditService auditService;
    @Autowired protected IdempotencyCipher idempotencyCipher;

    protected void makeUnknown(String materialId, int version) {
        jdbc.update("""
                UPDATE material_response
                SET legacy_recorded_at = created_at, created_at = NULL,
                    created_at_confidence = 'UNKNOWN', time_source = 'LEGACY_UNKNOWN',
                    time_evidence = NULL, time_recovered_by = NULL, time_recovered_at = NULL
                WHERE material_id = ? AND version = ?
                """, materialId, version);
    }

    protected String responsePath(String materialId) {
        return "/api/v1/material-requests/" + materialId + "/responses";
    }

    protected String reviewsPath(String materialId) {
        return "/api/v1/material-requests/" + materialId + "/reviews";
    }

    protected String withdrawalsPath(String materialId) {
        return "/api/v1/material-requests/" + materialId + "/withdrawals";
    }

    protected String receiptId(String userId, String path, String key) {
        return jdbc.queryForObject("""
                SELECT id FROM operation_receipt
                WHERE user_id = ? AND method = 'POST' AND path = ? AND key = ?
                """, String.class, userId, path, key);
    }

    protected String requestMaterialWithKey(String token, String caseId, String recipient, String key)
            throws Exception {
        return data(mockMvc.perform(post("/api/v1/personnel-cases/{id}/material-requests", caseId)
                        .header("Authorization", bearer(token)).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(materialCreate(caseRevision(caseId), recipient)))
                .andExpect(status().isOk()).andReturn()).get("id").asText();
    }

    protected String insertEvidenceReceipt(String userId, String method, String path, String key,
                                           String keyId, String evidenceJson) {
        return insertReceipt(userId, method, path, key, 200, "OK", keyId,
                idempotencyCipher.encrypt(evidenceJson), Instant.now(), Instant.now().plusSeconds(86400));
    }

    protected String insertReceipt(String userId, String method, String path, String key, int httpStatus,
                                   String businessCode, String keyId, String ciphertext,
                                   Instant createdAt, Instant expiresAt) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO operation_receipt
                    (id, user_id, method, path, key, body_hash, http_status, business_code,
                     result_ciphertext, key_id, expires_at, revision, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?)
                """, id, userId, method, path, key, "a".repeat(64), httpStatus, businessCode,
                ciphertext, keyId, OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC),
                OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC));
        return id;
    }

    protected String copyReceipt(String sourceReceiptId, String key) {
        String ciphertext = jdbc.queryForObject(
                "SELECT result_ciphertext FROM operation_receipt WHERE id = ?", String.class, sourceReceiptId);
        String userId = jdbc.queryForObject(
                "SELECT user_id FROM operation_receipt WHERE id = ?", String.class, sourceReceiptId);
        String path = jdbc.queryForObject(
                "SELECT path FROM operation_receipt WHERE id = ?", String.class, sourceReceiptId);
        String keyId = jdbc.queryForObject(
                "SELECT key_id FROM operation_receipt WHERE id = ?", String.class, sourceReceiptId);
        return insertReceipt(userId, "POST", path, key, 200, "OK", keyId, ciphertext,
                Instant.now(), Instant.now().plusSeconds(86400));
    }

    protected ObjectNode evidenceRoot(String materialId, int responseVersion) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("id", materialId);
        root.put("responseVersion", responseVersion);
        root.putArray("responses");
        return root;
    }

    protected ObjectNode addItem(ObjectNode root, int version, String authorId, String createdAt,
                                 String confidence, Boolean withdrawn) {
        ObjectNode item = objectMapper.createObjectNode();
        item.put("version", version);
        item.put("authorId", authorId);
        if (createdAt == null) {
            item.putNull("createdAt");
        } else {
            item.put("createdAt", createdAt);
        }
        if (confidence != null) {
            item.put("createdAtConfidence", confidence);
        }
        if (withdrawn != null) {
            item.put("withdrawn", withdrawn);
        }
        ArrayNode responses = (ArrayNode) root.get("responses");
        responses.add(item);
        return item;
    }

    protected void setCompatible(ObjectNode root, ObjectNode item) {
        root.set("response", item.deepCopy());
    }

    protected String evidenceJson(ObjectNode root) {
        try {
            return objectMapper.writeValueAsString(root);
        } catch (Exception ex) {
            throw new IllegalStateException("无法构造测试证据JSON", ex);
        }
    }

    protected RowState rowState(String materialId, int version) {
        return jdbc.queryForObject("""
                SELECT id, created_at, created_at_confidence, time_source, time_evidence,
                       time_recovered_by, time_recovered_at, legacy_recorded_at, withdrawn
                FROM material_response WHERE material_id = ? AND version = ?
                """, (rs, n) -> new RowState(
                rs.getString("id"), instant(rs, "created_at"), rs.getString("created_at_confidence"),
                rs.getString("time_source"), rs.getString("time_evidence"),
                rs.getString("time_recovered_by"), instant(rs, "time_recovered_at"),
                instant(rs, "legacy_recorded_at"), rs.getBoolean("withdrawn")), materialId, version);
    }

    protected ReceiptState receiptState(String receiptId) {
        return jdbc.queryForObject("""
                SELECT created_at, expires_at FROM operation_receipt WHERE id = ?
                """, (rs, n) -> new ReceiptState(instant(rs, "created_at"), instant(rs, "expires_at")),
                receiptId);
    }

    protected ApiException expectSourceChanged(ThrowingCallable callable) {
        return expectCode(ApiCode.SOURCE_CHANGED, callable);
    }

    protected ApiException expectCode(ApiCode code, ThrowingCallable callable) {
        try {
            callable.call();
            fail("预期返回 " + code);
        } catch (ApiException ex) {
            assertThat(ex.code()).isEqualTo(code);
            return ex;
        } catch (Throwable ex) {
            throw new AssertionError("预期 ApiException(" + code + ")，实际 " + ex, ex);
        }
        throw new AssertionError("unreachable");
    }

    protected static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    protected record RowState(String id, Instant createdAt, String confidence, String source,
                              String evidence, String recoveredBy, Instant recoveredAt,
                              Instant legacyRecordedAt, boolean withdrawn) {
    }

    protected record ReceiptState(Instant createdAt, Instant expiresAt) {
    }
}
