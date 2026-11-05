package cn.gulupai.server.identity.api;

import cn.gulupai.server.common.validation.Utf16Length;
import jakarta.validation.constraints.NotNull;

/**
 * 匿名注册正文。账号和姓名在服务层 trim 后再校验；密码保持原文。
 * toString 不包含密码和核验说明。
 */
public record RegistrationRequest(
        @NotNull String loginId,
        @NotNull @Utf16Length(min = 8, max = 64) String password,
        @NotNull String displayName,
        @NotNull String schoolId,
        @NotNull String requestedRole,
        @Utf16Length(max = 500) String teacherApplication
) {
    @Override
    public String toString() {
        return "RegistrationRequest[loginId=" + loginId + ", requestedRole=" + requestedRole
                + ", schoolId=" + schoolId + "]";
    }
}
