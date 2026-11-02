package cn.gulupai.server.governance;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * K03：存量 RECOVERED_EVIDENCE 只读核查。
 *
 * <p>本测试同时验证三类结果和“只读不写”：VALID、CORRECTION_REQUIRED、
 * UNVERIFIABLE；普通 SERVER_SUBMISSION 不进入候选。第五轮没有实现自动纠错，
 * 因此这里只证明分类与不改数据；真实环境纠错需另行受控执行并留持久审计。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(GovMaterialScenarioSupport.TestClockConfig.class)
class GovW01MaterialTimeAuditIT extends W01MaterialTimeSupport {

    @Test
    void w01AuditClassifiesRecoveredRowsWithoutWriting() throws Exception {
        clock.setInstant(BASE.plusSeconds(10 * 3600));

        Scenario valid = materialScenario("w01-audit-valid");
        respondOk(valid.member(), valid.materialId(), "有效恢复正文", 1, "w01-audit-valid-v1");
        makeUnknown(valid.materialId(), 1);
        String validReceipt = receiptId(MEMBER, responsePath(valid.materialId()), "w01-audit-valid-v1");
        assertThat(recoveryService.recover(valid.materialId(), 1, validReceipt, false, "admin-a").status())
                .isEqualTo("RECOVERED");
        RowState validBefore = rowState(valid.materialId(), 1);

        Scenario correction = materialScenario("w01-audit-correction");
        respondOk(correction.member(), correction.materialId(), "错时间恢复正文", 1,
                "w01-audit-correction-v1");
        makeUnknown(correction.materialId(), 1);
        String correctionReceipt = receiptId(MEMBER, responsePath(correction.materialId()),
                "w01-audit-correction-v1");
        assertThat(recoveryService.recover(correction.materialId(), 1, correctionReceipt, false, "admin-b")
                .status()).isEqualTo("RECOVERED");
        jdbc.update("""
                UPDATE material_response SET created_at = ?
                WHERE material_id = ? AND version = 1
                """, OffsetDateTime.ofInstant(BASE.plusSeconds(11 * 3600), ZoneOffset.UTC),
                correction.materialId());
        RowState correctionBefore = rowState(correction.materialId(), 1);

        Scenario unverifiable = materialScenario("w01-audit-unverifiable");
        respondOk(unverifiable.member(), unverifiable.materialId(), "缺回执正文", 1,
                "w01-audit-unverifiable-v1");
        makeUnknown(unverifiable.materialId(), 1);
        String unverifiableReceipt = receiptId(MEMBER, responsePath(unverifiable.materialId()),
                "w01-audit-unverifiable-v1");
        assertThat(recoveryService.recover(unverifiable.materialId(), 1, unverifiableReceipt, false, "admin-c")
                .status()).isEqualTo("RECOVERED");
        jdbc.update("DELETE FROM operation_receipt WHERE id = ?", unverifiableReceipt);
        RowState unverifiableBefore = rowState(unverifiable.materialId(), 1);

        // 普通服务器提交不在扫描范围。
        Scenario server = materialScenario("w01-audit-server");
        respondOk(server.member(), server.materialId(), "普通提交正文", 1, "w01-audit-server-v1");

        MaterialTimeRecoveryAuditService.AuditReport report = auditService.audit();
        assertThat(report.items()).hasSize(3);
        assertThat(report.valid()).isEqualTo(1);
        assertThat(report.correctionRequired()).isEqualTo(1);
        assertThat(report.unverifiable()).isEqualTo(1);
        assertThat(report.describe()).contains("候选=3", "VALID=1", "CORRECTION_REQUIRED=1",
                "UNVERIFIABLE=1");

        assertThat(categoryOf(report, valid.materialId())).isEqualTo(
                MaterialTimeRecoveryAuditService.Category.VALID);
        assertThat(categoryOf(report, correction.materialId())).isEqualTo(
                MaterialTimeRecoveryAuditService.Category.CORRECTION_REQUIRED);
        assertThat(categoryOf(report, unverifiable.materialId())).isEqualTo(
                MaterialTimeRecoveryAuditService.Category.UNVERIFIABLE);
        assertThat(report.items()).noneMatch(item -> item.materialId().equals(server.materialId()));

        // 只读核查不得产生纠错或任何业务写入。
        assertThat(rowState(valid.materialId(), 1)).isEqualTo(validBefore);
        assertThat(rowState(correction.materialId(), 1)).isEqualTo(correctionBefore);
        assertThat(rowState(unverifiable.materialId(), 1)).isEqualTo(unverifiableBefore);
        assertThat(correctionBefore.createdAt()).isEqualTo(Instant.parse("2026-10-31T11:00:00Z"));
    }

    @Test
    void w01AuditWithNoRecoveredCandidatesReportsZeroAndKeepsServerSubmission() throws Exception {
        clock.setInstant(BASE.plusSeconds(10 * 3600));
        Scenario server = materialScenario("w01-audit-empty");
        respondOk(server.member(), server.materialId(), "普通提交正文", 1, "w01-audit-empty-v1");
        RowState before = rowState(server.materialId(), 1);

        MaterialTimeRecoveryAuditService.AuditReport report = auditService.audit();
        assertThat(report.candidatesAbsent()).isTrue();
        assertThat(report.valid()).isZero();
        assertThat(report.correctionRequired()).isZero();
        assertThat(report.unverifiable()).isZero();
        assertThat(rowState(server.materialId(), 1)).isEqualTo(before);
        assertThat(before.source()).isEqualTo("SERVER_SUBMISSION");
    }

    private MaterialTimeRecoveryAuditService.Category categoryOf(
            MaterialTimeRecoveryAuditService.AuditReport report, String materialId) {
        return report.items().stream()
                .filter(item -> item.materialId().equals(materialId))
                .findFirst()
                .orElseThrow()
                .category();
    }
}
