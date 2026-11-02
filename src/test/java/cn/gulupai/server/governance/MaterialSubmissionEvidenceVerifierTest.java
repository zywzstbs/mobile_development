package cn.gulupai.server.governance;

import cn.gulupai.server.common.api.ApiCode;
import cn.gulupai.server.common.api.ApiException;
import cn.gulupai.server.common.idempotency.IdempotencyCipher;
import cn.gulupai.server.common.idempotency.IdempotencyProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * K01 单元测试：只验证解密后的结构和时间精度，不依赖数据库。
 * 数据库级拒绝/无写入由 GovW01MaterialTimeEvidenceIT 覆盖。
 */
class MaterialSubmissionEvidenceVerifierTest {

    private static final String MATERIAL = "material-1";
    private static final String AUTHOR = "user-1";
    private static final Instant SUBMITTED = Instant.parse("2026-10-31T10:00:00Z");

    private final IdempotencyCipher cipher = cipher();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MaterialSubmissionEvidenceVerifier verifier =
            new MaterialSubmissionEvidenceVerifier(cipher, objectMapper);
    private final GovRepository.MaterialResponseRow target = target(2);

    @Test
    void acceptsOnlyTheTopLevelVersionWithExactUnwithdrawnTime() {
        VerifiedSubmissionEvidence evidence = verifier.verify(target,
                receipt(validJson(2, SUBMITTED, "EXACT", false), cipher.keyId()));

        assertThat(evidence.materialId()).isEqualTo(MATERIAL);
        assertThat(evidence.responseVersion()).isEqualTo(2);
        assertThat(evidence.authorId()).isEqualTo(AUTHOR);
        assertThat(evidence.submittedAt()).isEqualTo(SUBMITTED);
        assertThat(evidence.receiptId()).isEqualTo("receipt-1");
    }

    @Test
    void rejectsReceiptProducedForAnotherVersionEvenIfArrayContainsTarget() {
        // 真正目标是 v2；回执顶层证明的是 v1，数组里即使带 v2 也不能采信。
        String json = """
                {"id":"material-1","responseVersion":1,
                 "responses":[{"version":1,"authorId":"user-1","createdAt":"2026-10-31T09:00:00Z",
                               "createdAtConfidence":"EXACT","withdrawn":false},
                              {"version":2,"authorId":"user-1","createdAt":"2026-10-31T10:00:00Z",
                               "createdAtConfidence":"EXACT","withdrawn":false}]}
                """;
        assertSourceChanged(() -> verifier.verify(target, receipt(json, cipher.keyId())), "版本");
    }

    @Test
    void rejectsMissingOrDuplicateTargetVersionEntries() {
        String missing = """
                {"id":"material-1","responseVersion":2,
                 "responses":[{"version":1,"authorId":"user-1","createdAt":"2026-10-31T09:00:00Z",
                               "createdAtConfidence":"EXACT","withdrawn":false}]}
                """;
        assertSourceChanged(() -> verifier.verify(target, receipt(missing, cipher.keyId())), "不包含");

        String duplicate = """
                {"id":"material-1","responseVersion":2,
                 "responses":[{"version":2,"authorId":"user-1","createdAt":"2026-10-31T10:00:00Z",
                               "createdAtConfidence":"EXACT","withdrawn":false},
                              {"version":2,"authorId":"user-1","createdAt":"2026-10-31T10:00:00Z",
                               "createdAtConfidence":"EXACT","withdrawn":false}]}
                """;
        assertSourceChanged(() -> verifier.verify(target, receipt(duplicate, cipher.keyId())), "重复");
    }

    @Test
    void rejectsMissingUnknownOrMalformedTime() {
        String unknown = validJson(2, SUBMITTED, "UNKNOWN", false);
        assertSourceChanged(() -> verifier.verify(target, receipt(unknown, cipher.keyId())), "EXACT");

        String missingTime = """
                {"id":"material-1","responseVersion":2,
                 "responses":[{"version":2,"authorId":"user-1","createdAtConfidence":"EXACT",
                               "withdrawn":false}]}
                """;
        assertSourceChanged(() -> verifier.verify(target, receipt(missingTime, cipher.keyId())), "提交时间");

        String numericTime = """
                {"id":"material-1","responseVersion":2,
                 "responses":[{"version":2,"authorId":"user-1","createdAt":1761904800,
                               "createdAtConfidence":"EXACT","withdrawn":false}]}
                """;
        assertSourceChanged(() -> verifier.verify(target, receipt(numericTime, cipher.keyId())), "提交时间");
    }

    @Test
    void rejectsWithdrawnTargetOrConflictingCompatibleObject() {
        String withdrawn = validJson(2, SUBMITTED, "EXACT", true);
        assertSourceChanged(() -> verifier.verify(target, receipt(withdrawn, cipher.keyId())), "撤回");

        String conflict = """
                {"id":"material-1","responseVersion":2,
                 "responses":[{"version":2,"authorId":"user-1","createdAt":"2026-10-31T10:00:00Z",
                               "createdAtConfidence":"EXACT","withdrawn":false}],
                 "response":{"version":2,"authorId":"user-1","createdAt":"2026-10-31T11:00:00Z",
                             "createdAtConfidence":"EXACT","withdrawn":false}}
                """;
        assertSourceChanged(() -> verifier.verify(target, receipt(conflict, cipher.keyId())), "兼容");
    }

    @Test
    void rejectsWrongMaterialAuthorPathStatusOrKeyId() {
        assertSourceChanged(() -> verifier.verify(target,
                receipt(validJson(2, SUBMITTED, "EXACT", false).replace(MATERIAL, "other-material"),
                        cipher.keyId())), "目标材料");

        GovRepository.OperationReceiptEvidence wrongAuthor = new GovRepository.OperationReceiptEvidence(
                "receipt-1", "other-user", "POST", path(), 200, "OK",
                cipher.encrypt(validJson(2, SUBMITTED, "EXACT", false)), cipher.keyId(),
                Instant.now(), Instant.now().plusSeconds(3600));
        assertSourceChanged(() -> verifier.verify(target, wrongAuthor), "作者");

        GovRepository.OperationReceiptEvidence wrongPath = new GovRepository.OperationReceiptEvidence(
                "receipt-1", AUTHOR, "POST", "/api/v1/material-requests/material-1/reviews", 200, "OK",
                cipher.encrypt(validJson(2, SUBMITTED, "EXACT", false)), cipher.keyId(),
                Instant.now(), Instant.now().plusSeconds(3600));
        assertSourceChanged(() -> verifier.verify(target, wrongPath), "路径");

        GovRepository.OperationReceiptEvidence failed = new GovRepository.OperationReceiptEvidence(
                "receipt-1", AUTHOR, "POST", path(), 409, "SOURCE_CHANGED",
                cipher.encrypt(validJson(2, SUBMITTED, "EXACT", false)), cipher.keyId(),
                Instant.now(), Instant.now().plusSeconds(3600));
        assertSourceChanged(() -> verifier.verify(target, failed), "成功");

        GovRepository.OperationReceiptEvidence oldKey = new GovRepository.OperationReceiptEvidence(
                "receipt-1", AUTHOR, "POST", path(), 200, "OK",
                cipher.encrypt(validJson(2, SUBMITTED, "EXACT", false)), "old-key",
                Instant.now(), Instant.now().plusSeconds(3600));
        assertSourceChanged(() -> verifier.verify(target, oldKey), "加密版本");
    }

    @Test
    void rejectsCorruptCiphertextAndMalformedJsonWithoutLeakingContent() {
        GovRepository.OperationReceiptEvidence corrupt = new GovRepository.OperationReceiptEvidence(
                "receipt-1", AUTHOR, "POST", path(), 200, "OK", "AAAA", cipher.keyId(),
                Instant.now(), Instant.now().plusSeconds(3600));
        ApiException corruptError = assertSourceChanged(() -> verifier.verify(target, corrupt), "解密");
        assertThat(corruptError.getMessage()).doesNotContain("AAAA");

        GovRepository.OperationReceiptEvidence malformed = new GovRepository.OperationReceiptEvidence(
                "receipt-1", AUTHOR, "POST", path(), 200, "OK",
                cipher.encrypt("not-json-sensitive-content"), cipher.keyId(),
                Instant.now(), Instant.now().plusSeconds(3600));
        ApiException malformedError = assertSourceChanged(
                () -> verifier.verify(target, malformed), "JSON");
        assertThat(malformedError.getMessage()).doesNotContain("sensitive-content");
    }

    // ---- fixtures ----

    private static IdempotencyCipher cipher() {
        IdempotencyProperties properties = new IdempotencyProperties();
        properties.setEncryptionKey(Base64.getEncoder().encodeToString(
                "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8)));
        properties.setKeyId("test-v1");
        return new IdempotencyCipher(properties);
    }

    private static GovRepository.MaterialResponseRow target(int version) {
        return new GovRepository.MaterialResponseRow("response-1", MATERIAL, version, AUTHOR, "正文",
                "[]", false, null, "UNKNOWN", null, "LEGACY_UNKNOWN", null, null, null);
    }

    private static String path() {
        return "/api/v1/material-requests/" + MATERIAL + "/responses";
    }

    private static String validJson(int topVersion, Instant submittedAt, String confidence, boolean withdrawn) {
        String time = submittedAt.toString().replace(".000Z", "Z");
        return """
                {"id":"material-1","responseVersion":%d,
                 "responses":[{"version":1,"authorId":"user-1","createdAt":"2026-10-31T09:00:00Z",
                               "createdAtConfidence":"EXACT","withdrawn":false},
                              {"version":%d,"authorId":"user-1","createdAt":"%s",
                               "createdAtConfidence":"%s","withdrawn":%s}],
                 "response":{"version":%d,"authorId":"user-1","createdAt":"%s",
                             "createdAtConfidence":"%s","withdrawn":%s}}
                """.formatted(topVersion, topVersion, time, confidence, withdrawn,
                topVersion, time, confidence, withdrawn);
    }

    private GovRepository.OperationReceiptEvidence receipt(String json, String keyId) {
        return new GovRepository.OperationReceiptEvidence("receipt-1", AUTHOR, "POST", path(), 200, "OK",
                cipher.encrypt(json), keyId, Instant.now(), Instant.now().plusSeconds(3600));
    }

    private ApiException assertSourceChanged(ThrowingCallable callable, String messagePart) {
        try {
            callable.call();
            fail("预期返回 SOURCE_CHANGED");
        } catch (ApiException ex) {
            assertThat(ex.code()).isEqualTo(ApiCode.SOURCE_CHANGED);
            assertThat(ex.getMessage()).contains(messagePart);
            return ex;
        } catch (Throwable ex) {
            throw new AssertionError("预期 ApiException(SOURCE_CHANGED)，实际 " + ex, ex);
        }
        throw new AssertionError("unreachable");
    }
}
