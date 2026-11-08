package cn.gulupai.server.identity.application;

import cn.gulupai.server.identity.api.UserView;
import cn.gulupai.server.identity.domain.Role;
import cn.gulupai.server.identity.persistence.UserRepository;
import cn.gulupai.server.identity.persistence.UserRow;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class UserProjectionService {

    private final UserRepository userRepository;
    private final CapabilityService capabilityService;

    public UserProjectionService(UserRepository userRepository, CapabilityService capabilityService) {
        this.userRepository = userRepository;
        this.capabilityService = capabilityService;
    }

    public UserView toView(UserRow user) {
        List<Role> roles = userRepository.findRoles(user.id());
        return new UserView(
                user.id(),
                user.displayName(),
                user.schoolId(),
                accountStatus(user),
                user.identityStatus().name(),
                roles.stream().map(Enum::name).toList(),
                user.skills(),
                user.revision(),
                capabilityService.forUser(user.id()));
    }

    /**
     * 账号状态与身份核验分开。历史停用若仍写成 ACTIVE，投影为 DISABLED，不把停用账号显示成可用。
     */
    private static String accountStatus(UserRow user) {
        if (user.disabled() && "ACTIVE".equals(user.accountStatus())) {
            return "DISABLED";
        }
        return user.accountStatus();
    }
}
