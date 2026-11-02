package cn.gulupai.server.governance;

import java.time.Instant;

/**
 * 已通过完整回执校验的历史提交证据。
 *
 * <p>时间只来自该次成功提交结果中与顶层 {@code responseVersion} 对应的条目；
 * 调用方不得再传入或改写已证明的提交时间。
 */
public record VerifiedSubmissionEvidence(String materialId, int responseVersion, String authorId,
                                         Instant submittedAt, String receiptId) {
}
