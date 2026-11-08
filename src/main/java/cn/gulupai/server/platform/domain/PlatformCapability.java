package cn.gulupai.server.platform.domain;

import java.util.List;

/**
 * 平台能力与课程 adminGrants 分开。名称原样返回给客户端。
 */
public enum PlatformCapability {
    PLATFORM_ACCOUNT_READ,
    PLATFORM_ACCOUNT_MANAGE,
    PLATFORM_TEACHER_REVIEW,
    PLATFORM_COURSE_GOVERNANCE,
    PLATFORM_ROUTING_MANAGE,
    PLATFORM_AUDIT_READ,
    PLATFORM_SUPER_ADMIN_MANAGE;

    public static final List<String> ALL = List.of(
            PLATFORM_ACCOUNT_READ.name(),
            PLATFORM_ACCOUNT_MANAGE.name(),
            PLATFORM_TEACHER_REVIEW.name(),
            PLATFORM_COURSE_GOVERNANCE.name(),
            PLATFORM_ROUTING_MANAGE.name(),
            PLATFORM_AUDIT_READ.name(),
            PLATFORM_SUPER_ADMIN_MANAGE.name());

    public String wireName() {
        return name();
    }
}
