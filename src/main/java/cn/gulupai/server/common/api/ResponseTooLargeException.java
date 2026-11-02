package cn.gulupai.server.common.api;

/**
 * 序列化后的普通响应超过 2MiB。由 JSON 消息转换器在写出前抛出。
 */
public class ResponseTooLargeException extends RuntimeException {

    public ResponseTooLargeException() {
        super("serialized response exceeds limit");
    }
}
