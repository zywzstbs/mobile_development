package cn.gulupai.server.delivery;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 交接约定上的 reviewerAcceptanceInherited。
 * 主契约 R_DELIVERY_01 把该字段定义为交接投影上的一个布尔值，不是逐分项字段。
 * 既有通过验收的验收人和种类必须等于目标约定自己的 reviewerPolicy。
 * 任务当前验收安排不参与计算。一条匹配的旧通过记录就够，没有匹配记录则为 false。
 */
public final class ReviewerInheritance {

    private ReviewerInheritance() {
    }

    public record Policy(String userId, String kind) {
    }

    public record Pass(String taskId, String unitId, Integer agreementVersion, String reviewerId, String kind,
                       boolean passed, boolean withdrawn) {
    }

    public static boolean inherited(Policy policy, int agreementVersion, String taskId, Collection<String> unitIds,
                                    List<Pass> passes) {
        if (policy == null || policy.userId() == null || policy.userId().isBlank()
                || policy.kind() == null || policy.kind().isBlank() || taskId == null || unitIds == null || unitIds.isEmpty()
                || passes == null) {
            return false;
        }
        Set<String> units = new HashSet<>(unitIds);
        for (Pass pass : passes) {
            if (pass == null || !pass.passed() || pass.withdrawn() || pass.unitId() == null || !units.contains(pass.unitId())) {
                continue;
            }
            if (!taskId.equals(pass.taskId()) || pass.agreementVersion() == null
                    || pass.agreementVersion() >= agreementVersion) {
                continue;
            }
            if (policy.userId().equals(pass.reviewerId()) && policy.kind().equals(pass.kind())) {
                return true;
            }
        }
        return false;
    }
}
