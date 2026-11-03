package cn.gulupai.server.common.pagination;

import cn.gulupai.server.common.api.ApiCode;
import cn.gulupai.server.common.api.ApiException;
import cn.gulupai.server.common.security.Hashing;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
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
 * 15 分钟分页快照：只保存有序ID和位置，不缓存私密正文；续页按当前授权重新读取。
 */
@Service
public class CursorService {

    public static final String ANONYMOUS_SUBJECT = "PUBLIC";
    private static final Duration TTL = Duration.ofMinutes(15);
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public CursorService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public String create(String userId, String scopeKey, String queryHash, int epoch,
                         List<String> ids, int position) {
        return create(userId, scopeKey, queryHash, epoch, ids, position,
                clock.instant().plus(TTL));
    }

    /**
     * 续页时继承原快照到期时点，避免每次续页都重新获得 15 分钟、无限延长同一快照。
     */
    @Transactional
    public String create(String userId, String scopeKey, String queryHash, int epoch,
                         List<String> ids, int position, Instant expiresAt) {
        return insert("USER", userId, userId, scopeKey, queryHash, epoch, ids, position, expiresAt);
    }

    /**
     * 匿名公共快照。subject_key 固定为 PUBLIC，user_id 保持空，不引用 app_user。
     */
    @Transactional
    public String createAnonymous(String scopeKey, String queryHash, int epoch, List<String> ids,
                                  int position, Instant expiresAt) {
        return insert("ANONYMOUS", null, ANONYMOUS_SUBJECT, scopeKey, queryHash, epoch, ids, position, expiresAt);
    }

    private String insert(String subjectType, String userId, String subjectKey, String scopeKey,
                          String queryHash, int epoch, List<String> ids, int position, Instant expiresAt) {
        String token = Hashing.newToken();
        jdbcTemplate.update("""
                INSERT INTO cursor_snapshot
                    (id, token_hash, user_id, subject_type, subject_key, scope_key, query_hash, epoch,
                     ids_json, total, position, expires_at, revision)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, 1)
                """, UUID.randomUUID().toString(), Hashing.sha256Hex(token), userId, subjectType, subjectKey,
                scopeKey, queryHash, epoch, writeIds(ids), ids.size(), position,
                OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC));
        return token;
    }

    @Transactional(readOnly = true)
    public CursorData resolve(String token, String userId, String scopeKey, String queryHash) {
        return resolve(token, userId, scopeKey, queryHash, null);
    }

    /**
     * 当调用方提供 expectedEpoch 时，游标必须来自同一授权代次；否则按过期处理。
     */
    @Transactional(readOnly = true)
    public CursorData resolve(String token, String userId, String scopeKey, String queryHash,
                              Integer expectedEpoch) {
        if (token == null || token.isBlank()) {
            throw new ApiException(ApiCode.CURSOR_EXPIRED);
        }
        return load(token, scopeKey, queryHash, expectedEpoch, """
                        SELECT ids_json::text AS ids_json, total, position, expires_at, query_hash,
                               scope_key, epoch
                        FROM cursor_snapshot
                        WHERE token_hash = ? AND user_id = ? AND subject_type = 'USER'
                        """, userId);
    }

    @Transactional(readOnly = true)
    public CursorData resolveAnonymous(String token, String scopeKey, String queryHash) {
        return load(token, scopeKey, queryHash, null, """
                        SELECT ids_json::text AS ids_json, total, position, expires_at, query_hash,
                               scope_key, epoch
                        FROM cursor_snapshot
                        WHERE token_hash = ? AND user_id IS NULL AND subject_type = 'ANONYMOUS'
                          AND subject_key = ?
                        """, ANONYMOUS_SUBJECT);
    }

    private CursorData load(String token, String scopeKey, String queryHash, Integer expectedEpoch,
                            String sql, Object subject) {
        if (token == null || token.isBlank()) {
            throw new ApiException(ApiCode.CURSOR_EXPIRED);
        }
        List<CursorRow> rows = jdbcTemplate.query(sql,
                (rs, rowNum) -> new CursorRow(
                        rs.getString("ids_json"), rs.getInt("total"), rs.getInt("position"),
                        rs.getObject("expires_at", OffsetDateTime.class).toInstant(),
                        rs.getString("query_hash"), rs.getString("scope_key"), rs.getInt("epoch")),
                Hashing.sha256Hex(token), subject);
        if (rows.isEmpty()) {
            throw new ApiException(ApiCode.CURSOR_EXPIRED);
        }
        CursorRow row = rows.getFirst();
        if (!row.expiresAt().isAfter(clock.instant())
                || !row.scopeKey().equals(scopeKey)
                || !row.queryHash().equals(queryHash)
                || (expectedEpoch != null && row.epoch() != expectedEpoch)) {
            throw new ApiException(ApiCode.CURSOR_EXPIRED);
        }
        try {
            return new CursorData(objectMapper.readValue(row.idsJson(), STRING_LIST),
                    row.position(), row.total(), row.expiresAt());
        } catch (Exception ex) {
            throw new IllegalStateException("cursor_snapshot.ids_json 无法解析", ex);
        }
    }

    private String writeIds(List<String> ids) {
        try {
            return objectMapper.writeValueAsString(ids);
        } catch (Exception ex) {
            throw new IllegalStateException("无法序列化游标ID", ex);
        }
    }

    public record CursorData(List<String> ids, int position, int total, Instant expiresAt) {
        public CursorData {
            ids = List.copyOf(ids);
        }

        public CursorData(List<String> ids, int position, int total) {
            this(ids, position, total, null);
        }
    }

    private record CursorRow(String idsJson, int total, int position, Instant expiresAt,
                             String queryHash, String scopeKey, int epoch) {
    }
}
