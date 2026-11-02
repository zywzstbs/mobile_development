package cn.gulupai.server.common.security;

import cn.gulupai.server.identity.domain.Role;

import java.util.List;

/**
 * 当前请求的已认证身份，只由访问令牌摘要查询得到；不从请求正文读取。
 *
 * @param revokedSession true 仅用于“已注销会话的注销重放”这一受限场景，
 *                       不能用于其他业务接口。
 */
public record AuthenticatedUser(
        String userId,
        String sessionId,
        String loginId,
        String schoolId,
        String displayName,
        String identityStatus,
        List<Role> roles,
        boolean revokedSession
) {

    public AuthenticatedUser {
        roles = List.copyOf(roles);
    }

    public boolean hasRole(Role role) {
        return roles.contains(role);
    }
}
