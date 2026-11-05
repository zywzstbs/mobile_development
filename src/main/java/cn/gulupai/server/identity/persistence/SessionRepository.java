package cn.gulupai.server.identity.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

@Repository
public class SessionRepository {

    private final JdbcTemplate jdbcTemplate;

    private final RowMapper<SessionRow> sessionMapper = (rs, rowNum) -> new SessionRow(
            rs.getString("id"),
            rs.getString("user_id"),
            rs.getString("device_label"),
            rs.getString("refresh_hash"),
            rs.getObject("expires_at", java.time.OffsetDateTime.class).toInstant(),
            nullableInstant(rs.getObject("revoked_at", java.time.OffsetDateTime.class)),
            rs.getInt("revision"));

    private final RowMapper<AuthTokenRow> authTokenMapper = (rs, rowNum) -> new AuthTokenRow(
            rs.getString("user_id"),
            rs.getString("session_id"),
            rs.getString("login_id"),
            rs.getString("school_id"),
            rs.getString("display_name"),
            rs.getString("identity_status"),
            rs.getBoolean("user_disabled"),
            rs.getString("account_status"),
            rs.getObject("session_expires_at", java.time.OffsetDateTime.class).toInstant(),
            nullableInstant(rs.getObject("session_revoked_at", java.time.OffsetDateTime.class)),
            rs.getObject("access_expires_at", java.time.OffsetDateTime.class).toInstant(),
            nullableInstant(rs.getObject("access_revoked_at", java.time.OffsetDateTime.class)));

    public SessionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insertSession(String id, String userId, String deviceLabel, String refreshHash,
                              Instant expiresAt) {
        jdbcTemplate.update("""
                INSERT INTO auth_session (id, user_id, device_label, refresh_hash, expires_at, revision)
                VALUES (?, ?, ?, ?, ?, 1)
                """, id, userId, deviceLabel, refreshHash, ts(expiresAt));
    }

    public void insertAccessToken(String id, String sessionId, String tokenHash, Instant expiresAt) {
        jdbcTemplate.update("""
                INSERT INTO access_token (id, session_id, token_hash, expires_at, revision)
                VALUES (?, ?, ?, ?, 1)
                """, id, sessionId, tokenHash, ts(expiresAt));
    }

    public Optional<SessionRow> findSessionByRefreshHashForUpdate(String refreshHash) {
        return jdbcTemplate.query("""
                        SELECT id, user_id, device_label, refresh_hash, expires_at, revoked_at, revision
                        FROM auth_session
                        WHERE refresh_hash = ?
                        FOR UPDATE
                        """, sessionMapper, refreshHash)
                .stream().findFirst();
    }

    public Optional<SessionRow> findSessionByIdForUpdate(String sessionId) {
        return jdbcTemplate.query("""
                        SELECT id, user_id, device_label, refresh_hash, expires_at, revoked_at, revision
                        FROM auth_session
                        WHERE id = ?
                        FOR UPDATE
                        """, sessionMapper, sessionId)
                .stream().findFirst();
    }

    public Optional<AuthTokenRow> findAuthToken(String tokenHash) {
        return jdbcTemplate.query("""
                        SELECT u.id AS user_id, s.id AS session_id, u.login_id, u.school_id,
                               u.display_name, u.identity_status, u.disabled AS user_disabled,
                               u.account_status AS account_status,
                               s.expires_at AS session_expires_at, s.revoked_at AS session_revoked_at,
                               a.expires_at AS access_expires_at, a.revoked_at AS access_revoked_at
                        FROM access_token a
                        JOIN auth_session s ON s.id = a.session_id
                        JOIN app_user u ON u.id = s.user_id
                        WHERE a.token_hash = ?
                        """, authTokenMapper, tokenHash)
                .stream().findFirst();
    }

    /**
     * 注销当前会话及其全部访问令牌；重复调用不重复增加 revision。
     */
    public void revokeSessionAndAccessTokens(String sessionId, Instant now) {
        jdbcTemplate.update("""
                UPDATE auth_session
                SET revoked_at = ?, revision = revision + 1, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND revoked_at IS NULL
                """, ts(now), sessionId);
        jdbcTemplate.update("""
                UPDATE access_token
                SET revoked_at = ?, revision = revision + 1, updated_at = CURRENT_TIMESTAMP
                WHERE session_id = ? AND revoked_at IS NULL
                """, ts(now), sessionId);
    }

    private static OffsetDateTime ts(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant nullableInstant(Object value) {
        return value == null ? null : ((java.time.OffsetDateTime) value).toInstant();
    }
}
