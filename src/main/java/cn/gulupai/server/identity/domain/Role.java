package cn.gulupai.server.identity.domain;

public enum Role {
    STUDENT,
    TEACHER,
    CLASS_ADMIN,
    /**
     * 冗余标记。平台管理是否放行只看有效 platform_admin_grant，
     * 不能因为角色行存在就进入课程、聊天、成绩或案情。
     */
    SUPER_ADMIN
}
