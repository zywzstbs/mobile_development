package cn.gulupai.server.common.api;

/**
 * 完整集合投影超过 1.75MiB；禁止截断后伪装成完整列表。
 */
public class CollectionTooLargeException extends RuntimeException {

    public CollectionTooLargeException() {
        super("serialized collection exceeds limit");
    }
}
