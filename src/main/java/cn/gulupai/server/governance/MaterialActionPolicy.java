package cn.gulupai.server.governance;

import java.util.Set;

/**
 * 材料层动作规则：can* 读取投影与三个写入口使用同一份纯规则。
 *
 * <p>本类不发 SQL、不改状态，也不负责身份口径；调用方先用
 * {@link PersonnelCaseAccessPolicy} 得到收件人/当前处理人，再把锁后的
 * case、material 和当前 response 的真实状态传进来。读取瞬间的 can*
 * 不是授权令牌，写入口必须在锁后重新计算。
 */
public final class MaterialActionPolicy {

    public enum Blocker {
        NONE,
        NOT_RECIPIENT,
        NOT_HANDLER,
        CASE_CLOSED,
        MATERIAL_ACCEPTED,
        MATERIAL_STATUS_DISALLOWS_RESPONSE,
        MATERIAL_STATUS_DISALLOWS_WITHDRAWAL,
        MATERIAL_STATUS_DISALLOWS_REVIEW,
        NO_CURRENT_RESPONSE,
        CURRENT_RESPONSE_WITHDRAWN,
        VERSION_LIMIT
    }

    public record Decision(boolean canRespond, boolean canWithdraw, boolean canReview,
                           Blocker respondBlocker, Blocker withdrawBlocker, Blocker reviewBlocker) {
    }

    private static final Set<String> RESPOND_STATUSES =
            Set.of("OPEN", "RESPONDED", "RETURNED", "WITHDRAWN");
    private static final Set<String> WITHDRAW_STATUSES = Set.of("RESPONDED", "RETURNED");

    private MaterialActionPolicy() {
    }

    /**
     * @param caseStatus          锁后 case 状态
     * @param materialStatus      锁后材料状态（内部值 RESPONDED 表示待审核）
     * @param responseVersion     锁后 material.responseVersion
     * @param currentResponseExists responseVersion 对应的真实行存在
     * @param currentResponseWithdrawn 该真实行已撤回
     * @param recipient           当前账号是有效指定学生
     * @param handler             当前账号是本 case 当前有效处理人（是否结案由本规则判断）
     */
    public static Decision evaluate(String caseStatus, String materialStatus, int responseVersion,
                                    boolean currentResponseExists, boolean currentResponseWithdrawn,
                                    boolean recipient, boolean handler) {
        boolean caseWritable = !"CLOSED".equals(caseStatus);
        boolean hasCurrent = responseVersion >= 1 && currentResponseExists;

        Blocker respondBlocker = null;
        if (!recipient) {
            respondBlocker = Blocker.NOT_RECIPIENT;
        } else if (!caseWritable) {
            respondBlocker = Blocker.CASE_CLOSED;
        } else if ("ACCEPTED".equals(materialStatus)) {
            respondBlocker = Blocker.MATERIAL_ACCEPTED;
        } else if (!RESPOND_STATUSES.contains(materialStatus)) {
            respondBlocker = Blocker.MATERIAL_STATUS_DISALLOWS_RESPONSE;
        }

        Blocker withdrawBlocker = null;
        if (!recipient) {
            withdrawBlocker = Blocker.NOT_RECIPIENT;
        } else if (!caseWritable) {
            withdrawBlocker = Blocker.CASE_CLOSED;
        } else if (!hasCurrent) {
            withdrawBlocker = Blocker.NO_CURRENT_RESPONSE;
        } else if (currentResponseWithdrawn) {
            withdrawBlocker = Blocker.CURRENT_RESPONSE_WITHDRAWN;
        } else if (!WITHDRAW_STATUSES.contains(materialStatus)) {
            withdrawBlocker = Blocker.MATERIAL_STATUS_DISALLOWS_WITHDRAWAL;
        }

        Blocker reviewBlocker = null;
        if (!handler) {
            reviewBlocker = Blocker.NOT_HANDLER;
        } else if (!caseWritable) {
            reviewBlocker = Blocker.CASE_CLOSED;
        } else if (!"RESPONDED".equals(materialStatus)) {
            reviewBlocker = Blocker.MATERIAL_STATUS_DISALLOWS_REVIEW;
        } else if (!hasCurrent) {
            reviewBlocker = Blocker.NO_CURRENT_RESPONSE;
        } else if (currentResponseWithdrawn) {
            reviewBlocker = Blocker.CURRENT_RESPONSE_WITHDRAWN;
        }

        return new Decision(respondBlocker == null, withdrawBlocker == null, reviewBlocker == null,
                respondBlocker == null ? Blocker.NONE : respondBlocker,
                withdrawBlocker == null ? Blocker.NONE : withdrawBlocker,
                reviewBlocker == null ? Blocker.NONE : reviewBlocker);
    }

    public static boolean isPermissionBlocker(Blocker blocker) {
        return blocker == Blocker.NOT_RECIPIENT || blocker == Blocker.NOT_HANDLER;
    }

    public static String message(Blocker blocker) {
        return switch (blocker) {
            case NOT_RECIPIENT -> "只有本次材料指定的学生可以提交或撤回";
            case NOT_HANDLER -> "只有本事项当前处理人可以审核材料";
            case CASE_CLOSED -> "事项已结案，不能再修改材料";
            case MATERIAL_ACCEPTED -> "材料已验收，不能再提交或撤回";
            case MATERIAL_STATUS_DISALLOWS_RESPONSE -> "当前材料状态不允许提交";
            case MATERIAL_STATUS_DISALLOWS_WITHDRAWAL -> "当前材料状态不允许撤回";
            case MATERIAL_STATUS_DISALLOWS_REVIEW -> "材料当前不在待审核状态";
            case NO_CURRENT_RESPONSE -> "没有可操作的材料版本";
            case CURRENT_RESPONSE_WITHDRAWN -> "该材料版本已撤回，不能再操作";
            case VERSION_LIMIT -> "材料版本号已达上限";
            case NONE -> "";
        };
    }
}
