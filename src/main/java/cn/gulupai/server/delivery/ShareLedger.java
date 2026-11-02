package cn.gulupai.server.delivery;

import cn.gulupai.server.common.api.ApiCode;
import cn.gulupai.server.common.api.ApiException;
import cn.gulupai.server.common.validation.DecimalStrings;
import cn.gulupai.server.task.api.TaskRequests;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 份额守恒：每个交付项的份额合计必须等于该项点数，人员不能重复。
 */
public final class ShareLedger {

    public static final BigDecimal MAX = new BigDecimal("1000000");

    private ShareLedger() {
    }

    public record Share(String userId, BigDecimal points, String responsibility) {
    }

    public record Unit(String clientKey, String title, BigDecimal points, List<Share> shares, List<String> criteria) {
    }

    public record Plan(String ownerId, Instant dueAt, List<Unit> units, String reviewerUserId, String requestedKind) {
    }

    public static Plan normalize(TaskRequests.AssessedPlanInput input) {
        if (input == null || input.ownerId() == null || input.ownerId().isBlank()) {
            throw ApiException.validation("正式任务必须指定统筹负责人");
        }
        if (input.dueAt() == null) {
            throw ApiException.validation("正式任务必须提供截止时间");
        }
        if (input.units() == null || input.units().isEmpty()) {
            throw ApiException.validation("正式任务必须提供交付项");
        }
        if (input.reviewerPolicy() == null || input.reviewerPolicy().userId() == null
                || input.reviewerPolicy().userId().isBlank()) {
            throw ApiException.validation("正式任务必须指定验收安排");
        }
        String requestedKind = blankToNull(input.reviewerPolicy().kind());
        if (requestedKind != null && !requestedKind.equals("INDEPENDENT") && !requestedKind.equals("SELF_CHECK")) {
            throw ApiException.validation("验收安排类型无效");
        }
        Set<String> keys = new LinkedHashSet<>();
        List<Unit> units = new ArrayList<>();
        for (TaskRequests.UnitInput unit : input.units()) {
            if (unit.clientKey() == null || !unit.clientKey().matches("[A-Za-z0-9_-]{1,120}")) {
                throw ApiException.validation("交付项clientKey无效");
            }
            if (!keys.add(unit.clientKey())) {
                throw ApiException.validation("交付项clientKey不能重复");
            }
            if (unit.title() == null || unit.title().isBlank() || unit.title().strip().length() > 120) {
                throw ApiException.validation("交付项标题无效");
            }
            BigDecimal points = points(unit.points());
            if (unit.criteria() == null || unit.criteria().isEmpty()) {
                throw ApiException.validation("每个交付项至少一条标准");
            }
            List<String> criteria = new ArrayList<>();
            for (TaskRequests.CriterionInput criterion : unit.criteria()) {
                if (criterion.text() == null || criterion.text().isBlank() || criterion.text().strip().length() > 500) {
                    throw ApiException.validation("验收标准无效");
                }
                criteria.add(criterion.text().strip());
            }
            units.add(new Unit(unit.clientKey(), unit.title().strip(), points, shares(unit), criteria));
        }
        return new Plan(input.ownerId().strip(), input.dueAt(), List.copyOf(units),
                input.reviewerPolicy().userId().strip(), requestedKind);
    }

    public static List<Share> normalizeShares(BigDecimal unitPoints, List<TaskRequests.ShareInput> inputs) {
        return shares(unitPoints, inputs);
    }

    public static BigDecimal points(String raw) {
        try {
            BigDecimal value = DecimalStrings.parse(raw);
            if (value.signum() < 0 || value.compareTo(MAX) > 0) {
                throw new ApiException(ApiCode.POINTS_INVALID, "点数必须在0到1000000之间");
            }
            return value.setScale(DecimalStrings.SCALE);
        } catch (IllegalArgumentException ex) {
            throw new ApiException(ApiCode.POINTS_INVALID, "点数字段无效");
        }
    }

    public static String text(BigDecimal value) {
        return DecimalStrings.canonical(value);
    }

    private static List<Share> shares(TaskRequests.UnitInput unit) {
        return shares(points(unit.points()), unit.shares());
    }

    private static List<Share> shares(BigDecimal unitPoints, List<TaskRequests.ShareInput> inputs) {
        if (inputs == null || inputs.isEmpty()) {
            throw ApiException.validation("每个交付项至少一份份额");
        }
        Set<String> users = new LinkedHashSet<>();
        BigDecimal sum = BigDecimal.ZERO.setScale(DecimalStrings.SCALE);
        List<Share> shares = new ArrayList<>();
        for (TaskRequests.ShareInput share : inputs) {
            if (share.userId() == null || share.userId().isBlank()) {
                throw ApiException.validation("份额必须指定成员");
            }
            if (!users.add(share.userId())) {
                throw ApiException.validation("同一交付项的份额人员不能重复");
            }
            if (share.responsibility() == null || share.responsibility().isBlank()
                    || share.responsibility().strip().length() > 500) {
                throw ApiException.validation("份额责任说明无效");
            }
            BigDecimal points = points(share.points());
            sum = sum.add(points);
            shares.add(new Share(share.userId(), points, share.responsibility().strip()));
        }
        if (sum.compareTo(unitPoints) != 0) {
            throw new ApiException(ApiCode.POINTS_INVALID, "份额合计必须等于交付项点数");
        }
        return List.copyOf(shares);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
