package cn.gulupai.server.common.ratelimit;

import cn.gulupai.server.common.api.ApiCode;
import cn.gulupai.server.common.api.RetryAfterException;
import cn.gulupai.server.common.security.Hashing;
import cn.gulupai.server.identity.AuthProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * 登录失败限流：按 loginId 摘要 + 来源 IP 的固定 15 分钟窗口计数，
 * 第 5 次失败后封 15 分钟，响应带 Retry-After。
 */
@Service
public class RateLimitService {

    public static final String LOGIN_RULE = "LOGIN";
    public static final String ADMIN_REAUTH_RULE = "ADMIN_REAUTH";

    private final JdbcTemplate jdbcTemplate;
    private final AuthProperties properties;
    private final Clock clock;

    public RateLimitService(JdbcTemplate jdbcTemplate, AuthProperties properties, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
        this.clock = clock;
    }

    public String loginSubject(String loginId, String ip) {
        return Hashing.sha256Hex(loginId + "|" + ip);
    }

    public void checkLoginBlocked(String subjectHash) {
        Instant now = clock.instant();
        List<Instant> blocked = jdbcTemplate.query("""
                        SELECT blocked_until
                        FROM rate_window
                        WHERE subject_hash = ? AND rule = ?
                          AND blocked_until IS NOT NULL
                        ORDER BY blocked_until DESC
                        LIMIT 1
                        """,
                (rs, rowNum) -> rs.getObject("blocked_until", OffsetDateTime.class).toInstant(),
                subjectHash, LOGIN_RULE);
        if (blocked.isEmpty() || blocked.getFirst() == null) {
            return;
        }
        Instant until = blocked.getFirst();
        if (until.isAfter(now)) {
            long seconds = Math.max(1, (Duration.between(now, until).toMillis() + 999) / 1000);
            throw new RetryAfterException(ApiCode.RATE_LIMITED,
                    ApiCode.RATE_LIMITED.defaultMessage(), seconds);
        }
    }

    @Transactional
    public void recordLoginFailure(String subjectHash) {
        Instant now = clock.instant();
        long windowSeconds = Math.max(1, properties.getLoginWindow().toSeconds());
        Instant windowStart = Instant.ofEpochSecond(
                (now.getEpochSecond() / windowSeconds) * windowSeconds);

        jdbcTemplate.query("SELECT pg_advisory_xact_lock(?)",
                (ResultSetExtractor<Void>) rs -> null,
                Hashing.advisoryLockKey("RATE|" + subjectHash + "|" + LOGIN_RULE + "|" + windowStart));

        List<FailureWindow> existing = jdbcTemplate.query("""
                        SELECT id, count, blocked_until
                        FROM rate_window
                        WHERE subject_hash = ? AND rule = ? AND window_start = ?
                        FOR UPDATE
                        """,
                (rs, rowNum) -> new FailureWindow(
                        rs.getString("id"),
                        rs.getInt("count"),
                        rs.getObject("blocked_until", OffsetDateTime.class) == null
                                ? null
                                : rs.getObject("blocked_until", OffsetDateTime.class).toInstant()),
                subjectHash, LOGIN_RULE, OffsetDateTime.ofInstant(windowStart, ZoneOffset.UTC));

        if (existing.isEmpty()) {
            int count = 1;
            Instant blockedUntil = count >= properties.getLoginMaxFailures()
                    ? now.plus(properties.getLoginBlock())
                    : null;
            jdbcTemplate.update("""
                    INSERT INTO rate_window
                        (id, subject_hash, rule, window_start, count, blocked_until, revision)
                    VALUES (?, ?, ?, ?, ?, ?, 1)
                    """, UUID.randomUUID().toString(), subjectHash, LOGIN_RULE,
                    OffsetDateTime.ofInstant(windowStart, ZoneOffset.UTC), count,
                    toOffset(blockedUntil));
            return;
        }

        FailureWindow window = existing.getFirst();
        int count = window.count() + 1;
        Instant blockedUntil = count >= properties.getLoginMaxFailures()
                ? now.plus(properties.getLoginBlock())
                : window.blockedUntil();
        jdbcTemplate.update("""
                UPDATE rate_window
                SET count = ?, blocked_until = ?, revision = revision + 1,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, count, toOffset(blockedUntil), window.id());
    }

    /**
     * 固定窗口计数。账号和 IP 必须分别调用，不能只对二者拼接后的一个主体计数。
     * 超限时写入封禁并返回 429，计数在独立事务提交，不随后续业务回滚消失。
     */
    @Transactional(noRollbackFor = RetryAfterException.class)
    public void consumeWindow(String subjectHash, String rule, int maxAttempts, Duration window) {
        Instant now = clock.instant();
        long windowSeconds = Math.max(1, window.toSeconds());
        Instant windowStart = Instant.ofEpochSecond((now.getEpochSecond() / windowSeconds) * windowSeconds);
        jdbcTemplate.query("SELECT pg_advisory_xact_lock(?)",
                (ResultSetExtractor<Void>) rs -> null,
                Hashing.advisoryLockKey("RATE|" + subjectHash + "|" + rule + "|" + windowStart));
        List<FailureWindow> blocked = jdbcTemplate.query("""
                        SELECT id, count, blocked_until
                        FROM rate_window
                        WHERE subject_hash = ? AND rule = ? AND blocked_until IS NOT NULL
                          AND blocked_until > ?
                        ORDER BY blocked_until DESC
                        LIMIT 1
                        """,
                (rs, rowNum) -> new FailureWindow(rs.getString("id"), rs.getInt("count"),
                        rs.getObject("blocked_until", OffsetDateTime.class).toInstant()),
                subjectHash, rule, OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
        if (!blocked.isEmpty()) {
            long seconds = Math.max(1, (Duration.between(now, blocked.getFirst().blockedUntil()).toMillis() + 999) / 1000);
            throw new RetryAfterException(ApiCode.RATE_LIMITED, ApiCode.RATE_LIMITED.defaultMessage(), seconds);
        }
        List<FailureWindow> existing = jdbcTemplate.query("""
                        SELECT id, count, blocked_until
                        FROM rate_window
                        WHERE subject_hash = ? AND rule = ? AND window_start = ?
                        FOR UPDATE
                        """,
                (rs, rowNum) -> new FailureWindow(rs.getString("id"), rs.getInt("count"), null),
                subjectHash, rule, OffsetDateTime.ofInstant(windowStart, ZoneOffset.UTC));
        if (existing.isEmpty()) {
            jdbcTemplate.update("""
                    INSERT INTO rate_window
                        (id, subject_hash, rule, window_start, count, blocked_until, revision)
                    VALUES (?, ?, ?, ?, 1, NULL, 1)
                    """, UUID.randomUUID().toString(), subjectHash, rule,
                    OffsetDateTime.ofInstant(windowStart, ZoneOffset.UTC));
            return;
        }
        FailureWindow current = existing.getFirst();
        int count = current.count() + 1;
        Instant blockedUntil = count > maxAttempts ? now.plus(window) : null;
        jdbcTemplate.update("""
                UPDATE rate_window
                SET count = ?, blocked_until = ?, revision = revision + 1, updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, count, toOffset(blockedUntil), current.id());
        if (blockedUntil != null) {
            long seconds = Math.max(1, window.toSeconds());
            throw new RetryAfterException(ApiCode.RATE_LIMITED, ApiCode.RATE_LIMITED.defaultMessage(), seconds);
        }
    }

    public void checkBlocked(String subjectHash, String rule) {
        Instant now = clock.instant();
        List<Instant> blocked = jdbcTemplate.query("""
                        SELECT blocked_until
                        FROM rate_window
                        WHERE subject_hash = ? AND rule = ?
                          AND blocked_until IS NOT NULL
                        ORDER BY blocked_until DESC
                        LIMIT 1
                        """,
                (rs, rowNum) -> rs.getObject("blocked_until", OffsetDateTime.class).toInstant(),
                subjectHash, rule);
        if (blocked.isEmpty() || blocked.getFirst() == null || !blocked.getFirst().isAfter(now)) {
            return;
        }
        long seconds = Math.max(1, (Duration.between(now, blocked.getFirst()).toMillis() + 999) / 1000);
        throw new RetryAfterException(ApiCode.RATE_LIMITED, ApiCode.RATE_LIMITED.defaultMessage(), seconds);
    }

    @Transactional
    public void recordWindowFailure(String subjectHash, String rule, int maxFailures, Duration window, Duration block) {
        Instant now = clock.instant();
        long windowSeconds = Math.max(1, window.toSeconds());
        Instant windowStart = Instant.ofEpochSecond((now.getEpochSecond() / windowSeconds) * windowSeconds);
        jdbcTemplate.query("SELECT pg_advisory_xact_lock(?)",
                (ResultSetExtractor<Void>) rs -> null,
                Hashing.advisoryLockKey("RATE|" + subjectHash + "|" + rule + "|" + windowStart));
        List<FailureWindow> existing = jdbcTemplate.query("""
                        SELECT id, count, blocked_until
                        FROM rate_window
                        WHERE subject_hash = ? AND rule = ? AND window_start = ?
                        FOR UPDATE
                        """,
                (rs, rowNum) -> new FailureWindow(
                        rs.getString("id"),
                        rs.getInt("count"),
                        rs.getObject("blocked_until", OffsetDateTime.class) == null
                                ? null
                                : rs.getObject("blocked_until", OffsetDateTime.class).toInstant()),
                subjectHash, rule, OffsetDateTime.ofInstant(windowStart, ZoneOffset.UTC));
        if (existing.isEmpty()) {
            int count = 1;
            Instant blockedUntil = count >= maxFailures ? now.plus(block) : null;
            jdbcTemplate.update("""
                    INSERT INTO rate_window
                        (id, subject_hash, rule, window_start, count, blocked_until, revision)
                    VALUES (?, ?, ?, ?, ?, ?, 1)
                    """, UUID.randomUUID().toString(), subjectHash, rule,
                    OffsetDateTime.ofInstant(windowStart, ZoneOffset.UTC), count, toOffset(blockedUntil));
            return;
        }
        FailureWindow current = existing.getFirst();
        int count = current.count() + 1;
        Instant blockedUntil = count >= maxFailures ? now.plus(block) : current.blockedUntil();
        jdbcTemplate.update("""
                UPDATE rate_window
                SET count = ?, blocked_until = ?, revision = revision + 1, updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, count, toOffset(blockedUntil), current.id());
    }

    @Transactional
    public void clearLoginFailures(String subjectHash) {
        jdbcTemplate.update("DELETE FROM rate_window WHERE subject_hash = ? AND rule = ?",
                subjectHash, LOGIN_RULE);
    }

    private static OffsetDateTime toOffset(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private record FailureWindow(String id, int count, Instant blockedUntil) {
    }
}
