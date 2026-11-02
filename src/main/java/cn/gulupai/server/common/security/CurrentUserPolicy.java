package cn.gulupai.server.common.security;

import cn.gulupai.server.common.api.ApiCode;
import cn.gulupai.server.common.api.ApiException;
import cn.gulupai.server.identity.domain.Role;
import cn.gulupai.server.identity.persistence.UserRepository;
import cn.gulupai.server.identity.persistence.UserRow;
import org.springframework.stereotype.Component;

/**
 * 当前用户授权基座。业务服务从这里取得本人/角色/账户状态，不信任请求正文。
 */
@Component
public class CurrentUserPolicy {

    private final UserRepository userRepository;

    public CurrentUserPolicy(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public AuthenticatedUser requireAuthenticated(AuthenticatedUser principal) {
        if (principal == null) {
            throw new ApiException(ApiCode.SESSION_EXPIRED);
        }
        return principal;
    }

    public AuthenticatedUser requireActiveBusinessUser(AuthenticatedUser principal) {
        requireAuthenticated(principal);
        if (principal.revokedSession()) {
            throw new ApiException(ApiCode.SESSION_EXPIRED);
        }
        UserRow user = userRepository.findById(principal.userId())
                .orElseThrow(() -> new ApiException(ApiCode.SESSION_EXPIRED));
        if (user.disabled()) {
            throw new ApiException(ApiCode.SESSION_EXPIRED);
        }
        return principal;
    }

    public void requireSelf(AuthenticatedUser principal, String userId) {
        requireAuthenticated(principal);
        if (!principal.userId().equals(userId)) {
            throw new ApiException(ApiCode.FORBIDDEN);
        }
    }

    public void requireRole(AuthenticatedUser principal, Role role) {
        requireActiveBusinessUser(principal);
        if (!principal.hasRole(role)) {
            throw new ApiException(ApiCode.FORBIDDEN);
        }
    }

    public void requireStudent(AuthenticatedUser principal) {
        requireRole(principal, Role.STUDENT);
    }
}
