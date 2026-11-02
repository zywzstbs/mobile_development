package cn.gulupai.server.common.idempotency;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 幂等结果加密参数。密钥独立于数据库备份；生产通过环境变量提供。
 */
@Component
@ConfigurationProperties(prefix = "app.idempotency")
public class IdempotencyProperties {

    /** Base64 编码的 32 字节 AES 密钥；为空时启动失败，避免明文保存回放结果。 */
    private String encryptionKey = "";

    /** 密钥版本标识，随每条回放记录保存。 */
    private String keyId = "local-dev-v1";

    private int retentionDays = 7;

    public String getEncryptionKey() {
        return encryptionKey;
    }

    public void setEncryptionKey(String encryptionKey) {
        this.encryptionKey = encryptionKey;
    }

    public String getKeyId() {
        return keyId;
    }

    public void setKeyId(String keyId) {
        this.keyId = keyId;
    }

    public int getRetentionDays() {
        return retentionDays;
    }

    public void setRetentionDays(int retentionDays) {
        this.retentionDays = retentionDays;
    }
}
