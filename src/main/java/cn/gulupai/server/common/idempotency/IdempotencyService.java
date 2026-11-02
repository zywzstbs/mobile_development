package cn.gulupai.server.common.idempotency;

import cn.gulupai.server.common.api.ApiCode;
import cn.gulupai.server.common.api.ApiException;
import cn.gulupai.server.common.security.Hashing;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.function.Supplier;

/**
 * 幂等事务模板：鉴权后按(user, method, path, key)取事务级 advisory lock，
 * 命中回放前重新核权；业务效果与加密结果在同一事务提交。
 */
@Service
public class IdempotencyService {

    private final IdempotencyStore store;
    private final CanonicalJson canonicalJson;
    private final IdempotencyCipher cipher;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final IdempotencyProperties properties;

    public IdempotencyService(IdempotencyStore store,
                              CanonicalJson canonicalJson,
                              IdempotencyCipher cipher,
                              ObjectMapper objectMapper,
                              Clock clock,
                              IdempotencyProperties properties) {
        this.store = store;
        this.canonicalJson = canonicalJson;
        this.cipher = cipher;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.properties = properties;
    }

    @Transactional(rollbackFor = Exception.class)
    public <T> T execute(IdempotencyRequest request,
                         Class<T> resultType,
                         Runnable authorizationCheck,
                         Supplier<T> action) {
        return execute(request, resultType, authorizationCheck, action, ReplayProjector.identity());
    }

    /**
     * 带回放投影的幂等执行。
     *
     * <p>新执行只返回本次业务结果；命中已成功回执时，先重新核权、核对请求摘要，
     * 再把缓存结果交给 {@code replayProjector} 按当前资源状态投影。投影器不得再次
     * 插入消息、分配序号、发 outbox 或扣配额。
     */
    @Transactional(rollbackFor = Exception.class)
    public <T> T execute(IdempotencyRequest request,
                         Class<T> resultType,
                         Runnable authorizationCheck,
                         Supplier<T> action,
                         ReplayProjector<T> replayProjector) {
        if (request.principal() == null) {
            throw new ApiException(ApiCode.SESSION_EXPIRED);
        }
        String key = request.key();
        if (key == null || key.isBlank()) {
            throw new ApiException(ApiCode.IDEMPOTENCY_KEY_REQUIRED);
        }
        if (key.length() > 128 || key.chars().anyMatch(ch -> ch < 0x20 || ch > 0x7E)) {
            throw new ApiException(ApiCode.IDEMPOTENCY_KEY_REQUIRED,
                    "幂等键必须为1～128个可打印ASCII字符");
        }

        String bodyHash = canonicalJson.hash(request.body());
        String scope = request.principal().userId() + "|" + request.method() + "|"
                + request.path() + "|" + key;
        Instant now = clock.instant();

        if (!store.tryLock(Hashing.advisoryLockKey(scope))) {
            throw new ApiException(ApiCode.OPERATION_IN_PROGRESS);
        }

        var existing = store.find(request.principal().userId(), request.method(), request.path(), key);
        if (existing.isPresent()) {
            ReceiptRow receipt = existing.get();
            if (receipt.expiresAt().isAfter(now)) {
                if (!receipt.bodyHash().equals(bodyHash)) {
                    throw new ApiException(ApiCode.IDEMPOTENCY_KEY_REUSED);
                }
                authorizationCheck.run();
                return replayProjector.project(deserialize(receipt.resultCiphertext(), resultType));
            }
            store.delete(receipt.id());
        }

        authorizationCheck.run();
        T result = action.get();
        if (result == null) {
            throw new IllegalStateException("幂等动作没有返回结果");
        }
        try {
            String json = objectMapper.writeValueAsString(result);
            String references = objectMapper.writeValueAsString(request.resourceRefs());
            store.insert(
                    java.util.UUID.randomUUID().toString(),
                    request.principal().userId(),
                    request.method(),
                    request.path(),
                    key,
                    bodyHash,
                    200,
                    ApiCode.OK.name(),
                    cipher.encrypt(json),
                    cipher.keyId(),
                    references,
                    now.plusSeconds(properties.getRetentionDays() * 24L * 3600L));
            return result;
        } catch (ApiException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("无法保存幂等回放结果", ex);
        }
    }

    private <T> T deserialize(String ciphertext, Class<T> type) {
        try {
            return objectMapper.readValue(cipher.decrypt(ciphertext), type);
        } catch (Exception ex) {
            throw new IllegalStateException("无法恢复幂等回放结果", ex);
        }
    }
}
