package cn.gulupai.server.platform.domain;

/**
 * 再认证与管理写入共用的动作。没有版本的新增授权使用前置条件，不填写假的 revision。
 */
public enum PlatformAction {
    ACCOUNT_STATUS_CHANGE(PlatformCapability.PLATFORM_ACCOUNT_MANAGE, "USER", true, null),
    PASSWORD_RESET_REQUEST(PlatformCapability.PLATFORM_ACCOUNT_MANAGE, "USER", false, "TARGET_ACCOUNT"),
    TEACHER_APPLICATION_DECIDE(PlatformCapability.PLATFORM_TEACHER_REVIEW, "TEACHER_APPLICATION", true, null),
    COURSE_CLAIM_DECIDE(PlatformCapability.PLATFORM_COURSE_GOVERNANCE, "TEACHER_CLAIM", true, null),
    ADMIN_GRANT_REVOKE(PlatformCapability.PLATFORM_COURSE_GOVERNANCE, "CLASS_GRANT", true, null),
    SUPER_ADMIN_GRANT(PlatformCapability.PLATFORM_SUPER_ADMIN_MANAGE, "USER", false, "NO_ACTIVE_GRANT"),
    SUPER_ADMIN_REVOKE(PlatformCapability.PLATFORM_SUPER_ADMIN_MANAGE, "USER", true, null);

    private final PlatformCapability capability;
    private final String targetType;
    private final boolean revisionRequired;
    private final String precondition;

    PlatformAction(PlatformCapability capability, String targetType, boolean revisionRequired, String precondition) {
        this.capability = capability;
        this.targetType = targetType;
        this.revisionRequired = revisionRequired;
        this.precondition = precondition;
    }

    public PlatformCapability capability() {
        return capability;
    }

    public String targetType() {
        return targetType;
    }

    public boolean revisionRequired() {
        return revisionRequired;
    }

    public String precondition() {
        return precondition;
    }

    public static PlatformAction parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return PlatformAction.valueOf(raw.strip());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
