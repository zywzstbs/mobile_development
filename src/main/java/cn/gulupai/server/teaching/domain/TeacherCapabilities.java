package cn.gulupai.server.teaching.domain;

import java.util.List;
import java.util.Set;

public final class TeacherCapabilities {

    public static final String COURSE_READ = "COURSE_READ";
    public static final String ANNOUNCEMENT_WRITE = "ANNOUNCEMENT_WRITE";
    public static final String PERSONNEL_READ = "PERSONNEL_READ";
    public static final String PERSONNEL_HANDLE = "PERSONNEL_HANDLE";
    public static final String TEAM_SUMMARY_READ = "TEAM_SUMMARY_READ";
    public static final String MATERIAL_REQUEST_HANDLE = "MATERIAL_REQUEST_HANDLE";
    public static final String COURSE_JOIN_CODE_MANAGE = "COURSE_JOIN_CODE_MANAGE";
    public static final String COURSE_MEMBERSHIP_REVIEW = "COURSE_MEMBERSHIP_REVIEW";

    /** 班级助理可被授予的能力白名单；不含改分、验收或教师权属。 */
    public static final Set<String> ASSISTANT_GRANTABLE = Set.of(
            ANNOUNCEMENT_WRITE,
            PERSONNEL_READ,
            PERSONNEL_HANDLE,
            TEAM_SUMMARY_READ,
            MATERIAL_REQUEST_HANDLE,
            COURSE_JOIN_CODE_MANAGE,
            COURSE_MEMBERSHIP_REVIEW);

    /** 当前负责教师的能力投影；不是全局角色，不能据此推导项目聊天读取。 */
    public static final List<String> TEACHER = List.of(
            COURSE_READ,
            ANNOUNCEMENT_WRITE,
            PERSONNEL_READ,
            PERSONNEL_HANDLE,
            TEAM_SUMMARY_READ,
            MATERIAL_REQUEST_HANDLE,
            COURSE_JOIN_CODE_MANAGE,
            COURSE_MEMBERSHIP_REVIEW);

    private TeacherCapabilities() {
    }
}
