package cn.gulupai.server.platform.application;

import cn.gulupai.server.platform.persistence.PlatformRepository;

/**
 * 账号可用性判定的唯一来源。A03 的 {@code restorationEligible} 投影与 A04 的事务内
 * 判定必须共用同一方法，避免读侧显示“可恢复”而写侧拒绝，或读侧隐藏了写侧允许的账号。
 *
 * <p>恢复资格只表达目标事实：账号当前是管理停用（{@code account_status=DISABLED}、
 * {@code disabled=true}、来源 {@code ADMIN_DISABLED}）。待审、驳回、导入停用
 * （{@code IMPORT_DISABLED}）和历史不明来源（{@code LEGACY_DISABLED_*}）一律不具资格。
 * 业务若最终确认“恢复还要求有效凭据或特定身份状态”，必须同时改这里，读投影和写入会一起生效。</p>
 */
public final class AccountEligibility {

    /** 管理停用的资格来源，由 A04 停用成功后写入。 */
    public static final String ADMIN_DISABLED = "ADMIN_DISABLED";
    /** 正常可用账号的资格来源；通过注册审核或人工恢复写入。 */
    public static final String VERIFIED_ACTIVE = "VERIFIED_ACTIVE";

    private AccountEligibility() {
    }

    public static boolean restorationEligible(String accountStatus, boolean disabled, String eligibilitySource) {
        return "DISABLED".equals(accountStatus) && disabled && ADMIN_DISABLED.equals(eligibilitySource);
    }

    public static boolean restorationEligible(PlatformRepository.ManagedUser user) {
        return restorationEligible(user.accountStatus(), user.disabled(), user.eligibility());
    }

    /**
     * 停用前置条件：只允许停用当前可登录且资格明确的账号。该判定不出现在读投影里，
     * 仅由 A04 在事务内使用；与恢复资格不同，它要求账号处于正常可用状态。
     */
    public static boolean disableEligible(PlatformRepository.ManagedUser user) {
        return "ACTIVE".equals(user.accountStatus()) && !user.disabled()
                && VERIFIED_ACTIVE.equals(user.eligibility());
    }
}
