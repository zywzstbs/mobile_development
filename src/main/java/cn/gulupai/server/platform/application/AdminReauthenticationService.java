package cn.gulupai.server.platform.application;

import cn.gulupai.server.common.api.ApiCode;
import cn.gulupai.server.common.api.ApiException;
import cn.gulupai.server.common.ratelimit.RateLimitService;
import cn.gulupai.server.common.security.AuthenticatedUser;
import cn.gulupai.server.common.security.CurrentUserPolicy;
import cn.gulupai.server.common.security.Hashing;
import cn.gulupai.server.identity.AuthProperties;
import cn.gulupai.server.identity.persistence.UserRepository;
import cn.gulupai.server.platform.api.PlatformRequests;
import cn.gulupai.server.platform.api.PlatformViews;
import cn.gulupai.server.platform.persistence.PlatformRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 再认证只验证当前操作者本人密码，不创建会话，不记录口令。令牌只在响应里出现一次，库存安全指纹。
 */
@Service
public class AdminReauthenticationService {

    static final Duration TTL = Duration.ofMinutes(5);

    private final CurrentUserPolicy policy;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final RateLimitService rateLimitService;
    private final AuthProperties authProperties;
    private final PlatformAuthorizationService authorizationService;
    private final PlatformRepository repository;
    private final AdminPayloadDigest digests;
    private final Clock clock;

    public AdminReauthenticationService(CurrentUserPolicy policy, UserRepository userRepository,
                                        PasswordEncoder passwordEncoder, RateLimitService rateLimitService,
                                        AuthProperties authProperties,
                                        PlatformAuthorizationService authorizationService,
                                        PlatformRepository repository, AdminPayloadDigest digests, Clock clock) {
        this.policy = policy;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.rateLimitService = rateLimitService;
        this.authProperties = authProperties;
        this.authorizationService = authorizationService;
        this.repository = repository;
        this.digests = digests;
        this.clock = clock;
    }

    public PlatformViews.ReauthenticationView reauthenticate(AuthenticatedUser principal, String ip,
                                                             PlatformRequests.ReauthenticationRequest request) {
        policy.requireActiveBusinessUser(principal);
        if (request.password() == null) {
            throw new ApiException(ApiCode.VALIDATION_FAILED, "请填写密码");
        }
        AdminCommands.CanonicalCommand command = AdminCommands.reauthentication(request);
        String userSubject = Hashing.sha256Hex("reauth-user|" + principal.userId());
        String ipSubject = Hashing.sha256Hex("reauth-ip|" + (ip == null ? "unknown" : ip));
        rateLimitService.checkBlocked(userSubject, RateLimitService.ADMIN_REAUTH_RULE);
        rateLimitService.checkBlocked(ipSubject, RateLimitService.ADMIN_REAUTH_RULE);
        var user = userRepository.findById(principal.userId())
                .orElseThrow(() -> new ApiException(ApiCode.SESSION_EXPIRED));
        if (!passwordEncoder.matches(request.password(), user.passwordHash())) {
            rateLimitService.recordWindowFailure(userSubject, RateLimitService.ADMIN_REAUTH_RULE,
                    authProperties.getLoginMaxFailures(), authProperties.getLoginWindow(),
                    authProperties.getLoginBlock());
            rateLimitService.recordWindowFailure(ipSubject, RateLimitService.ADMIN_REAUTH_RULE,
                    authProperties.getLoginMaxFailures(), authProperties.getLoginWindow(),
                    authProperties.getLoginBlock());
            throw new ApiException(ApiCode.INVALID_CREDENTIALS);
        }
        PlatformAuthorizationService.Access access = authorizationService.require(
                principal, command.action().capability());
        authorizationService.prepare(access, command);
        String digest = digest(command);
        if (request.payloadDigest() != null && !request.payloadDigest().isBlank()
                && !request.payloadDigest().equals(digest)) {
            throw new ApiException(ApiCode.REAUTH_CONTEXT_MISMATCH, "业务摘要与服务端计算不一致");
        }
        String token = Hashing.newToken();
        Instant expiresAt = clock.instant().plus(TTL);
        repository.insertReauth(UUID.randomUUID().toString(), Hashing.sha256Hex(token), principal.userId(),
                principal.sessionId(), request.operationId().strip(), command.action().name(),
                command.action().targetType(), command.targetId(), command.expectedRevision(),
                command.precondition(), digest, expiresAt);
        return new PlatformViews.ReauthenticationView(token, expiresAt);
    }

    public String verifyUnused(AuthenticatedUser principal, String token,
                               PlatformRequests.BusinessFingerprint fingerprint, String digest) {
        if (token == null || token.isBlank()) {
            throw new ApiException(ApiCode.REAUTH_REQUIRED);
        }
        PlatformRepository.ReauthRow row = repository.lockReauth(Hashing.sha256Hex(token))
                .orElseThrow(() -> new ApiException(ApiCode.REAUTH_REQUIRED));
        if (row.consumedAt() != null) {
            throw new ApiException(ApiCode.REAUTH_CONTEXT_MISMATCH, "重新验证已使用");
        }
        if (!row.expiresAt().isAfter(clock.instant())) {
            throw new ApiException(ApiCode.REAUTH_EXPIRED);
        }
        if (!row.actorId().equals(principal.userId()) || !row.sessionId().equals(principal.sessionId())
                || !row.operationId().equals(fingerprint.operationId())
                || !row.action().equals(fingerprint.action())
                || !row.targetType().equals(fingerprint.targetType())
                || !row.targetId().equals(fingerprint.targetId())
                || !Objects.equals(row.expectedRevision(), fingerprint.expectedRevision())
                || !Objects.equals(row.precondition(), fingerprint.precondition())
                || !row.payloadDigest().equals(digest)) {
            throw new ApiException(ApiCode.REAUTH_CONTEXT_MISMATCH);
        }
        return row.id();
    }

    /**
     * 核销必须与“仍在使用有效期内”同事务原子完成：等待业务行锁期间时钟可能已越过 TTL，
     * 此时核销失败会让整笔业务、审计和操作记录一起回滚。
     */
    public void consume(String reauthId) {
        Instant now = clock.instant();
        if (repository.consumeReauth(reauthId, now, now) != 1) {
            throw new ApiException(ApiCode.REAUTH_EXPIRED);
        }
    }

    public String digest(AdminCommands.CanonicalCommand command) {
        return digests.digest(command.action().name(), command.action().targetType(), command.targetId(),
                command.expectedRevision(), command.precondition(), command.decision(), command.status(),
                command.reason(), command.note(), command.targetUserId());
    }
}
