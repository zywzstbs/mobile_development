package cn.gulupai.server.delivery;

import cn.gulupai.server.common.api.ApiCode;
import cn.gulupai.server.common.api.ApiException;

import java.util.Set;

/**
 * 独立验收人不能是贡献者。只有没有合格独立人选、且组长自己参与时，才核定为自检。
 */
public final class ReviewerQualification {

    private ReviewerQualification() {
    }

    public static String resolve(String reviewerId, String leaderId, Set<String> contributors,
                                 Set<String> members, String requestedKind) {
        if (!members.contains(reviewerId)) {
            throw ApiException.validation("验收人不是当前项目成员");
        }
        boolean contributor = contributors.contains(reviewerId);
        boolean independentAvailable = members.stream().anyMatch(member -> !contributors.contains(member));
        String kind;
        if (!contributor) {
            kind = "INDEPENDENT";
        } else if (reviewerId.equals(leaderId) && contributors.contains(leaderId) && !independentAvailable) {
            kind = "SELF_CHECK";
        } else {
            throw new ApiException(ApiCode.SELF_REVIEW_FORBIDDEN, "验收人不能是本任务的贡献者");
        }
        if (requestedKind != null && !requestedKind.equals(kind)) {
            if (contributor && "INDEPENDENT".equals(requestedKind)) {
                throw new ApiException(ApiCode.SELF_REVIEW_FORBIDDEN, "贡献者不能担任独立验收");
            }
            throw ApiException.validation("验收安排与当前资格不一致");
        }
        return kind;
    }
}
