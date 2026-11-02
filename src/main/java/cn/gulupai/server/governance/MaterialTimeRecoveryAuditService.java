package cn.gulupai.server.governance;

import cn.gulupai.server.common.api.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * K03 只读核查：扫描曾按回执恢复的历史版本，并复用 K01 验证器逐条判断证据。
 *
 * <p>本服务不写数据库、不新增迁移、不清理任何行。它只输出三类结果：
 * VALID（证据可靠且当前时间等于证据时间）、CORRECTION_REQUIRED（证据可靠但
 * 当前时间不同）、UNVERIFIABLE（回执缺失/已清理、跨版本、密文不可用或缺时间）。
 * 真正的纠错必须另行按受控流程执行并持久化修改前审计。
 */
@Service
public class MaterialTimeRecoveryAuditService {

    private static final String EVIDENCE_PREFIX = "operation_receipt:";

    private final GovRepository governance;
    private final MaterialSubmissionEvidenceVerifier verifier;

    public MaterialTimeRecoveryAuditService(GovRepository governance,
                                            MaterialSubmissionEvidenceVerifier verifier) {
        this.governance = governance;
        this.verifier = verifier;
    }

    @Transactional(readOnly = true)
    public AuditReport audit() {
        List<AuditItem> items = governance.recoveredMaterialResponses().stream()
                .map(this::inspect)
                .toList();
        int valid = (int) items.stream().filter(item -> item.category() == Category.VALID).count();
        int correction = (int) items.stream()
                .filter(item -> item.category() == Category.CORRECTION_REQUIRED).count();
        int unverifiable = items.size() - valid - correction;
        return new AuditReport(items, valid, correction, unverifiable);
    }

    private AuditItem inspect(GovRepository.MaterialResponseRow row) {
        String receiptId = receiptId(row.timeEvidence());
        if (receiptId == null) {
            return item(row, null, Category.UNVERIFIABLE, "恢复依据缺少回执引用");
        }
        if (!MaterialResponseTime.EXACT.equals(row.createdAtConfidence()) || row.createdAt() == null) {
            return item(row, receiptId, Category.UNVERIFIABLE, "恢复行时间可信度或时间不完整");
        }
        // 数据库/连接异常直接上抛，不伪装成“证据不足”。
        Optional<GovRepository.OperationReceiptEvidence> receipt = governance.operationReceipt(receiptId);
        if (receipt.isEmpty()) {
            return item(row, receiptId, Category.UNVERIFIABLE, "回执不存在或已清理");
        }
        try {
            VerifiedSubmissionEvidence verified = verifier.verify(row, receipt.get());
            if (verified.submittedAt().equals(row.createdAt())) {
                return item(row, receiptId, Category.VALID, "证据与当前恢复时间一致");
            }
            return item(row, receiptId, Category.CORRECTION_REQUIRED,
                    "证据可靠但当前恢复时间与证据时间不同");
        } catch (ApiException ex) {
            return item(row, receiptId, Category.UNVERIFIABLE, ex.getMessage());
        }
    }

    private static String receiptId(String evidence) {
        if (evidence == null || !evidence.startsWith(EVIDENCE_PREFIX)) {
            return null;
        }
        String id = evidence.substring(EVIDENCE_PREFIX.length());
        return id.isBlank() ? null : id;
    }

    private static AuditItem item(GovRepository.MaterialResponseRow row, String receiptId,
                                  Category category, String reason) {
        return new AuditItem(row.materialId(), row.version(), row.authorId(), row.createdAt(),
                row.createdAtConfidence(), row.timeSource(), row.timeEvidence(), receiptId,
                category, reason);
    }

    public enum Category {
        VALID, CORRECTION_REQUIRED, UNVERIFIABLE
    }

    public record AuditItem(String materialId, int version, String authorId, Instant createdAt,
                            String createdAtConfidence, String timeSource, String timeEvidence,
                            String receiptId, Category category, String reason) {
        public String describe() {
            return "材料时间核查：" + category + " materialId=" + materialId + " version=" + version
                    + " authorId=" + authorId + " createdAt=" + createdAt
                    + " confidence=" + createdAtConfidence + " evidence=" + timeEvidence
                    + " receiptId=" + receiptId + "；" + reason;
        }
    }

    public record AuditReport(List<AuditItem> items, int valid, int correctionRequired,
                              int unverifiable) {
        public boolean candidatesAbsent() {
            return items.isEmpty();
        }

        public String describe() {
            return "材料时间恢复核查：候选=" + items.size() + " VALID=" + valid
                    + " CORRECTION_REQUIRED=" + correctionRequired + " UNVERIFIABLE=" + unverifiable;
        }
    }
}
