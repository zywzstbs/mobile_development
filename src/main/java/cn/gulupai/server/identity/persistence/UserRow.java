package cn.gulupai.server.identity.persistence;

import cn.gulupai.server.identity.domain.IdentityStatus;

import java.util.List;

public record UserRow(
        String id,
        String schoolId,
        String loginId,
        String studentNo,
        String displayName,
        String passwordHash,
        IdentityStatus identityStatus,
        boolean disabled,
        String accountStatus,
        List<String> skills,
        int revision
) {
}
