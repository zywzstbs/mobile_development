package cn.gulupai.server.delivery;

import cn.gulupai.server.common.api.ApiCode;
import cn.gulupai.server.common.api.ApiException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 验收必须逐条覆盖本次提交冻结的标准。通过与否由核对结果计算，不能只给一个总评。
 */
public final class AcceptanceMatch {

    private AcceptanceMatch() {
    }

    public record Required(String unitId, String criterionId) {
    }

    public record Check(String unitId, String criterionId, boolean met) {
    }

    public record Result(String decision, Set<String> passedUnitIds) {
    }

    public static Result match(List<Required> required, List<Check> checks, List<String> returnReasons) {
        if (required.isEmpty()) {
            throw ApiException.validation("本次提交没有可核对的标准");
        }
        if (checks.size() != required.size()) {
            throw new ApiException(ApiCode.STANDARD_NOT_CHECKED, "必须逐条核对本次提交的全部标准");
        }
        Set<String> expected = new LinkedHashSet<>();
        Map<String, List<Boolean>> byUnit = new LinkedHashMap<>();
        for (Required item : required) {
            expected.add(key(item.unitId(), item.criterionId()));
            byUnit.computeIfAbsent(item.unitId(), ignored -> new ArrayList<>());
        }
        Set<String> seen = new LinkedHashSet<>();
        List<Boolean> unmet = new ArrayList<>();
        for (Check check : checks) {
            String id = key(check.unitId(), check.criterionId());
            if (!expected.contains(id) || !seen.add(id)) {
                throw new ApiException(ApiCode.STANDARD_NOT_CHECKED, "核对项必须恰好覆盖本次提交的标准");
            }
            byUnit.get(check.unitId()).add(check.met());
            if (!check.met()) {
                unmet.add(false);
            }
        }
        if (seen.size() != expected.size()) {
            throw new ApiException(ApiCode.STANDARD_NOT_CHECKED, "还有标准未核对");
        }
        if (returnReasons.size() != unmet.size()) {
            throw ApiException.validation("退回理由必须按未通过标准逐条给出");
        }
        for (String reason : returnReasons) {
            if (reason == null || reason.isBlank() || reason.strip().length() > 500) {
                throw ApiException.validation("退回理由需为1～500字");
            }
        }
        Set<String> passed = new LinkedHashSet<>();
        int passedCount = 0;
        for (Map.Entry<String, List<Boolean>> entry : byUnit.entrySet()) {
            boolean all = entry.getValue().stream().allMatch(Boolean::booleanValue);
            if (all) {
                passedCount++;
                if (entry.getKey() != null) {
                    passed.add(entry.getKey());
                }
            }
        }
        String decision = passedCount == byUnit.size() ? "PASSED" : passedCount == 0 ? "RETURNED" : "PARTIAL";
        return new Result(decision, Set.copyOf(passed));
    }

    private static String key(String unitId, String criterionId) {
        return (unitId == null ? "" : unitId) + "|" + criterionId;
    }
}
