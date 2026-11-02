package cn.gulupai.server.identity.persistence;

import java.time.Instant;

/**
 * 访问令牌鉴权联查结果；包含会话和用户状态，供过滤器逐请求判定。
 */
public record AuthTokenRow(
        String userId,
        String sessionId,
        String loginId,
        String schoolId,
        String displayName,
        String identityStatus,
        boolean userDisabled,
        Instant sessionExpiresAt,
        Instant sessionRevokedAt,
        Instant accessExpiresAt,
        Instant accessRevokedAt
) {
}
