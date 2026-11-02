package cn.gulupai.server.common.idempotency;

import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 幂等回放结果加密（AES-256-GCM）。密文格式：base64(iv || ciphertext || tag)。
 */
@Component
public class IdempotencyCipher {

    private static final int KEY_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecureRandom random = new SecureRandom();
    private final SecretKeySpec key;
    private final String keyId;

    public IdempotencyCipher(IdempotencyProperties properties) {
        if (properties.getEncryptionKey() == null || properties.getEncryptionKey().isBlank()) {
            throw new IllegalStateException(
                    "缺少 app.idempotency.encryption-key；生产必须通过 IDEMPOTENCY_ENCRYPTION_KEY 提供 32 字节密钥");
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(properties.getEncryptionKey());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("幂等加密密钥不是合法 Base64", ex);
        }
        if (decoded.length != KEY_BYTES) {
            throw new IllegalStateException("幂等加密密钥必须解码为 32 字节");
        }
        if (properties.getKeyId() == null || properties.getKeyId().isBlank()) {
            throw new IllegalStateException("app.idempotency.key-id 不能为空");
        }
        this.key = new SecretKeySpec(decoded, "AES");
        this.keyId = properties.getKeyId();
    }

    public String keyId() {
        return keyId;
    }

    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] combined = ByteBuffer.allocate(iv.length + encrypted.length)
                    .put(iv)
                    .put(encrypted)
                    .array();
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception ex) {
            throw new IllegalStateException("无法加密幂等回放结果", ex);
        }
    }

    public String decrypt(String encoded) {
        try {
            byte[] combined = Base64.getDecoder().decode(encoded);
            if (combined.length <= IV_BYTES) {
                throw new IllegalArgumentException("幂等密文长度无效");
            }
            byte[] iv = new byte[IV_BYTES];
            System.arraycopy(combined, 0, iv, 0, IV_BYTES);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] plaintext = cipher.doFinal(combined, IV_BYTES, combined.length - IV_BYTES);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new IllegalStateException("无法解密幂等回放结果", ex);
        }
    }
}
