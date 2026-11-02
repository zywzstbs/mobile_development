package cn.gulupai.server.common.idempotency;

import cn.gulupai.server.common.security.AuthenticatedUser;

import java.util.List;

public record IdempotencyRequest(
        AuthenticatedUser principal,
        String method,
        String path,
        String key,
        Object body,
        List<ResourceRef> resourceRefs
) {

    public IdempotencyRequest {
        resourceRefs = resourceRefs == null ? List.of() : List.copyOf(resourceRefs);
    }

    public record ResourceRef(String type, String id) {
    }
}
