package cn.gulupai.server.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 协议大小限制。默认值与 API 契约一致：JSON 1MiB、普通响应 2MiB、完整集合 1.75MiB。
 */
@ConfigurationProperties(prefix = "app.api")
public class ApiProperties {

    private long maxJsonBytes = 1024L * 1024L;
    private long maxResponseBytes = 2L * 1024L * 1024L;
    private long maxCollectionBytes = 1792L * 1024L;

    public long getMaxJsonBytes() {
        return maxJsonBytes;
    }

    public void setMaxJsonBytes(long maxJsonBytes) {
        this.maxJsonBytes = maxJsonBytes;
    }

    public long getMaxResponseBytes() {
        return maxResponseBytes;
    }

    public void setMaxResponseBytes(long maxResponseBytes) {
        this.maxResponseBytes = maxResponseBytes;
    }

    public long getMaxCollectionBytes() {
        return maxCollectionBytes;
    }

    public void setMaxCollectionBytes(long maxCollectionBytes) {
        this.maxCollectionBytes = maxCollectionBytes;
    }
}
