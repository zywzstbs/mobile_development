package cn.gulupai.server.governance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import cn.gulupai.server.common.api.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * W01 K04：跨版本、缺失/错误证据、损坏密文、已有可靠时间和并发恢复。
 *
 * <p>所有“可恢复”前提都由真实 HTTP 提交产生；JDBC 只把目标版本改成升级前
 * UNKNOWN，或改回执创建时间、构造损坏证据。拒绝用例都核对整行恢复相关字段
 * 未变化，而不是只断言异常。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(GovMaterialScenarioSupport.TestClockConfig.class)
class GovW01MaterialTimeEvidenceIT extends W01MaterialTimeSupport {

    @Test
    void w01_01SameAuthorMaterialButV1ReceiptCannotRecoverV2() throws Exception {
        clock.setInstant(BASE.plusSeconds(10 * 3600));
        Scenario s = materialScenario("w01-01");
        respondOk(s.member(), s.materialId(), "v1 正文", 1, "w01-01-v1");
        clock.setInstant(BASE.plusSeconds(10 * 3600 + 600));
        respondOk(s.member(), s.materialId(), "v2 正文", materialRevision(s.materialId()), "w01-01-v2");
        makeUnknown(s.materialId(), 2);

        String v1Receipt = receiptId(MEMBER, responsePath(s.materialId()), "w01-01-v1");
        RowState before = rowState(s.materialId(), 2);

        ApiException dryRun = expectSourceChanged(
                () -> recoveryService.recover(s.materialId(), 2, v1Receipt, true, "test-admin"));
        assertThat(dryRun.getMessage()).contains("版本");
        assertThat(rowState(s.materialId(), 2)).isEqualTo(before);

        ApiException actual = expectSourceChanged(
                () -> recoveryService.recover(s.materialId(), 2, v1Receipt, false, "test-admin"));
        assertThat(actual.getMessage()).contains("版本");
        assertThat(rowState(s.materialId(), 2)).isEqualTo(before);
        assertThat(rowState(s.materialId(), 1).confidence()).isEqualTo("EXACT");
    }

    @Test
    void w01_02V2ReceiptContainsV1ButMustNotRecoverV1() throws Exception {
        clock.setInstant(BASE.plusSeconds(10 * 3600));
        Scenario s = materialScenario("w01-02");
        respondOk(s.member(), s.materialId(), "v1 正文", 1, "w01-02-v1");
        clock.setInstant(BASE.plusSeconds(10 * 3600 + 600));
        respondOk(s.member(), s.materialId(), "v2 正文", materialRevision(s.materialId()), "w01-02-v2");
        makeUnknown(s.materialId(), 1);

        String v2Receipt = receiptId(MEMBER, responsePath(s.materialId()), "w01-02-v2");
        RowState before = rowState(s.materialId(), 1);

        ApiException error = expectSourceChanged(
                () -> recoveryService.recover(s.materialId(), 1, v2Receipt, false, "test-admin"));
        assertThat(error.getMessage()).contains("版本");
        assertThat(rowState(s.materialId(), 1)).isEqualTo(before);
    }

    @Test
    void w01_03RecoveryTakesEvidenceTimeNotReceiptCreatedAt() throws Exception {
        clock.setInstant(BASE.plusSeconds(10 * 3600));
        Scenario s = materialScenario("w01-03");
        respondOk(s.member(), s.materialId(), "可恢复正文", 1, "w01-03-v1");
        Instant submittedAt = BASE.plusSeconds(10 * 3600);
        makeUnknown(s.materialId(), 1);
        RowState before = rowState(s.materialId(), 1);

        String receiptId = receiptId(MEMBER, responsePath(s.materialId()), "w01-03-v1");
        jdbc.update("UPDATE operation_receipt SET created_at = ? WHERE id = ?",
                OffsetDateTime.ofInstant(submittedAt.minus(Duration.ofHours(1)), ZoneOffset.UTC), receiptId);
        ReceiptState receiptBefore = receiptState(receiptId);
        assertThat(receiptBefore.createdAt()).isNotEqualTo(submittedAt);

        MaterialResponseTimeRecoveryService.RecoveryResult dryRun =
                recoveryService.recover(s.materialId(), 1, receiptId, true, "test-admin");
        assertThat(dryRun.status()).isEqualTo("DRY_RUN");
        assertThat(dryRun.createdAt()).isEqualTo(submittedAt);
        assertThat(rowState(s.materialId(), 1)).isEqualTo(before);

        MaterialResponseTimeRecoveryService.RecoveryResult recovered =
                recoveryService.recover(s.materialId(), 1, receiptId, false, "test-admin");
        assertThat(recovered.status()).isEqualTo("RECOVERED");
        assertThat(recovered.createdAt()).isEqualTo(submittedAt);
        assertThat(rowState(s.materialId(), 1).createdAt()).isEqualTo(submittedAt);
        assertThat(receiptState(receiptId)).isEqualTo(receiptBefore);
        assertThat(materialData(s.member(), s.materialId()).get("response").get("createdAt").asText())
                .isEqualTo("2026-10-31T10:00:00Z");
    }

    @Test
    void w01_04MissingNullUnknownOrOldFormatTimeIsRejected() throws Exception {
        clock.setInstant(BASE.plusSeconds(10 * 3600));
        Scenario s = materialScenario("w01-04");
        respondOk(s.member(), s.materialId(), "历史正文", 1, "w01-04-v1");
        makeUnknown(s.materialId(), 1);
        RowState before = rowState(s.materialId(), 1);
        String submitted = "2026-10-31T10:00:00Z";

        List<ObjectNode> variants = new ArrayList<>();
        ObjectNode missingTime = evidenceRoot(s.materialId(), 1);
        ObjectNode missingTimeItem = addItem(missingTime, 1, MEMBER, submitted, "EXACT", false);
        missingTimeItem.remove("createdAt");
        variants.add(missingTime);

        ObjectNode nullTime = evidenceRoot(s.materialId(), 1);
        addItem(nullTime, 1, MEMBER, null, "EXACT", false);
        variants.add(nullTime);

        ObjectNode unknown = evidenceRoot(s.materialId(), 1);
        addItem(unknown, 1, MEMBER, submitted, "UNKNOWN", false);
        variants.add(unknown);

        ObjectNode missingConfidence = evidenceRoot(s.materialId(), 1);
        addItem(missingConfidence, 1, MEMBER, submitted, null, false);
        variants.add(missingConfidence);

        ObjectNode numericTime = evidenceRoot(s.materialId(), 1);
        ObjectNode numericItem = addItem(numericTime, 1, MEMBER, submitted, "EXACT", false);
        numericItem.put("createdAt", 1761904800L);
        variants.add(numericTime);

        // 旧简化格式：没有 responses 数组，不能凭 id/version 猜测时间。
        ObjectNode simplified = objectMapper.createObjectNode();
        simplified.put("id", s.materialId());
        simplified.put("responseVersion", 1);
        variants.add(simplified);

        int index = 0;
        for (ObjectNode variant : variants) {
            String key = "w01-04-" + index++;
            String customReceipt = insertEvidenceReceipt(MEMBER, "POST", responsePath(s.materialId()),
                    key, idempotencyCipher.keyId(), evidenceJson(variant));
            expectSourceChanged(() -> recoveryService.recover(s.materialId(), 1, customReceipt,
                    false, "test-admin"));
            assertThat(rowState(s.materialId(), 1)).isEqualTo(before);
        }
    }

    @Test
    void w01_05OtherMaterialAuthorReviewWithdrawAndFailedReceiptAreRejected() throws Exception {
        clock.setInstant(BASE.plusSeconds(10 * 3600));
        Scenario s = materialScenario("w01-05");
        respondOk(s.member(), s.materialId(), "目标正文", 1, "w01-05-target");
        reviewOk(s.teacher(), s.materialId(), 1, "ACCEPT", null,
                materialRevision(s.materialId()), "w01-05-review");
        makeUnknown(s.materialId(), 1);
        RowState before = rowState(s.materialId(), 1);

        String otherMaterial = requestMaterialWithKey(s.teacher(), s.caseId(), MEMBER, "w01-05-other-mat");
        respondOk(s.member(), otherMaterial, "其他材料正文", 1, "w01-05-other");
        String otherReceipt = receiptId(MEMBER, responsePath(otherMaterial), "w01-05-other");
        expectSourceChanged(() -> recoveryService.recover(s.materialId(), 1, otherReceipt,
                false, "test-admin"));
        assertThat(rowState(s.materialId(), 1)).isEqualTo(before);

        // 同材料、同路径、合法加密结果，但回执作者是教师：不能恢复学生版本。
        String authorReceipt = insertEvidenceReceipt(TEACHER_A, "POST", responsePath(s.materialId()),
                "w01-05-author", idempotencyCipher.keyId(), validEvidence(s.materialId(), 1, MEMBER,
                        BASE.plusSeconds(10 * 3600)));
        expectSourceChanged(() -> recoveryService.recover(s.materialId(), 1, authorReceipt,
                false, "test-admin"));
        assertThat(rowState(s.materialId(), 1)).isEqualTo(before);

        // 同材料审核动作的回执。
        String reviewReceipt = receiptId(TEACHER_A, reviewsPath(s.materialId()), "w01-05-review");
        expectSourceChanged(() -> recoveryService.recover(s.materialId(), 1, reviewReceipt,
                false, "test-admin"));
        assertThat(rowState(s.materialId(), 1)).isEqualTo(before);

        // 同材料撤回动作的回执：另建材料走真实撤回，避免审核后状态不允许撤回。
        Scenario withdrawn = materialScenario("w01-05-withdraw");
        respondOk(withdrawn.member(), withdrawn.materialId(), "撤回正文", 1, "w01-05-withdraw-v1");
        withdrawOk(withdrawn.member(), withdrawn.materialId(), materialRevision(withdrawn.materialId()),
                "撤回", "w01-05-withdraw");
        makeUnknown(withdrawn.materialId(), 1);
        RowState withdrawnBefore = rowState(withdrawn.materialId(), 1);
        String withdrawReceipt = receiptId(MEMBER, withdrawalsPath(withdrawn.materialId()),
                "w01-05-withdraw");
        expectSourceChanged(() -> recoveryService.recover(withdrawn.materialId(), 1, withdrawReceipt,
                false, "test-admin"));
        assertThat(rowState(withdrawn.materialId(), 1)).isEqualTo(withdrawnBefore);

        // 非成功回执：即使路径、作者、密文都合法也不能恢复。
        String failedReceipt = insertReceipt(MEMBER, "POST", responsePath(s.materialId()),
                "w01-05-failed", 409, "SOURCE_CHANGED", idempotencyCipher.keyId(),
                idempotencyCipher.encrypt(validEvidence(s.materialId(), 1, MEMBER,
                        BASE.plusSeconds(10 * 3600))), Instant.now(), Instant.now().plusSeconds(86400));
        expectSourceChanged(() -> recoveryService.recover(s.materialId(), 1, failedReceipt,
                false, "test-admin"));
        assertThat(rowState(s.materialId(), 1)).isEqualTo(before);
    }

    @Test
    void w01_06MissingCorruptMalformedOrUnsupportedKeyReceiptFailsWithoutLeaking() throws Exception {
        clock.setInstant(BASE.plusSeconds(10 * 3600));
        Scenario s = materialScenario("w01-06");
        respondOk(s.member(), s.materialId(), "历史正文", 1, "w01-06-v1");
        makeUnknown(s.materialId(), 1);
        RowState before = rowState(s.materialId(), 1);

        expectCode(cn.gulupai.server.common.api.ApiCode.NOT_FOUND,
                () -> recoveryService.recover(s.materialId(), 1, "missing-" + UUID.randomUUID(),
                        false, "test-admin"));
        assertThat(rowState(s.materialId(), 1)).isEqualTo(before);

        String corrupt = insertReceipt(MEMBER, "POST", responsePath(s.materialId()),
                "w01-06-corrupt", 200, "OK", idempotencyCipher.keyId(), "AAAA",
                Instant.now(), Instant.now().plusSeconds(86400));
        ApiException corruptError = expectSourceChanged(() -> recoveryService.recover(s.materialId(), 1,
                corrupt, false, "test-admin"));
        assertThat(corruptError.getMessage()).contains("解密").doesNotContain("AAAA");
        assertThat(rowState(s.materialId(), 1)).isEqualTo(before);

        String malformed = insertEvidenceReceipt(MEMBER, "POST", responsePath(s.materialId()),
                "w01-06-malformed", idempotencyCipher.keyId(),
                "not-json-sensitive-content");
        ApiException malformedError = expectSourceChanged(() -> recoveryService.recover(s.materialId(), 1,
                malformed, false, "test-admin"));
        assertThat(malformedError.getMessage()).contains("JSON").doesNotContain("sensitive-content");
        assertThat(rowState(s.materialId(), 1)).isEqualTo(before);

        String oldKey = insertEvidenceReceipt(MEMBER, "POST", responsePath(s.materialId()),
                "w01-06-old-key", "old-key-for-diagnosis",
                validEvidence(s.materialId(), 1, MEMBER, BASE.plusSeconds(10 * 3600)));
        ApiException keyError = expectSourceChanged(() -> recoveryService.recover(s.materialId(), 1,
                oldKey, false, "test-admin"));
        assertThat(keyError.getMessage()).contains("加密版本").doesNotContain("old-key-for-diagnosis");
        assertThat(rowState(s.materialId(), 1)).isEqualTo(before);
    }

    @Test
    void w01_07DuplicateWrongTypeAndCompatibleConflictAreRejected() throws Exception {
        clock.setInstant(BASE.plusSeconds(10 * 3600));
        Scenario s = materialScenario("w01-07");
        respondOk(s.member(), s.materialId(), "历史正文", 1, "w01-07-v1");
        makeUnknown(s.materialId(), 1);
        RowState before = rowState(s.materialId(), 1);
        String submitted = "2026-10-31T10:00:00Z";

        ObjectNode duplicate = evidenceRoot(s.materialId(), 1);
        addItem(duplicate, 1, MEMBER, submitted, "EXACT", false);
        addItem(duplicate, 1, MEMBER, submitted, "EXACT", false);
        String duplicateReceipt = insertEvidenceReceipt(MEMBER, "POST", responsePath(s.materialId()),
                "w01-07-duplicate", idempotencyCipher.keyId(), evidenceJson(duplicate));
        expectSourceChanged(() -> recoveryService.recover(s.materialId(), 1, duplicateReceipt,
                false, "test-admin"));
        assertThat(rowState(s.materialId(), 1)).isEqualTo(before);

        ObjectNode wrongType = evidenceRoot(s.materialId(), 1);
        ObjectNode wrongTypeItem = addItem(wrongType, 1, MEMBER, submitted, "EXACT", false);
        wrongTypeItem.put("version", "1");
        String wrongTypeReceipt = insertEvidenceReceipt(MEMBER, "POST", responsePath(s.materialId()),
                "w01-07-type", idempotencyCipher.keyId(), evidenceJson(wrongType));
        expectSourceChanged(() -> recoveryService.recover(s.materialId(), 1, wrongTypeReceipt,
                false, "test-admin"));
        assertThat(rowState(s.materialId(), 1)).isEqualTo(before);

        ObjectNode conflict = evidenceRoot(s.materialId(), 1);
        ObjectNode item = addItem(conflict, 1, MEMBER, submitted, "EXACT", false);
        ObjectNode compatible = item.deepCopy();
        compatible.put("createdAt", "2026-10-31T11:00:00Z");
        setCompatible(conflict, compatible);
        String conflictReceipt = insertEvidenceReceipt(MEMBER, "POST", responsePath(s.materialId()),
                "w01-07-conflict", idempotencyCipher.keyId(), evidenceJson(conflict));
        expectSourceChanged(() -> recoveryService.recover(s.materialId(), 1, conflictReceipt,
                false, "test-admin"));
        assertThat(rowState(s.materialId(), 1)).isEqualTo(before);
    }

    @Test
    void w01_08SameEvidenceRepeatedRecoveryIsIdempotentAndKeepsAudit() throws Exception {
        clock.setInstant(BASE.plusSeconds(10 * 3600));
        Scenario s = materialScenario("w01-08");
        respondOk(s.member(), s.materialId(), "可恢复正文", 1, "w01-08-v1");
        makeUnknown(s.materialId(), 1);
        String receipt = receiptId(MEMBER, responsePath(s.materialId()), "w01-08-v1");

        MaterialResponseTimeRecoveryService.RecoveryResult first =
                recoveryService.recover(s.materialId(), 1, receipt, false, "admin-a");
        assertThat(first.status()).isEqualTo("RECOVERED");
        RowState recovered = rowState(s.materialId(), 1);
        assertThat(recovered.confidence()).isEqualTo("EXACT");
        assertThat(recovered.source()).isEqualTo("RECOVERED_EVIDENCE");
        assertThat(recovered.evidence()).isEqualTo(EVIDENCE_PREFIX + receipt);
        assertThat(recovered.recoveredBy()).isEqualTo("admin-a");
        assertThat(recovered.recoveredAt()).isEqualTo(BASE.plusSeconds(10 * 3600));
        assertThat(recovered.createdAt()).isEqualTo(BASE.plusSeconds(10 * 3600));
        assertThat(recovered.legacyRecordedAt()).isEqualTo(BASE.plusSeconds(10 * 3600));

        MaterialResponseTimeRecoveryService.RecoveryResult again =
                recoveryService.recover(s.materialId(), 1, receipt, false, "admin-b");
        assertThat(again.status()).isEqualTo("ALREADY_RECOVERED");
        assertThat(rowState(s.materialId(), 1)).isEqualTo(recovered);

        MaterialResponseTimeRecoveryService.RecoveryResult dryRun =
                recoveryService.recover(s.materialId(), 1, receipt, true, "admin-c");
        assertThat(dryRun.status()).isEqualTo("ALREADY_RECOVERED");
        assertThat(rowState(s.materialId(), 1)).isEqualTo(recovered);
    }

    @Test
    void w01_09ServerSubmissionAndOtherExactTimesAreNeverOverwritten() throws Exception {
        clock.setInstant(BASE.plusSeconds(10 * 3600));
        Scenario server = materialScenario("w01-09-server");
        respondOk(server.member(), server.materialId(), "服务器时间正文", 1, "w01-09-server-v1");
        String serverReceipt = receiptId(MEMBER, responsePath(server.materialId()), "w01-09-server-v1");
        RowState serverBefore = rowState(server.materialId(), 1);
        ApiException serverError = expectSourceChanged(() -> recoveryService.recover(server.materialId(), 1,
                serverReceipt, false, "test-admin"));
        assertThat(serverError.getMessage()).contains("已有");
        assertThat(rowState(server.materialId(), 1)).isEqualTo(serverBefore);
        assertThat(serverBefore.source()).isEqualTo("SERVER_SUBMISSION");

        Scenario different = materialScenario("w01-09-different");
        respondOk(different.member(), different.materialId(), "不同证据正文", 1, "w01-09-different-v1");
        makeUnknown(different.materialId(), 1);
        String firstReceipt = receiptId(MEMBER, responsePath(different.materialId()),
                "w01-09-different-v1");
        assertThat(recoveryService.recover(different.materialId(), 1, firstReceipt, false, "admin-a").status())
                .isEqualTo("RECOVERED");
        String secondReceipt = copyReceipt(firstReceipt, "w01-09-copy-receipt");
        RowState differentBefore = rowState(different.materialId(), 1);
        ApiException differentError = expectSourceChanged(() -> recoveryService.recover(different.materialId(),
                1, secondReceipt, false, "admin-b"));
        assertThat(differentError.getMessage()).contains("已有");
        assertThat(rowState(different.materialId(), 1)).isEqualTo(differentBefore);

        Scenario wrongTime = materialScenario("w01-09-wrong-time");
        respondOk(wrongTime.member(), wrongTime.materialId(), "旧错误时间正文", 1, "w01-09-wrong-time-v1");
        makeUnknown(wrongTime.materialId(), 1);
        String wrongTimeReceipt = receiptId(MEMBER, responsePath(wrongTime.materialId()),
                "w01-09-wrong-time-v1");
        assertThat(recoveryService.recover(wrongTime.materialId(), 1, wrongTimeReceipt, false, "admin-a")
                .status()).isEqualTo("RECOVERED");
        jdbc.update("""
                UPDATE material_response SET created_at = ?
                WHERE material_id = ? AND version = 1
                """, OffsetDateTime.ofInstant(BASE.plusSeconds(10 * 3600 + 3600), ZoneOffset.UTC),
                wrongTime.materialId());
        RowState wrongTimeBefore = rowState(wrongTime.materialId(), 1);
        ApiException wrongTimeError = expectSourceChanged(() -> recoveryService.recover(wrongTime.materialId(),
                1, wrongTimeReceipt, false, "admin-b"));
        assertThat(wrongTimeError.getMessage()).contains("已有");
        assertThat(rowState(wrongTime.materialId(), 1)).isEqualTo(wrongTimeBefore);
    }

    @Test
    void w01_10ConcurrentRecoveryChangesStateOnlyOnce() throws Exception {
        clock.setInstant(BASE.plusSeconds(10 * 3600));
        Scenario s = materialScenario("w01-10");
        respondOk(s.member(), s.materialId(), "并发正文", 1, "w01-10-v1");
        makeUnknown(s.materialId(), 1);
        String receipt = receiptId(MEMBER, responsePath(s.materialId()), "w01-10-v1");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<Object> task = () -> {
                start.await(10, TimeUnit.SECONDS);
                try {
                    return recoveryService.recover(s.materialId(), 1, receipt, false, "concurrent-admin");
                } catch (Exception ex) {
                    return ex;
                }
            };
            Future<Object> first = pool.submit(task);
            Future<Object> second = pool.submit(task);
            start.countDown();
            Object firstResult = first.get(30, TimeUnit.SECONDS);
            Object secondResult = second.get(30, TimeUnit.SECONDS);

            List<Object> results = List.of(firstResult, secondResult);
            long recovered = results.stream()
                    .filter(result -> result instanceof MaterialResponseTimeRecoveryService.RecoveryResult r
                            && "RECOVERED".equals(r.status()))
                    .count();
            long idempotent = results.stream()
                    .filter(result -> result instanceof MaterialResponseTimeRecoveryService.RecoveryResult r
                            && "ALREADY_RECOVERED".equals(r.status()))
                    .count();
            long conflict = results.stream()
                    .filter(result -> result instanceof cn.gulupai.server.common.api.ApiException ex
                            && ex.code() == cn.gulupai.server.common.api.ApiCode.SOURCE_CHANGED)
                    .count();
            assertThat(recovered).isEqualTo(1);
            assertThat(idempotent + conflict).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }

        RowState row = rowState(s.materialId(), 1);
        assertThat(row.confidence()).isEqualTo("EXACT");
        assertThat(row.source()).isEqualTo("RECOVERED_EVIDENCE");
        assertThat(row.evidence()).isEqualTo(EVIDENCE_PREFIX + receipt);
        assertThat(row.createdAt()).isEqualTo(BASE.plusSeconds(10 * 3600));
        assertThat(row.recoveredAt()).isNotNull();
    }

    @Test
    void w01_11RecoveryKeepsEvidenceSecondPrecisionForFractionalSubmission() throws Exception {
        Instant actual = BASE.plusSeconds(10 * 3600).plusNanos(987_654_321);
        clock.setInstant(actual);
        Scenario s = materialScenario("w01-11");
        respondOk(s.member(), s.materialId(), "小数时间正文", 1, "w01-11-v1");
        Instant stored = jdbc.queryForObject("""
                SELECT created_at FROM material_response WHERE material_id = ? AND version = 1
                """, OffsetDateTime.class, s.materialId()).toInstant();
        assertThat(stored.getNano()).isNotZero();

        makeUnknown(s.materialId(), 1);
        String receipt = receiptId(MEMBER, responsePath(s.materialId()), "w01-11-v1");
        MaterialResponseTimeRecoveryService.RecoveryResult result =
                recoveryService.recover(s.materialId(), 1, receipt, false, "test-admin");
        assertThat(result.status()).isEqualTo("RECOVERED");
        assertThat(result.createdAt()).isEqualTo(BASE.plusSeconds(10 * 3600));

        RowState row = rowState(s.materialId(), 1);
        assertThat(row.createdAt()).isEqualTo(BASE.plusSeconds(10 * 3600));
        assertThat(row.legacyRecordedAt()).isAfter(BASE.plusSeconds(10 * 3600));
        // 操作时刻来自 Clock（仍保留小数），提交时间来自秒级证据。
        assertThat(row.recoveredAt()).isAfter(row.createdAt());
        JsonNode view = materialData(s.member(), s.materialId());
        assertThat(view.get("response").get("createdAt").asText()).isEqualTo("2026-10-31T10:00:00Z");
        assertThat(view.get("response").get("createdAtConfidence").asText()).isEqualTo("EXACT");
    }

    @Test
    void w01_12ExpiredReceiptIsStillUsableButDeletedReceiptIsRejected() throws Exception {
        clock.setInstant(BASE.plusSeconds(10 * 3600));
        Scenario s = materialScenario("w01-12");
        respondOk(s.member(), s.materialId(), "过期证据正文", 1, "w01-12-v1");
        makeUnknown(s.materialId(), 1);
        RowState before = rowState(s.materialId(), 1);

        String deleted = insertEvidenceReceipt(MEMBER, "POST", responsePath(s.materialId()),
                "w01-12-deleted", idempotencyCipher.keyId(),
                validEvidence(s.materialId(), 1, MEMBER, BASE.plusSeconds(10 * 3600)));
        jdbc.update("DELETE FROM operation_receipt WHERE id = ?", deleted);
        expectCode(cn.gulupai.server.common.api.ApiCode.NOT_FOUND,
                () -> recoveryService.recover(s.materialId(), 1, deleted, false, "test-admin"));
        assertThat(rowState(s.materialId(), 1)).isEqualTo(before);

        String receipt = receiptId(MEMBER, responsePath(s.materialId()), "w01-12-v1");
        ReceiptState receiptBefore = receiptState(receipt);
        jdbc.update("UPDATE operation_receipt SET expires_at = ? WHERE id = ?",
                OffsetDateTime.ofInstant(Instant.now().minusSeconds(3600), ZoneOffset.UTC), receipt);
        ReceiptState expired = receiptState(receipt);
        assertThat(expired.expiresAt()).isBefore(Instant.now());

        MaterialResponseTimeRecoveryService.RecoveryResult result =
                recoveryService.recover(s.materialId(), 1, receipt, false, "test-admin");
        assertThat(result.status()).isEqualTo("RECOVERED");
        assertThat(rowState(s.materialId(), 1).createdAt()).isEqualTo(BASE.plusSeconds(10 * 3600));
        // 恢复只读取过期回执，不延长有效期，也不改回执创建时间。
        assertThat(receiptState(receipt)).isEqualTo(expired);
        assertThat(expired.createdAt()).isEqualTo(receiptBefore.createdAt());
    }

    @Test
    void w01UnknownWithAbnormalSourceOrRecoveryMetadataIsNotAutoCleaned() throws Exception {
        clock.setInstant(BASE.plusSeconds(10 * 3600));
        Scenario s = materialScenario("w01-unknown-abnormal");
        respondOk(s.member(), s.materialId(), "异常组合正文", 1, "w01-unknown-abnormal-v1");
        makeUnknown(s.materialId(), 1);
        String receipt = receiptId(MEMBER, responsePath(s.materialId()), "w01-unknown-abnormal-v1");

        jdbc.update("""
                UPDATE material_response SET time_source = 'SERVER_SUBMISSION'
                WHERE material_id = ? AND version = 1
                """, s.materialId());
        RowState serverSource = rowState(s.materialId(), 1);
        ApiException sourceError = expectSourceChanged(() -> recoveryService.recover(s.materialId(), 1,
                receipt, false, "test-admin"));
        assertThat(sourceError.getMessage()).contains("状态异常");
        assertThat(rowState(s.materialId(), 1)).isEqualTo(serverSource);

        jdbc.update("""
                UPDATE material_response
                SET time_source = 'LEGACY_UNKNOWN', time_evidence = 'operation_receipt:orphan'
                WHERE material_id = ? AND version = 1
                """, s.materialId());
        RowState leftoverEvidence = rowState(s.materialId(), 1);
        ApiException evidenceError = expectSourceChanged(() -> recoveryService.recover(s.materialId(), 1,
                receipt, false, "test-admin"));
        assertThat(evidenceError.getMessage()).contains("状态异常");
        assertThat(rowState(s.materialId(), 1)).isEqualTo(leftoverEvidence);
    }

    @Test
    void w01RecoveryIsNotBlockedByAcceptedMaterialOrClosedCase() throws Exception {
        clock.setInstant(BASE.plusSeconds(10 * 3600));
        Scenario s = materialScenario("w01-status");
        respondOk(s.member(), s.materialId(), "已验收正文", 1, "w01-status-v1");
        reviewOk(s.teacher(), s.materialId(), 1, "ACCEPT", null,
                materialRevision(s.materialId()), "w01-status-review");
        closeCase(s.teacher(), s.caseId(), "w01-status-close");
        makeUnknown(s.materialId(), 1);
        assertThat(caseStatus(s.caseId())).isEqualTo("CLOSED");

        String receipt = receiptId(MEMBER, responsePath(s.materialId()), "w01-status-v1");
        MaterialResponseTimeRecoveryService.RecoveryResult result =
                recoveryService.recover(s.materialId(), 1, receipt, false, "test-admin");
        assertThat(result.status()).isEqualTo("RECOVERED");
        assertThat(rowState(s.materialId(), 1).confidence()).isEqualTo("EXACT");
        assertThat(caseStatus(s.caseId())).isEqualTo("CLOSED");
    }

    private String validEvidence(String materialId, int version, String authorId, Instant submittedAt) {
        ObjectNode root = evidenceRoot(materialId, version);
        ObjectNode item = addItem(root, version, authorId, submittedAt.toString(), "EXACT", false);
        setCompatible(root, item);
        return evidenceJson(root);
    }
}
