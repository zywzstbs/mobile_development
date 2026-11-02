package cn.gulupai.server.identity.application;

import cn.gulupai.server.common.api.ApiCode;
import cn.gulupai.server.common.api.ApiException;
import cn.gulupai.server.common.audit.AuditService;
import cn.gulupai.server.common.idempotency.IdempotencyRequest;
import cn.gulupai.server.common.idempotency.IdempotencyService;
import cn.gulupai.server.common.ratelimit.RateLimitService;
import cn.gulupai.server.common.security.AuthenticatedUser;
import cn.gulupai.server.common.security.CurrentUserPolicy;
import cn.gulupai.server.common.security.Hashing;
import cn.gulupai.server.identity.AuthProperties;
import cn.gulupai.server.identity.api.LoginRequest;
import cn.gulupai.server.identity.api.LogoutRequest;
import cn.gulupai.server.identity.api.LogoutView;
import cn.gulupai.server.identity.api.RefreshRequest;
import cn.gulupai.server.identity.api.SessionView;
import cn.gulupai.server.identity.api.UserView;
import cn.gulupai.server.identity.persistence.SessionRepository;
import cn.gulupai.server.identity.persistence.SessionRow;
import cn.gulupai.server.identity.persistence.UserRepository;
import cn.gulupai.server.identity.persistence.UserRow;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class AuthService {

    private static final String DEFAULT_DEVICE = "Android App";

    private final UserRepository userRepository;
    private final SessionRepository sessionRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthProperties properties;
    private final Clock clock;
    private final RateLimitService rateLimitService;
    private final AuditService auditService;
    private final IdempotencyService idempotencyService;
    private final CurrentUserPolicy policy;
    private final UserProjectionService userProjectionService;
    private final TransactionTemplate transactionTemplate;
    private final String dummyPasswordHash;

    public AuthService(UserRepository userRepository,
                       SessionRepository sessionRepository,
                       PasswordEncoder passwordEncoder,
                       AuthProperties properties,
                       Clock clock,
                       RateLimitService rateLimitService,
                       AuditService auditService,
                       IdempotencyService idempotencyService,
                       CurrentUserPolicy policy,
                       UserProjectionService userProjectionService,
                       TransactionTemplate transactionTemplate) {
        this.userRepository = userRepository;
        this.sessionRepository = sessionRepository;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
        this.clock = clock;
        this.rateLimitService = rateLimitService;
        this.auditService = auditService;
        this.idempotencyService = idempotencyService;
        this.policy = policy;
        this.userProjectionService = userProjectionService;
        this.transactionTemplate = transactionTemplate;
        // 未知账号也执行一次同等成本的哈希校验，避免通过响应时间枚举账号。
        this.dummyPasswordHash = passwordEncoder.encode("gulupai-dummy-password-never-used");
    }

    public SessionView login(LoginRequest request, String remoteIp) {
        String loginId = request.loginId().strip();
        if (loginId.isEmpty()) {
            throw ApiException.validation("登录名不能为空");
        }
        String deviceLabel = normalizeDeviceLabel(request.deviceLabel());
        String subject = rateLimitService.loginSubject(loginId, remoteIp);
        rateLimitService.checkLoginBlocked(subject);

        var found = userRepository.findByLoginId(loginId);
        boolean matches;
        if (found.isPresent()) {
            matches = passwordEncoder.matches(request.password(), found.get().passwordHash());
        } else {
            passwordEncoder.matches(request.password(), dummyPasswordHash);
            matches = false;
        }
        if (!matches) {
            rateLimitService.recordLoginFailure(subject);
            throw new ApiException(ApiCode.INVALID_CREDENTIALS);
        }

        UserRow user = found.get();
        if (user.disabled()) {
            throw new ApiException(ApiCode.ACCOUNT_DISABLED);
        }

        SessionView session = transactionTemplate.execute(status -> {
            UserRow current = userRepository.findById(user.id())
                    .orElseThrow(() -> new ApiException(ApiCode.INVALID_CREDENTIALS));
            if (current.disabled()) {
                throw new ApiException(ApiCode.ACCOUNT_DISABLED);
            }
            SessionView view = createSession(current, deviceLabel);
            rateLimitService.clearLoginFailures(subject);
            auditService.record(current.id(), "AUTH_LOGIN", "USER", current.id(),
                    null, current.revision(), null);
            return view;
        });
        return Objects.requireNonNull(session);
    }

    public SessionView refresh(RefreshRequest request) {
        SessionView session = transactionTemplate.execute(status -> {
            Instant now = clock.instant();
            String refreshHash = Hashing.sha256Hex(request.refreshToken());
            SessionRow row = sessionRepository.findSessionByRefreshHashForUpdate(refreshHash)
                    .orElseThrow(() -> new ApiException(ApiCode.SESSION_EXPIRED));
            if (row.revoked() || !row.expiresAt().isAfter(now)) {
                throw new ApiException(ApiCode.SESSION_EXPIRED);
            }
            UserRow user = userRepository.findById(row.userId())
                    .orElseThrow(() -> new ApiException(ApiCode.SESSION_EXPIRED));
            if (user.disabled()) {
                throw new ApiException(ApiCode.SESSION_EXPIRED);
            }
            Instant accessExpiresAt = now.plus(properties.getAccessTtl());
            String accessToken = Hashing.newToken();
            sessionRepository.insertAccessToken(
                    UUID.randomUUID().toString(), row.id(), Hashing.sha256Hex(accessToken), accessExpiresAt);
            return new SessionView(
                    accessToken,
                    request.refreshToken(),
                    "Bearer",
                    secondsUntil(now, accessExpiresAt),
                    row.expiresAt(),
                    userProjectionService.toView(user));
        });
        return Objects.requireNonNull(session);
    }

    public UserView me(AuthenticatedUser principal) {
        policy.requireActiveBusinessUser(principal);
        UserRow user = userRepository.findById(principal.userId())
                .orElseThrow(() -> new ApiException(ApiCode.SESSION_EXPIRED));
        return userProjectionService.toView(user);
    }

    public LogoutView logout(AuthenticatedUser principal, String idempotencyKey, String path) {
        policy.requireAuthenticated(principal);
        return idempotencyService.execute(
                new IdempotencyRequest(principal, "DELETE", path, idempotencyKey, new LogoutRequest(),
                        List.of(new IdempotencyRequest.ResourceRef("SESSION", principal.sessionId()))),
                LogoutView.class,
                () -> {
                    if (!principal.revokedSession()) {
                        policy.requireActiveBusinessUser(principal);
                    }
                },
                () -> revokeCurrentSession(principal));
    }

    private LogoutView revokeCurrentSession(AuthenticatedUser principal) {
        Instant now = clock.instant();
        SessionRow session = sessionRepository.findSessionByIdForUpdate(principal.sessionId())
                .orElseThrow(() -> new ApiException(ApiCode.SESSION_EXPIRED));
        int before = session.revision();
        sessionRepository.revokeSessionAndAccessTokens(session.id(), now);
        auditService.record(principal.userId(), "AUTH_LOGOUT", "SESSION", session.id(),
                before, before + 1, null);
        return new LogoutView(true);
    }

    private SessionView createSession(UserRow user, String deviceLabel) {
        Instant now = clock.instant();
        Instant accessExpiresAt = now.plus(properties.getAccessTtl());
        Instant refreshExpiresAt = now.plus(properties.getRefreshTtl());

        String sessionId = UUID.randomUUID().toString();
        String accessToken = Hashing.newToken();
        String refreshToken = Hashing.newToken();

        sessionRepository.insertSession(sessionId, user.id(), deviceLabel,
                Hashing.sha256Hex(refreshToken), refreshExpiresAt);
        sessionRepository.insertAccessToken(
                UUID.randomUUID().toString(), sessionId, Hashing.sha256Hex(accessToken), accessExpiresAt);

        return new SessionView(
                accessToken,
                refreshToken,
                "Bearer",
                secondsUntil(now, accessExpiresAt),
                refreshExpiresAt,
                userProjectionService.toView(user));
    }

    private String normalizeDeviceLabel(String raw) {
        if (raw == null) {
            return DEFAULT_DEVICE;
        }
        String value = raw.strip();
        if (value.isEmpty()) {
            throw ApiException.validation("设备说明不能为空");
        }
        if (value.length() > 80) {
            throw ApiException.validation("设备说明不能超过80个字符");
        }
        return value;
    }

    private int secondsUntil(Instant now, Instant expiresAt) {
        long seconds = Duration.between(now, expiresAt).toSeconds();
        return (int) Math.max(0, seconds);
    }
}
