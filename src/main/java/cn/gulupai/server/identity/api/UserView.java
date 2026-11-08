package cn.gulupai.server.identity.api;

import java.util.List;

public record UserView(
        String id,
        String displayName,
        String schoolId,
        String accountStatus,
        String identityStatus,
        List<String> roles,
        List<String> skills,
        int revision,
        List<String> capabilities
) {
    public UserView {
        roles = List.copyOf(roles);
        skills = List.copyOf(skills);
        capabilities = List.copyOf(capabilities);
    }
}
