package cn.gulupai.server.governance;

import cn.gulupai.server.common.api.ApiCode;
import cn.gulupai.server.common.api.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * U02 受控恢复：只把能完整证明到一次真实提交结果的历史版本恢复为 EXACT。
 *
 * <p>可靠证据不是“同作者曾成功 POST 过同一路径”，而是仍存在的加密幂等回执中
 * 那份原始 {@link GovViews.Material}：根节点材料 ID、顶层 {@code responseVersion}
 * 和该版本在 {@code responses} 中的唯一 EXACT 时间必须同时匹配目标
 * {@code material_response(material_id, version, author_id)}。同一材料的 v1/v2
 * 共用提交路径，因此顶层版本证明不可省略。
 *
 * <p>恢复时间只取通过验证的原始提交结果中的 {@code createdAt}。
 * {@code operation_receipt.created_at} 是数据库生成的回执时间，仅用于诊断，
 * 不是该次提交的服务器时刻，不能作为恢复来源。
 *
 * <p>没有回执、回执已删除、密文/密钥不可用、证据不匹配或该版本已是其他 EXACT
 * 时都拒绝并保留原状态；UNKNOWN 异常组合也不自动清洗。dry-run 与正式执行
 * 使用同一套证据校验，只有条件更新成功才返回 RECOVERED。
 */
@Service
public class MaterialResponseTimeRecoveryService {

    private final GovRepository governance;
    private final MaterialSubmissionEvidenceVerifier evidenceVerifier;
    private final Clock clock;

    public MaterialResponseTimeRecoveryService(GovRepository governance,
                                               MaterialSubmissionEvidenceVerifier evidenceVerifier,
                                               Clock clock) {
        this.governance = governance;
        this.evidenceVerifier = evidenceVerifier;
        this.clock = clock;
    }

    @Transactional(rollbackFor = Exception.class)
    public RecoveryResult recover(String materialId, int version, String receiptId,
                                  boolean dryRun, String actorId) {
        if (materialId == null || materialId.isBlank() || version < 1
                || receiptId == null || receiptId.isBlank()) {
            throw ApiException.validation("恢复提交时间需要 materialId、responseVersion 和 receiptId");
        }
        GovRepository.MaterialResponseRow response = governance.materialResponse(materialId, version)
                .orElseThrow(ApiException::notFound);
        GovRepository.OperationReceiptEvidence receipt = governance.operationReceipt(receiptId)
                .orElseThrow(() -> new ApiException(ApiCode.NOT_FOUND, "提交回执不存在"));

        // 所有分支（dry-run、重复恢复、正式更新）共用一次完整验证。
        VerifiedSubmissionEvidence verified = evidenceVerifier.verify(response, receipt);
        String evidence = "operation_receipt:" + verified.receiptId();

        if (MaterialResponseTime.EXACT.equals(response.createdAtConfidence())) {
            if (isSameRecovery(response, verified, evidence)) {
                return new RecoveryResult(materialId, version, "ALREADY_RECOVERED",
                        response.createdAt(), evidence, "该版本已按同一回执恢复，无变更");
            }
            throw new ApiException(ApiCode.SOURCE_CHANGED,
                    "该版本已有其他时间依据，不能覆盖");
        }
        if (!MaterialResponseTime.UNKNOWN.equals(response.createdAtConfidence())) {
            throw new ApiException(ApiCode.SOURCE_CHANGED, "该版本时间可信度异常，不能恢复");
        }
        requireCleanUnknown(response);

        if (dryRun) {
            return new RecoveryResult(materialId, version, "DRY_RUN", verified.submittedAt(), evidence,
                    "仅演练，未修改数据库");
        }
        String actor = actorId == null || actorId.isBlank() ? "cli" : actorId;
        // 操作时刻来自 Clock；提交时间只来自已验证证据。
        Instant now = clock.instant();
        int updated = governance.recoverMaterialResponseTime(materialId, version, verified.submittedAt(),
                evidence, actor, now);
        if (updated != 1) {
            // 并发恢复可能已由另一请求完成；只有同证据同时间才可返回幂等成功。
            GovRepository.MaterialResponseRow current = governance.materialResponse(materialId, version)
                    .orElse(null);
            if (current != null && isSameRecovery(current, verified, evidence)) {
                return new RecoveryResult(materialId, version, "ALREADY_RECOVERED",
                        current.createdAt(), evidence, "该版本已按同一回执恢复，无变更");
            }
            throw new ApiException(ApiCode.SOURCE_CHANGED, "材料版本状态已变化，未恢复时间");
        }
        return new RecoveryResult(materialId, version, "RECOVERED", verified.submittedAt(), evidence,
                "已按真实提交结果恢复时间，旧诊断值保留");
    }

    /**
     * UNKNOWN 只允许来自 legacy 占位；时间非空、来源异常或残留恢复审计字段
     * 都是异常组合，拒绝自动清洗。
     */
    private void requireCleanUnknown(GovRepository.MaterialResponseRow response) {
        if (response.createdAt() != null
                || !MaterialResponseTime.LEGACY_UNKNOWN.equals(response.timeSource())
                || response.timeEvidence() != null
                || response.timeRecoveredBy() != null
                || response.timeRecoveredAt() != null) {
            throw new ApiException(ApiCode.SOURCE_CHANGED,
                    "该版本当前时间状态异常，不能自动恢复");
        }
    }

    private boolean isSameRecovery(GovRepository.MaterialResponseRow response,
                                   VerifiedSubmissionEvidence verified, String evidence) {
        return MaterialResponseTime.EXACT.equals(response.createdAtConfidence())
                && MaterialResponseTime.RECOVERED_EVIDENCE.equals(response.timeSource())
                && evidence.equals(response.timeEvidence())
                && verified.submittedAt().equals(response.createdAt());
    }

    public record RecoveryResult(String materialId, int version, String status, Instant createdAt,
                                 String evidence, String message) {
        public String describe() {
            return "材料提交时间恢复：" + status + " materialId=" + materialId + " version=" + version
                    + " createdAt=" + createdAt + " evidence=" + evidence + "；" + message;
        }
    }
}
